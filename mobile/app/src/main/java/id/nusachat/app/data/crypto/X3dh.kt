package id.nusachat.app.data.crypto

import id.nusachat.app.data.crypto.CryptoPrimitives.b64d
import id.nusachat.app.data.crypto.CryptoPrimitives.b64e
import id.nusachat.app.data.crypto.CryptoPrimitives.edVerify
import id.nusachat.app.data.crypto.CryptoPrimitives.hkdf
import id.nusachat.app.data.crypto.CryptoPrimitives.wipe
import id.nusachat.app.data.crypto.CryptoPrimitives.x25519Agree
import id.nusachat.app.data.crypto.CryptoPrimitives.x25519KeyPair
import id.nusachat.app.data.crypto.CryptoPrimitives.x25519Public

/**
 * X3DH sesuai docs/E2EE-PROTOCOL.md (implementasi sendiri, murni).
 *
 * DH1 = DH(id_A, spk_B), DH2 = DH(ek_A, id_B), DH3 = DH(ek_A, spk_B),
 * [DH4 = DH(ek_A, otpk_B)]. SK = HKDF(0, DH1||DH2||DH3[||DH4], "NusaX3DHv1").
 */
object X3dh {
    private val INFO = "NusaX3DHv1".toByteArray(Charsets.UTF_8)
    private val ZERO_SALT = ByteArray(32)

    /** Bundle dari server (GET /devices/bundle), sudah JSON-decoded. */
    data class Bundle(
        val identitySignPubB64: String,
        val identityDhPubB64: String,
        val signedKeyId: Int,
        val signedPubB64: String,
        val signedSigB64: String,
        val onetimeKeyId: Int?,
        val onetimePubB64: String?,
    )

    /**
     * Header pesan pertama (plaintext, untuk responder merekonstruksi SK).
     * Pubkey ratchet pertama Alice TIDAK di sini — datang di header DR
     * pesan pertama, sesuai spek.
     */
    data class HandshakeHeader(
        val identityDhPubB64: String,
        val ephemeralPubB64: String,
        val signedKeyId: Int,
        val onetimeKeyId: Int?,
    ) {
        fun canonical(): ByteArray {
            val ot = onetimeKeyId?.toString() ?: "null"
            return ("{\"idh\":\"$identityDhPubB64\",\"ek\":\"$ephemeralPubB64\"," +
                "\"spk\":$signedKeyId,\"otk\":$ot}")
                .toByteArray(Charsets.UTF_8)
        }

        companion object {
            private val PATTERN = Regex(
                "\\{\"idh\":\"([A-Za-z0-9+/=]+)\",\"ek\":\"([A-Za-z0-9+/=]+)\"," +
                    "\"spk\":(\\d+),\"otk\":(null|\\d+)\\}"
            )
            fun parse(bytes: ByteArray): HandshakeHeader? {
                val m = PATTERN.matchEntire(bytes.toString(Charsets.UTF_8)) ?: return null
                val g = m.groupValues
                return HandshakeHeader(g[1], g[2], g[3].toInt(),
                    if (g[4] == "null") null else g[4].toInt())
            }
        }
    }

    data class InitiatorResult(val sharedKey: ByteArray, val header: HandshakeHeader)

    /**
     * Sisi inisiator. Verifikasi tanda tangan signed-prekey dulu;
     * gagal = exception (jangan lanjut dengan bundle palsu).
     * `ephemeralPrivB64` hanya untuk tes deterministik.
     */
    fun initiator(
        myIdentityDhPrivB64: String,
        bundle: Bundle,
        ephemeralPrivB64: String? = null,
    ): InitiatorResult {
        val sigOk = edVerify(
            b64d(bundle.identitySignPubB64),
            b64d(bundle.signedPubB64),
            b64d(bundle.signedSigB64),
        )
        require(sigOk) { "tanda tangan signed-prekey tidak valid" }
        val ekPriv = ephemeralPrivB64?.let { b64d(it) } ?: x25519KeyPair().first
        try {
            val dh1 = x25519Agree(b64d(myIdentityDhPrivB64), b64d(bundle.signedPubB64))
            val dh2 = x25519Agree(ekPriv, b64d(bundle.identityDhPubB64))
            val dh3 = x25519Agree(ekPriv, b64d(bundle.signedPubB64))
            val dh4 = if (bundle.onetimePubB64 != null) {
                x25519Agree(ekPriv, b64d(bundle.onetimePubB64))
            } else null
            val ikm = dh1 + dh2 + dh3 + (dh4 ?: ByteArray(0))
            val sk = hkdf(ikm, ZERO_SALT, INFO, 32)
            wipe(dh1, dh2, dh3)
            dh4?.let { wipe(it) }
            wipe(ikm)
            return InitiatorResult(
                sk,
                HandshakeHeader(
                    identityDhPubB64 = b64e(x25519Public(b64d(myIdentityDhPrivB64))),
                    ephemeralPubB64 = b64e(x25519Public(ekPriv)),
                    signedKeyId = bundle.signedKeyId,
                    onetimeKeyId = bundle.onetimeKeyId,
                ),
            )
        } finally {
            if (ephemeralPrivB64 == null) wipe(ekPriv)
        }
    }

    data class ResponderResult(val sharedKey: ByteArray, val onetimeKeyIdUsed: Int?)

    /**
     * Sisi responder. Butuh: privkey identity DH sendiri, privkey
     * signed-prekey yang cocok (`signedKeyId`), privkey one-time yang
     * cocok (bila header menyebutnya; HAPUS setelah dipakai).
     */
    fun responder(
        myIdentityDhPrivB64: String,
        signedPrivB64: String,
        onetimePrivB64: String?,
        header: HandshakeHeader,
    ): ResponderResult {
        val dh1 = x25519Agree(b64d(signedPrivB64), b64d(header.identityDhPubB64))
        val dh2 = x25519Agree(b64d(myIdentityDhPrivB64), b64d(header.ephemeralPubB64))
        val dh3 = x25519Agree(b64d(signedPrivB64), b64d(header.ephemeralPubB64))
        val dh4 = if (header.onetimeKeyId != null && onetimePrivB64 != null) {
            x25519Agree(b64d(onetimePrivB64), b64d(header.ephemeralPubB64))
        } else null
        val ikm = dh1 + dh2 + dh3 + (dh4 ?: ByteArray(0))
        val sk = hkdf(ikm, ZERO_SALT, INFO, 32)
        wipe(dh1, dh2, dh3)
        dh4?.let { wipe(it) }
        wipe(ikm)
        return ResponderResult(sk, header.onetimeKeyId)
    }
}
