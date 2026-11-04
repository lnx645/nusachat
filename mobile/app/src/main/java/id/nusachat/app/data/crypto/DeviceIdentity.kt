package id.nusachat.app.data.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import id.nusachat.app.data.crypto.CryptoPrimitives.b64d
import id.nusachat.app.data.crypto.CryptoPrimitives.b64e
import id.nusachat.app.data.crypto.CryptoPrimitives.edKeyPair
import id.nusachat.app.data.crypto.CryptoPrimitives.edPublic
import id.nusachat.app.data.crypto.CryptoPrimitives.edSign
import id.nusachat.app.data.crypto.CryptoPrimitives.x25519KeyPair
import id.nusachat.app.data.crypto.CryptoPrimitives.x25519Public
import java.util.UUID

/**
 * Identitas device E2EE per device (docs/E2EE-PROTOCOL.md).
 *
 * - 1 device_id stabil (`dev_` + hex, disimpan, tidak berubah).
 * - Identity signing (Ed25519) + identity DH (X25519), dibuat sekali.
 * - Signed prekey (rotasi ≤30 hari) + one-time prekey (batch 100).
 * - Privat TIDAK PERNAH keluar HP; yang naik ke server hanya publik.
 *
 * Lapisan tipis Android (penyimpanan); logika kripto di modul murni.
 */
class DeviceIdentity(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "nusa.device.v2",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    data class SignedPrekey(val keyId: Int, val privB64: String, val pubB64: String, val sigB64: String)
    data class Otpk(val keyId: Int, val privB64: String, val pubB64: String)

    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val fresh = "dev_" + UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        return fresh
    }

    fun identitySignPrivB64(): String = getOrCreate(KEY_ED_PRIV) { b64e(edKeyPair().first) }
    fun identityDhPrivB64(): String = getOrCreate(KEY_X_PRIV) { b64e(x25519KeyPair().first) }

    fun identitySignPubB64(): String = b64e(edPublic(b64d(identitySignPrivB64())))
    fun identityDhPubB64(): String = b64e(x25519Public(b64d(identityDhPrivB64())))

    fun deviceName(): String {
        prefs.getString(KEY_NAME, null)?.let { return it }
        val fresh = "Android ${Build.MANUFACTURER} ${Build.MODEL}".take(64)
        prefs.edit().putString(KEY_NAME, fresh).apply()
        return fresh
    }

    /** Signed prekey aktif; buat baru bila belum ada / dipaksa rotasi. */
    fun signedPrekey(forceRotate: Boolean = false): SignedPrekey {
        if (!forceRotate) {
            val id = prefs.getInt(KEY_SPK_ID, 0)
            val priv = prefs.getString(KEY_SPK_PRIV, null)
            val pub = prefs.getString(KEY_SPK_PUB, null)
            val sig = prefs.getString(KEY_SPK_SIG, null)
            if (id > 0 && priv != null && pub != null && sig != null) {
                return SignedPrekey(id, priv, pub, sig)
            }
        }
        // Simpan yang lama: peer yang sudah mengambil bundle lama masih
        // boleh menyelesaikan handshake (satu generasi ke belakang).
        val edit = prefs.edit()
        val oldId = prefs.getInt(KEY_SPK_ID, 0)
        val oldPriv = prefs.getString(KEY_SPK_PRIV, null)
        if (oldId > 0 && oldPriv != null) {
            edit.putInt(KEY_SPK_PREV_ID, oldId).putString(KEY_SPK_PREV_PRIV, oldPriv)
        }
        val id = prefs.getInt(KEY_SPK_NEXT, 1)
        val (priv, pub) = x25519KeyPair()
        val sig = edSign(b64d(identitySignPrivB64()), pub)
        edit.putInt(KEY_SPK_ID, id)
            .putString(KEY_SPK_PRIV, b64e(priv))
            .putString(KEY_SPK_PUB, b64e(pub))
            .putString(KEY_SPK_SIG, b64e(sig))
            .putLong(KEY_SPK_CREATED, System.currentTimeMillis())
            .putInt(KEY_SPK_NEXT, id + 1)
            .apply()
        return SignedPrekey(id, b64e(priv), b64e(pub), b64e(sig))
    }

    /** Privkey signed-prekey aktif ATAU satu generasi sebelumnya. */
    fun signedPrekeyPriv(keyId: Int): String? {
        if (prefs.getInt(KEY_SPK_ID, 0) == keyId) {
            return prefs.getString(KEY_SPK_PRIV, null)
        }
        if (prefs.getInt(KEY_SPK_PREV_ID, 0) == keyId) {
            return prefs.getString(KEY_SPK_PREV_PRIV, null)
        }
        return null
    }

    /** Rotasi ≤30 hari sesuai spek (dipanggil saat provision/login). */
    fun signedPrekeyNeedsRotation(): Boolean {
        if (prefs.getInt(KEY_SPK_ID, 0) <= 0) return true
        val created = prefs.getLong(KEY_SPK_CREATED, 0L)
        if (created <= 0L) return true
        return System.currentTimeMillis() - created > SPK_MAX_AGE_MS
    }

    /** Sisa one-time prekey lokal (untuk isi ulang bila <20). */
    fun otpkRemaining(): Int =
        runCatching {
            prefs.all.keys.count { it.startsWith(KEY_OTPK) }
        }.getOrDefault(0)

    /** Buat batch one-time prekey baru (default 100). */
    fun mintOtpks(count: Int = 100): List<Otpk> {
        var next = prefs.getInt(KEY_OTPK_NEXT, 1)
        val out = ArrayList<Otpk>(count)
        val edit = prefs.edit()
        repeat(count) {
            val (priv, pub) = x25519KeyPair()
            val id = next++
            edit.putString(KEY_OTPK + id, b64e(priv))
            out.add(Otpk(id, b64e(priv), b64e(pub)))
        }
        edit.putInt(KEY_OTPK_NEXT, next).apply()
        return out
    }

    /** Ambil + HAPUS privkey one-time (sekali pakai, sesuai spek). */
    fun consumeOtpk(keyId: Int): String? {
        val key = KEY_OTPK + keyId
        val priv = prefs.getString(key, null) ?: return null
        prefs.edit().remove(key).apply()
        return priv
    }

    /** Hapus semua (logout / reset). Sesi di [CryptoStore] ikut dibuang. */
    fun wipeAll() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreate(key: String, make: () -> String): String {
        prefs.getString(key, null)?.let { return it }
        val fresh = make()
        prefs.edit().putString(key, fresh).apply()
        return fresh
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ED_PRIV = "id_ed_priv"
        private const val KEY_X_PRIV = "id_x_priv"
        private const val KEY_NAME = "device_name"
        private const val KEY_SPK_ID = "spk_id"
        private const val KEY_SPK_PRIV = "spk_priv"
        private const val KEY_SPK_PUB = "spk_pub"
        private const val KEY_SPK_SIG = "spk_sig"
        private const val KEY_SPK_NEXT = "spk_next"
        private const val KEY_SPK_CREATED = "spk_created"
        private const val KEY_SPK_PREV_ID = "spk_prev_id"
        private const val KEY_SPK_PREV_PRIV = "spk_prev_priv"
        private const val SPK_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val KEY_OTPK = "otpk:"
        private const val KEY_OTPK_NEXT = "otpk_next"
    }
}
