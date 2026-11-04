package id.nusachat.app.data

import org.json.JSONObject

data class VoiceCall(
    val id: Long,
    val uuid: String = "",
    val callerId: Long,
    val calleeId: Long,
    val status: String,
    val kind: String = "audio",
    val conversationId: Long? = null,
)

internal fun JSONObject.toVoiceCall(): VoiceCall = VoiceCall(
    id = optLong("id"),
    uuid = optString("uuid"),
    callerId = optLong("caller_id"),
    calleeId = optLong("callee_id"),
    status = optString("status", "ringing"),
    kind = optString("kind", "audio"),
    conversationId = if (has("conversation_id") && !isNull("conversation_id")) optLong("conversation_id") else null,
)

data class RoomParticipant(
    val user: User,
    val status: String = "invited",
)

data class GroupCallRoom(
    val call: VoiceCall,
    val participants: List<RoomParticipant> = emptyList(),
)

internal fun JSONObject.toRoomParticipant(): RoomParticipant = RoomParticipant(
    user = optJSONObject("user")?.toUser() ?: User(0, "", "Pengguna"),
    status = optString("status", "invited"),
)

class CallRepository(private val api: ApiClient) {
    suspend fun offer(toUserId: Long, sdp: String, kind: String = "audio"): VoiceCall {
        val data = api.post(
            "/calls/offer",
            JSONObject().put("to_user_id", toUserId).put("sdp", sdp).put("kind", kind)
        )
        return data.getJSONObject("call").toVoiceCall()
    }

    suspend fun answer(callId: Long, sdp: String): VoiceCall {
        val data = api.post(
            "/calls/answer",
            JSONObject().put("call_id", callId).put("sdp", sdp)
        )
        return data.getJSONObject("call").toVoiceCall()
    }

    suspend fun ice(callId: Long, candidate: JSONObject, toUserId: Long? = null) {
        val payload = JSONObject().put("call_id", callId).put("candidate", candidate)
        if (toUserId != null) payload.put("to_user_id", toUserId)
        api.post("/calls/ice", payload)
    }

    /** Short-lived Cloudflare TURN servers (docs/TURN.md); STUN fallback bawaan bila gagal. */
    suspend fun iceServers(): JSONObject {
        return runCatching { api.get("/calls/ice_servers") }.getOrDefault(JSONObject())
    }

    suspend fun reject(callId: Long) {
        api.post("/calls/reject", JSONObject().put("call_id", callId))
    }

    suspend fun end(callId: Long) {
        api.post("/calls/end", JSONObject().put("call_id", callId))
    }

    suspend fun incoming(): Pair<List<VoiceCall>, List<VoiceCall>> {
        val data = api.get("/calls/incoming")
        val calls = mutableListOf<VoiceCall>()
        data.optJSONArray("calls")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { calls.add(it.toVoiceCall()) }
            }
        }
        val rooms = mutableListOf<VoiceCall>()
        data.optJSONArray("rooms")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { rooms.add(it.toVoiceCall()) }
            }
        }
        return calls to rooms
    }

    private fun JSONObject.toRoom(): GroupCallRoom {
        val participants = mutableListOf<RoomParticipant>()
        optJSONArray("participants")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { participants.add(it.toRoomParticipant()) }
            }
        }
        return GroupCallRoom(getJSONObject("call").toVoiceCall(), participants)
    }

    suspend fun roomCreate(conversationId: Long, kind: String = "audio"): GroupCallRoom {
        val data = api.post(
            "/calls/room_create",
            JSONObject().put("conversation_id", conversationId).put("kind", kind)
        )
        return data.toRoom()
    }

    suspend fun roomJoin(callId: Long): GroupCallRoom {
        val data = api.post("/calls/room_join", JSONObject().put("call_id", callId))
        return data.toRoom()
    }

    suspend fun roomLeave(callId: Long): Boolean {
        val data = api.post("/calls/room_leave", JSONObject().put("call_id", callId))
        return data.optBoolean("ended", true)
    }

    suspend fun roomState(callId: Long): GroupCallRoom {
        val data = api.get("/calls/room_state", mapOf("call_id" to callId.toString()))
        return data.toRoom()
    }

    suspend fun roomSignal(callId: Long, toUserId: Long, sdp: String, signal: String) {
        api.post(
            "/calls/room_signal",
            JSONObject()
                .put("call_id", callId)
                .put("to_user_id", toUserId)
                .put("sdp", sdp)
                .put("signal", signal)
        )
    }
}
