package id.nusachat.app.data

import org.json.JSONObject

data class VoiceCall(
    val id: Long,
    val uuid: String = "",
    val callerId: Long,
    val calleeId: Long,
    val status: String,
)

internal fun JSONObject.toVoiceCall(): VoiceCall = VoiceCall(
    id = optLong("id"),
    uuid = optString("uuid"),
    callerId = optLong("caller_id"),
    calleeId = optLong("callee_id"),
    status = optString("status", "ringing"),
)

class CallRepository(private val api: ApiClient) {
    suspend fun offer(toUserId: Long, sdp: String): VoiceCall {
        val data = api.post(
            "/calls/offer",
            JSONObject().put("to_user_id", toUserId).put("sdp", sdp)
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

    suspend fun ice(callId: Long, candidate: JSONObject) {
        api.post(
            "/calls/ice",
            JSONObject().put("call_id", callId).put("candidate", candidate)
        )
    }

    suspend fun reject(callId: Long) {
        api.post("/calls/reject", JSONObject().put("call_id", callId))
    }

    suspend fun end(callId: Long) {
        api.post("/calls/end", JSONObject().put("call_id", callId))
    }

    suspend fun incoming(): List<VoiceCall> {
        val array = api.get("/calls/incoming").optJSONArray("calls") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toVoiceCall() }
    }
}
