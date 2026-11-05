package id.nusachat.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Kunci backup lokal .updb (EncryptedSharedPreferences + Keystore).
 *
 * Backup otomatis harian butuh kunci yang menetap di HP: user mencatatnya
 * sekali, HP memakainya tiap malam tanpa bertanya. Tanpa kunci tersimpan,
 * scheduler tak bisa jalan mandiri. Kunci tak pernah naik ke server —
 * file .updb + kunci catatannya tetap satu-satunya jalan pulih.
 *
 * Dihapus saat logout (satu akun = satu kunci; akun baru = kunci baru).
 */
class BackupKeys(context: Context) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "nusachat.backupkey",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    data class Secret(val mode: String, val value: String)

    /** Baca; null bila belum pernah disiapkan. */
    fun get(): Secret? {
        val mode = prefs.getString(KEY_MODE, null) ?: return null
        val value = prefs.getString(KEY_VALUE, null)?.takeIf { it.isNotBlank() } ?: return null
        return Secret(mode, value)
    }

    /**
     * Ambil yang ada atau buat baru (digits acak). Dipakai scheduler agar
     * backup malam pertama langsung jalan tanpa menunggu user.
     * @return secret + true bila baru dibuat (tampilkan ke user sekali).
     */
    fun getOrCreateDigits(): Pair<Secret, Boolean> {
        get()?.let { return it to false }
        val digits = ChatBackup.generateDigits()
        prefs.edit().putString(KEY_MODE, "digits").putString(KEY_VALUE, digits).apply()
        return Secret("digits", digits) to true
    }

    fun setDigits(digits: String) {
        require(ChatBackup.isValidDigits(digits)) { "Kunci harus 64 digit angka." }
        prefs.edit().putString(KEY_MODE, "digits").putString(KEY_VALUE, digits).apply()
    }

    fun setPassphrase(pass: String) {
        require(pass.length >= 4) { "Passphrase minimal 4 karakter." }
        prefs.edit().putString(KEY_MODE, "pass").putString(KEY_VALUE, pass).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_VALUE = "secret"
    }
}
