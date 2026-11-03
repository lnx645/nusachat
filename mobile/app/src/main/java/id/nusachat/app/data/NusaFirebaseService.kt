package id.nusachat.app.data

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM data messages and renders a local notification.
 * Signaling payloads (call_offer/answer/ice/end) are also forwarded to the
 * realtime handler via a broadcast the ViewModel observes on next start;
 * the websocket remains the source of truth while the app is open.
 */
class NusaFirebaseService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PushManager.ensureFirebase(this)
        // Stored; AppViewModel registers it with the backend after login.
        getSharedPreferences("nusachat.push", MODE_PRIVATE).edit().putString("pending_token", token).apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        PushManager.ensureFirebase(this)
        PushManager.createChannels(this)
        val data = message.data ?: emptyMap()
        if (data.isEmpty() && message.notification != null) {
            PushManager.showDataNotification(
                this,
                mapOf(
                    "title" to (message.notification?.title.orEmpty()),
                    "body" to (message.notification?.body.orEmpty()),
                    "event" to "message",
                )
            )
        } else if (data.isNotEmpty()) {
            PushManager.showDataNotification(this, data)
        }
    }
}
