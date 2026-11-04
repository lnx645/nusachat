package id.nusachat.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class ChatRepository(
    private val context: Context,
    private val api: ApiClient,
    private val session: SessionStore,
) {

    suspend fun login(login: String, password: String): User {
        val data = api.post(
            "/auth/login",
            JSONObject()
                .put("login", login)
                .put("password", password)
                .put("device", "NusaChat Android")
        )
        return storeAuth(data)
    }

    suspend fun register(
        username: String,
        password: String,
        displayName: String,
        phone: String,
    ): User {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("display_name", displayName)
            .put("device", "NusaChat Android")
        if (phone.isNotBlank()) body.put("phone", phone)
        return storeAuth(api.post("/auth/register", body))
    }

    sealed interface GoogleAuthResult {
        data class LoggedIn(val user: User) : GoogleAuthResult
        data object NeedRegister : GoogleAuthResult
    }

    /**
     * Login Google. Tanpa profil -> login langsung bila google_sub tertaut,
     * atau NeedRegister (409) bila akun Google belum punya akun NusaChat.
     * Dengan profil lengkap -> buat akun + tautkan.
     */
    suspend fun authGoogle(
        idToken: String,
        username: String = "",
        displayName: String = "",
        password: String = "",
        avatarUrl: String = "",
    ): GoogleAuthResult {
        val body = JSONObject()
            .put("id_token", idToken)
            .put("device", "NusaChat Android")
        if (username.isNotBlank()) {
            body.put("username", username)
                .put("display_name", displayName)
                .put("password", password)
            if (avatarUrl.isNotBlank()) body.put("avatar_url", avatarUrl)
        }
        try {
            return GoogleAuthResult.LoggedIn(storeAuth(api.post("/auth/google", body)))
        } catch (e: ApiException) {
            if (e.statusCode == 409 && e.code == "google_register_required") {
                return GoogleAuthResult.NeedRegister
            }
            throw e
        }
    }

    private fun storeAuth(data: JSONObject): User {
        val user = data.getJSONObject("user").toUser()
        val auth = data.getJSONObject("auth")
        session.saveSession(
            user,
            auth.getString("access_token"),
            auth.getString("refresh_token"),
        )
        return user
    }

    suspend fun me(): User {
        val user = api.get("/users/me").getJSONObject("user").toUser()
        session.updateUser(user)
        return user
    }

    suspend fun updateProfile(fields: JSONObject): User {
        val user = api.post("/users/update", fields).getJSONObject("user").toUser()
        session.updateUser(user)
        return user
    }

    suspend fun logout() {
        try {
            api.post("/auth/logout", JSONObject())
        } finally {
            session.clear()
        }
    }

    suspend fun conversations(): List<Conversation> {
        val array = api.get("/conversations").optJSONArray("conversations") ?: JSONArray()
        val result = mutableListOf<Conversation>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toConversation()) }
        }
        return result
    }

    suspend fun openDirect(userId: Long): Conversation {
        val data = api.post("/conversations/create_direct", JSONObject().put("user_id", userId))
        return data.getJSONObject("conversation").toConversation()
    }

    suspend fun createGroup(title: String, memberIds: List<Long>): Conversation {
        val ids = JSONArray()
        memberIds.forEach { ids.put(it) }
        val data = api.post(
            "/conversations/create_group",
            JSONObject().put("title", title).put("member_ids", ids)
        )
        return data.getJSONObject("conversation").toConversation()
    }

    suspend fun messages(
        conversationId: Long,
        beforeId: Long? = null,
        afterId: Long? = null,
        limit: Int = 30,
    ): MessagesPage {
        val data = api.get(
            "/conversations/messages",
            mapOf(
                "id" to conversationId.toString(),
                "before_id" to beforeId?.toString(),
                "after_id" to afterId?.toString(),
                "limit" to limit.toString(),
            )
        )
        val array = data.optJSONArray("messages") ?: JSONArray()
        val messages = mutableListOf<Message>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { messages.add(it.toMessage()) }
        }
        return MessagesPage(messages, data.optBoolean("has_more", false))
    }

    suspend fun sendMessage(
        conversationId: Long,
        body: String,
        clientId: String,
        replyToId: Long? = null,
    ): Message {
        val payload = JSONObject()
            .put("id", conversationId)
            .put("type", "text")
            .put("body", body)
            .put("client_id", clientId)
        if (replyToId != null) payload.put("reply_to_id", replyToId)
        val data = api.post("/conversations/send", payload)
        return data.getJSONObject("message").toMessage()
    }

    suspend fun sendImage(
        conversationId: Long,
        url: String,
        clientId: String,
        caption: String = "",
        replyToId: Long? = null,
    ): Message {
        return sendAttachment(conversationId, url, "image", clientId, caption, replyToId)
    }

    suspend fun sendAttachment(
        conversationId: Long,
        url: String,
        type: String,
        clientId: String,
        caption: String = "",
        replyToId: Long? = null,
    ): Message {
        val payload = JSONObject()
            .put("id", conversationId)
            .put("type", type)
            .put("attachment_url", url)
            .put("client_id", clientId)
        if (caption.isNotBlank()) payload.put("body", caption)
        if (replyToId != null) payload.put("reply_to_id", replyToId)
        return api.post("/conversations/send", payload).getJSONObject("message").toMessage()
    }

    suspend fun revokeMessage(messageId: Long) {
        api.post("/messages/revoke", JSONObject().put("message_id", messageId))
    }

    suspend fun editMessage(messageId: Long, body: String): Message {
        val data = api.post(
            "/messages/edit",
            JSONObject().put("message_id", messageId).put("body", body)
        )
        return data.getJSONObject("message").toMessage()
    }

    suspend fun reactMessage(messageId: Long, emoji: String): Pair<String, List<Reaction>> {
        val data = api.post(
            "/messages/react",
            JSONObject().put("message_id", messageId).put("emoji", emoji)
        )
        val reactions = mutableListOf<Reaction>()
        data.optJSONArray("reactions")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let {
                    reactions.add(
                        Reaction(
                            emoji = it.optString("emoji"),
                            count = it.optInt("count", 0),
                            reactedByMe = it.optBoolean("reacted_by_me", false),
                        )
                    )
                }
            }
        }
        return data.optString("action", "added") to reactions
    }

    suspend fun forwardMessage(
        messageId: Long,
        toConversationId: Long,
        clientId: String,
        body: String? = null,
    ): Message {
        val payload = JSONObject()
            .put("message_id", messageId)
            .put("to_id", toConversationId)
            .put("client_id", clientId)
        if (!body.isNullOrEmpty()) payload.put("body", body)
        val data = api.post("/conversations/forward", payload)
        return data.getJSONObject("message").toMessage()
    }

    suspend fun searchMessages(conversationId: Long, query: String): List<Message> {
        val data = api.get(
            "/conversations/search",
            mapOf("id" to conversationId.toString(), "q" to query)
        )
        val array = data.optJSONArray("messages") ?: JSONArray()
        val result = mutableListOf<Message>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toMessage()) }
        }
        return result
    }

    suspend fun pins(conversationId: Long): List<PinnedMessage> {
        val array = api.get("/conversations/pins", mapOf("id" to conversationId.toString()))
            .optJSONArray("pins") ?: JSONArray()
        val result = mutableListOf<PinnedMessage>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toPinnedMessage()) }
        }
        return result
    }

    suspend fun pinMessage(conversationId: Long, messageId: Long): List<PinnedMessage> {
        val array = api.post(
            "/conversations/pin",
            JSONObject().put("id", conversationId).put("message_id", messageId)
        ).optJSONArray("pins") ?: JSONArray()
        val result = mutableListOf<PinnedMessage>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toPinnedMessage()) }
        }
        return result
    }

    suspend fun unpinMessage(conversationId: Long, messageId: Long) {
        api.post(
            "/conversations/unpin",
            JSONObject().put("id", conversationId).put("message_id", messageId)
        )
    }

    suspend fun archiveConversation(conversationId: Long, archived: Boolean): Boolean {
        val data = api.post(
            "/conversations/archive",
            JSONObject().put("id", conversationId).put("archived", archived)
        )
        return data.optBoolean("archived", archived)
    }

    suspend fun archivedConversations(): List<Conversation> {
        val array = api.get("/conversations", mapOf("archived" to "1"))
            .optJSONArray("conversations") ?: JSONArray()
        val result = mutableListOf<Conversation>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toConversation()) }
        }
        return result
    }

    suspend fun conversationDetail(conversationId: Long): Conversation {
        val data = api.get("/conversations/show", mapOf("id" to conversationId.toString()))
        return data.getJSONObject("conversation").toConversation()
    }

    suspend fun addGroupMembers(conversationId: Long, userIds: List<Long>): List<User> {
        val ids = JSONArray()
        userIds.forEach { ids.put(it) }
        val data = api.post(
            "/conversations/add_members",
            JSONObject().put("id", conversationId).put("user_ids", ids)
        )
        val array = data.optJSONArray("members") ?: JSONArray()
        return array.toUserList()
    }

    suspend fun removeGroupMember(conversationId: Long, userId: Long) {
        api.post(
            "/conversations/remove_member",
            JSONObject().put("id", conversationId).put("user_id", userId)
        )
    }

    suspend fun muteConversation(conversationId: Long, muted: Boolean): Boolean {
        val data = api.post(
            "/conversations/mute",
            JSONObject().put("id", conversationId).put("muted", muted)
        )
        return data.optBoolean("muted", muted)
    }

    suspend fun clearHistory(conversationId: Long) {
        api.post("/conversations/clear", JSONObject().put("id", conversationId))
    }

    suspend fun updateGroupInfo(conversationId: Long, title: String? = null, avatarUrl: String? = null): Conversation {
        val body = JSONObject().put("id", conversationId)
        if (title != null) body.put("title", title)
        if (avatarUrl != null) body.put("avatar_url", avatarUrl)
        return api.post("/conversations/update_group", body).getJSONObject("conversation").toConversation()
    }

    suspend fun groupSettings(conversationId: Long): JSONObject {
        return api.get("/conversations/group_settings", mapOf("id" to conversationId.toString()))
            .optJSONObject("settings") ?: JSONObject()
    }

    suspend fun updateGroupSettings(conversationId: Long, fields: Map<String, Any>): JSONObject {
        val body = JSONObject().put("id", conversationId)
        fields.forEach { (k, v) -> body.put(k, v) }
        return api.post("/conversations/update_group_settings", body)
            .optJSONObject("settings") ?: JSONObject()
    }

    suspend fun inviteLink(conversationId: Long, rotate: Boolean = false): Pair<String, String> {
        val data = api.post(
            "/conversations/invite",
            JSONObject().put("id", conversationId).put("rotate", rotate)
        )
        return data.optString("invite_token") to data.optString("invite_link")
    }

    suspend fun joinGroup(token: String): Pair<String, Conversation?> {
        val data = api.post("/conversations/join", JSONObject().put("token", token.trim()))
        val status = data.optString("status", "joined")
        val conversation = data.optJSONObject("conversation")?.toConversation()
        return status to conversation
    }

    suspend fun joinRequests(conversationId: Long): List<GroupJoinRequest> {
        val data = api.get("/conversations/join_requests", mapOf("id" to conversationId.toString()))
        val array = data.optJSONArray("requests") ?: JSONArray()
        val result = mutableListOf<GroupJoinRequest>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toGroupJoinRequest(conversationId)) }
        }
        return result
    }

    suspend fun reviewJoinRequest(requestId: Long, approve: Boolean): String {
        val data = api.post(
            "/conversations/review_request",
            JSONObject().put("request_id", requestId).put("action", if (approve) "approve" else "reject")
        )
        return data.optString("status", if (approve) "approved" else "rejected")
    }

    suspend fun setMemberRole(conversationId: Long, userId: Long, role: String): String {
        val data = api.post(
            "/conversations/set_role",
            JSONObject().put("id", conversationId).put("user_id", userId).put("role", role)
        )
        return data.optString("role", role)
    }

    suspend fun markRead(conversationId: Long, messageId: Long) {
        api.post(
            "/conversations/read",
            JSONObject().put("id", conversationId).put("message_id", messageId)
        )
    }

    suspend fun typing(conversationId: Long) {
        api.post("/conversations/typing", JSONObject().put("id", conversationId))
    }

    suspend fun friends(): List<User> {
        val array = api.get("/friends").optJSONArray("friends") ?: JSONArray()
        return array.toUserList()
    }

    suspend fun friendRequests(): Pair<List<FriendRequest>, List<FriendRequest>> {
        val data = api.get("/friends/requests")
        val incoming = mutableListOf<FriendRequest>()
        val outgoing = mutableListOf<FriendRequest>()
        val incomingArray = data.optJSONArray("incoming") ?: JSONArray()
        for (i in 0 until incomingArray.length()) {
            incomingArray.optJSONObject(i)?.let { incoming.add(it.toFriendRequest(true)) }
        }
        val outgoingArray = data.optJSONArray("outgoing") ?: JSONArray()
        for (i in 0 until outgoingArray.length()) {
            outgoingArray.optJSONObject(i)?.let { outgoing.add(it.toFriendRequest(false)) }
        }
        return incoming to outgoing
    }

    suspend fun sendFriendRequest(userId: Long) {
        api.post("/friends/send", JSONObject().put("user_id", userId))
    }

    suspend fun respondFriendRequest(requestId: Long, accept: Boolean) {
        api.post(
            "/friends/respond",
            JSONObject()
                .put("request_id", requestId)
                .put("action", if (accept) "accept" else "decline")
        )
    }

    suspend fun removeFriend(userId: Long) {
        api.post("/friends/remove", JSONObject().put("user_id", userId))
    }

    suspend fun blockUser(userId: Long) {
        api.post("/friends/block", JSONObject().put("user_id", userId))
    }

    suspend fun unblockUser(userId: Long) {
        api.post("/friends/unblock", JSONObject().put("user_id", userId))
    }

    suspend fun blockedUsers(): List<User> {
        val array = api.get("/friends/blocked").optJSONArray("users") ?: JSONArray()
        return array.toUserList()
    }

    suspend fun deleteAccount(password: String): Boolean {
        val data = api.post("/users/delete", JSONObject().put("password", password))
        return data.optBoolean("deleted", false)
    }

    suspend fun publishDeviceKey(deviceId: String, publicKey: String) {
        api.post(
            "/users/device_key",
            JSONObject().put("device_id", deviceId).put("public_key", publicKey)
        )
    }

    suspend fun deviceKeys(userId: Long): List<DeviceKey> {
        val array = api.get("/users/device_keys", mapOf("user_id" to userId.toString()))
            .optJSONArray("keys") ?: JSONArray()
        val result = mutableListOf<DeviceKey>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let {
                result.add(DeviceKey(it.optString("device_id"), it.optString("public_key")))
            }
        }
        return result
    }

    suspend fun convKeys(conversationId: Long, deviceId: String = ""): ConvKeys {
        val data = api.get("/conversations/keys", mapOf("id" to conversationId.toString(), "device_id" to deviceId))
        val holders = mutableListOf<Long>()
        data.optJSONArray("holders")?.let { array ->
            for (i in 0 until array.length()) holders.add(array.optLong(i))
        }
        val devices = mutableListOf<KeyHolder>()
        data.optJSONArray("devices")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let {
                    devices.add(KeyHolder(it.optLong("user_id"), it.optString("device_id"), it.optString("key_fp")))
                }
            }
        }
        val myKey = if (data.has("my_key") && !data.isNull("my_key")) data.optString("my_key") else null
        return ConvKeys(myKey, holders, devices)
    }

    suspend fun setConvKeys(conversationId: Long, keys: List<Triple<Long, String, String>>): Int {
        val array = JSONArray()
        keys.forEach { (userId, deviceId, wrapped) ->
            array.put(JSONObject().put("user_id", userId).put("device_id", deviceId).put("wrapped_key", wrapped))
        }
        return api.post(
            "/conversations/set_keys",
            JSONObject().put("id", conversationId).put("keys", array)
        ).optInt("stored", 0)
    }

    suspend fun wallet(): Long {
        return api.get("/wallet").optLong("balance", 0L)
    }

    suspend fun walletTopup(amount: Long): Long {
        return api.post("/wallet/topup", JSONObject().put("amount", amount)).optLong("balance", 0L)
    }

    suspend fun walletTransfer(toUserId: Long, amount: Long, note: String?): Long {
        val payload = JSONObject().put("to_user_id", toUserId).put("amount", amount)
        if (!note.isNullOrBlank()) payload.put("note", note)
        return api.post("/wallet/transfer", payload).optLong("balance", 0L)
    }

    suspend fun walletHistory(limit: Int = 20): List<WalletTxn> {
        val array = api.get("/wallet/history", mapOf("limit" to limit.toString()))
            .optJSONArray("transactions") ?: JSONArray()
        val result = mutableListOf<WalletTxn>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toWalletTxn()) }
        }
        return result
    }

    suspend fun syncContacts(contacts: List<Pair<String, String>>): Pair<Int, List<User>> {
        val array = JSONArray()
        contacts.forEach { (name, phone) ->
            array.put(JSONObject().put("name", name).put("phone", phone))
        }
        val data = api.post("/contacts/sync", JSONObject().put("contacts", array))
        val users = mutableListOf<User>()
        val usersArray = data.optJSONArray("users") ?: JSONArray()
        for (i in 0 until usersArray.length()) {
            usersArray.optJSONObject(i)?.let { users.add(it.toUser()) }
        }
        return data.optInt("received", 0) to users
    }

    suspend fun searchUsers(query: String): List<User> {
        val array = api.get("/users/search", mapOf("q" to query)).optJSONArray("users") ?: JSONArray()
        return array.toUserList()
    }

    suspend fun nearby(lat: Double, lng: Double, radiusKm: Int = 25): List<User> {
        val array = api.get(
            "/nearby",
            mapOf(
                "lat" to lat.toString(),
                "lng" to lng.toString(),
                "radius" to radiusKm.toString(),
            )
        ).optJSONArray("users") ?: JSONArray()
        return array.toUserList()
    }

    suspend fun sendLocation(
        conversationId: Long,
        lat: Double,
        lng: Double,
        label: String = "",
        clientId: String,
        replyToId: Long? = null,
    ): Message {
        val payload = JSONObject()
            .put("id", conversationId)
            .put("type", "location")
            .put("client_id", clientId)
            .put("attachment_meta", JSONObject().put("lat", lat).put("lng", lng))
        if (label.isNotBlank()) payload.put("body", label)
        if (replyToId != null) payload.put("reply_to_id", replyToId)
        return api.post("/conversations/send", payload).getJSONObject("message").toMessage()
    }

    suspend fun updatePresence(lat: Double?, lng: Double?) {
        val body = JSONObject()
        if (lat != null && lng != null) {
            body.put("lat", lat)
            body.put("lng", lng)
        }
        api.post("/users/presence", body)
    }

    suspend fun moments(beforeId: Long? = null): Pair<List<Moment>, Boolean> {
        val data = api.get("/moments", mapOf("before_id" to beforeId?.toString()))
        val array = data.optJSONArray("moments") ?: JSONArray()
        return array.toMomentList() to data.optBoolean("has_more", false)
    }

    suspend fun createMoment(body: String, media: List<String>): Moment {
        val array = JSONArray()
        media.forEach { array.put(it) }
        val data = api.post(
            "/moments/create",
            JSONObject().put("body", body).put("media", array)
        )
        return data.getJSONObject("moment").toMoment()
    }

    suspend fun likeMoment(momentId: Long) {
        api.post("/moments/like", JSONObject().put("id", momentId))
    }

    suspend fun unlikeMoment(momentId: Long) {
        api.post("/moments/unlike", JSONObject().put("id", momentId))
    }

    suspend fun deleteMoment(momentId: Long) {
        api.post("/moments/delete", JSONObject().put("id", momentId))
    }

    suspend fun reshareMoment(momentId: Long): Moment {
        val data = api.post("/moments/reshare", JSONObject().put("id", momentId))
        return data.getJSONObject("moment").toMoment()
    }

    suspend fun stories(): List<Story> {
        val array = api.get("/moments/stories").optJSONArray("stories") ?: JSONArray()
        val result = mutableListOf<Story>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toStory()) }
        }
        return result
    }

    suspend fun createStory(body: String, media: List<String>): Story {
        val array = JSONArray()
        media.forEach { array.put(it) }
        val data = api.post(
            "/moments/story_create",
            JSONObject().put("body", body).put("media", array)
        )
        return data.getJSONObject("story").toStory()
    }

    suspend fun deleteStory(storyId: Long) {
        api.post("/moments/story_delete", JSONObject().put("id", storyId))
    }

    suspend fun sendAttachment(
        conversationId: Long,
        type: String,
        url: String,
        body: String?,
        clientId: String,
    ): Message {
        val payload = JSONObject()
            .put("id", conversationId)
            .put("type", type)
            .put("attachment_url", url)
            .put("client_id", clientId)
        if (!body.isNullOrBlank()) payload.put("body", body)
        return api.post("/conversations/send", payload).getJSONObject("message").toMessage()
    }

    suspend fun changePassword(oldPassword: String, newPassword: String) {
        api.post(
            "/users/password",
            JSONObject().put("old_password", oldPassword).put("new_password", newPassword)
        )
    }

    suspend fun comments(momentId: Long): List<MomentComment> {
        val array = api.get("/moments/comments", mapOf("id" to momentId.toString()))
            .optJSONArray("comments") ?: JSONArray()
        val result = mutableListOf<MomentComment>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toMomentComment()) }
        }
        return result
    }

    suspend fun addComment(momentId: Long, body: String, parentId: Long? = null): MomentComment {
        val payload = JSONObject().put("id", momentId).put("body", body)
        if (parentId != null) payload.put("parent_id", parentId)
        val data = api.post("/moments/comment", payload)
        return data.getJSONObject("comment").toMomentComment()
    }

    suspend fun upload(uri: Uri): String {
        return api.upload(context, uri).getString("url")
    }

    suspend fun sync(): JSONObject = api.get("/sync")
}
