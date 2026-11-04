package id.nusachat.app.data.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inti paket NVP/1: round-trip kripto, penolakan paket rusak, dan keunikan
 * nonce 10k paket (syarat IV-GCM). javax.crypto tersedia di JVM unit test.
 */
class NvpPacketTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val salt = byteArrayOf(1, 2, 3, 4)
    private val nvp = NvpPacket(key, salt)

    @Test
    fun roundTrip() {
        val plain = ByteArray(60) { it.toByte() }
        val datagram = nvp.seal(
            callId = 11L, slot = 2, flags = 0, token = 12345L,
            seq = 100, ts = 9999L, plaintext = plain,
        )
        assertEquals(Nvp.HEADER_LEN + 60 + Nvp.TAG_LEN, datagram.size)
        val (header, opened) = nvp.open(datagram)!!
        assertEquals(11L, header.callId)
        assertEquals(2, header.slot)
        assertEquals(12345L, header.token)
        assertEquals(100, header.seq)
        assertEquals(9999L, header.ts)
        assertArrayEquals(plain, opened)
    }

    @Test
    fun keepaliveRoundTrip() {
        val datagram = nvp.keepalive(
            callId = 11L, slot = 0, token = 1L, seq = 2, ts = 3L,
        )
        assertEquals(Nvp.HEADER_LEN, datagram.size)
        val (_, opened) = nvp.open(datagram)!!
        assertEquals(0, opened.size)
    }

    @Test
    fun tamperedCiphertextRejected() {
        val datagram = nvp.seal(11L, 0, 0, 1L, 5, 80L, ByteArray(40) { 9 })
        datagram[datagram.size - 1] = (datagram[datagram.size - 1] + 1).toByte()
        assertNull(nvp.open(datagram))
    }

    @Test
    fun tamperedHeaderRejected() {
        // AAD mencakup header: ubah 1 bit callId → tag tak valid.
        val datagram = nvp.seal(11L, 0, 0, 1L, 5, 80L, ByteArray(40) { 9 })
        datagram[2] = (datagram[2] + 1).toByte()
        assertNull(nvp.open(datagram))
    }

    @Test
    fun wrongKeyRejected() {
        val datagram = nvp.seal(11L, 0, 0, 1L, 5, 80L, ByteArray(40) { 9 })
        val other = NvpPacket(ByteArray(32) { 42 }, salt)
        assertNull(other.open(datagram))
    }

    @Test
    fun malformedRejected() {
        // Magic salah.
        val badMagic = nvp.seal(11L, 0, 0, 1L, 5, 80L, ByteArray(40))
        badMagic[0] = 0x00
        assertNull(nvp.open(badMagic))
        // Terpotong.
        assertNull(nvp.open(byteArrayOf(1, 2, 3)))
        // Media tanpa tag penuh.
        val short = nvp.keepalive(11L, 0, 1L, 5, 80L) + byteArrayOf(1, 2, 3)
        // (tanpa flag keepalive → butuh ≥16 byte tag)
        short[7] = 0x00
        assertNull(nvp.open(short))
        // Keepalive berpayload.
        val kaPayload = nvp.keepalive(11L, 0, 1L, 5, 80L) + byteArrayOf(1)
        assertNull(nvp.open(kaPayload))
        // Terlalu besar.
        assertNull(nvp.open(ByteArray(Nvp.MAX_DGRAM + 1)))
    }

    @Test
    fun noncesUniqueOver10kPackets() {
        val seen = HashSet<String>(10000)
        repeat(10000) { i ->
            val nonce = nvp.nonceOf(slot = 1, seq = i % 65536, ts = i.toLong() * 960)
            assertTrue("nonce rangkap di $i", seen.add(nonce.toHex()))
        }
        assertEquals(10000, seen.size)
    }

    @Test
    fun codecNegotiation() {
        assertEquals(
            "opus",
            NvpCodec.negotiate(listOf("opus", "amr-wb"), listOf("opus", "amr-wb")),
        )
        // HP jadul tanpa Opus → AMR-WB.
        assertEquals(
            "amr-wb",
            NvpCodec.negotiate(listOf("opus", "amr-wb"), listOf("amr-wb")),
        )
        // Tak ada irisan → null (klien tolak call).
        assertEquals(
            null,
            NvpCodec.negotiate(listOf("opus"), listOf("amr-wb")),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
