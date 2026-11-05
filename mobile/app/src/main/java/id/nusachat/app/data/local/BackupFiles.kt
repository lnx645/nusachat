package id.nusachat.app.data.local

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nama file backup — murni, unit-testable tanpa Android.
 *
 * Folder tetap `Download/NusaChat/`, pola `nusachat-backup-*.updb`:
 * - `nusachat-backup-latest.updb` ditimpa tiap backup otomatis,
 * - `nusachat-backup-YYYYMMDD-HHmm.updb` arsip harian (disimpan 7 terakhir).
 */
object BackupNames {
    const val PREFIX = "nusachat-backup-"
    const val EXT = ".updb"
    const val LATEST = "nusachat-backup-latest.updb"
    const val MIME = "application/octet-stream"
    /** Relative path MediaStore (API 29+). */
    const val REL_PATH = "Download/NusaChat/"
    const val KEEP_STAMPED = 7

    fun stampedNow(now: Long = System.currentTimeMillis()): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        return "$PREFIX$ts$EXT"
    }

    fun isBackupName(name: String): Boolean =
        name.startsWith(PREFIX) && name.endsWith(EXT)

    fun isStamped(name: String): Boolean =
        isBackupName(name) && name != LATEST

    /**
     * Pilih arsip yang dihapus agar sisa ≤ [keep] file terbaru.
     * Input boleh tak urut; output = nama yang boleh dihapus.
     */
    fun prune(stamped: List<String>, keep: Int = KEEP_STAMPED): List<String> {
        if (keep < 0) return stamped
        return stamped.sortedDescending().drop(keep)
    }
}

data class BackupFileEntry(
    val name: String,
    val size: Long,
    /** Epoch ms; 0 bila tak diketahui. */
    val modified: Long,
    /** Cara baca: content Uri (MediaStore) atau file langsung (API lama). */
    val uri: Uri? = null,
    val file: File? = null,
)

/**
 * Folder backup tetap di Download/NusaChat — tanpa cloud, tanpa picker
 * untuk alur otomatis. API 29+: MediaStore (tanpa izin); API 24–28:
 * File API + WRITE_EXTERNAL_STORAGE (maxSdk 28 di manifest).
 */
class BackupFiles(context: Context) {
    private val app = context.applicationContext

    fun saveFixed(displayName: String, bytes: ByteArray) {
        require(BackupNames.isBackupName(displayName)) { "Nama file harus *.updb" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveMediaStore(displayName, bytes)
        } else {
            saveLegacy(displayName, bytes)
        }
    }

    fun list(): List<BackupFileEntry> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listMediaStore()
        else listLegacy()

    fun read(entry: BackupFileEntry): String {
        val bytes = when {
            entry.uri != null -> app.contentResolver.openInputStream(entry.uri)?.use { it.readBytes() }
            entry.file != null -> entry.file.readBytes()
            else -> null
        } ?: throw IllegalStateException("Tidak bisa membaca ${entry.name}.")
        return bytes.toString(Charsets.UTF_8).trim()
    }

    fun delete(entry: BackupFileEntry): Boolean = runCatching {
        when {
            entry.uri != null -> app.contentResolver.delete(entry.uri, null, null) > 0
            entry.file != null -> entry.file.delete()
            else -> false
        }
    }.getOrDefault(false)

    // ---------------------------------------------------------- API 29+ ---

    private fun saveMediaStore(displayName: String, bytes: ByteArray) {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // Timpa file lama bernama sama (MediaStore tak punya UPSERT).
        app.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(displayName, BackupNames.REL_PATH),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                runCatching {
                    app.contentResolver.delete(ContentUris.withAppendedId(collection, id), null, null)
                }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, BackupNames.MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, BackupNames.REL_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Tidak bisa membuat file backup.")
        try {
            app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Tidak bisa menulis file backup.")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            app.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { app.contentResolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun listMediaStore(): List<BackupFileEntry> {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val out = mutableListOf<BackupFileEntry>()
        runCatching {
            app.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_MODIFIED,
                ),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf(BackupNames.PREFIX + "%" + BackupNames.EXT),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    if (!BackupNames.isBackupName(name)) continue
                    out.add(
                        BackupFileEntry(
                            name = name,
                            size = runCatching { c.getLong(sizeCol) }.getOrDefault(0L),
                            modified = runCatching { c.getLong(dateCol) * 1000L }.getOrDefault(0L),
                            uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                        )
                    )
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------- API 24-28 ---

    private fun legacyDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "NusaChat",
        )
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    private fun saveLegacy(displayName: String, bytes: ByteArray) {
        File(legacyDir(), displayName).writeBytes(bytes)
    }

    private fun listLegacy(): List<BackupFileEntry> {
        val dir = legacyDir()
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles { f -> f.isFile && BackupNames.isBackupName(f.name) }.orEmpty())
            .map { f -> BackupFileEntry(f.name, f.length(), f.lastModified(), file = f) }
            .sortedByDescending { it.modified }
    }
}
