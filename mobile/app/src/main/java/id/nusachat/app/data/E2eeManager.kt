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
        // Tanpa kunci: teks biasa (pra-E2EE) tampil apa adanya, blob cipher
        // JANGAN tampil mentah (bocor membingungkan + trauma reinstall).
        if (!isEnabled(conversationId)) {
            return if (looksLikeCiphertext(cipherText)) PLACEHOLDER_SYNC else cipherText
        }
        return try {
            val raw = Base64.decode(cipherText, Base64.NO_WRAP)
            if (raw.size < 13) return cipherText
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val keyB64 = prefs.getString(keyName(conversationId), null)
                ?: return if (looksLikeCiphertext(cipherText)) PLACEHOLDER_SYNC else cipherText
            val key = SecretKeySpec(Base64.decode(keyB64, Base64.NO_WRAP), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv, 0, 12))
            }
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            if (looksLikeCiphertext(cipherText)) PLACEHOLDER_SYNC else cipherText
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

    /** Ekspor semua kunci sesi legacy untuk backup terenkripsi (plaintext ikut dienkrip). */
    fun exportKeys(): Map<Long, String> {
        val out = mutableMapOf<Long, String>()
        for ((k, v) in prefs.all) {
            if (!k.startsWith("e2ee_key_")) continue
            val id = k.removePrefix("e2ee_key_").toLongOrNull() ?: continue
            (v as? String)?.takeIf { it.isNotBlank() }?.let { out[id] = it }
        }
        return out
    }

    /** Impor kunci sesi legacy dari backup (overwrite). */
    fun importKeys(keys: Map<Long, String>) {
        if (keys.isEmpty()) return
        val edit = prefs.edit()
        keys.forEach { (id, b64) ->
            if (b64.isNotBlank()) edit.putString(keyName(id), b64)
        }
        edit.apply()
    }

    companion object {
        /** Legacy tak terbaca tapi mungkin pulih (ala WhatsApp, tanpa kurung). */
        const val PLACEHOLDER_SYNC = "Pesan ini tidak tersedia, atau membutuhkan waktu untuk ditampilkan."

        /** String fallback KURUNG era lama → dipetakan ke gaya baru saat tampil. */
        const val LEGACY_SYNC = "[Menunggu sinkronisasi kunci…]"
        const val LEGACY_BROKEN = "[Pesan terenkripsi tidak dapat dibuka]"
        const val LEGACY_SELF = "[Pesan terkirim]"
        const val LEGACY_ENCRYPTED = "[Pesan terenkripsi]"

        /**
         * Heuristik blob cipher legacy: base64 panjang tanpa spasi.
         * Teks biasa (pendek/ada spasi/tanda baca) → false.
         */
        fun looksLikeCiphertext(s: String?): Boolean {
            if (s == null || s.length < 24) return false
            if (s.any { it.isWhitespace() }) return false
            if (s.startsWith("[") && s.endsWith("]")) return false
            return s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }
        }

        /** Isi cache layak tampil (bukan placeholder/cipher). */
        fun isReadableBody(body: String?): Boolean {
            if (body == null) return true
            if (body == PLACEHOLDER_SYNC || body == LEGACY_SYNC) return false
            if (body == LEGACY_BROKEN || body == LEGACY_SELF || body == LEGACY_ENCRYPTED) return false
            if (body == SessionCrypto.PLACEHOLDER_BROKEN || body == SessionCrypto.PLACEHOLDER_SELF) return false
            return !looksLikeCiphertext(body)
        }

        /**
         * Permanen tak-terbaca (kunci hilang/rantai v2 mati): jangan ditampil
         * atau disimpan — chat terlihat kosong & fresh seperti habis install.
         * Beda dengan SYNC ("membutuhkan waktu") yang masih bisa heal dan
         * tetap ditampil/disimpan.
         */
        fun isDeadBody(body: String?): Boolean {
            if (body == null) return false
            return body == SessionCrypto.PLACEHOLDER_BROKEN ||
                body == SessionCrypto.PLACEHOLDER_SELF ||
                body == LEGACY_BROKEN ||
                body == LEGACY_SELF
        }

        /**
         * Teks fallback rapi ala WhatsApp untuk body tak terbaca, atau null
         * bila body normal. Cipher mentah yang lolos = SYNC (mungkin pulih).
         */
        fun fallbackTextFor(body: String?): String? {
            if (body == null) return null
            if (body == PLACEHOLDER_SYNC || body == LEGACY_SYNC || body == LEGACY_ENCRYPTED) {
                return PLACEHOLDER_SYNC
            }
            if (looksLikeCiphertext(body)) return PLACEHOLDER_SYNC
            if (body == SessionCrypto.PLACEHOLDER_BROKEN || body == LEGACY_BROKEN) {
                return SessionCrypto.PLACEHOLDER_BROKEN
            }
            if (body == SessionCrypto.PLACEHOLDER_SELF || body == LEGACY_SELF) {
                return SessionCrypto.PLACEHOLDER_SELF
            }
            return null
        }

        /** Blob fresh = sidik cocok pubkey pemilik saat ini (holder mungkin hidup). */
        fun blobIsFresh(holderFp: String, currentFp: String?): Boolean =
            holderFp.isNotBlank() && currentFp != null && holderFp == currentFp

        /**
         * Ada holder hidup? Blob device-kosong diabaikan (lookup server
         * strict per-device → tak terbuka siapa pun).
         */
        fun anyFreshHolder(
            holders: List<KeyHolder>,
            currentFpOf: (KeyHolder) -> String?,
        ): Boolean = holders.any { h ->
            h.deviceId.isNotBlank() && blobIsFresh(h.keyFp, currentFpOf(h))
        }

        /** Re-key hanya userId terkecil (hindari K2 ganda dua pihak keyless). */
        fun shouldRekey(myId: Long, memberIds: List<Long>): Boolean {
            val all = (memberIds + myId).filter { it > 0 }.distinct()
            if (all.isEmpty()) return true
            return myId == all.min()
        }

        /** Batas holder dianggap hidup: publish < 60 hari (ganti HP = basi). */
        const val FRESH_DEVICE_DAYS = 60L

        /**
         * Device holder hidup? updatedAt kosong/tak parse = true (konservatif:
         * server lama tanpa kolom → perilaku tunggu seperti dulu).
         */
        fun isFreshDevice(updatedAtIso: String, nowMs: Long): Boolean {
            if (updatedAtIso.isBlank()) return true
            val at = runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(updatedAtIso)?.time
            }.getOrNull() ?: return true
            return nowMs - at < FRESH_DEVICE_DAYS * 24 * 3600 * 1000L
        }
    }
}
