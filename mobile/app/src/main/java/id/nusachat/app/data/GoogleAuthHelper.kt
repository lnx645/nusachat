package id.nusachat.app.data

import android.content.Context
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import id.nusachat.app.BuildConfig
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Profil publik dari ID token (display saja; server yang memverifikasi). */
data class GoogleProfile(val email: String, val name: String, val picture: String)

class GoogleCancelled : CancellationException("Pemilihan akun Google dibatalkan.")

/**
 * Login Google via Credential Manager. Butuh GOOGLE_SERVER_CLIENT_ID
 * (Web client ID; sementara client ID Android juga diterima server
 * selama tercantum di GOOGLE_CLIENT_IDS).
 */
object GoogleAuthHelper {
    fun isConfigured(): Boolean = BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank()

    /** Minta ID token; panggil dari Activity (bukan applicationContext). */
    suspend fun requestIdToken(context: Context): String {
        if (!isConfigured()) throw ApiException(0, "google_not_configured", "Login Google belum dikonfigurasi.")
        val manager = CredentialManager.create(context)
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_SERVER_CLIENT_ID)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        try {
            val result = manager.getCredential(context, request)
            return GoogleIdTokenCredential.createFrom(result.credential.data).idToken
        } catch (e: GetCredentialCancellationException) {
            throw GoogleCancelled()
        } catch (e: NoCredentialException) {
            throw ApiException(0, "google_no_account", "Tidak ada akun Google di perangkat ini.")
        }
    }

    /** Baca klaim publik ID token tanpa verifikasi (prefill form saja). */
    fun parsePublic(idToken: String): GoogleProfile {
        val seg = idToken.split(".")
        if (seg.size != 3) return GoogleProfile("", "", "")
        val json = runCatching {
            JSONObject(String(Base64.decode(seg[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
        }.getOrNull() ?: return GoogleProfile("", "", "")
        return GoogleProfile(
            email = json.optString("email"),
            name = json.optString("name"),
            picture = json.optString("picture"),
        )
    }
}
