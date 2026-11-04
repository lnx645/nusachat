package id.nusachat.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Persists the bearer tokens and a snapshot of the signed-in user.
 *
 * Tokens live in EncryptedSharedPreferences (AES256-GCM via Android Keystore).
 * Migrasi sekali jalan dari file plaintext lama ("nusachat.session") bila ada.
 * Bila Keystore tidak tersedia, fallback ke prefs biasa agar login tidak mati —
 * kasus ini dicatat di docs/SECURITY-AUDIT.md (H1).
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences = openPrefs(context)

    @Volatile
    var accessToken: String? = prefs.getString(KEY_ACCESS, null)
        private set

    @Volatile
    var refreshToken: String? = prefs.getString(KEY_REFRESH, null)
        private set

    @Volatile
    var user: User? = prefs.getString(KEY_USER, null)?.let { raw ->
        runCatching { JSONObject(raw).toUser() }.getOrNull()
    }
        private set

    val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank()

    fun saveSession(user: User, accessToken: String, refreshToken: String) {
        this.user = user
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putString(KEY_USER, user.toJson().toString())
            .apply()
    }

    fun updateUser(user: User) {
        this.user = user
        prefs.edit().putString(KEY_USER, user.toJson().toString()).apply()
    }

    fun clear() {
        user = null
        accessToken = null
        refreshToken = null
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "nusachat.session"
        const val PREFS_NAME_ENC = "nusachat.session.enc"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER = "user"

        fun openPrefs(context: Context): SharedPreferences {
            val encrypted = runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME_ENC,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrNull() ?: return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            migrateLegacy(context, encrypted)
            return encrypted
        }

        /** Pindahkan token plaintext lama ke store terenkripsi, sekali saja. */
        fun migrateLegacy(context: Context, encrypted: SharedPreferences) {
            if (encrypted.contains(KEY_ACCESS)) return
            val legacy = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val access = legacy.getString(KEY_ACCESS, null) ?: return
            val refresh = legacy.getString(KEY_REFRESH, null) ?: return
            encrypted.edit()
                .putString(KEY_ACCESS, access)
                .putString(KEY_REFRESH, refresh)
                .putString(KEY_USER, legacy.getString(KEY_USER, null))
                .apply()
            legacy.edit().clear().apply()
        }
    }
}
