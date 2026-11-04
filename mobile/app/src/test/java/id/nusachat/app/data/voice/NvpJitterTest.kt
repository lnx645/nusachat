package id.nusachat.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jitter buffer NVP/1 dengan jam palsu: urut, telat, duplikat, adaptasi. */
class NvpJitterTest {
    private var now = 1_000_000L
    private val jitter = NvpJitter(clockMs = { now })

    @Test
    fun playsInTimestampOrderAfterDelay() {
        // Tiba acak (ts 1920 dulu), main harus urut ts.
        jitter.insert(ts = 1920L, seq = 2, payload = shortArrayOf(2))
        jitter.insert(ts = 960L, seq = 1, payload = shortArrayOf(1))
        jitter.insert(ts = 0L, seq = 0, payload = shortArrayOf(0))
        // Belum waktunya (target awal 120 ms).
        assertNull(jitter.pop())
        now += 120
        assertEquals(0, jitter.pop()!!.payload[0].toInt())
        assertEquals(1, jitter.pop()!!.payload[0].toInt())
        assertEquals(2, jitter.pop()!!.payload[0].toInt())
        assertNull(jitter.pop())
        assertEquals(3, jitter.played)
    }

    @Test
    fun dropsLateAndDuplicates() {
        jitter.insert(ts = 0L, seq = 0, payload = shortArrayOf(0))
        now += 120
        assertNotNull(jitter.pop())
        // ts lama setelah dimainkan = telat.
        jitter.insert(ts = 0L, seq = 0, payload = shortArrayOf(0))
        assertEquals(1, jitter.lateDropped)
        // Duplikat ts yang masih antre.
        jitter.insert(ts = 960L, seq = 1, payload = shortArrayOf(1))
        jitter.insert(ts = 960L, seq = 1, payload = shortArrayOf(1))
        assertEquals(1, jitter.duplicates)
    }

    @Test
    fun dropsOverdueHead() {
        jitter.insert(ts = 0L, seq = 0, payload = shortArrayOf(0))
        now += 500 // head basi (>400 ms)
        jitter.insert(ts = 960L, seq = 1, payload = shortArrayOf(1))
        now += 200 // fresh due (target adaptif ~162 ms)
        assertEquals(1, jitter.pop()!!.payload[0].toInt())
        assertEquals(1, jitter.lateDropped)
    }

    @Test
    fun targetDelayAdaptsToJitter() {
        val calm = NvpJitter(clockMs = { now })
        // Aliran rapi 60 ms/960 ts.
        repeat(10) { i ->
            calm.insert(ts = i * 960L, seq = i, payload = shortArrayOf())
            now += 60
        }
        val calmTarget = calm.targetDelayMs
        // Aliran kacau: jeda acak 10..200 ms.
        val gaps = listOf(10L, 200L, 15L, 190L, 20L, 180L, 10L, 200L, 15L, 190L)
        gaps.forEachIndexed { i, gap ->
            calm.insert(ts = 10 * 960L + i * 960L, seq = 10 + i, payload = shortArrayOf())
            now += gap
        }
        assertTrue(
            "target ${calm.targetDelayMs} harus > $calmTarget saat jitter",
            calm.targetDelayMs > calmTarget,
        )
        assertTrue(calm.targetDelayMs <= 400L)
    }
}
