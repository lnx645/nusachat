package id.nusachat.app.data.crypto

import id.nusachat.app.data.crypto.CryptoPrimitives.aesDecrypt
import id.nusachat.app.data.crypto.CryptoPrimitives.aesEncrypt
import id.nusachat.app.data.crypto.CryptoPrimitives.b64d
import id.nusachat.app.data.crypto.CryptoPrimitives.b64e
import id.nusachat.app.data.crypto.CryptoPrimitives.edKeyPair
import id.nusachat.app.data.crypto.CryptoPrimitives.edSign
import id.nusachat.app.data.crypto.CryptoPrimitives.edVerify
import id.nusachat.app.data.crypto.CryptoPrimitives.hmacSha256
import id.nusachat.app.data.crypto.CryptoPrimitives.randomBytes
import id.nusachat.app.data.crypto.CryptoPrimitives.wipe

/**
 * Sender Key grup ala Signal (implementasi sendiri, murni).
 *
 * Tiap device pengirim punya 1 rantai per grup: chain key maju tiap
 * pesan (forward secrecy per pengirim), pesan ditandatangani Ed25519.
 * Distribusi awal via sesi pairwise (X3DH+DR) sebagai
 * [Distribution]; TIDAK PERNAH lewat server plaintext (server hanya
 * meneruskan ciphertext pairwise yang membungkusnya).
 */
object SenderKey {
    const val MAX_SKIP = 1000
    private val MSG_KEY = byteArrayOf(0x01)
    private val CHAIN_KEY = byteArrayOf(0x02)

    /** Rantai kirim milik sendiri. */
    data class SendChain(
        val chainKey: ByteArray,
        val signPrivB64: String,
        val signPubB64: String,
        val index: Int = 0,
    )

    /** Rantai terima dari satu device pengirim. */
    data class RecvChain(
        val chainKey: ByteArray,
        val signPubB64: String,
        val index: Int = 0,
        val skipped: Map<Int, ByteArray> = emptyMap(),
    )

    data class Distribution(val chainKeyB64: String, val signPubB64: String)

    data class GroupCiphertext(
        val senderDevice: String,
        val index: Int,
        val nonceB64: String,
        val bodyB64: String,
        val sigB64: String,
    )

    fun create(): SendChain {
        val (signPriv, signPub) = edKeyPair()
        return SendChain(
            chainKey = randomBytes(32),
            signPrivB64 = b64e(signPriv),
            signPubB64 = b64e(signPub),
        ).also { wipe(signPriv) }
    }

    fun distribution(chain: SendChain): Distribution =
        Distribution(b64e(chain.chainKey), chain.signPubB64)

    fun receive(distribution: Distribution): RecvChain =
        RecvChain(
            chainKey = b64d(distribution.chainKeyB64),
            signPubB64 = distribution.signPubB64,
        )

    data class EncryptResult(val chain: SendChain, val ciphertext: GroupCiphertext)

    fun encrypt(chain: SendChain, senderDevice: String, plaintext: ByteArray): EncryptResult {
        val msgKey = hmacSha256(chain.chainKey, MSG_KEY)
        val aad = aadFor(senderDevice, chain.index)
        val ct = aesEncrypt(msgKey, plaintext, aad)
        wipe(msgKey)
        val sigPayload = aad + ct.nonce12 + ct.bytes
        val sig = edSign(b64d(chain.signPrivB64), sigPayload)
        return EncryptResult(
            chain.copy(
                chainKey = hmacSha256(chain.chainKey, CHAIN_KEY),
                index = chain.index + 1,
            ),
            GroupCiphertext(
                senderDevice = senderDevice,
                index = chain.index,
                nonceB64 = b64e(ct.nonce12),
                bodyB64 = b64e(ct.bytes),
                sigB64 = b64e(sig),
            ),
        )
    }

    data class DecryptResult(val chain: RecvChain, val plaintext: ByteArray)

    /** Gagal = exception, state asal utuh. */
    fun decrypt(chain: RecvChain, ct: GroupCiphertext): DecryptResult {
        require(ct.senderDevice.isNotBlank()) { "sender kosong" }
        // 1. Verifikasi tanda tangan DULU (tolak palsu sebelum komputasi).
        val aad = aadFor(ct.senderDevice, ct.index)
        val sigOk = edVerify(
            b64d(chain.signPubB64), aad + b64d(ct.nonceB64) + b64d(ct.bodyB64), b64d(ct.sigB64)
        )
        require(sigOk) { "tanda tangan grup tidak valid" }
        // 2. Kunci terlewati?
        chain.skipped[ct.index]?.let { key ->
            val pt = aesDecrypt(key, b64d(ct.nonceB64), b64d(ct.bodyB64), aad)
            wipe(key)
            return DecryptResult(chain.copy(skipped = chain.skipped - ct.index), pt)
        }
        // 3. Maju sampai index (batas anti-DoS).
        val jump = ct.index - chain.index
        require(jump >= 0 && jump <= MAX_SKIP) { "index di luar jangkauan: $jump" }
        require(chain.skipped.size + jump <= MAX_SKIP) { "cache kunci penuh" }
        var ck = chain.chainKey
        val skipped = chain.skipped.toMutableMap()
        repeat(jump) { i ->
            skipped[chain.index + i] = hmacSha256(ck, MSG_KEY)
            ck = hmacSha256(ck, CHAIN_KEY)
        }
        val msgKey = hmacSha256(ck, MSG_KEY)
        val pt = try {
            aesDecrypt(msgKey, b64d(ct.nonceB64), b64d(ct.bodyB64), aad)
        } finally {
            wipe(msgKey)
        }
        return DecryptResult(
            RecvChain(
                chainKey = hmacSha256(ck, CHAIN_KEY),
                signPubB64 = chain.signPubB64,
                index = ct.index + 1,
                skipped = skipped,
            ),
            pt,
        )
    }

    private fun aadFor(senderDevice: String, index: Int): ByteArray =
        "NusaSKv1|$senderDevice|$index".toByteArray(Charsets.UTF_8)
}
