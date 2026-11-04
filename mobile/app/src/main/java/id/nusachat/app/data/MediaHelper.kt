package id.nusachat.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Unduh, buka, dan bagikan file: gambar/video/audio/file chat tersimpan ke
 * galeri/unduhan, lalu bisa dibuka aplikasi lain atau dibagikan ulang.
 */
object MediaHelper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class SavedFile(val uri: Uri, val displayName: String, val mime: String)

    /** Unduh ke cache privat (tidak masuk galeri) untuk diunggah ulang. */
    suspend fun downloadToCache(context: Context, url: String, fileName: String): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ApiException(response.code, "download_failed", "Unduhan gagal (HTTP ${response.code}).")
                val bytes = response.body?.bytes() ?: throw ApiException(0, "download_failed", "Unduhan kosong.")
                if (bytes.size > 25 * 1024 * 1024) throw ApiException(0, "too_large", "File lebih dari 25 MB.")
                val dir = File(context.cacheDir, "fetch").apply { mkdirs() }
                val file = File(dir, fileName)
                file.writeBytes(bytes)
                file
            }
        }

    suspend fun download(context: Context, url: String, hintName: String? = null): SavedFile =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ApiException(response.code, "download_failed", "Unduhan gagal (HTTP ${response.code}).")
                val bytes = response.body?.bytes() ?: throw ApiException(0, "download_failed", "Unduhan kosong.")
                val mime = response.header("Content-Type")?.substringBefore(";")?.trim()
                    ?.takeIf { it.contains("/") } ?: guessMime(url)
                val name = (hintName ?: url.substringAfterLast("/").substringBefore("?")).ifBlank { "nusachat-file" }
                val safeName = name.take(80).replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .let { if (it.contains(".")) it else it + extensionFor(mime) }
                saveBytes(context, bytes, safeName, mime)
            }
        }

    private fun guessMime(url: String): String {
        val ext = url.substringBefore("?").substringAfterLast(".", "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun extensionFor(mime: String): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return if (ext.isNullOrBlank()) "" else ".$ext"
    }

    private fun saveBytes(context: Context, bytes: ByteArray, name: String, mime: String): SavedFile {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NusaChat")
            }
            val uri = context.contentResolver.insert(collection, values)
                ?: throw ApiException(0, "save_failed", "Tidak bisa menyimpan file.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw ApiException(0, "save_failed", "Tidak bisa menyimpan file.")
            return SavedFile(uri, name, mime)
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "NusaChat")
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        return SavedFile(uri, name, mime)
    }

    fun openIntent(saved: SavedFile): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(saved.uri, saved.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun shareText(context: Context, text: String, title: String = "Bagikan") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareFile(context: Context, saved: SavedFile, title: String = "Bagikan") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = saved.mime
            putExtra(Intent.EXTRA_STREAM, saved.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Ukuran cache aplikasi (byte) untuk layar penyimpanan. */
    fun cacheSize(context: Context): Long {
        fun dirSize(dir: File?): Long {
            if (dir == null || !dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) }
        }
        return dirSize(context.cacheDir) + dirSize(context.externalCacheDir)
    }

    suspend fun clearCache(context: Context): Unit = withContext(Dispatchers.IO) {
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
            dir.listFiles()?.forEach { runCatching { if (it.isDirectory) it.deleteRecursively() else it.delete() } }
        }
        val coil = File(context.cacheDir, "coil")
        runCatching { if (coil.exists()) coil.deleteRecursively() }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
