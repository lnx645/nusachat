package id.nusachat.app.data.voice

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Probe + wrapper MediaCodec untuk NVP/1 (lihat docs/VOICE-PROTOCOL.md §3.3).
 *
 * - Encoder Opus hanya dijamin API 29+ (fragmentasi antar vendor!) → probe
 *   runtime, fallback AMR-WB (semua versi, clock 16 kHz sama).
 * - AMR-WB hanya menerima mode bitrate valid → ladder per-codec di bawah.
 * - Perubahan profil = restart encoder (setParameters bitrate audio tidak
 *   konsisten antar vendor; jeda ~50-100 ms bisa diterima).
 */
object NvpCodecs {
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val TICK_SAMPLES = 320 // 20 ms @16 kHz

    /** Tangga bitrate valid per codec: ultra → hemat → aman.
     * Opus dinaikkan (16/24/32k) agar jernih; 8k terbukti sengau/patah. */
    private val ladder = mapOf(
        Nvp.CODEC_OPUS to intArrayOf(16000, 24000, 32000),
        Nvp.CODEC_AMR_WB to intArrayOf(12650, 15850, 23850),
    )

    /** Frame per paket per profil: 60/40/40 ms. */
    val TICKS_PER_PACKET = intArrayOf(3, 2, 2)

    /** Codec yang encoder-nya benar-benar ada di perangkat ini. */
    fun probeEncoders(): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                val types = runCatching { info.supportedTypes }.getOrNull() ?: continue
                if (MediaFormat.MIMETYPE_AUDIO_OPUS in types &&
                    Nvp.CODEC_OPUS !in out
                ) {
                    out.add(Nvp.CODEC_OPUS)
                }
                if (MediaFormat.MIMETYPE_AUDIO_AMR_WB in types &&
                    Nvp.CODEC_AMR_WB !in out
                ) {
                    out.add(Nvp.CODEC_AMR_WB)
                }
            }
        }.onFailure { Log.w("NvpCodecs", "probe gagal: ${it.message}") }
        // AMR-WB dijamin semua versi Android; bila probe aneh, klaim manual.
        if (Nvp.CODEC_AMR_WB !in out) out.add(Nvp.CODEC_AMR_WB)
        // Urutan preferensi: Opus dulu.
        return out.sortedBy { if (it == Nvp.CODEC_OPUS) 0 else 1 }
    }

    fun bitrate(codec: String, profile: Int): Int =
        ladder[codec]?.getOrNull(profile.coerceIn(0, 2)) ?: 12000

    fun mimeOf(codec: String): String = when (codec) {
        Nvp.CODEC_OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
        else -> MediaFormat.MIMETYPE_AUDIO_AMR_WB
    }
}

/** Encoder einmal-pakai: satu instance per (codec, bitrate); profil ganti = buat baru. */
class NvpEncoder(codecName: String, bitrate: Int) {
    private val mime = NvpCodecs.mimeOf(codecName)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(mime)
    private val info = MediaCodec.BufferInfo()
    private var released = false

    /**
     * Codec-specific data (mis. OpusHead) bila encoder mengeluarkannya.
     * Terisi lazy saat [encode] berjalan; lawan memakainya untuk configure
     * decoder (handshake `csd` via paket kontrol). AMR-WB: selalu null.
     */
    @Volatile var codecConfig: ByteArray? = null
        private set

    init {
        val format = MediaFormat.createAudioFormat(mime, NvpCodecs.SAMPLE_RATE, NvpCodecs.CHANNELS)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /**
     * Encode tepat [samples] (kelipatan 320) → satu blob audio, atau null
     * bila codec belum mengeluarkan output. Buffer codec-config TIDAK pernah
     * dikembalikan (disimpan ke [codecConfig]) agar tak terkirim sebagai audio.
     */
    fun encode(samples: ShortArray, ptsUs: Long): ByteArray? {
        if (released) return null
        return runCatching {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx < 0) return null
            val inBuf = codec.getInputBuffer(inIdx) ?: return null
            inBuf.clear()
            inBuf.order(ByteOrder.LITTLE_ENDIAN)
            inBuf.asShortBuffer().put(samples)
            codec.queueInputBuffer(inIdx, 0, samples.size * 2, ptsUs, 0)
            var tries = 0
            while (tries++ < 4) {
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    grabCsd(codec.outputFormat)
                    continue
                }
                if (outIdx < 0) return null
                val outBuf = codec.getOutputBuffer(outIdx)
                val blob = if (outBuf != null && info.size > 0) {
                    ByteArray(info.size).also {
                        outBuf.position(info.offset)
                        outBuf.get(it, 0, info.size)
                    }
                } else {
                    null
                }
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                codec.releaseOutputBuffer(outIdx, false)
                if (isConfig) {
                    if (blob != null) codecConfig = blob
                    continue
                }
                return blob
            }
            null
        }.getOrNull()
    }

    private fun grabCsd(format: MediaFormat) {
        runCatching {
            val csd = format.getByteBuffer("csd-0") ?: return
            val bytes = ByteArray(csd.remaining())
            csd.get(bytes)
            if (bytes.isNotEmpty()) codecConfig = bytes
        }
    }

    fun release() {
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

/**
 * Decoder simetris encoder: satu blob → PCM 16 kHz mono.
 * @param csd codec-specific data dari encoder lawan (Opus wajib di
 * sebagian vendor, mis. Infinix); null = configure polos (AMR-WB / fallback).
 */
class NvpDecoder(codecName: String, csd: ByteArray? = null) {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(NvpCodecs.mimeOf(codecName))
    private val info = MediaCodec.BufferInfo()
    private var released = false

    init {
        val format = MediaFormat.createAudioFormat(
            NvpCodecs.mimeOf(codecName), NvpCodecs.SAMPLE_RATE, NvpCodecs.CHANNELS,
        )
        if (csd != null && csd.isNotEmpty()) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.copyOf()))
        }
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** Decode satu blob → PCM, atau null bila gagal/timeout (panggil PLC). */
    fun decode(blob: ByteArray, ptsUs: Long): ShortArray? {
        if (released || blob.isEmpty()) return null
        return runCatching {
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx < 0) return null
            val inBuf = codec.getInputBuffer(inIdx) ?: return null
            inBuf.clear()
            inBuf.put(blob)
            codec.queueInputBuffer(inIdx, 0, blob.size, ptsUs, 0)
            // Output pertama sering INFO_OUTPUT_FORMAT_CHANGED / preskip
            // kosong (Opus) → dequeue ulang, jangan langsung menyerah.
            var tries = 0
            while (tries++ < 4) {
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (outIdx < 0) return null
                if (info.size <= 0) {
                    codec.releaseOutputBuffer(outIdx, false)
                    continue
                }
                val outBuf = codec.getOutputBuffer(outIdx) ?: run {
                    codec.releaseOutputBuffer(outIdx, false)
                    return null
                }
                outBuf.position(info.offset)
                outBuf.order(ByteOrder.LITTLE_ENDIAN)
                val shorts = ShortArray(info.size / 2)
                outBuf.asShortBuffer().get(shorts)
                codec.releaseOutputBuffer(outIdx, false)
                return shorts
            }
            null
        }.getOrNull()
    }

    fun release() {
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
