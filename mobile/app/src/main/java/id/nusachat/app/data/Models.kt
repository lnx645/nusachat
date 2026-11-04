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
    val friendCount: Int = 0,
    val role: String? = null,
    val blockedByMe: Boolean = false,
    val remark: String? = null,
) {
    /** Nama panggilan pribadi bila ada, kalau tidak nama tampilan. */
    val familiarName: String get() = remark?.takeIf { it.isNotBlank() } ?: displayName
}

data class GroupJoinRequest(
    val requestId: Long,
    val conversationId: Long = 0L,
    val status: String = "pending",
    val createdAt: String = "",
    val user: User,
)

data class DeviceKey(
    val deviceId: String,
    val publicKey: String,
)

data class KeyHolder(
    val userId: Long,
    val deviceId: String,
    val keyFp: String = "",
)

data class ConvKeys(
    val myKey: String?,
    val holders: List<Long>,
    val devices: List<KeyHolder> = emptyList(),
)

data class WalletTxn(
    val id: Long,
    val kind: String,
    val amount: Long,
    val direction: String,
    val note: String? = null,
    val peerName: String? = null,
    val createdAt: String = "",
)

data class Reaction(
    val emoji: String,
    val count: Int = 0,
    val reactedByMe: Boolean = false,
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
    val editedAt: String? = null,
    val forwarded: Boolean = false,
    val forwardedFromId: Long? = null,
    val mentions: List<Long> = emptyList(),
    val mentionedMe: Boolean = false,
    val reactions: List<Reaction> = emptyList(),
    val packetId: Long? = null,
    val conversationTitle: String? = null,
    val conversationType: String? = null,
    val starredAt: String? = null,
)

data class PinnedMessage(
    val message: Message,
    val pinnedBy: Long = 0L,
    val pinnedAt: String? = null,
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
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val description: String? = null,
    val inviteToken: String? = null,
    val onlyAdminsCanSend: Boolean = false,
    val onlyAdminsCanEdit: Boolean = false,
    val onlyAdminsCanAdd: Boolean = false,
    val inviteLinkEnabled: Boolean = true,
    val approveNewMembers: Boolean = false,
    val disappearingDays: Int = 0,
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
    val reshared: ResharedInfo? = null,
    val createdAt: String,
    val audience: String = "friends",
    val editedAt: String? = null,
)

data class ResharedInfo(
    val momentId: Long,
    val author: User? = null,
)

data class Story(
    val id: Long,
    val author: User,
    val body: String,
    val media: List<String>,
    val createdAt: String,
    val expiresAt: String,
    val viewCount: Int = 0,
    val viewedByMe: Boolean = false,
    val style: String? = null,
)

data class StoryViewer(
    val user: User,
    val viewedAt: String = "",
)

data class SessionItem(
    val jti: String,
    val device: String? = null,
    val createdAt: String = "",
    val lastUsedAt: String? = null,
    val expiresAt: String = "",
)

data class WalletRequestItem(
    val id: Long,
    val amount: Long,
    val note: String? = null,
    val status: String = "pending",
    val createdAt: String = "",
    val requester: User,
    val target: User,
)

data class RedPacketClaim(
    val user: User,
    val amount: Long,
    val createdAt: String = "",
)

data class RedPacket(
    val id: Long,
    val conversationId: Long,
    val messageId: Long? = null,
    val sender: User,
    val totalAmount: Long,
    val totalCount: Int,
    val claimedCount: Int,
    val claimedAmount: Long,
    val note: String? = null,
    val status: String = "active",
    val myClaim: Long? = null,
    val claims: List<RedPacketClaim> = emptyList(),
    val createdAt: String = "",
    val expiresAt: String = "",
)

data class CallHistoryItem(
    val call: VoiceCall,
    val direction: String = "incoming",
    val missed: Boolean = false,
    val duration: Int = 0,
    val peer: User? = null,
    val groupTitle: String? = null,
)

data class ReportItem(
    val id: Long,
    val targetType: String,
    val targetId: Long,
    val reason: String,
    val details: String? = null,
    val status: String = "pending",
    val createdAt: String = "",
)

data class MomentComment(
    val id: Long,
    val body: String,
    val createdAt: String,
    val user: User,
    val parentId: Long? = null,
    val replyToUserId: Long? = null,
    val replyToDisplayName: String? = null,
    val replyToUsername: String? = null,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false,
)

data class FriendRequest(
    val requestId: Long,
    val status: String,
    val createdAt: String,
    val user: User,
    val incoming: Boolean,
    val greeting: String? = null,
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
    friendCount = optInt("friend_count", 0),
    role = stringOrNull("role"),
    blockedByMe = optBoolean("blocked_by_me", false),
    remark = stringOrNull("remark"),
)

internal fun JSONObject.toWalletTxn(): WalletTxn {
    val peer = if (optString("direction") == "out") {
        optJSONObject("to")
    } else {
        optJSONObject("from")
    }
    return WalletTxn(
        id = optLong("id"),
        kind = optString("kind"),
        amount = optLong("amount"),
        direction = optString("direction"),
        note = stringOrNull("note"),
        peerName = peer?.optString("display_name"),
        createdAt = optString("created_at"),
    )
}

internal fun JSONObject.toGroupJoinRequest(conversationId: Long = 0L): GroupJoinRequest = GroupJoinRequest(
    requestId = optLong("request_id"),
    conversationId = conversationId,
    status = optString("status", "pending"),
    createdAt = optString("created_at"),
    user = optJSONObject("user")?.toUser() ?: User(0, "", "Pengguna"),
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

/**
 * Encode Message ke bentuk JSON server agar [toMessage] bisa membaca ulang
 * (dipakai cache lokal). `pending` milik klien — disimpan sebagai kolom DB.
 */
internal fun Message.toJson(): JSONObject {
    val meta = JSONObject()
    if (lat != null) meta.put("lat", lat)
    if (lng != null) meta.put("lng", lng)
    if (packetId != null) meta.put("packet_id", packetId)
    val mentionArray = JSONArray()
    mentions.forEach { mentionArray.put(it) }
    val reactionArray = JSONArray()
    reactions.forEach {
        reactionArray.put(
            JSONObject()
                .put("emoji", it.emoji)
                .put("count", it.count)
                .put("reacted_by_me", it.reactedByMe)
        )
    }
    return JSONObject()
        .put("id", id)
        .put("uuid", uuid)
        .put("conversation_id", conversationId)
        .put("sender", sender?.toJson())
        .put("type", type)
        .put("body", body)
        .put("attachment_url", attachmentUrl)
        .put("status", status)
        .put("revoked", revoked)
        .put("created_at", createdAt)
        .put("client_id", clientId)
        .put("reply_to_id", replyToId)
        .put("edited_at", editedAt)
        .put("forwarded", forwarded)
        .put("forwarded_from_id", forwardedFromId)
        .put("mentions", mentionArray)
        .put("mentioned_me", mentionedMe)
        .put("reactions", reactionArray)
        .put("attachment_meta", meta)
        .put("conversation_title", conversationTitle)
        .put("conversation_type", conversationType)
        .put("starred_at", starredAt)
}

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
    val mentions = mutableListOf<Long>()
    optJSONArray("mentions")?.let { array ->
        for (i in 0 until array.length()) {
            mentions.add(array.optLong(i))
        }
    }
    val reactions = mutableListOf<Reaction>()
    optJSONArray("reactions")?.let { array ->
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
        editedAt = stringOrNull("edited_at"),
        forwarded = optBoolean("forwarded", false),
        forwardedFromId = longOrNull("forwarded_from_id"),
        mentions = mentions,
        mentionedMe = optBoolean("mentioned_me", false),
        reactions = reactions,
        packetId = meta?.takeIf { it.has("packet_id") && !it.isNull("packet_id") }?.optLong("packet_id"),
        conversationTitle = stringOrNull("conversation_title"),
        conversationType = stringOrNull("conversation_type"),
        starredAt = stringOrNull("starred_at"),
    )
}

internal fun JSONObject.toPinnedMessage(): PinnedMessage = PinnedMessage(
    message = optJSONObject("message")?.toMessage() ?: Message(0, "", 0, null, "text", null, null, "sent", createdAt = ""),
    pinnedBy = optLong("pinned_by", 0L),
    pinnedAt = stringOrNull("pinned_at"),
)

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
        archived = optBoolean("archived", false),
        pinned = optBoolean("pinned", false),
        description = stringOrNull("description"),
        inviteToken = stringOrNull("invite_token"),
        onlyAdminsCanSend = optBoolean("only_admins_can_send", false),
        onlyAdminsCanEdit = optBoolean("only_admins_can_edit", false),
        onlyAdminsCanAdd = optBoolean("only_admins_can_add", false),
        inviteLinkEnabled = optBoolean("invite_link_enabled", true),
        approveNewMembers = optBoolean("approve_new_members", false),
        disappearingDays = optInt("disappearing_days", 0),
    )
}

fun isVideoUrl(url: String): Boolean {
    val clean = url.substringBefore("?").lowercase()
    return clean.endsWith(".mp4") || clean.endsWith(".m4a") || clean.endsWith(".mov") || clean.endsWith(".3gp")
}

internal fun JSONObject.toMoment(): Moment {
    val media = mutableListOf<String>()
    optJSONArray("media")?.let { array ->
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) media.add(value)
        }
    }
    val reshared = optJSONObject("reshared")?.let {
        ResharedInfo(
            momentId = it.optLong("moment_id"),
            author = it.optJSONObject("author")?.toUser(),
        )
    }
    return Moment(
        id = optLong("id"),
        author = optJSONObject("author")?.toUser() ?: User(0, "", "Pengguna"),
        body = optString("body"),
        media = media,
        likeCount = optInt("like_count", 0),
        commentCount = optInt("comment_count", 0),
        likedByMe = optBoolean("liked_by_me", false),
        reshared = reshared,
        createdAt = optString("created_at"),
        audience = optString("audience", "friends"),
        editedAt = stringOrNull("edited_at"),
    )
}

internal fun JSONObject.toStory(): Story {
    val media = mutableListOf<String>()
    optJSONArray("media")?.let { array ->
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) media.add(value)
        }
    }
    return Story(
        id = optLong("id"),
        author = optJSONObject("author")?.toUser() ?: User(0, "", "Pengguna"),
        body = optString("body"),
        media = media,
        createdAt = optString("created_at"),
        expiresAt = optString("expires_at"),
        viewCount = optInt("view_count", 0),
        viewedByMe = optBoolean("viewed_by_me", false),
        style = stringOrNull("style"),
    )
}

internal fun JSONObject.toMomentComment(): MomentComment = MomentComment(
    id = optLong("id"),
    body = optString("body"),
    createdAt = optString("created_at"),
    user = optJSONObject("user")?.toUser() ?: User(0, "", "Pengguna"),
    parentId = longOrNull("parent_id"),
    replyToUserId = longOrNull("reply_to_user_id"),
    replyToDisplayName = stringOrNull("reply_to_display_name"),
    replyToUsername = stringOrNull("reply_to_username"),
    likeCount = optInt("like_count", 0),
    likedByMe = optInt("liked_by_me", 0) == 1 || optBoolean("liked_by_me", false),
)

internal fun JSONObject.toFriendRequest(incoming: Boolean): FriendRequest = FriendRequest(
    requestId = optLong("request_id"),
    status = optString("status", "pending"),
    createdAt = optString("created_at"),
    user = optJSONObject("user")?.toUser() ?: User(0, "", "Pengguna"),
    incoming = incoming,
    greeting = stringOrNull("greeting"),
)

internal fun JSONObject.toSessionItem(): SessionItem = SessionItem(
    jti = optString("jti"),
    device = stringOrNull("device"),
    createdAt = optString("created_at"),
    lastUsedAt = stringOrNull("last_used_at"),
    expiresAt = optString("expires_at"),
)

internal fun JSONObject.toWalletRequest(): WalletRequestItem = WalletRequestItem(
    id = optLong("id"),
    amount = optLong("amount"),
    note = stringOrNull("note"),
    status = optString("status", "pending"),
    createdAt = optString("created_at"),
    requester = optJSONObject("requester")?.toUser() ?: User(0, "", "Pengguna"),
    target = optJSONObject("target")?.toUser() ?: User(0, "", "Pengguna"),
)

internal fun JSONObject.toRedPacket(): RedPacket {
    val claims = mutableListOf<RedPacketClaim>()
    optJSONArray("claims")?.let { array ->
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let {
                claims.add(
                    RedPacketClaim(
                        user = it.optJSONObject("user")?.toUser() ?: User(0, "", "Pengguna"),
                        amount = it.optLong("amount"),
                        createdAt = it.optString("created_at"),
                    )
                )
            }
        }
    }
    return RedPacket(
        id = optLong("id"),
        conversationId = optLong("conversation_id"),
        messageId = longOrNull("message_id"),
        sender = optJSONObject("sender")?.toUser() ?: User(0, "", "Pengguna"),
        totalAmount = optLong("total_amount"),
        totalCount = optInt("total_count", 0),
        claimedCount = optInt("claimed_count", 0),
        claimedAmount = optLong("claimed_amount"),
        note = stringOrNull("note"),
        status = optString("status", "active"),
        myClaim = longOrNull("my_claim"),
        claims = claims,
        createdAt = optString("created_at"),
        expiresAt = optString("expires_at"),
    )
}

internal fun JSONObject.toCallHistory(): CallHistoryItem = CallHistoryItem(
    call = toVoiceCall(),
    direction = optString("direction", "incoming"),
    missed = optBoolean("missed", false),
    duration = optInt("duration", 0),
    peer = optJSONObject("peer")?.toUser(),
    groupTitle = stringOrNull("group_title"),
)

internal fun JSONObject.toReport(): ReportItem = ReportItem(
    id = optLong("id"),
    targetType = optString("target_type"),
    targetId = optLong("target_id"),
    reason = optString("reason"),
    details = stringOrNull("details"),
    status = optString("status", "pending"),
    createdAt = optString("created_at"),
)

internal fun JSONObject.toStoryViewer(): StoryViewer = StoryViewer(
    user = User(
        id = optLong("id"),
        username = optString("username"),
        displayName = stringOrNull("display_name") ?: optString("username"),
        avatarUrl = stringOrNull("avatar_url"),
        gender = stringOrNull("gender"),
    ),
    viewedAt = optString("viewed_at"),
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
