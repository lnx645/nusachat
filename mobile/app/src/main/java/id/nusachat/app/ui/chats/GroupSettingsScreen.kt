package id.nusachat.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.nusachat.app.data.Conversation
import id.nusachat.app.data.GroupSettings
import id.nusachat.app.data.GroupSettingsStore
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaSwitch
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.theme.NusaIcons
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Halaman pengaturan grup ala WhatsApp — sinkron ke backend.
 * Perubahan disimpan via API lalu cache lokal, jadi berlaku untuk semua anggota.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsScreen(
    conversation: Conversation,
    vm: AppViewModel?,
    isOwner: Boolean,
    onBack: () -> Unit,
    isAdmin: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(conversation.id) { GroupSettingsStore(context) }
    var settings by remember(conversation.id) { mutableStateOf(GroupSettings.fromConversation(conversation)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var inviteLink by remember(conversation.id) {
        mutableStateOf(conversation.inviteToken?.let { "nusachat://group/$it" })
    }
    val requests by vm?.joinRequests?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyList()) }

    LaunchedEffect(settings.inviteLinkEnabled, conversation.id) {
        inviteLink = if (settings.inviteLinkEnabled && vm != null) {
            vm.fetchInviteLink(conversation.id)
        } else null
    }
    LaunchedEffect(conversation.id, settings.approveNewMembers) {
        if (isOwner || isAdmin) vm?.loadJoinRequests(conversation.id)
    }

    LaunchedEffect(conversation.id) {
        settings = GroupSettings.fromConversation(conversation)
    }

    fun update(next: GroupSettings) {
        val prev = settings
        settings = next
        store.save(conversation.id, next)
        error = null
        if (vm == null) return
        saving = true
        scope.launch {
            try {
                val changed = mutableMapOf<String, Any>()
                if (next.onlyAdminsCanSend != prev.onlyAdminsCanSend) changed["only_admins_can_send"] = next.onlyAdminsCanSend
                if (next.onlyAdminsCanEdit != prev.onlyAdminsCanEdit) changed["only_admins_can_edit"] = next.onlyAdminsCanEdit
                if (next.onlyAdminsCanAddMembers != prev.onlyAdminsCanAddMembers) changed["only_admins_can_add"] = next.onlyAdminsCanAddMembers
                if (next.inviteLinkEnabled != prev.inviteLinkEnabled) changed["invite_link_enabled"] = next.inviteLinkEnabled
                if (next.approveNewMembers != prev.approveNewMembers) changed["approve_new_members"] = next.approveNewMembers
                if (next.disappearingDays != prev.disappearingDays) changed["disappearing_days"] = next.disappearingDays
                if (changed.isNotEmpty()) {
                    vm.updateGroupSettings(conversation.id, changed)
                    vm.loadConversationDetail()
                }
            } catch (e: Exception) {
                settings = prev
                store.save(conversation.id, prev)
                error = e.message ?: "Gagal menyimpan"
            } finally {
                saving = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NusaTopAppBar(
            title = { Text("Pengaturan grup") },
            navigationIcon = {
                NusaIconButton(onClick = onBack) {
                    Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                }
            },
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (error != null) {
                item(key = "error") {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            // --- Privasi grup -------------------------------------------------
            item(key = "label-privacy") { SectionLabel("Privasi grup") }
            item(key = "who-send") {
                SettingSwitchRow(
                    icon = NusaIcons.Megaphone,
                    title = "Izinkan mengirim pesan",
                    subtitle = if (settings.onlyAdminsCanSend) "Hanya admin" else "Semua anggota",
                    checked = !settings.onlyAdminsCanSend,
                    enabled = isOwner && !saving,
                    onCheckedChange = { allowAll -> update(settings.copy(onlyAdminsCanSend = !allowAll)) },
                )
            }
            item(key = "div-1") { SettingDivider() }
            item(key = "who-edit") {
                SettingSwitchRow(
                    icon = NusaIcons.Edit,
                    title = "Edit info grup",
                    subtitle = if (settings.onlyAdminsCanEdit) "Hanya admin dapat mengedit nama & foto" else "Semua anggota dapat mengedit",
                    checked = !settings.onlyAdminsCanEdit,
                    enabled = isOwner && !saving,
                    onCheckedChange = { allowAll -> update(settings.copy(onlyAdminsCanEdit = !allowAll)) },
                )
            }
            item(key = "div-2") { SettingDivider() }
            item(key = "who-add") {
                SettingSwitchRow(
                    icon = NusaIcons.GroupAdd,
                    title = "Tambah anggota",
                    subtitle = if (settings.onlyAdminsCanAddMembers) "Hanya admin" else "Semua anggota",
                    checked = !settings.onlyAdminsCanAddMembers,
                    enabled = isOwner && !saving,
                    onCheckedChange = { allowAll -> update(settings.copy(onlyAdminsCanAddMembers = !allowAll)) },
                )
            }

            // --- Undangan -----------------------------------------------------
            item(key = "label-invite") { SectionLabel("Undangan") }
            item(key = "invite-link") {
                SettingSwitchRow(
                    icon = NusaIcons.Link,
                    title = "Undang via tautan",
                    subtitle = if (settings.inviteLinkEnabled) "Siapa pun dengan tautan dapat bergabung" else "Tautan dinonaktifkan",
                    checked = settings.inviteLinkEnabled,
                    enabled = isOwner && !saving,
                    onCheckedChange = { enabled ->
                        update(
                            settings.copy(
                                inviteLinkEnabled = enabled,
                                approveNewMembers = if (!enabled) false else settings.approveNewMembers,
                            ),
                        )
                    },
                )
            }
            item(key = "div-3") { SettingDivider() }
            item(key = "approve") {
                SettingSwitchRow(
                    icon = NusaIcons.Shield,
                    title = "Setujui anggota baru",
                    subtitle = "Admin harus menyetujui sebelum bergabung",
                    checked = settings.approveNewMembers,
                    enabled = isOwner && settings.inviteLinkEnabled && !saving,
                    onCheckedChange = { approve -> update(settings.copy(approveNewMembers = approve)) },
                )
            }
            if (settings.inviteLinkEnabled && inviteLink != null) {
                item(key = "invite-show") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Tautan undangan",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = inviteLink.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        NusaIconButton(onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(inviteLink.orEmpty()))
                        }) {
                            Icon(
                                painter = painterResource(id = NusaIcons.Copy),
                                contentDescription = "Salin tautan",
                            )
                        }
                        if (isOwner) {
                            NusaIconButton(onClick = {
                                scope.launch {
                                    inviteLink = vm?.fetchInviteLink(conversation.id, true)
                                }
                            }) {
                                Icon(
                                    painter = painterResource(id = NusaIcons.Refresh),
                                    contentDescription = "Buat tautan baru",
                                )
                            }
                        }
                    }
                }
                item(key = "div-4") { SettingDivider() }
            }
            if ((isOwner || isAdmin) && requests.isNotEmpty()) {
                item(key = "label-requests") { SectionLabel("Permintaan gabung (${requests.size})") }
                item(key = "requests") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(vertical = 4.dp),
                    ) {
                        requests.forEach { request ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = request.user.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "@${request.user.username}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                NusaIconButton(onClick = { vm?.reviewJoinRequest(request, true) }) {
                                    Icon(
                                        painter = painterResource(id = NusaIcons.Check),
                                        contentDescription = "Setujui",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                NusaIconButton(onClick = { vm?.reviewJoinRequest(request, false) }) {
                                    Icon(
                                        painter = painterResource(id = NusaIcons.Close),
                                        contentDescription = "Tolak",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // --- Pesan sementara ----------------------------------------------
            item(key = "label-disappear") { SectionLabel("Pesan") }
            item(key = "disappear") {
                var showPicker by remember { mutableStateOf(false) }
                SettingActionRow(
                    icon = NusaIcons.Timer,
                    title = "Pesan sementara",
                    subtitle = when (settings.disappearingDays) {
                        1 -> "24 jam"
                        7 -> "7 hari"
                        90 -> "90 hari"
                        else -> "Mati"
                    },
                    onClick = { if (isOwner && !saving) showPicker = true },
                    enabled = isOwner && !saving,
                )
                if (showPicker) {
                    DisappearingPicker(
                        selected = settings.disappearingDays,
                        onPick = { days ->
                            update(settings.copy(disappearingDays = days))
                            showPicker = false
                        },
                        onDismiss = { showPicker = false },
                    )
                }
            }

            item(key = "hint") {
                Text(
                    text = if (!isOwner) "Hanya admin yang dapat mengubah pengaturan ini."
                    else if (saving) "Menyimpan..."
                    else "Perubahan tersimpan dan berlaku untuk semua anggota.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 7.dp),
    )
}

@Composable
private fun SettingSwitchRow(
    icon: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        NusaSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SettingActionRow(
    icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            painter = painterResource(id = NusaIcons.Next),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 66.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.7.dp,
    )
}

@Composable
private fun DisappearingPicker(
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text("Pesan sementara", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                listOf(0 to "Mati", 1 to "24 jam", 7 to "7 hari", 90 to "90 hari").forEach { (days, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(days) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (selected == days) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(20.dp),
                        ) {
                            if (selected == days) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surface),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Tutup") }
        },
    )
}
