package id.nusachat.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Base64url tulisan-tangan (vektor RFC 4648, tanpa padding) + kurir NVP. */
class NvpSignalingTest {
    @Test
    fun base64UrlVectors() {
        assertEquals("", NvpBase64.encode(ByteArray(0)))
        assertEquals("Zg", NvpBase64.encode("f".toByteArray()))
        assertEquals("Zm8", NvpBase64.encode("fo".toByteArray()))
        assertEquals("Zm9v", NvpBase64.encode("foo".toByteArray()))
        assertEquals("Zm9vYg", NvpBase64.encode("foob".toByteArray()))
        assertEquals("Zm9vYmFy", NvpBase64.encode("foobar".toByteArray()))
        // URL-safe: 0xFB 0xFF → "+/8" standar, "-_8" di sini.
        assertEquals("-_8", NvpBase64.encode(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
    }

    @Test
    fun base64RoundTrip() {
        val data = ByteArray(64) { (it * 13 + 7).toByte() }
        val encoded = NvpBase64.encode(data)
        assertTrue(encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val decoded = NvpBase64.decode(encoded)!!
        assertEquals(data.toList(), decoded.toList())
    }

    @Test
    fun base64RejectsGarbage() {
        assertNull(NvpBase64.decode("ab+c")) // '+' ilegal di url-safe
        assertNull(NvpBase64.decode("abc=")) // padding ilegal
        assertNull(NvpBase64.decode("a")) // sisa 1 tak valid
    }

    @Test
    fun offerMarkerRoundTrip() {
        val sdp = NvpMarkers.offerToSdp(
            listOf("opus", "amr-wb"), keyB64 = "a2V5", saltB64 = "c2FsdA",
        )
        assertTrue(sdp.startsWith("nvp1:"))
        val offer = NvpMarkers.parseOfferSdp(sdp)!!
        assertEquals(listOf("opus", "amr-wb"), offer.codecs)
        assertEquals("a2V5", offer.keyB64)
        assertEquals("c2FsdA", offer.saltB64)
    }

    @Test
    fun offerMarkerRejectsGarbage() {
        assertNull(NvpMarkers.parseOfferSdp("v=0\r\nm=audio...")) // SDP WebRTC asli
        assertNull(NvpMarkers.parseOfferSdp("nvp1:!!!")) // b64 rusak
        assertNull(NvpMarkers.parseOfferSdp(NvpBase64.encode("""{"a":1}""".toByteArray()).let { "nvp1:$it" }))
        // Codec ilegal.
        val badCodec = "nvp1:" + NvpBase64.encode(
            """{"codecs":["g729"],"key_ct":"a2V5","salt":"c2FsdA"}""".toByteArray(),
        )
        assertNull(NvpMarkers.parseOfferSdp(badCodec))
    }

    @Test
    fun answerMarkerRoundTrip() {
        assertEquals("nvp1:opus", NvpMarkers.answerToSdp("opus"))
        assertEquals("opus", NvpMarkers.parseAnswerSdp("nvp1:opus"))
        assertNull(NvpMarkers.parseAnswerSdp("v=0\r\nm=audio..."))
        assertNull(NvpMarkers.parseAnswerSdp("nvp1:g729"))
        // Marker offer bukan answer.
        val offer = NvpMarkers.offerToSdp(listOf("opus"), "a2V5", "c2FsdA")
        assertNull(NvpMarkers.parseAnswerSdp(offer))
    }
}
