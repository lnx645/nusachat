package id.nusachat.app.data.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Paket media NVP/1 (lihat docs/VOICE-PROTOCOL.md §3). Murni + JVM-testable.
 *
 * Kabel: header 18 byte (big-endian) + ciphertext + tag GCM 16 byte.
 * Nonce 12 byte = salt(4) | ver(1) | slot(1) | seq(2) | ts(4) — direkonstruksi
 * dari header + konteks call, NOL byte di kabel. Header ikut AAD.
 */
object Nvp {
    const val MAGIC = 0x4E
    const val VERSION = 0x01
    const val HEADER_LEN = 18
    const val TAG_LEN = 16
    const val MAX_DGRAM = 1200
    const val FLAG_REDUNDANT = 0x01
    const val FLAG_COMFORT = 0x02
    const val FLAG_KEEPALIVE = 0x04
    const val FLAG_CONTROL = 0x08

    const val CODEC_OPUS = "opus"
    const val CODEC_AMR_WB = "amr-wb"
}

/** Header terurai + payload cipher (termasuk tag). */
data class NvpHeader(
    /** uint32 */
    val callId: Long,
    val slot: Int,
    val flags: Int,
    /** uint32 */
    val token: Long,
    /** uint16 */
    val seq: Int,
    /** uint32, clock 16 kHz */
    val ts: Long,
    val payload: ByteArray,
)

class NvpPacket(key: ByteArray, private val salt: ByteArray) {
    private val secret = SecretKeySpec(key.copyOf(32), "AES")

    init {
        require(key.size == 32) { "kunci NVP wajib 32 byte" }
        require(salt.size == 4) { "salt NVP wajib 4 byte" }
    }

    /** Bungkus plaintext jadi datagram siap kirim. */
    fun seal(
        callId: Long,
        slot: Int,
        flags: Int,
        token: Long,
        seq: Int,
        ts: Long,
        plaintext: ByteArray,
    ): ByteArray {
        val header = buildHeader(callId, slot, flags, token, seq, ts)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonceOf(slot, seq, ts)))
        cipher.updateAAD(header)
        val sealed = cipher.doFinal(plaintext)
        return header + sealed
    }

    /** Keepalive 18 byte tanpa payload (token tetap divalidasi relay). */
    fun keepalive(callId: Long, slot: Int, token: Long, seq: Int, ts: Long): ByteArray =
        buildHeader(callId, slot, Nvp.FLAG_KEEPALIVE, token, seq, ts)

    /**
     * Buka datagram → plaintext, atau null bila header rusak / tag salah /
     * kunci salah. Keepalive valid → ByteArray kosong (bukan null).
     */
    fun open(datagram: ByteArray): Pair<NvpHeader, ByteArray>? {
        if (datagram.size > Nvp.MAX_DGRAM || datagram.size < Nvp.HEADER_LEN) return null
        val buf = ByteBuffer.wrap(datagram).order(ByteOrder.BIG_ENDIAN)
        if (buf.get().toInt() and 0xFF != Nvp.MAGIC) return null
        if (buf.get().toInt() and 0xFF != Nvp.VERSION) return null
        val callId = buf.int.toLong() and 0xFFFFFFFFL
        val slot = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF
        val token = buf.int.toLong() and 0xFFFFFFFFL
        val seq = buf.short.toInt() and 0xFFFF
        val ts = buf.int.toLong() and 0xFFFFFFFFL
        val rest = datagram.copyOfRange(Nvp.HEADER_LEN, datagram.size)
        val keepAlive = flags and Nvp.FLAG_KEEPALIVE != 0
        if (keepAlive) {
            if (rest.isNotEmpty()) return null
            val header = datagram.copyOfRange(0, Nvp.HEADER_LEN)
            return NvpHeader(callId, slot, flags, token, seq, ts, header) to ByteArray(0)
        }
        if (rest.size < Nvp.TAG_LEN) return null
        val header = datagram.copyOfRange(0, Nvp.HEADER_LEN)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, nonceOf(slot, seq, ts)))
            cipher.updateAAD(header)
            val plain = cipher.doFinal(rest)
            NvpHeader(callId, slot, flags, token, seq, ts, rest) to plain
        } catch (_: Exception) {
            null
        }
    }

    /** Nonce turunan (internal agar uji bisa assert keunikan 10k paket). */
    internal fun nonceOf(slot: Int, seq: Int, ts: Long): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            put(salt)
            put(Nvp.VERSION.toByte())
            put(slot.toByte())
            putShort(seq.toShort())
            putInt((ts and 0xFFFFFFFFL).toInt())
        }.array()

    private fun buildHeader(
        callId: Long,
        slot: Int,
        flags: Int,
        token: Long,
        seq: Int,
        ts: Long,
    ): ByteArray =
        ByteBuffer.allocate(Nvp.HEADER_LEN).order(ByteOrder.BIG_ENDIAN).apply {
            put(Nvp.MAGIC.toByte())
            put(Nvp.VERSION.toByte())
            putInt((callId and 0xFFFFFFFFL).toInt())
            put(slot.toByte())
            put(flags.toByte())
            putInt((token and 0xFFFFFFFFL).toInt())
            putShort(seq.toShort())
            putInt((ts and 0xFFFFFFFFL).toInt())
        }.array()
}

/**
 * Negosiasi codec murni: item offer pertama yang didukung lokal.
 * Offer berurutan preferensi penelepon; list lokal dari probe MediaCodec.
 */
object NvpCodec {
    fun negotiate(offer: List<String>, local: List<String>): String? =
        offer.firstOrNull { it in local }
}
