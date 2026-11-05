package id.nusachat.app.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ratchet murni + JVM-safe. Test replay mendokumentasikan invariant yang
 * melahirkan bug "pesan hilang": kunci pesan sekali pakai, dekrip kedua
 * atas ciphertext SAMA harus gagal — karena itu preview notif wajib
 * kering (previewPlaintext), tak boleh memakai decryptOne.
 */
class DoubleRatchetTest {
    private fun freshPair(): Pair<DoubleRatchet.State, DoubleRatchet.State> {
        val shared = ByteArray(32) { (it * 7 + 1).toByte() }
        val bobPriv = CryptoPrimitives.x25519KeyPair().first
        val bobPub = CryptoPrimitives.b64e(CryptoPrimitives.x25519Public(bobPriv))
        val alice = DoubleRatchet.initAlice(shared, bobPub)
        val bob = DoubleRatchet.initBob(shared, CryptoPrimitives.b64e(bobPriv))
        return alice to bob
    }

    @Test
    fun roundtrip() {
        val (alice, bob) = freshPair()
        val (_, ct) = DoubleRatchet.encrypt(alice, "halo".toByteArray())
        val res = DoubleRatchet.decrypt(bob, ct)
        assertEquals("halo", res.plaintext.toString(Charsets.UTF_8))
    }

    @Test
    fun replayFails() {
        val (alice, bob) = freshPair()
        val (_, ct) = DoubleRatchet.encrypt(alice, "sekali".toByteArray())
        val first = DoubleRatchet.decrypt(bob, ct)
        assertEquals("sekali", first.plaintext.toString(Charsets.UTF_8))
        try {
            DoubleRatchet.decrypt(first.state, ct)
            fail("dekrip kedua atas ciphertext sama harus gagal (anti-replay)")
        } catch (e: Exception) {
            // Diharapkan: msg_no di luar jangkauan (jump < 0).
        }
    }

    @Test
    fun dryPreviewKeepsState() {
        val (alice, bob) = freshPair()
        val (_, ct) = DoubleRatchet.encrypt(alice, "pratinjau".toByteArray())
        // "Dekrip kering": hasil state dibuang, state asli tak disentuh.
        val preview = DoubleRatchet.decrypt(bob, ct)
        assertEquals("pratinjau", preview.plaintext.toString(Charsets.UTF_8))
        // Dekrip sungguhan dengan state ASLI harus tetap berhasil.
        val real = DoubleRatchet.decrypt(bob, ct)
        assertEquals("pratinjau", real.plaintext.toString(Charsets.UTF_8))
        // Setelah state hasil dekrip sungguhan disimpan, replay harus gagal.
        try {
            DoubleRatchet.decrypt(real.state, ct)
            fail("replay setelah commit state harus gagal")
        } catch (e: Exception) {
            // Diharapkan.
        }
    }

    @Test
    fun outOfOrderHeals() {
        val (alice, bob) = freshPair()
        val (a1, c0) = DoubleRatchet.encrypt(alice, "nol".toByteArray())
        val (_, c1) = DoubleRatchet.encrypt(a1, "satu".toByteArray())
        // Terima c1 dulu (c0 tersimpan sebagai terlewati), lalu c0.
        val r1 = DoubleRatchet.decrypt(bob, c1)
        val r0 = DoubleRatchet.decrypt(r1.state, c0)
        assertEquals("satu", r1.plaintext.toString(Charsets.UTF_8))
        assertEquals("nol", r0.plaintext.toString(Charsets.UTF_8))
    }
}
