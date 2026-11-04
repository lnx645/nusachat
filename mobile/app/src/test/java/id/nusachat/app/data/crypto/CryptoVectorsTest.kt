package id.nusachat.app.data.crypto

import id.nusachat.app.data.crypto.CryptoPrimitives.b64d
import id.nusachat.app.data.crypto.CryptoPrimitives.b64e
import id.nusachat.app.data.crypto.CryptoPrimitives.edKeyPair
import id.nusachat.app.data.crypto.CryptoPrimitives.edSign
import id.nusachat.app.data.crypto.CryptoPrimitives.x25519KeyPair
import id.nusachat.app.data.crypto.CryptoPrimitives.x25519Public
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Vektor uji protokol E2EE (docs/E2EE-PROTOCOL.md).
 *
 * Kunci TETAP (bukan acak) agar deterministik: bila kode berubah dan
 * vektor ini gagal, itu regresi protokol — bukan flake. Dijalankan
 * `./gradlew :app:testDebugUnitTest --tests "*Crypto*"`.
 */
class CryptoVectorsTest {
    // Kunci tetap 32 byte (nilai apa pun sah untuk X25519/Ed25519 mentah).
    private val aliceIdDh = ByteArray(32) { (it + 1).toByte() }
    private val bobIdDh = ByteArray(32) { (it + 41).toByte() }
    private val bobSpk = ByteArray(32) { (it + 81).toByte() }
    private val bobOtpk = ByteArray(32) { (it + 121).toByte() }
    private val aliceEk = ByteArray(32) { (it + 161).toByte() }
    private val bobEdPriv = ByteArray(32) { (it + 201).toByte() }

    private fun bundle(withOtpk: Boolean = true): X3dh.Bundle {
        val spkPub = x25519Public(bobSpk)
        return X3dh.Bundle(
            identitySignPubB64 = b64e(edPubOf(bobEdPriv)),
            identityDhPubB64 = b64e(x25519Public(bobIdDh)),
            signedKeyId = 7,
            signedPubB64 = b64e(spkPub),
            signedSigB64 = b64e(edSign(bobEdPriv, spkPub)),
            onetimeKeyId = if (withOtpk) 3 else null,
            onetimePubB64 = if (withOtpk) b64e(x25519Public(bobOtpk)) else null,
        )
    }

    private fun edPubOf(priv: ByteArray): ByteArray =
        CryptoPrimitives.edPublic(priv)

    // ------------------------------------------------------------ X3DH

    @Test
    fun x3dhBothSidesAgree() {
        val b = bundle()
        val a = X3dh.initiator(b64e(aliceIdDh), b, b64e(aliceEk))
        val r = X3dh.responder(b64e(bobIdDh), b64e(bobSpk), b64e(bobOtpk), a.header)
        assertArrayEquals(a.sharedKey, r.sharedKey)
        assertEquals(3, r.onetimeKeyIdUsed)
    }

    @Test
    fun x3dhWithoutOtpkAgrees() {
        val b = bundle(withOtpk = false)
        val a = X3dh.initiator(b64e(aliceIdDh), b, b64e(aliceEk))
        val r = X3dh.responder(b64e(bobIdDh), b64e(bobSpk), null, a.header)
        assertArrayEquals(a.sharedKey, r.sharedKey)
        // SK tanpa DH4 harus beda dari SK dengan DH4.
        val withOtpk = X3dh.initiator(b64e(aliceIdDh), bundle(), b64e(aliceEk))
        assertTrue(!a.sharedKey.contentEquals(withOtpk.sharedKey))
    }

    @Test
    fun x3dhRejectsBadSignature() {
        val evil = bundle().copy(signedSigB64 = b64e(ByteArray(64) { 0xEE.toByte() }))
        try {
            X3dh.initiator(b64e(aliceIdDh), evil, b64e(aliceEk))
            fail("harus menolak tanda tangan palsu")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun handshakeHeaderRoundTrip() {
        val a = X3dh.initiator(b64e(aliceIdDh), bundle(), b64e(aliceEk))
        val parsed = X3dh.HandshakeHeader.parse(a.header.canonical())
        assertEquals(a.header, parsed)
        assertNull(X3dh.HandshakeHeader.parse("sampah".toByteArray()))
        assertNull(X3dh.HandshakeHeader.parse(ByteArray(0)))
    }

    // ------------------------------------------------------------ Ratchet

    private fun freshSession(): Triple<DoubleRatchet.State, DoubleRatchet.State, X3dh.Bundle> {
        val b = bundle()
        val a = X3dh.initiator(b64e(aliceIdDh), b, b64e(aliceEk))
        val r = X3dh.responder(b64e(bobIdDh), b64e(bobSpk), b64e(bobOtpk), a.header)
        val alice = DoubleRatchet.initAlice(a.sharedKey, b.signedPubB64)
        val bob = DoubleRatchet.initBob(r.sharedKey, b64e(bobSpk))
        return Triple(alice, bob, b)
    }

    @Test
    fun ratchetRoundTripAlternating() {
        var alice: DoubleRatchet.State
        var bob: DoubleRatchet.State
        freshSession().also { alice = it.first; bob = it.second }
        repeat(10) { i ->
            val (a2, ct) = DoubleRatchet.encrypt(alice, "a-$i".toByteArray())
            alice = a2
            val (b2, pt) = DoubleRatchet.decrypt(bob, ct)
            bob = b2
            assertEquals("a-$i", pt.toString(Charsets.UTF_8))
            val (b3, back) = DoubleRatchet.encrypt(bob, "b-$i".toByteArray())
            bob = b3
            val (a3, pt2) = DoubleRatchet.decrypt(alice, back)
            alice = a3
            assertEquals("b-$i", pt2.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun ratchetOutOfOrder() {
        val (alice0, bob0) = freshSession()
        var alice = alice0
        val cts = (1..5).map { i ->
            val (s, ct) = DoubleRatchet.encrypt(alice, "m$i".toByteArray())
            alice = s
            ct
        }
        var bob = bob0
        // Terima 3,1,2,5,4.
        for (idx in listOf(2, 0, 1, 4, 3)) {
            val (s, pt) = DoubleRatchet.decrypt(bob, cts[idx])
            bob = s
            assertEquals("m${idx + 1}", pt.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun ratchetReplayFailsForwardSecrecy() {
        val (alice0, bob0) = freshSession()
        val (_, ct) = DoubleRatchet.encrypt(alice0, "sekali".toByteArray())
        val (bob1, pt) = DoubleRatchet.decrypt(bob0, ct)
        assertEquals("sekali", pt.toString(Charsets.UTF_8))
        try {
            DoubleRatchet.decrypt(bob1, ct)
            fail("replay harus gagal (kunci sudah dihapus)")
        } catch (e: Exception) {
            // ok — kunci pesan sekali pakai.
        }
    }

    @Test
    fun ratchetTamperFailsStateIntact() {
        val (alice0, bob0) = freshSession()
        val (_, ct) = DoubleRatchet.encrypt(alice0, "asli".toByteArray())
        val evilBody = b64d(ct.bodyB64).also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val evil = ct.copy(bodyB64 = b64e(evilBody))
        try {
            DoubleRatchet.decrypt(bob0, evil)
            fail("ciphertext rusak harus gagal")
        } catch (e: Exception) {
            // ok
        }
        // State tidak rusak: pesan asli tetap bisa didekrip.
        val (_, pt) = DoubleRatchet.decrypt(bob0, ct)
        assertEquals("asli", pt.toString(Charsets.UTF_8))
    }

    @Test
    fun ratchetSkipLimit() {
        val (alice0, bob0) = freshSession()
        // Lompat raksasa tanpa pesan antara = tolak (anti-DoS).
        val fake = DoubleRatchet.Ciphertext(
            DoubleRatchet.Header(
                b64e(freshRatchetPubOf(alice0)),
                0, DoubleRatchet.MAX_SKIP + 5,
            ),
            b64e(ByteArray(12)), b64e(ByteArray(32)),
        )
        try {
            DoubleRatchet.decrypt(bob0, fake)
            fail("lompat > MAX_SKIP harus ditolak")
        } catch (e: Exception) {
            // ok
        }
    }

    private fun freshRatchetPubOf(state: DoubleRatchet.State): ByteArray =
        b64d(state.dhSelfPrivB64).let { x25519Public(it) }

    @Test
    fun ratchetHeaderRoundTrip() {
        val h = DoubleRatchet.Header("QUJDRA==", 3, 17)
        assertEquals(h, DoubleRatchet.Header.parse(h.canonical()))
        assertNull(DoubleRatchet.Header.parse("{\"rp\":}".toByteArray()))
    }

    @Test
    fun ratchetFuzzAlternating() {
        var alice: DoubleRatchet.State
        var bob: DoubleRatchet.State
        run {
            val (aId, _) = x25519KeyPair()
            val (bId, _) = x25519KeyPair()
            val (sp, _) = x25519KeyPair()
            val (ek, _) = x25519KeyPair()
            val (edPriv, edPub) = edKeyPair()
            val spPub = x25519Public(sp)
            val b = X3dh.Bundle(b64e(edPub), b64e(x25519Public(bId)), 1,
                b64e(spPub), b64e(edSign(edPriv, spPub)), null, null)
            val x = X3dh.initiator(b64e(aId), b, b64e(ek))
            val y = X3dh.responder(b64e(bId), b64e(sp), null, x.header)
            alice = DoubleRatchet.initAlice(x.sharedKey, b.signedPubB64)
            bob = DoubleRatchet.initBob(y.sharedKey, b64e(sp))
        }
        val rnd = java.util.Random(42)
        var turnAlice = true
        repeat(50) { i ->
            if (turnAlice) {
                val (s, ct) = DoubleRatchet.encrypt(alice, "p$i".toByteArray())
                alice = s
                val (s2, pt) = DoubleRatchet.decrypt(bob, ct)
                bob = s2
                assertEquals("p$i", pt.toString(Charsets.UTF_8))
            } else {
                val (s, ct) = DoubleRatchet.encrypt(bob, "q$i".toByteArray())
                bob = s
                val (s2, pt) = DoubleRatchet.decrypt(alice, ct)
                alice = s2
                assertEquals("q$i", pt.toString(Charsets.UTF_8))
            }
            if (rnd.nextBoolean()) turnAlice = !turnAlice
        }
    }

    // ------------------------------------------------------------ Sender key

    @Test
    fun senderKeyGroupFlow() {
        var send = SenderKey.create()
        val recvA = SenderKey.receive(SenderKey.distribution(send))
        val recvB = SenderKey.receive(SenderKey.distribution(send))
        // 3 pesan, B terima out-of-order.
        val c1 = SenderKey.encrypt(send, "devA", "halo".toByteArray()); send = c1.chain
        val c2 = SenderKey.encrypt(send, "devA", "semua".toByteArray()); send = c2.chain
        val c3 = SenderKey.encrypt(send, "devA", "grup".toByteArray())
        val (_, p1) = SenderKey.decrypt(recvA, c1.ciphertext)
        assertEquals("halo", p1.toString(Charsets.UTF_8))
        var rb = recvB
        val (rb2, p3) = SenderKey.decrypt(rb, c3.ciphertext); rb = rb2
        assertEquals("grup", p3.toString(Charsets.UTF_8))
        val (_, p2) = SenderKey.decrypt(rb, c2.ciphertext)
        assertEquals("semua", p2.toString(Charsets.UTF_8))
    }

    @Test
    fun senderKeyRejectsForged() {
        var send = SenderKey.create()
        val recv = SenderKey.receive(SenderKey.distribution(send))
        val c = SenderKey.encrypt(send, "devA", "x".toByteArray())
        // Ganti sender (AAD) → tanda tangan tak cocok.
        val forged = c.ciphertext.copy(senderDevice = "devEvil")
        try {
            SenderKey.decrypt(recv, forged)
            fail("pemalsuan sender harus ditolak")
        } catch (e: Exception) {
            // ok
        }
    }

    // ------------------------------------------------------------ Interop amplop

    @Test
    fun envelopePairwiseFullHandshake() {
        // Alice → Bob pesan pertama (X3DH di amplop) → Bob balas → Alice baca.
        val devA = "dev_aaaaaaaaaaaaaaaa"
        val devB = "dev_bbbbbbbbbbbbbbbb"
        val b = bundle()
        val init = X3dh.initiator(b64e(aliceIdDh), b, b64e(aliceEk))
        var alice = DoubleRatchet.initAlice(init.sharedKey, b.signedPubB64)
        // Pesan pertama Alice.
        val e1 = DoubleRatchet.encrypt(alice, "halo Bob".toByteArray()); alice = e1.state
        val wire1 = EnvelopeV2.pairwise(
            devA,
            mapOf(
                devB to EnvelopeV2.Part(
                    init.header, e1.ciphertext.header,
                    e1.ciphertext.nonceB64, e1.ciphertext.bodyB64,
                )
            ),
        )
        // Bob: buat sesi dari header X3DH di amplop, lalu dekrip.
        val p1 = EnvelopeV2.parse(wire1) as EnvelopeV2.Parsed.Pairwise
        val part1 = p1.parts.getValue(devB)
        val resp = X3dh.responder(
            b64e(bobIdDh), b64e(bobSpk), b64e(bobOtpk), part1.x3dh!!
        )
        var bob = DoubleRatchet.initBob(resp.sharedKey, b64e(bobSpk))
        val ct1 = DoubleRatchet.Ciphertext(part1.dr, part1.nonceB64, part1.ctB64)
        val (bob2, pt1) = DoubleRatchet.decrypt(bob, ct1); bob = bob2
        assertEquals("halo Bob", pt1.toString(Charsets.UTF_8))
        // Bob balas (tanpa X3DH); Alice dekrip balasan.
        val e2 = DoubleRatchet.encrypt(bob, "halo Alice".toByteArray()); bob = e2.state
        val wire2 = EnvelopeV2.pairwise(
            devB,
            mapOf(
                devA to EnvelopeV2.Part(
                    null, e2.ciphertext.header,
                    e2.ciphertext.nonceB64, e2.ciphertext.bodyB64,
                )
            ),
        )
        val p2 = EnvelopeV2.parse(wire2) as EnvelopeV2.Parsed.Pairwise
        val part2 = p2.parts.getValue(devA)
        assertNull(part2.x3dh)
        val ct2 = DoubleRatchet.Ciphertext(part2.dr, part2.nonceB64, part2.ctB64)
        val (_, pt2) = DoubleRatchet.decrypt(alice, ct2)
        assertEquals("halo Alice", pt2.toString(Charsets.UTF_8))
    }

    @Test
    fun envelopeSkdmWrapsSenderKey() {
        // SKDM = distribusi sender key lewat sesi pairwise, lewat amplop.
        val (alice, bob, _) = freshSession()
        var send = SenderKey.create()
        val dist = SenderKey.distribution(send)
        val skdmPt = EnvelopeV2.skdmPlaintext(dist.chainKeyB64, dist.signPubB64)
        val e = DoubleRatchet.encrypt(alice, skdmPt.toByteArray())
        val wire = EnvelopeV2.skdm(
            "dev_aaaaaaaaaaaaaaaa", "dev_bbbbbbbbbbbbbbbb", 99L,
            EnvelopeV2.Part(null, e.ciphertext.header, e.ciphertext.nonceB64, e.ciphertext.bodyB64),
        )
        val parsed = EnvelopeV2.parse(wire) as EnvelopeV2.Parsed.Skdm
        assertEquals(99L, parsed.conversationId)
        val ct = DoubleRatchet.Ciphertext(
            parsed.part.dr, parsed.part.nonceB64, parsed.part.ctB64
        )
        val (_, pt) = DoubleRatchet.decrypt(bob, ct)
        val (ck, sp) = EnvelopeV2.parseSkdmPlaintext(pt)!!
        assertEquals(dist.chainKeyB64, ck)
        assertEquals(dist.signPubB64, sp)
        // Rantai dari SKDM membuka pesan grup pengirim.
        val recv = SenderKey.receive(SenderKey.Distribution(ck, sp))
        val g = SenderKey.encrypt(send, "dev_aaaaaaaaaaaaaaaa", "grup ok".toByteArray())
        val (_, gpt) = SenderKey.decrypt(recv, g.ciphertext)
        assertEquals("grup ok", gpt.toString(Charsets.UTF_8))
    }

    @Test
    fun senderKeyRotation() {
        // Anggota keluar → rantai baru; pesan lama tak terbaca dengan baru.
        val old = SenderKey.create()
        val recvOld = SenderKey.receive(SenderKey.distribution(old))
        val fresh = SenderKey.create()
        val recvNew = SenderKey.receive(SenderKey.distribution(fresh))
        val c = SenderKey.encrypt(fresh, "devA", "baru".toByteArray())
        try {
            SenderKey.decrypt(recvOld, c.ciphertext)
            fail("rantai lama tak boleh buka pesan rantai baru")
        } catch (e: Exception) {
            // ok
        }
        val (_, pt) = SenderKey.decrypt(recvNew, c.ciphertext)
        assertEquals("baru", pt.toString(Charsets.UTF_8))
    }
}
