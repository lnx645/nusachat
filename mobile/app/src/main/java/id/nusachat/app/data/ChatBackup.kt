package id.nusachat.app.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup chat terenkripsi file lokal (.updb) ala WhatsApp — TANPA server.
 *
 * Latar: NusaChat itu device-centric: server hanya relay sementara (TTL
 * 30 hari, disapu 24 jam setelah semua penerima ack). Storage permanen =
 * Room di HP. Install ulang / hapus data = cache + kunci E2EE hilang.
 *
 * Solusi: HP mengenkripsi dump chat (plaintext hasil dekrip + kunci sesi
 * legacy) dengan AES-256-GCM memakai kunci milik user, lalu menyimpan ke
 * file `.updb` via Storage Access Framework (Documents/Downloads/Drive
 * terserah user). Kunci tak pernah keluar HP kecuali dicatat user.
 * Restore = pilih file .updb + masukkan kunci yang sama.
 *
 * Format file (.updb = teks base64 satu baris, gampang disalin):
 * base64(JSON {v, mode, salt_b64?, iter?, wrapped_b64?, nonce_b64?,
 *  iv_b64, ct_b64, backup_at, user_id, msg_count, conv_count})
 * - mode digits: backupKey = SHA-256(64 digit ASCII). 64 digit ditampilkan
 *   sekali untuk dicatat.
 * - mode pass: backupKey acak 32B, KEK = PBKDF2-HMAC-SHA256(passphrase,
 *   salt 16B, 200k), wrapped = AES-GCM(KEK, backupKey).
 * - payload = JSON {v, exported_at, user_id, conversations, messages, e2ee_keys}
 *   dienkripsi AES-256-GCM(backupKey, iv 12B).
 *
 * Media biner TIDAK ikut: yang disimpan URL + caption. URL upload di server
 * persisten (tidak disapu), jadi media tetap terbuka selama file masih ada.
 */
object ChatBackup {

    const val VERSION = 1
    const val PBKDF2_ITER = 200_000
    const val MAX_BLOB_B64 = 12 * 1024 * 1024

    data class BuiltBlob(
        val blobB64: String,
        /** Kunci untuk dicatat user: 64 digit atau passphrase (gema input). */
        val displayKey: String,
        val msgCount: Int,
        val convCount: Int,
    )

    data class Payload(
        val userId: Long,
        val exportedAt: String,
        val conversations: List<BackupConv>,
        val messages: List<Message>,
        val e2eeKeys: Map<Long, String>,
    )

    data class BackupConv(
        val id: Long,
        val title: String,
        val type: String,
    )

    private val random = SecureRandom()

    /** 64 digit desimal acak (0-9), ditampilkan sekali untuk dicatat. */
    fun generateDigits(): String {
        val sb = StringBuilder(64)
        repeat(64) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    fun isValidDigits(s: String): Boolean =
        s.length == 64 && s.all { it in '0'..'9' }

    /** digits (64 char ASCII) -> kunci 32B = SHA-256. Sama di HP lama & baru. */
    fun digitsToKey(digits: String): ByteArray {
        require(isValidDigits(digits)) { "Kunci harus 64 digit." }
        return MessageDigest.getInstance("SHA-256")
            .digest(digits.toByteArray(Charsets.US_ASCII))
    }

    fun randomBackupKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun randomSalt(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    /** KEK dari passphrase (PBKDF2-HMAC-SHA256). Argon2id ideal tapi butuh lib native. */
    fun deriveKek(passphrase: String, salt: ByteArray, iter: Int = PBKDF2_ITER): ByteArray {
        require(passphrase.length >= 4) { "Passphrase minimal 4 karakter." }
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iter, 256)
        return try {
            val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            f.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    internal fun aesEncrypt(key: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv to c.doFinal(plain)
    }

    internal fun aesDecrypt(key: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }

    private fun b64e(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s.trim())

    /** Bangun payload JSON (plaintext sebelum enkripsi). */
    fun payloadJson(p: Payload): String {
        val convs = JSONArray()
        p.conversations.forEach {
            convs.put(JSONObject().put("id", it.id).put("title", it.title).put("type", it.type))
        }
        val msgs = JSONArray()
        p.messages.forEach { msgs.put(it.toJson()) }
        val keys = JSONObject()
        p.e2eeKeys.forEach { (id, b64) -> keys.put(id.toString(), b64) }
        return JSONObject()
            .put("v", VERSION)
            .put("exported_at", p.exportedAt)
            .put("user_id", p.userId)
            .put("conversations", convs)
            .put("messages", msgs)
            .put("e2ee_keys", keys)
            .toString()
    }

    fun parsePayloadJson(raw: String): Payload {
        val o = JSONObject(raw)
        val convs = mutableListOf<BackupConv>()
        o.optJSONArray("conversations")?.let { a ->
            for (i in 0 until a.length()) {
                a.optJSONObject(i)?.let {
                    convs.add(BackupConv(it.optLong("id"), it.optString("title"), it.optString("type", "direct")))
                }
            }
        }
        val msgs = mutableListOf<Message>()
        o.optJSONArray("messages")?.let { a ->
            for (i in 0 until a.length()) {
                a.optJSONObject(i)?.let { runCatching { msgs.add(it.toMessage()) } }
            }
        }
        val keys = mutableMapOf<Long, String>()
        o.optJSONObject("e2ee_keys")?.let { k ->
            val it = k.keys()
            while (it.hasNext()) {
                val key = it.next()
                key.toLongOrNull()?.let { id -> keys[id] = k.optString(key) }
            }
        }
        return Payload(
            userId = o.optLong("user_id"),
            exportedAt = o.optString("exported_at"),
            conversations = convs,
            messages = msgs,
            e2eeKeys = keys,
        )
    }

    /**
     * Buat blob siap upload.
     * @param mode "digits" (pakai [digitsInput] 64 digit) atau "pass" (pakai [passphrase]).
     * Bila null/blank: digits dibuat acak baru dan dikembalikan di [BuiltBlob.displayKey].
     */
    fun createBlob(
        payload: Payload,
        mode: String = "digits",
        digitsInput: String? = null,
        passphrase: String? = null,
    ): BuiltBlob {
        val plain = payloadJson(payload).toByteArray(Charsets.UTF_8)
        val outer = JSONObject().put("v", VERSION)
        val backupKey: ByteArray
        val displayKey: String
        if (mode == "pass") {
            val pass = passphrase ?: throw IllegalArgumentException("Passphrase wajib diisi.")
            require(pass.length >= 4) { "Passphrase minimal 4 karakter." }
            backupKey = randomBackupKey()
            val salt = randomSalt()
            val kek = deriveKek(pass, salt)
            val (nonce, wrapped) = aesEncrypt(kek, backupKey)
            outer.put("mode", "pass")
                .put("salt_b64", b64e(salt))
                .put("iter", PBKDF2_ITER)
                .put("nonce_b64", b64e(nonce))
                .put("wrapped_b64", b64e(wrapped))
            displayKey = pass
        } else {
            val digits = digitsInput?.takeIf { it.isNotBlank() } ?: generateDigits()
            backupKey = digitsToKey(digits)
            outer.put("mode", "digits")
            displayKey = digits
        }
        val (iv, ct) = aesEncrypt(backupKey, plain)
        // Bersihkan kunci dari memori sesegera mungkin.
        backupKey.fill(0)
        outer.put("iv_b64", b64e(iv))
            .put("ct_b64", b64e(ct))
            .put("backup_at", payload.exportedAt)
            .put("user_id", payload.userId)
            .put("msg_count", payload.messages.size)
            .put("conv_count", payload.conversations.size)
        val blobB64 = Base64.getEncoder().encodeToString(outer.toString().toByteArray(Charsets.UTF_8))
        require(blobB64.length <= MAX_BLOB_B64) { "Backup terlalu besar (maks ~9 MB)." }
        return BuiltBlob(blobB64, displayKey, payload.messages.size, payload.conversations.size)
    }

    /**
     * Buka blob dengan kunci user ([digitsOrPass]: 64 digit atau passphrase,
     * otomatis dideteksi + dicoba sesuai header).
     * @throws IllegalArgumentException bila kunci salah / blob rusak.
     */
    fun openBlob(blobB64: String, digitsOrPass: String): Payload {
        val outerRaw = try {
            String(b64d(blobB64), Charsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalArgumentException("File backup rusak (bukan base64).")
        }
        val outer = try {
            JSONObject(outerRaw)
        } catch (_: Exception) {
            throw IllegalArgumentException("File backup rusak.")
        }
        val mode = outer.optString("mode", "digits")
        val backupKey: ByteArray = try {
            if (mode == "pass") {
                val salt = b64d(outer.getString("salt_b64"))
                val iter = outer.optInt("iter", PBKDF2_ITER)
                val kek = deriveKek(digitsOrPass, salt, iter)
                val nonce = b64d(outer.getString("nonce_b64"))
                val wrapped = b64d(outer.getString("wrapped_b64"))
                aesDecrypt(kek, nonce, wrapped).also { kek.fill(0) }
            } else {
                digitsToKey(digitsOrPass.trim())
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("Kunci salah atau backup rusak.")
        }
        try {
            val iv = b64d(outer.getString("iv_b64"))
            val ct = b64d(outer.getString("ct_b64"))
            val plain = aesDecrypt(backupKey, iv, ct)
            return parsePayloadJson(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            throw IllegalArgumentException("Kunci salah atau backup rusak.")
        } finally {
            backupKey.fill(0)
        }
    }

    /** Baca metadata tanpa kunci (untuk daftar backup). Tak perlu dekrip. */
    fun peekInfo(blobB64: String): Triple<Int, Int, String?> {
        return try {
            val outer = JSONObject(String(b64d(blobB64), Charsets.UTF_8))
            Triple(
                outer.optInt("msg_count", 0),
                outer.optInt("conv_count", 0),
                outer.optString("backup_at").takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            Triple(0, 0, null)
        }
    }
}
