package id.nusachat.app.data.voice

import java.util.TreeMap

/**
 * Jitter buffer adaptif NVP/1: meratakan keterlambatan paket (80–400 ms).
 * Kunci urut = timestamp 16 kHz pengirim (unik per frame 20 ms).
 * Payload = PCM 16 kHz mono (decode dikerjakan saat terima, bukan saat pop).
 * Murni-testable via jam injeksi; null dari [pop] = penerima wajib PLC.
 */
class NvpJitter(private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    data class Entry(val ts: Long, val seq: Int, val payload: ShortArray, val arrivedAt: Long)

    private val queue = TreeMap<Long, Entry>()
    private var lastPlayedTs: Long? = null
    private var lastTs: Long? = null
    private var lastArrival: Long? = null

    var targetDelayMs: Long = 120L
        private set
    var jitterMs: Double = 0.0
        private set
    var played: Int = 0
        private set
    var lateDropped: Int = 0
        private set
    var duplicates: Int = 0
        private set

    /**
     * Masukkan frame PCM 20 ms. False = duplikat/telat (di-hit counter).
     */
    fun insert(ts: Long, seq: Int, payload: ShortArray): Boolean {
        val last = lastPlayedTs
        if (last != null && ts <= last) {
            lateDropped++
            return false
        }
        if (queue.containsKey(ts)) {
            duplicates++
            return false
        }
        if (queue.size >= MAX_BUFFERED) {
            queue.pollFirstEntry()
            lateDropped++
        }
        val now = clockMs()
        val prevTs = lastTs
        val prevArr = lastArrival
        if (prevTs != null && prevArr != null) {
            val transitGap = (now - prevArr).toDouble()
            val tsGapMs = (ts - prevTs).toDouble() / 16.0
            val deviation = kotlin.math.abs(transitGap - tsGapMs)
            jitterMs += (deviation - jitterMs) / 16.0
            targetDelayMs = (80.0 + jitterMs * 3.0).toLong().coerceIn(80L, 400L)
        }
        lastTs = ts
        lastArrival = now
        queue[ts] = Entry(ts, seq, payload, now)
        return true
    }

    /** Ambil frame yang sudah waktunya main, atau null (PLC). */
    fun pop(): Entry? {
        while (true) {
            val head = queue.firstEntry() ?: return null
            val now = clockMs()
            if (now - head.value.arrivedAt > MAX_LATE_MS) {
                queue.pollFirstEntry()
                lateDropped++
                continue
            }
            return if (head.value.arrivedAt + targetDelayMs <= now) {
                queue.pollFirstEntry()
                played++
                lastPlayedTs = head.value.ts
                head.value
            } else {
                null
            }
        }
    }

    fun reset() {
        queue.clear()
        lastPlayedTs = null
        lastTs = null
        lastArrival = null
        targetDelayMs = 120L
        jitterMs = 0.0
    }

    companion object {
        const val MAX_BUFFERED = 50
        const val MAX_LATE_MS = 400L
    }
}
