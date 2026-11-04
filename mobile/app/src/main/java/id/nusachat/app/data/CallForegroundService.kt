package id.nusachat.app.data

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import id.nusachat.app.MainActivity
import id.nusachat.app.R

/**
 * Foreground service telepon ala WhatsApp: menjaga proses tetap hidup saat
 * panggilan berjalan di background (audio tidak putus), menampilkan notif
 * panggilan, serta tombol Jawab/Tolak/Tutup dari notifikasi.
 *
 * Aksi notifikasi selalu lewat MainActivity (singleTop) agar tidak membuat
 * ulang activity: NusaChatApp mengonsumsi [PushDeepLink.pendingCallAction].
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PushManager.createChannels(this)
        // Wajib langsung foreground: menunggu showCallNotification (ringtone,
        // dsb) bisa melewati batas ~5 detik dan memicu
        // ForegroundServiceDidNotStartInTimeException (app crash saat telepon).
        // Notif sementara ini langsung ditimpa notif asli.
        runCatching {
            startForeground(NOTIF_ID, placeholderNotification())
        }
    }

    private fun placeholderNotification() =
        NotificationCompat.Builder(this, PushManager.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("NusaChat")
            .setContentText("Menyiapkan panggilan...")
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            if (intent?.action == ACTION_START || intent?.action == ACTION_INCOMING) {
                showCallNotification(intent)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CallRingtone.stop()
        super.onDestroy()
    }

    private fun showCallNotification(intent: Intent) {
        val incoming = intent.action == ACTION_INCOMING
        val callId = intent.getLongExtra(EXTRA_CALL_ID, 0L)
        val peer = intent.getStringExtra(EXTRA_PEER).orEmpty().ifBlank { "Penelepon" }
        val status = intent.getStringExtra(EXTRA_STATUS).orEmpty()

        val openApp = PendingIntent.getActivity(
            this, 1001, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, PushManager.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(!incoming)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)

        if (incoming) {
            CallRingtone.start(this)
            builder
                .setContentTitle("Panggilan suara masuk")
                .setContentText(peer)
                .addAction(0, "Tolak", activityAction("reject", 1002, callId))
                .addAction(0, "Jawab", activityAction("answer", 1003, callId))
                .setFullScreenIntent(openApp, true)
        } else {
            CallRingtone.stop()
            val text = when (status) {
                "active" -> "Panggilan berlangsung"
                else -> "Memanggil..."
            }
            builder
                .setContentTitle(peer)
                .setContentText(text)
                .addAction(0, "Tutup", activityAction("end", 1004, callId))
        }
        startForeground(NOTIF_ID, builder.build())
    }

    // Semua aksi lewat MainActivity (singleTop) agar state panggilan di
    // ViewModel tidak hilang: NusaChatApp mengonsumsi pendingCallAction.
    private fun activityAction(action: String, code: Int, callId: Long): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("call_action", action)
            putExtra("call_id", callId)
        }
        return PendingIntent.getActivity(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val ACTION_START = "id.nusachat.app.call.START"
        const val ACTION_INCOMING = "id.nusachat.app.call.INCOMING"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_PEER = "peer"
        const val EXTRA_STATUS = "status"
        const val NOTIF_ID = 42001
    }
}
