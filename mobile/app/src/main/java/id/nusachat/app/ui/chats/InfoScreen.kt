package id.nusachat.app.ui.chats
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.components.NusaBottomSheet
import id.nusachat.app.ui.components.NusaSwitch
import id.nusachat.app.ui.components.NusaCheckbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusachat.app.data.Message
import id.nusachat.app.data.User
import id.nusachat.app.ui.chats.components.ConversationMediaCard
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaConfirmSheet
import id.nusachat.app.ui.components.NusaOnlineGreen
import id.nusachat.app.ui.components.formatClockTime
import id.nusachat.app.ui.theme.NusaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    conversation: id.nusachat.app.data.Conversation,
    friends: List<User>,
    myId: Long,
    muting: Boolean,
    onBack: () -> Unit,
    onToggleMute: () -> Unit,
    onAddMembers: (List<Long>) -> Unit,
    onRemoveMember: (Long) -> Unit,
    onLeave: () -> Unit,
    onShowAddMembers: () -> Unit,
    onCallPeer: (User) -> Unit = {},
    media: List<Message> = emptyList(),
    onImageClick: (String) -> Unit = {},
    onVideoClick: (String) -> Unit = {},
) {
    var confirmRemove by remember { mutableStateOf<User?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val isOwner = conversation.createdBy == myId || conversation.myRole == "owner"
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NusaTopAppBar(
                title = {
                    Column {
                        Text(
                            text = conversation.displayTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (conversation.isGroup) {
                                "${conversation.members.size} anggota"
                            } else {
                                conversation.peer?.let { "@${it.username}" } ?: ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "header") {
                val peer = conversation.peer
                // Hero ala WhatsApp: bersih netral, avatar besar, status, aksi cepat.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 28.dp, bottom = 22.dp, start = 20.dp, end = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            NusaAvatar(
                                name = conversation.displayTitle,
                                url = conversation.avatarUrl ?: peer?.avatarUrl,
                                size = 104.dp,
                            )
                            if (peer != null && !conversation.isGroup) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 6.dp, bottom = 6.dp)
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(if (peer.online) NusaOnlineGreen else Color(0xFFBDBDBD))
                                        .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            conversation.displayTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (peer != null && !conversation.isGroup) {
                            Text(
                                "@${peer.username}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            peer.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    bio,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (peer.online) {
                                    NusaOnlineGreen.copy(alpha = 0.12f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (peer.online) NusaOnlineGreen
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            ),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (peer.online) {
                                            "Online"
                                        } else if (peer.lastSeenAt != null) {
                                            "Terakhir dilihat " + formatClockTime(peer.lastSeenAt)
                                        } else {
                                            "Offline"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (peer.online) {
                                            NusaOnlineGreen
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                        if (conversation.isGroup) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${conversation.members.size} anggota",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            if (peer != null && !conversation.isGroup) {
                                ContactAction(
                                    icon = NusaIcons.Chat,
                                    label = "Chat",
                                    onClick = onBack,
                                )
                                ContactAction(
                                    icon = NusaIcons.Call,
                                    label = "Telepon",
                                    onClick = { onCallPeer(peer) },
                                )
                            } else {
                                ContactAction(
                                    icon = NusaIcons.GroupAdd,
                                    label = "Tambah",
                                    onClick = onShowAddMembers,
                                )
                                ContactAction(
                                    icon = if (conversation.muted) NusaIcons.VolumeUp else NusaIcons.VolumeOff,
                                    label = if (conversation.muted) "Bunyikan" else "Bisukan",
                                    onClick = onToggleMute,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (media.isNotEmpty()) {
                item(key = "media") {
                    ConversationMediaCard(
                        media = media,
                        onImageClick = onImageClick,
                        onVideoClick = onVideoClick,
                    )
                }
            }
            if (conversation.isGroup) {
                item(key = "settings") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(
                                        id = if (conversation.muted) NusaIcons.VolumeOff else NusaIcons.Bell
                                    ),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Bisukan notifikasi", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (conversation.muted) "Notifikasi grup sedang dibisukan" else "Kamu menerima notifikasi grup",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            NusaSwitch(checked = conversation.muted, onCheckedChange = { onToggleMute() }, enabled = !muting)
                        }
                    }
                }
                item(key = "members") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                    ) {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Anggota",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Text(
                                        "${conversation.members.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                NusaTextButton(onClick = onShowAddMembers) {
                                    Icon(painter = painterResource(id = NusaIcons.GroupAdd), contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Tambah")
                                }
                            }
                            conversation.members.forEachIndexed { index, member ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 64.dp, end = 16.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    NusaAvatar(name = member.displayName, url = member.avatarUrl, size = 40.dp)
                                    Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                member.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (member.id == conversation.createdBy) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(50),
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                ) {
                                                    Text(
                                                        "Pemilik",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            "@${member.username}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (member.id != myId && isOwner) {
                                        NusaTextButton(onClick = { confirmRemove = member }) {
                                            Text("Keluarkan", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "leave") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { confirmLeave = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(id = NusaIcons.Logout),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Keluar grup",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            item(key = "note") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Lock),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Pesan terenkripsi end-to-end",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    confirmRemove?.let { target ->
        NusaConfirmSheet(
            title = "Keluarkan ${target.displayName}?",
            text = "Dia tidak bisa membaca atau mengirim pesan di grup ini lagi.",
            confirmLabel = "Keluarkan",
            danger = true,
            onConfirm = { onRemoveMember(target.id); confirmRemove = null },
            onDismiss = { confirmRemove = null },
        )
    }
    if (confirmLeave) {
        NusaConfirmSheet(
            title = "Keluar dari grup?",
            text = "Kamu tidak lagi menerima pesan dari grup ini.",
            confirmLabel = "Keluar",
            danger = true,
            onConfirm = { confirmLeave = false; onLeave() },
            onDismiss = { confirmLeave = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMembersSheet(
    friends: List<User>,
    existingIds: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    val selectable = friends.filterNot { existingIds.contains(it.id) }
    val selected = remember { mutableStateListOf<Long>() }
    NusaBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Tambah anggota", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (selectable.isEmpty()) "Semua teman sudah menjadi anggota."
                else "Pilih teman untuk dimasukkan ke grup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(selectable, key = { it.id }) { friend ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (selected.contains(friend.id)) selected.remove(friend.id)
                                else selected.add(friend.id)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NusaCheckbox(
                            checked = selected.contains(friend.id),
                            onCheckedChange = {
                                if (selected.contains(friend.id)) selected.remove(friend.id)
                                else selected.add(friend.id)
                            },
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(friend.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "@${friend.username}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            NusaButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Tambah (${selected.size})")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ContactAction(icon: Int, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

