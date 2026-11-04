package id.nusachat.app.data.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Primitif kriptografi — SEMUA dari library teruji (BouncyCastle /
 * javax.crypto). Tidak ada satu pun algoritma yang ditulis sendiri.
 *
 * Murni JVM (tanpa android.*) agar bisa di-unit-test di JVM biasa.
 */
object CryptoPrimitives {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun b64e(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s)

    // ---------------------------------------------------------- X25519

    fun x25519KeyPair(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(random)
        return priv.encoded to priv.generatePublicKey().encoded
    }

    fun x25519Public(priv32: ByteArray): ByteArray {
        require(priv32.size == 32) { "x25519 priv must be 32 bytes" }
        return X25519PrivateKeyParameters(priv32).generatePublicKey().encoded
    }

    fun x25519Agree(priv32: ByteArray, pub32: ByteArray): ByteArray {
        require(priv32.size == 32 && pub32.size == 32) { "x25519 keys must be 32 bytes" }
        val out = ByteArray(32)
        X25519PrivateKeyParameters(priv32)
            .generateSecret(X25519PublicKeyParameters(pub32), out, 0)
        return out
    }

    // ---------------------------------------------------------- Ed25519

    fun edKeyPair(): Pair<ByteArray, ByteArray> {
        val priv = Ed25519PrivateKeyParameters(random)
        return priv.encoded to priv.generatePublicKey().encoded
    }

    fun edPublic(priv32: ByteArray): ByteArray {
        require(priv32.size == 32) { "ed25519 priv must be 32 bytes" }
        return Ed25519PrivateKeyParameters(priv32).generatePublicKey().encoded
    }

    fun edSign(priv32: ByteArray, message: ByteArray): ByteArray {
        require(priv32.size == 32) { "ed25519 priv must be 32 bytes" }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(priv32))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun edVerify(pub32: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (pub32.size != 32 || signature.size != 64) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(pub32))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------- HKDF/HMAC

    /** HKDF-SHA256 (extract+expand). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        return ByteArray(outLen).also { gen.generateBytes(it, 0, outLen) }
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ---------------------------------------------------------- AES-GCM

    data class AesCiphertext(val nonce12: ByteArray, val bytes: ByteArray)

    fun aesEncrypt(key32: ByteArray, plaintext: ByteArray, aad: ByteArray, nonce12: ByteArray? = null): AesCiphertext {
        require(key32.size == 32) { "aes key must be 32 bytes" }
        val nonce = nonce12 ?: randomBytes(12)
        require(nonce.size == 12) { "nonce must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key32, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return AesCiphertext(nonce, cipher.doFinal(plaintext))
    }

    /** @throws javax.crypto.AEADBadTagException bila kunci/AAD/ciphertext salah. */
    fun aesDecrypt(key32: ByteArray, nonce12: ByteArray, bytes: ByteArray, aad: ByteArray): ByteArray {
        require(key32.size == 32) { "aes key must be 32 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key32, "AES"), GCMParameterSpec(128, nonce12))
        cipher.updateAAD(aad)
        return cipher.doFinal(bytes)
    }

    /** Hapus materi kunci dari memori (best-effort, panggil setelah pakai). */
    fun wipe(vararg arrays: ByteArray) {
        arrays.forEach { it.fill(0) }
    }
}
