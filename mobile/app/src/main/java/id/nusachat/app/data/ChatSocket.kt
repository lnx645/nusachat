package id.nusachat.app.data

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Realtime connection to the NusaChat gateway. Events are delivered as
 * {"type": "...", "data": {...}} envelopes. Reconnects with backoff.
 */
class ChatSocket(
    private val wsUrl: String,
    private val tokenProvider: () -> String?,
    private val onEvent: (String, JSONObject) -> Unit,
    private val onStateChange: (Boolean) -> Unit = {},
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var attempt = 0

    fun connect() {
        if (running) return
        running = true
        attempt = 0
        open()
    }

    fun disconnect() {
        running = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "bye")
        socket = null
        onStateChange(false)
    }

    /**
     * Kirim pesan client->server (mis. location.update, message.ack).
     * Best-effort; false = socket tutup, penelepon fallback ke REST.
     */
    fun send(type: String, data: JSONObject = JSONObject()): Boolean {
        val ws = socket ?: return false
        return runCatching {
            ws.send(JSONObject().put("type", type).put("data", data).toString())
        }.getOrDefault(false)
    }

    val isOpen: Boolean get() = socket != null

    private fun open() {
        val token = tokenProvider()
        if (!running || token.isNullOrBlank()) {
            return
        }
        val url = wsUrl.trimEnd('/') + "/?token=" + token
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            onStateChange(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            val type = json.optString("type")
            if (type.isBlank()) return
            val data = json.optJSONObject("data") ?: JSONObject()
            mainHandler.post { onEvent(type, data) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            onStateChange(false)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            onStateChange(false)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!running || reconnectJob?.isActive == true) return
        attempt += 1
        val delayMs = minOf(30_000L, 1_000L * (1 shl minOf(attempt, 5)))
        reconnectJob = scope.launch {
            delay(delayMs)
            if (running) open()
        }
    }
}
