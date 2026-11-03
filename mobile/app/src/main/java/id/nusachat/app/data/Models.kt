package id.nusachat.app.data

import org.json.JSONArray
import org.json.JSONObject

data class User(
    val id: Long,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val phone: String? = null,
    val gender: String? = null,
    val birthday: String? = null,
    val discoverable: Boolean = true,
    val online: Boolean = false,
    val lastSeenAt: String? = null,
    val friendStatus: String? = null,
    val requestId: Long? = null,
    val requestDirection: String? = null,
    val distanceKm: Double? = null,
    val conversationId: Long? = null,
)

data class Message(
    val id: Long,
    val uuid: String = "",
    val conversationId: Long,
    val sender: User?,
    val type: String,
    val body: String?,
    val attachmentUrl: String?,
    val status: String,
    val revoked: Boolean = false,
    val createdAt: String,
    val clientId: String? = null,
    val pending: Boolean = false,
    val replyToId: Long? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

data class Conversation(
    val id: Long,
    val type: String,
    val title: String,
    val avatarUrl: String?,
    val lastMessage: Message?,
    val lastMessageAt: String?,
    val unreadCount: Int,
    val peer: User?,
    val members: List<User> = emptyList(),
    val memberCount: Int = 0,
    val createdBy: Long = 0L,
    val myRole: String = "member",
    val muted: Boolean = false,
) {
    val isGroup: Boolean get() = type == "group"
    val displayTitle: String get() = title.ifBlank { if (isGroup) "Grup" else "Pengguna" }
}

data class Moment(
    val id: Long,
    val author: User,
    val body: String,
    val media: List<String>,
    val likeCount: Int,
    val commentCount: Int,
    val likedByMe: Boolean,
    val createdAt: String,
)

data class MomentComment(
    val id: Long,
    val body: String,
    val createdAt: String,
    val user: User,
)

data class FriendRequest(
    val requestId: Long,
    val status: String,
    val createdAt: String,
    val user: User,
    val incoming: Boolean,
)

data class MessagesPage(
    val messages: List<Message>,
    val hasMore: Boolean,
)

internal fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() }
}

internal fun JSONObject.longOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return optLong(key)
}

internal fun JSONObject.toUser(): User = User(
    id = optLong("id"),
    username = optString("username"),
    displayName = stringOrNull("display_name") ?: optString("username"),
    avatarUrl = stringOrNull("avatar_url"),
    bio = stringOrNull("bio"),
    phone = stringOrNull("phone"),
    gender = stringOrNull("gender"),
    birthday = stringOrNull("birthday"),
    discoverable = if (has("discoverable") && !isNull("discoverable")) optBoolean("discoverable", true) else true,
    online = optBoolean("online", false),
    lastSeenAt = stringOrNull("last_seen_at"),
    friendStatus = stringOrNull("friend_status"),
    requestId = longOrNull("request_id"),
    requestDirection = stringOrNull("request_direction"),
    distanceKm = if (has("distance_km") && !isNull("distance_km")) optDouble("distance_km") else null,
    conversationId = longOrNull("conversation_id"),
)

internal fun User.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("username", username)
    .put("display_name", displayName)
    .put("avatar_url", avatarUrl)
    .put("bio", bio)
    .put("phone", phone)
    .put("online", online)
    .put("last_seen_at", lastSeenAt)

internal fun JSONObject.toMessage(): Message {
    val sender = optJSONObject("sender")?.toUser()
    val meta = optJSONObject("attachment_meta")
    val lat = when {
        meta != null && meta.has("lat") && !meta.isNull("lat") -> meta.optDouble("lat")
        has("lat") && !isNull("lat") -> optDouble("lat")
        else -> null
    }
    val lng = when {
        meta != null && meta.has("lng") && !meta.isNull("lng") -> meta.optDouble("lng")
        has("lng") && !isNull("lng") -> optDouble("lng")
        else -> null
    }
    return Message(
        id = optLong("id"),
        uuid = optString("uuid"),
        conversationId = optLong("conversation_id"),
        sender = sender,
        type = optString("type", "text"),
        body = stringOrNull("body"),
        attachmentUrl = stringOrNull("attachment_url"),
        status = optString("status", "sent"),
        revoked = optBoolean("revoked", false),
        createdAt = optString("created_at"),
        clientId = stringOrNull("client_id"),
        replyToId = longOrNull("reply_to_id"),
        lat = lat,
        lng = lng,
    )
}

internal fun JSONObject.toConversation(): Conversation {
    val members = mutableListOf<User>()
    optJSONArray("members")?.let { array ->
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { members.add(it.toUser()) }
        }
    }
    val title = stringOrNull("title") ?: ""
    return Conversation(
        id = optLong("id"),
        type = optString("type", "direct"),
        title = title,
        avatarUrl = stringOrNull("avatar_url"),
        lastMessage = optJSONObject("last_message")?.toMessage(),
        lastMessageAt = stringOrNull("last_message_at"),
        unreadCount = optInt("unread_count", 0),
        peer = optJSONObject("peer")?.toUser(),
        members = members,
        memberCount = optInt("member_count", members.size),
        createdBy = optLong("created_by"),
        myRole = optString("role", "member"),
        muted = optBoolean("muted", false),
    )
}

internal fun JSONObject.toMoment(): Moment {
    val media = mutableListOf<String>()
    optJSONArray("media")?.let { array ->
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) media.add(value)
        }
    }
    return Moment(
        id = optLong("id"),
        author = optJSONObject("author")?.toUser() ?: User(0, "", "Pengguna"),
        body = optString("body"),
        media = media,
        likeCount = optInt("like_count", 0),
        commentCount = optInt("comment_count", 0),
        likedByMe = optBoolean("liked_by_me", false),
        createdAt = optString("created_at"),
    )
}

internal fun JSONObject.toMomentComment(): MomentComment = MomentComment(
    id = optLong("id"),
    body = optString("body"),
    createdAt = optString("created_at"),
    user = optJSONObject("user")?.toUser() ?: User(0, "", "Pengguna"),
)

internal fun JSONObject.toFriendRequest(incoming: Boolean): FriendRequest = FriendRequest(
    requestId = optLong("request_id"),
    status = optString("status", "pending"),
    createdAt = optString("created_at"),
    user = optJSONObject("user")?.toUser() ?: User(0, "", "Pengguna"),
    incoming = incoming,
)

internal fun JSONArray.toUserList(): List<User> {
    val result = mutableListOf<User>()
    for (i in 0 until length()) {
        optJSONObject(i)?.let { result.add(it.toUser()) }
    }
    return result
}

internal fun JSONArray.toMomentList(): List<Moment> {
    val result = mutableListOf<Moment>()
    for (i in 0 until length()) {
        optJSONObject(i)?.let { result.add(it.toMoment()) }
    }
    return result
}
