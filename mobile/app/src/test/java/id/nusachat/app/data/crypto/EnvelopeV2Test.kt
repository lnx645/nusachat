package id.nusachat.app.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec amplop v2 murni (tanpa android.*): pairwise multi-part,
 * senderkey grup, dan SKDM. Bentuk kabel didokumentasikan di
 * docs/E2EE-PROTOCOL.md — ubah hanya dengan versionalisasi.
 */
class EnvelopeV2Test {
    private val devA = "dev_aaaaaaaaaaaaaaaa"
    private val devB = "dev_bbbbbbbbbbbbbbbb"
    private val b64_32 = CryptoPrimitives.b64e(ByteArray(32) { it.toByte() })
    private val b64_12 = CryptoPrimitives.b64e(ByteArray(12) { 7 })
    private val b64_16 = CryptoPrimitives.b64e(ByteArray(16) { 9 })

    private fun part(withX3dh: Boolean): EnvelopeV2.Part =
        EnvelopeV2.Part(
            x3dh = if (withX3dh) {
                X3dh.HandshakeHeader(b64_32, b64_32, 3, 9)
            } else null,
            dr = DoubleRatchet.Header(b64_32, 0, 0),
            nonceB64 = b64_12,
            ctB64 = b64_16,
        )

    @Test
    fun pairwiseRoundTrip() {
        val body = EnvelopeV2.pairwise(devA, mapOf(devB to part(true)))
        val parsed = EnvelopeV2.parse(body) as EnvelopeV2.Parsed.Pairwise
        assertEquals(devA, parsed.fromDevice)
        assertEquals(setOf(devB), parsed.parts.keys)
        val p = parsed.parts.getValue(devB)
        assertEquals(3, p.x3dh?.signedKeyId)
        assertEquals(9, p.x3dh?.onetimeKeyId)
        assertEquals(b64_32, p.dr.ratchetPubB64)
        assertEquals(b64_12, p.nonceB64)
        assertEquals(b64_16, p.ctB64)
    }

    @Test
    fun pairwiseMultiPartAndNullOtk() {
        val p1 = part(false)
        val p2 = part(true).copy(x3dh = X3dh.HandshakeHeader(b64_32, b64_32, 1, null))
        val body = EnvelopeV2.pairwise(devA, mapOf(devB to p1, devA to p2))
        val parsed = EnvelopeV2.parse(body) as EnvelopeV2.Parsed.Pairwise
        assertEquals(2, parsed.parts.size)
        assertNull(parsed.parts.getValue(devB).x3dh)
        assertNull(parsed.parts.getValue(devA).x3dh?.onetimeKeyId)
    }

    @Test
    fun senderkeyRoundTrip() {
        val sig64 = CryptoPrimitives.b64e(ByteArray(64) { 3 })
        val body = EnvelopeV2.senderkey(devA, 41, b64_12, b64_16, sig64)
        val parsed = EnvelopeV2.parse(body) as EnvelopeV2.Parsed.Senderkey
        assertEquals(devA, parsed.fromDevice)
        assertEquals(41, parsed.index)
        assertEquals(b64_12, parsed.nonceB64)
        assertEquals(b64_16, parsed.ctB64)
    }

    @Test
    fun skdmRoundTrip() {
        val body = EnvelopeV2.skdm(devA, devB, 123L, part(true))
        val parsed = EnvelopeV2.parse(body) as EnvelopeV2.Parsed.Skdm
        assertEquals(devA, parsed.fromDevice)
        assertEquals(devB, parsed.toDevice)
        assertEquals(123L, parsed.conversationId)
        assertEquals(3, parsed.part.x3dh?.signedKeyId)
    }

    @Test
    fun rejectsMalformed() {
        assertNull(EnvelopeV2.parse("bukan json"))
        assertNull(EnvelopeV2.parse("{\"v\":2,\"kind\":\"pairwise\"}"))
        assertNull(EnvelopeV2.parse("{\"v\":1,\"kind\":\"pairwise\",\"from\":\"$devA\",\"parts\":{}}"))
        assertNull(EnvelopeV2.parse("{\"v\":2,\"kind\":\"alien\",\"from\":\"$devA\"}"))
        // Tipe pesan lama: plaintext, blob legacy, system — bukan amplop.
        assertNull(EnvelopeV2.parse("Halo apa kabar"))
        assertNull(EnvelopeV2.parse("QUJDREVGR0hJSktMTU5PUA=="))
        // Part rusak: field wajib hilang.
        assertNull(
            EnvelopeV2.parse(
                "{\"v\":2,\"kind\":\"pairwise\",\"from\":\"$devA\"," +
                    "\"parts\":{\"$devB\":{\"dr\":{\"rp\":\"$b64_32\",\"pl\":0,\"n\":0}}}}"
            )
        )
    }

    @Test
    fun tieBreakAgreesBothSides() {
        val idhA = CryptoPrimitives.b64e(ByteArray(32) { 1 })
        val idhB = CryptoPrimitives.b64e(ByteArray(32) { 2 })
        // Tepat satu pihak yang mengalah — deterministik di dua sisi.
        assertTrue(
            EnvelopeV2.replaceOnRemoteInit(idhA, idhB) !=
                EnvelopeV2.replaceOnRemoteInit(idhB, idhA)
        )
        // Id sama (gema sendiri): jangan ganti.
        assertTrue(!EnvelopeV2.replaceOnRemoteInit(idhA, idhA))
    }
}
