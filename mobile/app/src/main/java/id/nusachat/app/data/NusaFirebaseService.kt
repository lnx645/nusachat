package id.nusachat.app.data

import com.google.firebase.messaging.FirebaseMessagingService
import id.nusachat.app.BuildConfig
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives FCM data messages and renders a local notification.
 * Signaling payloads (call_offer/answer/ice/end) are also forwarded to the
 * realtime handler via a broadcast the ViewModel observes on next start;
 * the websocket remains the source of truth while the app is open.
 *
 * Untuk pesan chat: server hanya mengirim "Pesan baru" (isi tidak boleh
 * bocor ke FCM). Bila sesi login dan kunci E2EE tersedia, ambil pesan lalu
 * dekrip lokal agar notifikasi menampilkan plain text.
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
            if (data["event"] == "call_offer" && !startIncomingCallService(data)) {
                PushManager.showDataNotification(this, data)
            } else if (data["event"] != "call_offer") {
                PushManager.showDataNotification(this, withPlainText(data))
            }
        }
    }

    /**
     * Panggilan masuk saat aplikasi mati/background: nyalakan foreground
     * service agar notif Jawab/Tolak + full-screen muncul ala WhatsApp.
     * @return false bila service gagal dinyalakan (fallback notif biasa).
     */
    private fun startIncomingCallService(data: Map<String, String>): Boolean {
        // Aplikasi sedang dibuka: layar panggilan + service dari ViewModel yang urus.
        if (PushManager.isAppForeground(this)) return true
        return runCatching {
            val intent = android.content.Intent(this, CallForegroundService::class.java).apply {
                action = CallForegroundService.ACTION_INCOMING
                putExtra(CallForegroundService.EXTRA_CALL_ID, data["call_id"]?.toLongOrNull() ?: 0L)
                putExtra(CallForegroundService.EXTRA_PEER, data["peer"].orEmpty().ifBlank { data["title"].orEmpty() })
                putExtra(CallForegroundService.EXTRA_STATUS, "ringing")
            }
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
            true
        }.getOrDefault(false)
    }

    private fun withPlainText(data: Map<String, String>): Map<String, String> {
        if (data["event"] != "message") return data
        val conversationId = data["conversation_id"]?.toLongOrNull() ?: return data
        val messageId = data["message_id"]?.toLongOrNull() ?: return data
        val session = SessionStore(this)
        if (!session.isLoggedIn) return data
        val plain = runCatching {
            runBlocking {
                withTimeoutOrNull(9_000) {
                    val api = ApiClient(BuildConfig.API_BASE_URL, session)
                    val e2ee = E2eeManager(this@NusaFirebaseService)
                    val page = ChatRepository(this@NusaFirebaseService, api, session)
                        .messages(conversationId, limit = 10)
                    val raw = page.messages.firstOrNull { it.id == messageId } ?: return@withTimeoutOrNull null
                    e2ee.decryptMessage(raw).body?.takeIf { it.isNotBlank() }
                }
            }
        }.getOrNull()
        if (plain.isNullOrBlank() || isCipherText(plain)) return data
        return data + ("body" to plain.take(180))
    }

    private fun isCipherText(text: String): Boolean {
        if (text.length <= 24 || !text.contains("=")) return false
        return text.matches(Regex("^[A-Za-z0-9+/=\\n\\r]+$"))
    }
}
