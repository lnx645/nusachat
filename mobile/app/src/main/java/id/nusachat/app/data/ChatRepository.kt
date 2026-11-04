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

    suspend fun updateGroupInfo(conversationId: Long, title: String? = null, avatarUrl: String? = null, description: String? = null): Conversation {
        val body = JSONObject().put("id", conversationId)
        if (title != null) body.put("title", title)
        if (avatarUrl != null) body.put("avatar_url", avatarUrl)
        if (description != null) body.put("description", description)
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

    suspend fun sendFriendRequest(userId: Long, message: String = "") {
        val body = JSONObject().put("user_id", userId)
        if (message.isNotBlank()) body.put("message", message)
        api.post("/friends/send", body)
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

    suspend fun walletTransfer(toUserId: Long, amount: Long, note: String?, pin: String? = null): Long {
        val payload = JSONObject().put("to_user_id", toUserId).put("amount", amount)
        if (!note.isNullOrBlank()) payload.put("note", note)
        if (!pin.isNullOrBlank()) payload.put("pin", pin)
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

    suspend fun createMoment(body: String, media: List<String>, audience: String = "friends"): Moment {
        val array = JSONArray()
        media.forEach { array.put(it) }
        val data = api.post(
            "/moments/create",
            JSONObject().put("body", body).put("media", array).put("audience", audience)
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

    suspend fun createStory(body: String, media: List<String>, style: String = ""): Story {
        val array = JSONArray()
        media.forEach { array.put(it) }
        val payload = JSONObject().put("body", body).put("media", array)
        if (style.isNotBlank()) payload.put("style", style)
        val data = api.post("/moments/story_create", payload)
        return data.getJSONObject("story").toStory()
    }

    suspend fun toggleCommentLike(commentId: Long, like: Boolean): Int {
        val path = if (like) "/moments/like_comment" else "/moments/unlike_comment"
        return api.post(path, JSONObject().put("id", commentId)).optInt("like_count", 0)
    }

    suspend fun momentsByTag(tag: String, limit: Int = 20): List<Moment> {
        val data = api.get("/moments/tag", mapOf("tag" to tag, "limit" to limit.toString()))
        val array = data.optJSONArray("moments") ?: JSONArray()
        val result = mutableListOf<Moment>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toMoment()) }
        }
        return result
    }

    suspend fun messageReads(conversationId: Long, messageId: Long): List<User> {
        val data = api.get(
            "/conversations/message_reads",
            mapOf("id" to conversationId.toString(), "message_id" to messageId.toString())
        )
        val array = data.optJSONArray("read_by") ?: JSONArray()
        return array.toUserList()
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

    // ------------------------------------------------------- fitur 0.2.0 ---

    /** Minta reset sandi. Kembali token bila server development, null bila produksi. */
    suspend fun forgotPassword(login: String): String? {
        val data = api.post("/auth/forgot", JSONObject().put("login", login))
        return data.stringOrNull("reset_token")
    }

    suspend fun resetPassword(token: String, newPassword: String) {
        api.post("/auth/reset", JSONObject().put("token", token).put("new_password", newPassword))
    }

    suspend fun sessions(): List<SessionItem> {
        val array = api.get("/auth/sessions").optJSONArray("sessions") ?: JSONArray()
        val result = mutableListOf<SessionItem>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toSessionItem()) }
        }
        return result
    }

    suspend fun revokeSession(jti: String) {
        api.post("/auth/revoke_session", JSONObject().put("jti", jti))
    }

    data class ConversationsPage(
        val conversations: List<Conversation>,
        val hasMore: Boolean,
        val nextBefore: String?,
    )

    suspend fun conversationsPage(limit: Int = 100, before: String? = null): ConversationsPage {
        val data = api.get(
            "/conversations",
            mapOf("limit" to limit.toString(), "before" to before)
        )
        val array = data.optJSONArray("conversations") ?: JSONArray()
        val result = mutableListOf<Conversation>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toConversation()) }
        }
        return ConversationsPage(result, data.optBoolean("has_more", false), data.stringOrNull("next_before"))
    }

    suspend fun pinChat(conversationId: Long, pinned: Boolean): Boolean {
        return api.post(
            "/conversations/pin_chat",
            JSONObject().put("id", conversationId).put("pinned", pinned)
        ).optBoolean("pinned", pinned)
    }

    suspend fun markUnread(conversationId: Long, messageId: Long? = null): Int {
        val body = JSONObject().put("id", conversationId)
        if (messageId != null && messageId > 0) body.put("message_id", messageId)
        return api.post("/conversations/mark_unread", body).optInt("unread_count", 0)
    }

    suspend fun searchAll(query: String, limit: Int = 30): List<Message> {
        val data = api.get("/conversations/search_all", mapOf("q" to query, "limit" to limit.toString()))
        val array = data.optJSONArray("messages") ?: JSONArray()
        val result = mutableListOf<Message>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toMessage()) }
        }
        return result
    }

    suspend fun transferOwnership(conversationId: Long, userId: Long): Long {
        return api.post(
            "/conversations/transfer_owner",
            JSONObject().put("id", conversationId).put("user_id", userId)
        ).optLong("owner_id", userId)
    }

    suspend fun sendSticker(conversationId: Long, emoji: String, clientId: String, replyToId: Long? = null): Message {
        val payload = JSONObject()
            .put("id", conversationId)
            .put("type", "sticker")
            .put("body", emoji)
            .put("client_id", clientId)
        if (replyToId != null) payload.put("reply_to_id", replyToId)
        return api.post("/conversations/send", payload).getJSONObject("message").toMessage()
    }

    suspend fun starMessage(messageId: Long) {
        api.post("/messages/star", JSONObject().put("message_id", messageId))
    }

    suspend fun unstarMessage(messageId: Long) {
        api.post("/messages/unstar", JSONObject().put("message_id", messageId))
    }

    suspend fun starredMessages(limit: Int = 50): List<Message> {
        val array = api.get("/messages/starred", mapOf("limit" to limit.toString()))
            .optJSONArray("messages") ?: JSONArray()
        val result = mutableListOf<Message>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toMessage()) }
        }
        return result
    }

    suspend fun hideMessage(messageId: Long) {
        api.post("/messages/hide", JSONObject().put("message_id", messageId))
    }

    suspend fun setRemark(userId: Long, remark: String?): String? {
        val body = JSONObject().put("user_id", userId)
        body.put("remark", remark ?: "")
        val data = api.post("/friends/remark", body)
        return data.stringOrNull("remark")
    }

    suspend fun editMoment(momentId: Long, body: String): Moment {
        val data = api.post("/moments/edit", JSONObject().put("id", momentId).put("body", body))
        return data.getJSONObject("moment").toMoment()
    }

    suspend fun momentLikers(momentId: Long): List<User> {
        val array = api.get("/moments/likes", mapOf("id" to momentId.toString()))
            .optJSONArray("users") ?: JSONArray()
        return array.toUserList()
    }

    suspend fun deleteComment(commentId: Long) {
        api.post("/moments/comment_delete", JSONObject().put("id", commentId))
    }

    suspend fun viewStory(storyId: Long) {
        api.post("/moments/story_view", JSONObject().put("id", storyId))
    }

    suspend fun storyViewers(storyId: Long): Pair<Int, List<StoryViewer>> {
        val data = api.get("/moments/story_views", mapOf("id" to storyId.toString()))
        val array = data.optJSONArray("viewers") ?: JSONArray()
        val result = mutableListOf<StoryViewer>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toStoryViewer()) }
        }
        return data.optInt("view_count", result.size) to result
    }

    suspend fun createReport(targetType: String, targetId: Long, reason: String, details: String = ""): Long {
        val body = JSONObject()
            .put("target_type", targetType)
            .put("target_id", targetId)
            .put("reason", reason)
        if (details.isNotBlank()) body.put("details", details)
        return api.post("/reports/create", body).getJSONObject("report").optLong("id")
    }

    suspend fun myReports(): List<ReportItem> {
        val array = api.get("/reports/mine").optJSONArray("reports") ?: JSONArray()
        val result = mutableListOf<ReportItem>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result.add(it.toReport()) }
        }
        return result
    }

    suspend fun walletInfo(): Pair<Long, Boolean> {
        val data = api.get("/wallet")
        return data.optLong("balance", 0L) to data.optBoolean("has_pin", false)
    }

    suspend fun setWalletPin(pin: String, oldPin: String? = null): Boolean {
        val body = JSONObject().put("pin", pin)
        if (!oldPin.isNullOrBlank()) body.put("old_pin", oldPin)
        return api.post("/wallet/set_pin", body).optBoolean("has_pin", true)
    }

    suspend fun requestMoney(toUserId: Long, amount: Long, note: String?): WalletRequestItem {
        val body = JSONObject().put("to_user_id", toUserId).put("amount", amount)
        if (!note.isNullOrBlank()) body.put("note", note)
        return api.post("/wallet/request_money", body).getJSONObject("request").toWalletRequest()
    }

    suspend fun walletRequests(): Pair<List<WalletRequestItem>, List<WalletRequestItem>> {
        val data = api.get("/wallet/requests")
        fun parse(key: String): List<WalletRequestItem> {
            val array = data.optJSONArray(key) ?: JSONArray()
            val result = mutableListOf<WalletRequestItem>()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { result.add(it.toWalletRequest()) }
            }
            return result
        }
        return parse("incoming") to parse("outgoing")
    }

    suspend fun payWalletRequest(requestId: Long, pin: String? = null): Long {
        val body = JSONObject().put("request_id", requestId)
        if (!pin.isNullOrBlank()) body.put("pin", pin)
        return api.post("/wallet/request_pay", body).optLong("balance", 0L)
    }

    suspend fun declineWalletRequest(requestId: Long) {
        api.post("/wallet/request_decline", JSONObject().put("request_id", requestId))
    }

    suspend fun cancelWalletRequest(requestId: Long) {
        api.post("/wallet/request_cancel", JSONObject().put("request_id", requestId))
    }

    suspend fun createRedPacket(
        conversationId: Long,
        total: Long,
        count: Int,
        note: String?,
        pin: String? = null,
        clientId: String,
    ): Pair<RedPacket, Message> {
        val body = JSONObject()
            .put("conversation_id", conversationId)
            .put("total", total)
            .put("count", count)
            .put("client_id", clientId)
        if (!note.isNullOrBlank()) body.put("note", note)
        if (!pin.isNullOrBlank()) body.put("pin", pin)
        val data = api.post("/wallet/redpacket_create", body)
        return data.getJSONObject("packet").toRedPacket() to data.getJSONObject("message").toMessage()
    }

    suspend fun redPacket(packetId: Long): RedPacket {
        return api.get("/wallet/redpacket", mapOf("id" to packetId.toString()))
            .getJSONObject("packet").toRedPacket()
    }

    suspend fun claimRedPacket(packetId: Long): Triple<String, Long, RedPacket> {
        val data = api.post("/wallet/redpacket_claim", JSONObject().put("packet_id", packetId))
        return Triple(
            data.optString("status", "claimed"),
            data.optLong("amount", 0L),
            data.getJSONObject("packet").toRedPacket()
        )
    }
}
