package id.nusachat.app.data

import android.content.Context
import org.json.JSONObject

/**
 * Persists the bearer tokens and a snapshot of the signed-in user.
 *
 * Tokens live in private SharedPreferences for the demo. For a production app,
 * swap this class for EncryptedSharedPreferences or the Android Keystore.
 */
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER = "user"
    }
}
