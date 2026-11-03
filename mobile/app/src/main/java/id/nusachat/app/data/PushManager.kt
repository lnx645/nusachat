package id.nusachat.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import id.nusachat.app.MainActivity
import id.nusachat.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * FCM push helper. Works without google-services.json for debug builds:
 * Firebase is initialized manually when empty, token fetch is best-effort,
 * and chat still works over the websocket when push is unavailable.
 */
object PushManager {
    const val CHANNEL_MESSAGES = "nusachat_messages"
    const val CHANNEL_CALLS = "nusachat_calls"
    private const val PREFS = "nusachat.push"
    private const val KEY_PENDING = "pending_token"
    private const val KEY_REGISTERED = "registered_token"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureFirebase(context: Context) {
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                // google-services.json is bundled, FirebaseInitProvider
                // normally auto-initializes. This is a fallback only.
                FirebaseApp.initializeApp(context)
            }
        }
    }

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Pesan", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifikasi pesan, pertemanan, dan momen"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Panggilan", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Panggilan suara masuk"
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun fetchAndRegister(api: ApiClient, context: Context) {
        ensureFirebase(context)
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) return@addOnCompleteListener
                val token = task.result ?: return@addOnCompleteListener
                persistPending(context, token)
                scope.launch {
                    runCatching {
                        api.post("/push/register", JSONObject().put("token", token).put("platform", "android"))
                        markRegistered(context, token)
                    }
                }
            }
        }
    }

    fun registerTokenDirect(api: ApiClient, context: Context, token: String) {
        persistPending(context, token)
        scope.launch {
            runCatching {
                api.post("/push/register", JSONObject().put("token", token).put("platform", "android"))
                markRegistered(context, token)
            }
        }
    }

    fun unregister(api: ApiClient, context: Context) {
        val registered = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REGISTERED, null)
        scope.launch {
            runCatching {
                val body = JSONObject()
                if (!registered.isNullOrBlank()) body.put("token", registered)
                api.post("/push/unregister", body)
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_REGISTERED).apply()
        }
        runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }

    fun showDataNotification(context: Context, data: Map<String, String>) {
        val event = data["event"].orEmpty()
        val title = data["title"] ?: when (event) {
            "call_offer" -> "Panggilan suara masuk"
            "message" -> "Pesan baru"
            "friend_request" -> "Permintaan pertemanan"
            "friend_accepted" -> "Pertemanan diterima"
            else -> "NusaChat"
        }
        val body = data["body"] ?: ""
        val channel = if (event == "call_offer") CHANNEL_CALLS else CHANNEL_MESSAGES
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data.forEach { (k, v) -> putExtra("push_$k", v) }
        }
        val pending = PendingIntent.getActivity(
            context, event.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (event == "call_offer") NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setNumber(1)
            .setContentIntent(pending)
            .build()
        if (!hasNotificationPermission(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(event, data), notification)
        }
    }

    private fun notificationId(event: String, data: Map<String, String>): Int {
        val key = data["conversation_id"] ?: data["call_id"] ?: data["request_id"] ?: event
        return (event + ":" + key).hashCode()
    }

    private fun persistPending(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING, token).apply()
    }

    private fun markRegistered(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_REGISTERED, token).apply()
    }

    fun pendingToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING, null)
}
