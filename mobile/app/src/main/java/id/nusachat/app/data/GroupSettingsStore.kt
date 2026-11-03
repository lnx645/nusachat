package id.nusachat.app.data

import android.content.Context
import org.json.JSONObject

/**
 * Penyimpanan pengaturan grup lokal per-conversation.
 * Sekarang sinkron ke backend (kolom di tabel conversations) + cache lokal.
 */
data class GroupSettings(
    val onlyAdminsCanSend: Boolean = false,
    val onlyAdminsCanEdit: Boolean = false,
    val onlyAdminsCanAddMembers: Boolean = false,
    val inviteLinkEnabled: Boolean = true,
    val approveNewMembers: Boolean = false,
    val disappearingDays: Int = 0,
) {
    companion object {
        fun fromConversation(c: Conversation) = GroupSettings(
            onlyAdminsCanSend = c.onlyAdminsCanSend,
            onlyAdminsCanEdit = c.onlyAdminsCanEdit,
            onlyAdminsCanAddMembers = c.onlyAdminsCanAdd,
            inviteLinkEnabled = c.inviteLinkEnabled,
            approveNewMembers = c.approveNewMembers,
            disappearingDays = c.disappearingDays,
        )
        fun fromJsonObject(o: JSONObject) = GroupSettings(
            onlyAdminsCanSend = o.optBoolean("only_admins_can_send", o.optBoolean("onlyAdminsCanSend", false)),
            onlyAdminsCanEdit = o.optBoolean("only_admins_can_edit", o.optBoolean("onlyAdminsCanEdit", false)),
            onlyAdminsCanAddMembers = o.optBoolean("only_admins_can_add", o.optBoolean("onlyAdminsCanAddMembers", false)),
            inviteLinkEnabled = o.optBoolean("invite_link_enabled", o.optBoolean("inviteLinkEnabled", true)),
            approveNewMembers = o.optBoolean("approve_new_members", o.optBoolean("approveNewMembers", false)),
            disappearingDays = o.optInt("disappearing_days", o.optInt("disappearingDays", 0)),
        )
    }
    fun toBackendMap(): Map<String, Any> = mapOf(
        "only_admins_can_send" to onlyAdminsCanSend,
        "only_admins_can_edit" to onlyAdminsCanEdit,
        "only_admins_can_add" to onlyAdminsCanAddMembers,
        "invite_link_enabled" to inviteLinkEnabled,
        "approve_new_members" to approveNewMembers,
        "disappearing_days" to disappearingDays,
    )
}

class GroupSettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        "nusachat.group_settings",
        Context.MODE_PRIVATE,
    )

    fun get(conversationId: Long): GroupSettings {
        val raw = prefs.getString(key(conversationId), null) ?: return GroupSettings()
        return runCatching { GroupSettings.fromJsonObject(JSONObject(raw)) }.getOrElse { GroupSettings() }
    }

    fun save(conversationId: Long, settings: GroupSettings) {
        prefs.edit().putString(key(conversationId), JSONObject(settings.toBackendMap()).toString()).apply()
    }

    private fun key(id: Long) = "group_$id"
}
