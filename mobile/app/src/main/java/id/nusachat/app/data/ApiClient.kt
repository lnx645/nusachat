package id.nusachat.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(
    val statusCode: Int,
    val code: String,
    override val message: String,
) : Exception(message)

/**
 * Small JSON client on top of OkHttp. The API always answers with
 * {"ok": true, "data": ...} or {"ok": false, "error": {...}}.
 */
class ApiClient(
    private val baseUrl: String,
    private val session: SessionStore,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun get(path: String, query: Map<String, String?> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = (baseUrl + path).toHttpUrl().newBuilder()
            query.forEach { (key, value) ->
                if (value != null) builder.addQueryParameter(key, value)
            }
            val request = authorized(Request.Builder().url(builder.build()).get()).build()
            execute(request)
        }

    suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) {
            val request = authorized(
                Request.Builder()
                    .url(baseUrl + path)
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            ).build()
            execute(request)
        }

    suspend fun upload(context: Context, uri: Uri): JSONObject = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: IOException) {
            null
        } ?: throw ApiException(0, "file_error", "Tidak bisa membaca file yang dipilih.")

        if (bytes.size > 25 * 1024 * 1024) {
            throw ApiException(413, "file_too_large", "Ukuran file maksimal 25 MB.")
        }

        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val fileName = queryFileName(context, uri) ?: "upload"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaTypeOrNull()))
            .build()

        val request = authorized(
            Request.Builder().url(baseUrl + "/uploads").post(body)
        ).build()
        execute(request)
    }

    private fun authorized(builder: Request.Builder): Request.Builder {
        builder.header("Accept", "application/json")
        session.accessToken?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        return builder
    }

    private fun execute(request: Request): JSONObject {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException(
                0,
                "network_error",
                "Tidak dapat terhubung ke server. Periksa koneksi dan alamat server."
            )
        }

        response.use {
            val text = it.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw ApiException(
                    it.code,
                    "invalid_response",
                    if (it.code == 404) "Endpoint tidak ditemukan." else "Respons server tidak valid."
                )

            if (!it.isSuccessful || !json.optBoolean("ok", false)) {
                val error = json.optJSONObject("error")
                throw ApiException(
                    it.code,
                    error?.optString("code").orEmpty(),
                    error?.optString("message")?.takeIf { message -> message.isNotBlank() }
                        ?: "Permintaan gagal (HTTP ${it.code})."
                )
            }
            return json.optJSONObject("data") ?: JSONObject()
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
