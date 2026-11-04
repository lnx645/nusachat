package id.nusachat.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Full-mesh voice untuk room panggilan grup: satu [WebRtcCallManager]
 * per lawan bicara, SDP offer/answer lewat `/calls/room_signal` dan
 * ICE lewat `/calls/ice` + `to_user_id`. Hanya audio.
 */
class GroupCallMesh(
    private val context: Context,
    private val calls: CallRepository,
    private val scope: CoroutineScope,
) {
    private val peers = mutableMapOf<Long, WebRtcCallManager>()
    private var roomId: Long = 0L
    private var cachedIce: JSONObject? = null

    private suspend fun ensureIceServers(mgr: WebRtcCallManager) {
        if (cachedIce == null) {
            cachedIce = runCatching { calls.iceServers() }.getOrNull() ?: JSONObject()
        }
        runCatching { mgr.applyIceServersJson(cachedIce) }
    }

    fun setRoom(callId: Long) {
        roomId = callId
    }

    fun isSupported(): Boolean = runCatching {
        WebRtcCallManager(context).isSupported()
    }.getOrDefault(false)

    private fun peerFor(userId: Long): WebRtcCallManager =
        peers.getOrPut(userId) {
            WebRtcCallManager(context).also { mgr -> wireIce(mgr, userId) }
        }

    private fun wireIce(mgr: WebRtcCallManager, userId: Long) {
        mgr.onIceCandidate = { candidate ->
            scope.launch {
                runCatching {
                    calls.ice(
                        roomId,
                        JSONObject()
                            .put("candidate", candidate.sdp)
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex),
                        userId,
                    )
                }
            }
        }
    }

    fun offerTo(userId: Long) {
        if (roomId <= 0) return
        scope.launch(Dispatchers.IO) {
            try {
                val mgr = peerFor(userId)
                mgr.ensureFactory()
                ensureIceServers(mgr)
                val offer = withContext(Dispatchers.IO) { mgr.startAsCaller() } ?: return@launch
                runCatching { calls.roomSignal(roomId, userId, offer.description, "offer") }
            } catch (_: Exception) {
            }
        }
    }

    fun answerTo(userId: Long, sdp: String) {
        if (roomId <= 0) return
        scope.launch(Dispatchers.IO) {
            try {
                val mgr = peerFor(userId)
                mgr.ensureFactory()
                ensureIceServers(mgr)
                val latch = java.util.concurrent.CountDownLatch(1)
                var answer: org.webrtc.SessionDescription? = null
                mgr.handleOffer(sdp) { desc ->
                    answer = desc
                    latch.countDown()
                }
                latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                val desc = answer ?: return@launch
                runCatching { calls.roomSignal(roomId, userId, desc.description, "answer") }
            } catch (_: Exception) {
            }
        }
    }

    fun onAnswer(userId: Long, sdp: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { peerFor(userId).handleAnswer(sdp) }
        }
    }

    fun onIce(userId: Long, candidate: JSONObject) {
        scope.launch(Dispatchers.IO) {
            runCatching { peerFor(userId).handleRemoteIce(candidate) }
        }
    }

    fun setMuted(muted: Boolean) {
        peers.values.forEach { it.setMuted(muted) }
    }

    fun removePeer(userId: Long) {
        peers.remove(userId)?.close()
    }

    fun closeAll() {
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        roomId = 0L
    }
}
