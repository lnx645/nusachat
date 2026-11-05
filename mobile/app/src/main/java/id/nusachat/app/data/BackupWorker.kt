package id.nusachat.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import id.nusachat.app.data.local.BackupFiles
import id.nusachat.app.data.local.BackupNames
import id.nusachat.app.data.local.ChatCache
import java.util.concurrent.TimeUnit

/**
 * Backup otomatis harian ke `Download/NusaChat/` — tanpa cloud, tanpa server.
 *
 * Tiap jalan: baca kunci tersimpan (atau buat digits baru bila belum ada),
 * dump plaintext cache + kunci legacy, enkripsi AES-GCM, tulis
 * `nusachat-backup-latest.updb` + arsip `nusachat-backup-<timestamp>.updb`,
 * pangkas arsip sisa 7 terakhir. Chat kosong = sukses no-op (bukan gagal)
 * agar scheduler tidak retry sia-sia.
 */
class BackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val app = applicationContext
            val (secret, _) = BackupKeys(app).getOrCreateDigits()
            val cache = ChatCache(app)
            val msgs = runCatching { cache.exportAll() }.getOrDefault(emptyList())
            if (msgs.isEmpty()) return Result.success()
            val convMeta = msgs.groupBy { it.conversationId }.map { (convId, list) ->
                val title = list.firstNotNullOfOrNull { it.conversationTitle?.takeIf { t -> t.isNotBlank() } }
                    ?: "Chat"
                val type = list.firstOrNull()?.conversationType?.takeIf { it.isNotBlank() } ?: "direct"
                ChatBackup.BackupConv(convId, title, type)
            }
            // user_id tak kritis untuk restore lokal; 0 bila tak login (tak terjadi: perlu login).
            val payload = ChatBackup.Payload(
                userId = 0L,
                exportedAt = nowIso(),
                conversations = convMeta,
                messages = msgs,
                e2eeKeys = runCatching {
                    E2eeManager(app).exportKeys()
                }.getOrDefault(emptyMap()),
            )
            val blob = if (secret.mode == "pass") {
                ChatBackup.createBlob(payload, "pass", passphrase = secret.value)
            } else {
                ChatBackup.createBlob(payload, "digits", digitsInput = secret.value)
            }
            val files = BackupFiles(app)
            val bytes = blob.blobB64.toByteArray(Charsets.UTF_8)
            files.saveFixed(BackupNames.LATEST, bytes)
            files.saveFixed(BackupNames.stampedNow(), bytes)
            // Pangkas arsip lama.
            val stamped = files.list().map { it.name }.filter { BackupNames.isStamped(it) }
            val byEntry = files.list().associateBy { it.name }
            BackupNames.prune(stamped).forEach { byEntry[it]?.let { e -> files.delete(e) } }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun nowIso(): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date())
    }

    companion object {
        const val TAG = "nusa_autobackup"

        /** Nyalakan/matikan jadwal harian (24 jam, baterai tak rendah, storage cukup). */
        fun ensureScheduled(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (!enabled) {
                wm.cancelUniqueWork(TAG)
                return
            }
            val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .addTag(TAG)
                .build()
            wm.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
