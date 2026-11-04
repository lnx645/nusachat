package id.nusachat.app.data

import id.nusachat.app.data.local.ChatCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresi bug reinstall: pesan jadi cipher text mentah dan cache bagus
 * tertimpa hasil dekrip gagal. Aturan:
 * - blob base64 tak terbaca → placeholder, jangan tampil mentah;
 * - teks biasa (pra-E2EE) tetap tampil apa adanya;
 * - cache: barisan tak terbaca tak boleh menimpa barisan terbaca.
 */
class E2eeRestoreTest {

    private val cipher = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI="

    private fun msg(id: Long, body: String?) = Message(
        id = id,
        conversationId = 7L,
        sender = null,
        type = "text",
        body = body,
        attachmentUrl = null,
        status = "sent",
        createdAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun blobBase64TerdeteksiCipher() {
        assertTrue(E2eeManager.looksLikeCiphertext(cipher))
    }

    @Test
    fun teksBiasaBukanCipher() {
        assertFalse(E2eeManager.looksLikeCiphertext("halo apa kabar?"))
        assertFalse(E2eeManager.looksLikeCiphertext("ok"))
        assertFalse(E2eeManager.looksLikeCiphertext(""))
        assertFalse(E2eeManager.looksLikeCiphertext(null))
    }

    @Test
    fun placeholderBukanCipher() {
        assertFalse(E2eeManager.looksLikeCiphertext(SessionCrypto.PLACEHOLDER_BROKEN))
        assertFalse(E2eeManager.looksLikeCiphertext(SessionCrypto.PLACEHOLDER_SELF))
    }

    @Test
    fun copyFallbackTerkunci() {
        assertEquals(
            "Pesan ini tidak tersedia, atau membutuhkan waktu untuk ditampilkan.",
            E2eeManager.PLACEHOLDER_SYNC,
        )
        assertEquals("Pesan ini tidak tersedia.", SessionCrypto.PLACEHOLDER_BROKEN)
        assertEquals("Pesan terkirim.", SessionCrypto.PLACEHOLDER_SELF)
    }

    @Test
    fun fallbackMappingRapi() {
        // Baru: identitas.
        assertEquals(E2eeManager.PLACEHOLDER_SYNC, E2eeManager.fallbackTextFor(E2eeManager.PLACEHOLDER_SYNC))
        assertEquals(SessionCrypto.PLACEHOLDER_BROKEN, E2eeManager.fallbackTextFor(SessionCrypto.PLACEHOLDER_BROKEN))
        // Lama (kurung, dari cache lawas): dipetakan ke gaya baru.
        assertEquals(E2eeManager.PLACEHOLDER_SYNC, E2eeManager.fallbackTextFor("[Menunggu sinkronisasi kunci…]"))
        assertEquals(E2eeManager.PLACEHOLDER_SYNC, E2eeManager.fallbackTextFor("[Pesan terenkripsi]"))
        assertEquals(SessionCrypto.PLACEHOLDER_BROKEN, E2eeManager.fallbackTextFor("[Pesan terenkripsi tidak dapat dibuka]"))
        assertEquals(SessionCrypto.PLACEHOLDER_SELF, E2eeManager.fallbackTextFor("[Pesan terkirim]"))
        // Cipher mentah yang lolos = SYNC; teks biasa = null.
        assertEquals(E2eeManager.PLACEHOLDER_SYNC, E2eeManager.fallbackTextFor(cipher))
        assertEquals(null, E2eeManager.fallbackTextFor("halo apa kabar?"))
        assertEquals(null, E2eeManager.fallbackTextFor(null))
    }

    @Test
    fun placeholderLamaTakTerbaca() {
        assertFalse(E2eeManager.isReadableBody("[Menunggu sinkronisasi kunci…]"))
        assertFalse(E2eeManager.isReadableBody("[Pesan terenkripsi]"))
        assertFalse(E2eeManager.isReadableBody(E2eeManager.PLACEHOLDER_SYNC))
        assertTrue(E2eeManager.isReadableBody("halo"))
        assertTrue(E2eeManager.isReadableBody(null))
    }

    @Test
    fun cipherTakTimpaCacheTerbaca() {
        assertTrue(ChatCache.shouldKeepExisting(msg(1, "halo"), msg(1, cipher)))
    }

    @Test
    fun bacaanBaruTimpaPlaceholderLama() {
        assertFalse(ChatCache.shouldKeepExisting(msg(1, SessionCrypto.PLACEHOLDER_BROKEN), msg(1, "halo")))
    }

    @Test
    fun tanpaCacheSelaluSimpan() {
        assertFalse(ChatCache.shouldKeepExisting(null, msg(1, cipher)))
        assertFalse(ChatCache.shouldKeepExisting(null, msg(1, "halo")))
    }

    @Test
    fun nonTeksSelaluSimpan() {
        val media = msg(1, null).copy(type = "image", attachmentUrl = "https://x/y.jpg")
        assertFalse(ChatCache.shouldKeepExisting(msg(1, "halo"), media))
    }

    @Test
    fun blobFreshButuhSidikCocok() {
        assertTrue(E2eeManager.blobIsFresh("abc", "abc"))
        assertFalse(E2eeManager.blobIsFresh("abc", "def"))
        assertFalse(E2eeManager.blobIsFresh("abc", null))
        assertFalse(E2eeManager.blobIsFresh("", ""))
    }

    @Test
    fun rekeyHanyaUserTerkecil() {
        assertTrue(E2eeManager.shouldRekey(1, listOf(1, 2)))
        assertFalse(E2eeManager.shouldRekey(2, listOf(1, 2)))
        assertTrue(E2eeManager.shouldRekey(5, emptyList()))
    }

    @Test
    fun deviceBasiBukanHolder() {
        // 2026-11-04T21:00:00Z dalam ms (UTC).
        val now = 1793826000000L
        assertTrue(E2eeManager.isFreshDevice("2026-11-04T20:56:06Z", now))
        assertTrue(E2eeManager.isFreshDevice("", now))
        assertTrue(E2eeManager.isFreshDevice("bukan-tanggal", now))
        assertFalse(E2eeManager.isFreshDevice("2026-08-01T00:00:00Z", now))
    }

    @Test
    fun blobDeviceKosongDiabaikan() {
        val holders = listOf(
            KeyHolder(userId = 1, deviceId = "", keyFp = "sama"),
            KeyHolder(userId = 1, deviceId = "d1", keyFp = "lama"),
        )
        // fp cocok untuk device-kosong TETAP tidak dihitung fresh.
        assertFalse(E2eeManager.anyFreshHolder(holders) { "sama" })
        // yang dihitung hanya device nyata.
        val real = listOf(KeyHolder(userId = 1, deviceId = "d1", keyFp = "sama"))
        assertTrue(E2eeManager.anyFreshHolder(real) { "sama" })
        assertFalse(E2eeManager.anyFreshHolder(real) { "baru" })
    }
}
