package id.nusachat.app.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyFactory
import java.security.KeyPair
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * E2EE per-percakapan: AES-256 GCM untuk pesan, RSA-2048 OAEP untuk
 * mendistribusikan kunci sesi antar perangkat/anggota.
 *
 * Alur: tiap perangkat punya pasangan kunci RSA; kunci publik diunggah ke
 * server. Pembuat/penyembuh percakapan membungkus kunci sesi AES dengan kunci
 * publik tiap anggota (`conversation_keys`). Server hanya melihat blob opaque.
 * Bukan double-ratchet produksi (tanpa forward secrecy / rotasi otomatis).
 */
class E2eeManager(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "nusachat.e2ee",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val random = SecureRandom()

    fun isEnabled(conversationId: Long): Boolean = prefs.contains(keyName(conversationId))

    fun ensureKey(conversationId: Long): SecretKey {
        val existing = prefs.getString(keyName(conversationId), null)
        if (existing != null) {
            val raw = Base64.decode(existing, Base64.NO_WRAP)
            return SecretKeySpec(raw, "AES")
        }
        val gen = KeyGenerator.getInstance("AES").apply { init(256, random) }
        val key = gen.generateKey()
        prefs.edit().putString(keyName(conversationId), Base64.encodeToString(key.encoded, Base64.NO_WRAP)).apply()
        return key
    }

    fun encrypt(conversationId: Long, plain: String): String {
        if (plain.isEmpty()) return plain
        val key = ensureKey(conversationId)
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv, 0, 12))
        }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = iv + ct
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(conversationId: Long, cipherText: String?): String? {
        if (cipherText == null) return null
        if (!isEnabled(conversationId)) return cipherText // belum ada kunci = tampil apa adanya
        return try {
            val raw = Base64.decode(cipherText, Base64.NO_WRAP)
            if (raw.size < 13) return cipherText
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val keyB64 = prefs.getString(keyName(conversationId), null) ?: return cipherText
            val key = SecretKeySpec(Base64.decode(keyB64, Base64.NO_WRAP), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv, 0, 12))
            }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            cipherText
        }
    }

    fun decryptMessage(msg: Message): Message {
        if (msg.type != "text" || msg.body == null) return msg
        val dec = decrypt(msg.conversationId, msg.body) ?: return msg
        return if (dec == msg.body) msg else msg.copy(body = dec)
    }

    // ------------------------------------------------- pertukaran kunci RSA

    fun deviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"

    private fun rsaKeyPair(): KeyPair {
        val privB64 = prefs.getString("rsa_priv", null)
        val pubB64 = prefs.getString("rsa_pub", null)
        if (privB64 != null && pubB64 != null) {
            try {
                val factory = KeyFactory.getInstance("RSA")
                val priv = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
                val pub = factory.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
                return KeyPair(pub, priv)
            } catch (_: Exception) {
            }
        }
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        prefs.edit()
            .putString("rsa_priv", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .putString("rsa_pub", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .apply()
        return pair
    }

    fun publicKeyB64(): String =
        Base64.encodeToString(rsaKeyPair().public.encoded, Base64.NO_WRAP)

    /**
     * Sidik jari SHA-256 (hex) kunci publik — dipakai mendeteksi blob basi
     * (reinstall = RSA baru, device_id sama). Harus sama dengan key_fp server.
     */
    fun fingerprint(publicKeyB64: String): String? {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val raw = Base64.decode(publicKeyB64, Base64.NO_WRAP)
            digest.digest(raw).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Bungkus kunci sesi AES percakapan untuk pemilik kunci publik.
     * @return null bila belum ada kunci sesi lokal.
     */
    fun wrapSessionKey(conversationId: Long, publicKeyB64: String): String? {
        val raw = prefs.getString(keyName(conversationId), null) ?: return null
        return try {
            val factory = KeyFactory.getInstance("RSA")
            val pub = factory.generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyB64, Base64.NO_WRAP)))
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
                init(Cipher.ENCRYPT_MODE, pub)
            }
            val wrapped = cipher.doFinal(Base64.decode(raw, Base64.NO_WRAP))
            Base64.encodeToString(wrapped, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Buka kunci sesi terbungkus lalu simpan sebagai kunci lokal.
     */
    fun unwrapSessionKey(conversationId: Long, wrappedB64: String): Boolean {
        return try {
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
                init(Cipher.DECRYPT_MODE, rsaKeyPair().private)
            }
            val raw = cipher.doFinal(Base64.decode(wrappedB64, Base64.NO_WRAP))
            prefs.edit().putString(keyName(conversationId), Base64.encodeToString(raw, Base64.NO_WRAP)).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun keyName(id: Long) = "e2ee_key_$id"
}
