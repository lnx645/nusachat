package id.nusachat.app.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Parameter media NVP/1 dari `call.prepared` + negosiasi codec. */
data class NvpCallParams(
    val callId: Long,
    val slot: Int,
    val token: Long,
    val relayHost: String,
    val relayPort: Int,
    val key: ByteArray,
    val salt: ByteArray,
    val codec: String,
)

data class NvpStats(
    val codec: String,
    val profile: Int,
    val sentPackets: Int,
    val sentBytes: Long,
    val recvPackets: Int,
    val recvBytes: Long,
    val lost: Int,
    val invalid: Int,
    val plcFrames: Int,
    val jitterMs: Double,
    val rttMs: Long,
    val slots: Int,
)

/**
 * Engine voice NVP/1: tangkap → encode 20 ms → bundel → UDP relay → terima →
 * decode → jitter PCM → mix → main. Multi-slot (room): tiap slot punya jitter
 * + decoder sendiri. Semua resource dilepas di [stop].
 *
 * Format bundel audio (plaintext): `[n:1B][len:2B,blob]×n`, n = tick/paket.
 * Redundansi: `[prevSamples:2B][prevLen:2B][prevBundel][curBundel]`.
 */
class NusaVoiceEngine(private val context: Context) {
    private val running = AtomicBoolean(false)
    private var senderThread: Thread? = null
    private var receiverThread: Thread? = null
    @Volatile private var muted = false
    @Volatile private var pendingProfile = -1
    @Volatile private var lastProfileChange = 0L
    @Volatile private var lastVoiceAt = 0L
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var focusRequest: AudioFocusRequest? = null

    private var socket: DatagramSocket? = null
    private var params: NvpCallParams? = null
    private var packet: NvpPacket? = null
    private val chains = ConcurrentHashMap<Int, SlotChain>()
    private val sentPackets = AtomicInteger(0)
    private val sentBytes = AtomicInteger(0)
    private val recvPackets = AtomicInteger(0)
    private val recvBytes = AtomicInteger(0)
    private val lostPackets = AtomicInteger(0)
    private val invalidPackets = AtomicInteger(0)
    private val plcFrames = AtomicInteger(0)
    @Volatile private var rttMs = 0L
    @Volatile private var profile = 0
    @Volatile private var pingId = 0
    @Volatile private var lastAudioSeq = 0
    @Volatile private var lastAudioTs = 0L
    private val pingSentAt = ConcurrentHashMap<Int, Long>()

    private inner class SlotChain(val slot: Int, val codecName: String) {
        val jitter = NvpJitter()
        @Volatile var decoder: NvpDecoder? = null
        @Volatile var pendingCsd: ByteArray? = null
        var lastSeq: Int? = null
        var everReceived = false
        var firstAudioAt = 0L
        @Volatile var lastActivity = SystemClock.elapsedRealtime()
        /** Frame terakhir untuk PLC (diulang dengan decay saat paket hilang). */
        @Volatile var lastPcm: ShortArray? = null

        /**
         * Decoder lazy: Opus menunggu CSD lawan (handshake `csd`); AMR-WB
         * langsung polos. Fallback: Opus tanpa CSD >2 dtk → coba polos
         * (beberapa vendor bisa).
         */
        @Synchronized
        fun ensureDecoder(now: Long): NvpDecoder? {
            decoder?.let { return it }
            if (codecName == Nvp.CODEC_OPUS) {
                pendingCsd?.let {
                    decoder = runCatching { NvpDecoder(codecName, it) }.getOrNull()
                    if (decoder != null) return decoder
                }
                if (firstAudioAt == 0L) firstAudioAt = now
                if (now - firstAudioAt < CSD_WAIT_MS) return null
            }
            decoder = runCatching { NvpDecoder(codecName) }.getOrNull()
            return decoder
        }

        @Synchronized
        fun onCsd(csd: ByteArray) {
            pendingCsd = csd
            // Ganti decoder polos (fallback) dengan yang ber-CSD.
            runCatching { decoder?.release() }
            decoder = runCatching { NvpDecoder(codecName, csd) }.getOrNull()
        }

        fun release() = runCatching { decoder?.release() }
    }

    /** Nama profil agar log/smoke terbaca: ultra/hemat/aman. */
    fun profileName(): String = arrayOf("ultra", "hemat", "aman")[profile.coerceIn(0, 2)]

    fun isRunning(): Boolean = running.get()

    fun setMuted(mute: Boolean) {
        muted = mute
    }

    fun setSpeakerphone(on: Boolean) {
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.mode = AudioManager.MODE_IN_COMMUNICATION
            audio?.isSpeakerphoneOn = on
        }
    }

    /**
     * Daftarkan slot room yang bergabung (UI memanggil dari event
     * `call_room_joined`). Slot tak dikenal yang mengirim paket juga
     * otomatis dibuat di penerima (tahan balap join).
     */
    fun addSlot(slot: Int) {
        val p = params ?: return
        if (chains.size < MAX_SLOTS) chains.getOrPut(slot) { SlotChain(slot, p.codec) }
    }

    fun removeSlot(slot: Int) {
        chains.remove(slot)?.release()
    }

    fun stats(): NvpStats {
        val jit = chains.values.map { it.jitter.jitterMs }.maxOrNull() ?: 0.0
        return NvpStats(
            codec = params?.codec ?: "?",
            profile = profile,
            sentPackets = sentPackets.get(),
            sentBytes = sentBytes.get().toLong(),
            recvPackets = recvPackets.get(),
            recvBytes = recvBytes.get().toLong(),
            lost = lostPackets.get(),
            invalid = invalidPackets.get(),
            plcFrames = plcFrames.get(),
            jitterMs = jit,
            rttMs = rttMs,
            slots = chains.size,
        )
    }

    fun start(p: NvpCallParams) {
        if (!running.compareAndSet(false, true)) return
        params = p
        packet = NvpPacket(p.key, p.salt)
        profile = 0
        lastVoiceAt = 0L
        // Reset counter agar statistik call baru tidak tercampur call sebelumnya.
        sentPackets.set(0); sentBytes.set(0)
        recvPackets.set(0); recvBytes.set(0)
        lostPackets.set(0); invalidPackets.set(0); plcFrames.set(0)
        rttMs = 0L
        // Socket dibuat di sini (bukan di sender) agar receiver tidak balapan
        // dapat socket bekas yang sudah di-close.
        runCatching { socket?.close() }
        socket = runCatching { DatagramSocket() }.getOrNull()
        if (socket == null) {
            running.set(false)
            Log.e(TAG, "socket UDP gagal dibuat")
            return
        }
        Log.i(TAG, "start call=${p.callId} slot=${p.slot} codec=${p.codec} relay=${p.relayHost}:${p.relayPort}")
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.mode = AudioManager.MODE_IN_COMMUNICATION
            // Minta fokus audio agar suara call tidak direbut notifikasi/musik.
            if (Build.VERSION.SDK_INT >= 26) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                focusRequest = req
                audio?.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audio?.requestAudioFocus(
                    null, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                )
            }
        }
        senderThread = Thread({ senderLoop(p) }, "nvp-sender").also { it.start() }
        receiverThread = Thread({ receiverLoop(p) }, "nvp-receiver").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        senderThread?.join(2000)
        receiverThread?.join(2000)
        senderThread = null
        receiverThread = null
        chains.values.forEach { runCatching { it.release() } }
        chains.clear()
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        runCatching { agc?.release() }
        aec = null
        ns = null
        agc = null
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { audio?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audio?.abandonAudioFocus(null)
            }
            focusRequest = null
            audio?.mode = AudioManager.MODE_NORMAL
            audio?.isSpeakerphoneOn = false
        }
        Log.i(TAG, "stop stats=${stats()}")
    }

    // ---------------------------------------------------------- pengirim

    private fun senderLoop(p: NvpCallParams) {
        var record: AudioRecord? = null
        var encoder: NvpEncoder? = null
        try {
            val sock = socket ?: run {
                Log.e(TAG, "socket belum siap")
                return
            }
            val relay = InetAddress.getByName(p.relayHost)
            val minBuf = AudioRecord.getMinBufferSize(
                NvpCodecs.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                NvpCodecs.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 4,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init gagal")
                return
            }
            // Hanya AEC yang dipaksa; NS/AGC dimatikan — di HP murah
            // keduanya sering bikin kresek/musical-noise. VOICE_COMMUNICATION
            // sudah ada NS/AGC software bawaan.
            runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(record.audioSessionId)?.also {
                        runCatching { it.enabled = true }
                    }
                    Log.i(TAG, "AEC enabled=${aec?.enabled}")
                } else {
                    Log.w(TAG, "AEC tidak tersedia di perangkat ini")
                }
            }
            runCatching { ns?.release() }
            runCatching { agc?.release() }
            ns = null
            agc = null
            record.startRecording()
            var seq = (Math.random() * 65536).toInt()
            var ts = (Math.random() * 4294967296L).toLong()
            lastAudioSeq = seq
            lastAudioTs = ts
            var ticksPerPacket = NvpCodecs.TICKS_PER_PACKET[profile]
            encoder = NvpEncoder(p.codec, NvpCodecs.bitrate(p.codec, profile))
            val blobs = mutableListOf<ByteArray>()
            var lastBundle: ByteArray? = null
            var lastBundleSamples = 0
            var lastKeepaliveAt = 0L
            var lastFeedbackAt = 0L
            var lastPingAt = 0L
            var csdSent = 0
            var lastCsdAt = 0L
            val tick = ShortArray(NvpCodecs.TICK_SAMPLES)
            while (running.get()) {
                // Terapkan adaptasi profil (dari feedback penerima lawan).
                val want = pendingProfile
                if (want in 0..2 && want != profile &&
                    System.currentTimeMillis() - lastProfileChange > 4000
                ) {
                    profile = want
                    pendingProfile = -1
                    lastProfileChange = System.currentTimeMillis()
                    ticksPerPacket = NvpCodecs.TICKS_PER_PACKET[profile]
                    runCatching { encoder?.release() }
                    encoder = NvpEncoder(p.codec, NvpCodecs.bitrate(p.codec, profile))
                    blobs.clear()
                    csdSent = 0
                    Log.i(TAG, "profil → ${profileName()} (${NvpCodecs.bitrate(p.codec, profile)} bps)")
                }
                val read = record.read(tick, 0, tick.size)
                if (read <= 0) continue
                // Bila read parsial, nolkan sisa agar tidak bawa sampel basi
                // (sumber kresek). Lalu gate lembut: sunyi dilemahkan 0,25
                // bukan dibuang — hiss turun tapi jitter tetap stabil.
                if (read < tick.size) java.util.Arrays.fill(tick, read, tick.size, 0)
                val now = System.currentTimeMillis()
                // Mute = jangan kirim audio (kirim keepalive agar NAT tetap buka).
                if (muted) {
                    blobs.clear()
                    if (now - lastKeepaliveAt > 2_500) {
                        sendRaw(sock, relay, packet!!.keepalive(p.callId, p.slot, p.token, seq, ts))
                        lastKeepaliveAt = now
                    }
                    continue
                }
                softNoiseGate(tick)
                // Tanpa gate VAD: selalu kirim agar suara pelan tidak terpotong
                // dan jitter tetap stabil (DTX lama bikin PLC melonjak + patah).
                val enc = encoder ?: continue
                val blob = enc.encode(tick.copyOf(), now * 1000)
                if (blob != null) blobs.add(blob)
                // Handshake CSD: kirim 3x di awal + ulang tiap 2 dtk agar tahan
                // loss (lawan join belakangan tetap dapat). AMR-WB tak punya
                // codecConfig → dilewati.
                if (p.codec == Nvp.CODEC_OPUS) {
                    val cfg = enc.codecConfig
                    if (cfg != null && (csdSent < 3 || now - lastCsdAt > 2_000)) {
                        val b64 = Base64.encodeToString(cfg, Base64.NO_WRAP)
                        sendControl(
                            sock, relay,
                            JSONObject().put("t", "csd").put("csd", b64)
                                .toString().toByteArray(),
                        )
                        if (csdSent < 3) csdSent++
                        lastCsdAt = now
                    }
                }
                if (blobs.size < ticksPerPacket) continue
                val bundle = bundleBlobs(blobs)
                blobs.clear()
                // Redundansi: bundel sebelumnya ikut (profil aman).
                var flags = 0
                var payload = bundle
                val prev = lastBundle
                if (profile >= 2 && prev != null) {
                    flags = flags or Nvp.FLAG_REDUNDANT
                    val head = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).apply {
                        putShort(lastBundleSamples.toShort())
                        putShort(prev.size.toShort())
                    }.array()
                    payload = head + prev + bundle
                }
                lastBundle = bundle
                lastBundleSamples = ticksPerPacket * NvpCodecs.TICK_SAMPLES
                sendRaw(
                    sock, relay,
                    packet!!.seal(p.callId, p.slot, flags, p.token, seq, ts, payload),
                )
                seq = (seq + 1) and 0xFFFF
                ts = (ts + ticksPerPacket * NvpCodecs.TICK_SAMPLES) and 0xFFFFFFFFL
                lastAudioSeq = seq
                lastAudioTs = ts
                if (now - lastFeedbackAt > 2000) {
                    sendControl(sock, relay, feedbackJson())
                    lastFeedbackAt = now
                }
                if (now - lastPingAt > 5000) {
                    if (pingSentAt.size > 32) pingSentAt.clear()
                    pingId++
                    pingSentAt[pingId] = SystemClock.elapsedRealtime()
                    sendControl(
                        sock, relay,
                        JSONObject().put("t", "ping").put("id", pingId)
                            .put("ts", pingSentAt[pingId]).toString().toByteArray(),
                    )
                    lastPingAt = now
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "sender gagal: ${e.message}")
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { encoder?.release() }
        }
    }

    private fun isVoice(tick: ShortArray, now: Long): Boolean {
        var sum = 0L
        for (s in tick) sum += s * s
        val rms = kotlin.math.sqrt(sum.toDouble() / tick.size)
        if (rms > VAD_THRESHOLD) {
            lastVoiceAt = now
            return true
        }
        // Hangover: tahan 400ms setelah suara terakhir agar ujung kata
        // tidak terpotong dan paket tidak putus-sambung.
        return now - lastVoiceAt < VAD_HANGOVER_MS
    }

    private fun softNoiseGate(tick: ShortArray) {
        var sum = 0L
        for (s in tick) sum += s * s
        val rms = kotlin.math.sqrt(sum.toDouble() / tick.size)
        // Sunyi (<250) dilemahkan agar hiss tidak ikut dikirim penuh.
        if (rms < 250.0) {
            for (i in tick.indices) tick[i] = (tick[i] * 0.25f).toInt().toShort()
        }
    }

    private fun bundleBlobs(blobs: List<ByteArray>): ByteArray {
        var size = 1
        blobs.forEach { size += 2 + it.size }
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            put(blobs.size.toByte())
            blobs.forEach {
                putShort(it.size.toShort())
                put(it)
            }
        }.array()
    }

    private fun sendRaw(sock: DatagramSocket, relay: InetAddress, bytes: ByteArray) {
        val p = params ?: return
        runCatching {
            sock.send(DatagramPacket(bytes, bytes.size, relay, p.relayPort))
            sentPackets.incrementAndGet()
            sentBytes.addAndGet(bytes.size)
        }
    }

    private fun sendControl(sock: DatagramSocket, relay: InetAddress, json: ByteArray) {
        val p = params ?: return
        // Kontrol tak majukan seq/ts audio (tak dihitung loss).
        sendRaw(
            sock, relay,
            packet!!.seal(
                p.callId, p.slot, Nvp.FLAG_CONTROL, p.token,
                lastAudioSeq, lastAudioTs, json,
            ),
        )
    }

    private fun feedbackJson(): ByteArray {
        val total = recvPackets.get()
        val loss = if (total > 0) lostPackets.get().toDouble() / total else 0.0
        val jit = chains.values.map { it.jitter.jitterMs }.maxOrNull() ?: 0.0
        return JSONObject().put("t", "fb").put("loss", loss)
            .put("jit", jit.toLong()).put("rtt", rttMs).toString().toByteArray()
    }

    // ---------------------------------------------------------- penerima

    private fun receiverLoop(p: NvpCallParams) {
        var track: AudioTrack? = null
        try {
            var waits = 0
            while (running.get() && socket == null && waits++ < 100) Thread.sleep(50)
            val sock = socket ?: return
            // Tick 20 ms (lihat NvpPlayout): timeout terima = satu tick agar
            // tiap iterasi memainkan frame yang jatuh tempo, bukan 200 ms+.
            sock.soTimeout = NvpPlayout.TICK_MS.toInt()
            val minBuf = AudioTrack.getMinBufferSize(
                NvpCodecs.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            track = if (Build.VERSION.SDK_INT >= 21) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setSampleRate(NvpCodecs.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                AudioTrack(
                    attrs, fmt, minBuf * 4,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
                )
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL, NvpCodecs.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4, AudioTrack.MODE_STREAM,
                )
            }
            track.play()
            val buf = ByteArray(2048)
            val datagram = DatagramPacket(buf, buf.size)
            val mix = ShortArray(NvpCodecs.TICK_SAMPLES)
            var lastTick = SystemClock.elapsedRealtime()
            while (running.get()) {
                while (running.get()) {
                    try {
                        sock.receive(datagram)
                    } catch (_: Exception) {
                        break
                    }
                    recvPackets.incrementAndGet()
                    recvBytes.addAndGet(datagram.length)
                    handleDatagram(p, datagram.data.copyOf(datagram.length))
                }
                // Prune slot basi (>30 dtk tanpa paket): peserta hilang diam-diam.
                val staleCutoff = SystemClock.elapsedRealtime() - 30_000
                for ((slot, chain) in chains.entries) {
                    if (chain.lastActivity < staleCutoff) {
                        chains.remove(slot)?.release()
                    }
                }
                // Tick main 20 ms (lihat NvpPlayout): pop rutin atau antrean
                // basi semua. Kejar ketinggalan dibatasi (catch-up burst).
                val now = SystemClock.elapsedRealtime()
                val due = NvpPlayout.framesDue(now - lastTick)
                if (due <= 0) {
                    // Jangan busy-spin: hemat CPU agar tidak ganggu audio.
                    Thread.sleep(5)
                    continue
                }
                lastTick = now
                var wroteAny = false
                repeat(due) {
                    // Satu tick main 20 ms: mix semua slot. Paket hilang =
                    // ulangi frame terakhir dengan decay (PLC), bukan sunyi
                    // mendadak — mengurangi patah-patah.
                    java.util.Arrays.fill(mix, 0)
                    var active = false
                    for (chain in chains.values) {
                        val frame = chain.jitter.pop()
                        val pcm: ShortArray? = if (frame != null) {
                            chain.lastPcm = frame.payload
                            frame.payload
                        } else {
                            val prev = chain.lastPcm
                            if (chain.everReceived && prev != null) {
                                plcFrames.incrementAndGet()
                                // Decay 0.9 agar tidak berdengung saat loss panjang.
                                ShortArray(prev.size) { i -> (prev[i] * 0.9f).toInt().toShort() }.also {
                                    chain.lastPcm = it
                                }
                            } else {
                                if (chain.everReceived) plcFrames.incrementAndGet()
                                null
                            }
                        }
                        if (pcm == null) continue
                        active = true
                        for (i in mix.indices) {
                            // Headroom 0,9 + hard-clip agar tidak kresek saat keras.
                            val mixed = (mix[i] + (pcm.getOrElse(i) { 0 })) * 0.9f
                            mix[i] = mixed.toInt().coerceIn(
                                Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt(),
                            ).toShort()
                        }
                    }
                    if (active || chains.isNotEmpty()) {
                        runCatching { track.write(mix, 0, mix.size) }
                        wroteAny = true
                    }
                }
                if (!wroteAny && chains.isEmpty()) {
                    Thread.sleep(20)
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "receiver gagal: ${e.message}")
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }

    private fun handleDatagram(p: NvpCallParams, bytes: ByteArray) {
        val (header, plain) = packet!!.open(bytes) ?: run {
            invalidPackets.incrementAndGet()
            return
        }
        if (header.flags and Nvp.FLAG_KEEPALIVE != 0) return
        if (header.callId != p.callId) return
        if (header.flags and Nvp.FLAG_CONTROL != 0) {
            handleControl(p, header.slot, plain)
            return
        }
        val chain = chains[header.slot] ?: run {
            if (chains.size >= MAX_SLOTS) return
            SlotChain(header.slot, p.codec).also { chains[header.slot] = it }
        }
        chain.everReceived = true
        chain.lastActivity = SystemClock.elapsedRealtime()
        // Akuntansi loss (hanya paket audio).
        val last = chain.lastSeq
        if (last != null) {
            val gap = (header.seq - last - 1) and 0xFFFF
            if (gap in 1..100) lostPackets.addAndGet(gap)
        }
        chain.lastSeq = header.seq
        // Urai redundansi + bundel → frame PCM per 20 ms.
        if (header.flags and Nvp.FLAG_REDUNDANT != 0 && plain.size >= 4) {
            val bb = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN)
            val prevSamples = bb.short.toInt() and 0xFFFF
            val prevLen = bb.short.toInt() and 0xFFFF
            if (prevLen in 1 until plain.size - 4) {
                val prevTs = (header.ts - prevSamples) and 0xFFFFFFFFL
                insertBundle(chain, plain.copyOfRange(4, 4 + prevLen), prevTs, header.seq - 1)
                insertBundle(chain, plain.copyOfRange(4 + prevLen, plain.size), header.ts, header.seq)
                return
            }
        }
        insertBundle(chain, plain, header.ts, header.seq)
    }

    private fun insertBundle(chain: SlotChain, bundle: ByteArray, baseTs: Long, seq: Int) {
        if (bundle.isEmpty()) return
        // Belum ada decoder (Opus menunggu CSD) → drop, bukan hening palsu.
        val decoder = chain.ensureDecoder(SystemClock.elapsedRealtime()) ?: return
        val bb = ByteBuffer.wrap(bundle).order(ByteOrder.BIG_ENDIAN)
        val count = bb.get().toInt() and 0xFF
        if (count !in 1..3) return
        var ts = baseTs
        repeat(count) {
            if (bb.remaining() < 2) return
            val len = bb.short.toInt() and 0xFFFF
            if (len <= 0 || len > bb.remaining()) return
            val blob = ByteArray(len)
            bb.get(blob)
            val pcm = decoder.decode(blob, ts * 1000 / 16)
                ?.padTo(NvpCodecs.TICK_SAMPLES) ?: ShortArray(NvpCodecs.TICK_SAMPLES)
            chain.jitter.insert(ts and 0xFFFFFFFFL, seq, pcm)
            ts = (ts + NvpCodecs.TICK_SAMPLES) and 0xFFFFFFFFL
        }
    }

    private fun ShortArray.padTo(n: Int): ShortArray =
        if (size == n) this else copyOf(n)

    private fun handleControl(p: NvpCallParams, fromSlot: Int, plain: ByteArray) {
        val json = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull() ?: return
        when (json.optString("t")) {
            "csd" -> {
                val csd = runCatching {
                    Base64.decode(json.optString("csd"), Base64.DEFAULT)
                }.getOrNull()?.takeIf { it.size in 1..256 } ?: return
                val chain = chains[fromSlot] ?: run {
                    if (chains.size >= MAX_SLOTS) return
                    SlotChain(fromSlot, p.codec).also { chains[fromSlot] = it }
                }
                chain.onCsd(csd)
                Log.i(TAG, "CSD ${csd.size}B dari slot $fromSlot")
            }
            "fb" -> {
                val loss = json.optDouble("loss", 0.0)
                val rtt = json.optLong("rtt", 0)
                pendingProfile = when {
                    loss > 0.10 || rtt > 400 -> 2
                    loss > 0.03 || rtt > 250 -> 1
                    else -> 0
                }
            }
            "ping" -> {
                val sock = socket ?: return
                val relay = runCatching { InetAddress.getByName(p.relayHost) }.getOrNull() ?: return
                val pong = JSONObject().put("t", "pong").put("id", json.optInt("id"))
                    .put("ts", json.optLong("ts")).toString().toByteArray()
                sendControl(sock, relay, pong)
            }
            "pong" -> {
                val sentAt = pingSentAt.remove(json.optInt("id"))
                if (sentAt != null) rttMs = SystemClock.elapsedRealtime() - sentAt
            }
        }
    }

    companion object {
        private const val TAG = "NusaVoice"
        private const val MAX_SLOTS = 8
        private const val VAD_THRESHOLD = 180.0
        private const val VAD_HANGOVER_MS = 400L
        private const val CSD_WAIT_MS = 2000L
    }
}
