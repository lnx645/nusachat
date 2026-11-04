package id.nusachat.app.data

import id.nusachat.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Klien GIF Giphy API (trending + cari). Kunci dari BuildConfig
 * `GIF_API_KEY`; kosong berarti sheet hanya menerima tempel tautan manual.
 */
object GifClient {
    data class Gif(val id: String, val previewUrl: String, val fullUrl: String, val width: Int = 0, val height: Int = 0)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val hasKey: Boolean get() = BuildConfig.GIF_API_KEY.isNotBlank()

    suspend fun trending(limit: Int = 24): List<Gif> {
        if (!hasKey) return emptyList()
        return fetch("https://api.giphy.com/v1/gifs/trending?api_key=${BuildConfig.GIF_API_KEY}&limit=${limit.coerceIn(1, 50)}&rating=pg-13")
    }

    suspend fun search(query: String, limit: Int = 24): List<Gif> {
        if (!hasKey || query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return fetch("https://api.giphy.com/v1/gifs/search?api_key=${BuildConfig.GIF_API_KEY}&q=$q&limit=${limit.coerceIn(1, 50)}&rating=pg-13")
    }

    private suspend fun fetch(url: String): List<Gif> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get()
            .header("User-Agent", "NusaChat-Android/0.2.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "gif_failed", "GIF gagal dimuat (HTTP ${response.code}).")
            val body = response.body?.string().orEmpty()
            val results = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
            val out = mutableListOf<Gif>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val images = item.optJSONObject("images") ?: continue
                fun rendition(vararg names: String): JSONObject? {
                    for (name in names) {
                        val obj = images.optJSONObject(name)
                        if (obj != null && obj.optString("url").isNotBlank()) return obj
                    }
                    return null
                }
                val full = rendition("downsized_large", "original", "fixed_width") ?: continue
                val preview = rendition("fixed_width_downsampled", "fixed_width_small", "preview_gif", "fixed_width")
                val fullUrl = full.optString("url")
                if (fullUrl.isBlank()) continue
                out.add(
                    Gif(
                        id = item.optString("id", "$i"),
                        previewUrl = preview?.optString("url")?.takeIf { it.isNotBlank() } ?: fullUrl,
                        fullUrl = fullUrl,
                        width = full.optString("width").toIntOrNull() ?: 0,
                        height = full.optString("height").toIntOrNull() ?: 0,
                    )
                )
            }
            out
        }
    }
}
