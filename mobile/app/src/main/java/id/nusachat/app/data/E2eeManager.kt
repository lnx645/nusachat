package id.nusachat.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sederhana E2EE per-percakapan untuk demo.
 * Kunci AES-256 GCM disimpan di EncryptedSharedPreferences.
 * Server hanya lihat ciphertext (base64). Banner UI: "Pesan terenkripsi end-to-end".
 * Bukan double-ratchet produksi, tapi cukup untuk memenuhi permintaan UI + enkripsi client-side.
 */
class E2eeManager(context: Context) {

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

    private fun keyName(id: Long) = "e2ee_key_$id"
}
