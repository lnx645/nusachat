package id.nusachat.app.ui.chats
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTopAppBar

import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.nusachat.app.data.Conversation
import id.nusachat.app.data.User
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.theme.NusaIcons
import id.nusachat.app.ui.components.EmptyState
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaOnlineGreen
import id.nusachat.app.ui.components.SkeletonList
import id.nusachat.app.ui.components.UnreadBadge
import id.nusachat.app.ui.components.UserRow
import id.nusachat.app.ui.components.formatListTime

@Composable
fun ChatsScreen(
    vm: AppViewModel,
    onOpenChat: (Conversation) -> Unit,
) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val loading by vm.conversationsLoading.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val session by vm.sessionState.collectAsStateWithLifecycle()
    val connected by vm.realtimeConnected.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showNewChat by remember { mutableStateOf(false) }

    val currentUserId = session.user?.id
    val filtered = conversations.filter { conversation ->
        if (query.isBlank()) return@filter true
        val preview = conversationPreview(conversation, currentUserId)
        conversation.displayTitle.contains(query, ignoreCase = true) ||
            preview.contains(query, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Obrolan",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "${conversations.size} percakapan",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NusaIconButton(onClick = { vm.refreshConversations() }) {
                Icon(painter = painterResource(id = NusaIcons.Refresh), contentDescription = "Segarkan")
            }
            NusaIconButton(onClick = { showNewChat = true }) {
                Icon(painter = painterResource(id = NusaIcons.Add), contentDescription = "Chat baru")
            }
        }

        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Cari obrolan",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )

        when {
            loading && conversations.isEmpty() -> SkeletonList(count = 7)
            filtered.isEmpty() -> EmptyState(
                icon = NusaIcons.Chat,
                title = if (query.isBlank()) "Belum ada obrolan" else "Tidak ditemukan",
                subtitle = if (query.isBlank()) {
                    "Tambahkan teman, lalu mulai percakapan pertamamu."
                } else {
                    "Coba kata kunci lain."
                },
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        currentUserId = currentUserId,
                        onClick = { onOpenChat(conversation) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 78.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (showNewChat) {
        NewChatDialog(
            friends = friends,
            onDismiss = { showNewChat = false },
            onPick = { user ->
                showNewChat = false
                vm.openChatWith(user)
            },
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = NusaIcons.Search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 9.dp, vertical = 11.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                NusaIconButton(onClick = { onValueChange("") }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Close),
                        contentDescription = "Hapus",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    currentUserId: Long?,
    onClick: () -> Unit,
) {
    val peerOnline = conversation.peer?.online == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            NusaAvatar(
                name = conversation.displayTitle,
                url = conversation.avatarUrl ?: conversation.peer?.avatarUrl, gender = conversation.peer?.gender, seed = conversation.peer?.id,
                size = 50.dp,
            )
            if (peerOnline) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(NusaOnlineGreen),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.displayTitle,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatListTime(conversation.lastMessageAt ?: conversation.lastMessage?.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversationPreview(conversation, currentUserId),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (conversation.unreadCount > 0) {
                    Spacer(Modifier.size(8.dp))
                    UnreadBadge(conversation.unreadCount)
                }
            }
        }
    }
}

private fun conversationPreview(conversation: Conversation, currentUserId: Long?): String {
    val message = conversation.lastMessage ?: return "Belum ada pesan"
    if (message.revoked) return "Pesan telah ditarik"
    if (message.type == "system") return message.body?.take(60) ?: "Aktivitas grup"
    val raw = when (message.type) {
        "image" -> "[Foto]"
        "video" -> "[Video]"
        "audio" -> "[Pesan suara]"
        "location" -> "[Lokasi]"
        "file" -> "[File]"
        else -> message.body.orEmpty()
    }
    // Jika body masih ciphertext base64 (gagal dekrip karena ganti HP/reinstall), jangan tampilkan raw base64
    val body = if (message.type == "text" && raw.length > 24 && raw.matches(Regex("^[A-Za-z0-9+/=\\n\\r]+$")) && raw.contains("=")) {
        "[Pesan terenkripsi]"
    } else raw
    val prefix = when {
        message.sender?.id == currentUserId -> "Kamu: "
        conversation.isGroup && message.sender != null -> "${message.sender.displayName}: "
        else -> ""
    }
    return prefix + body
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatDialog(
    friends: List<User>,
    onDismiss: () -> Unit,
    onPick: (User) -> Unit,
) {
    // LINE: layar penuh via Dialog — TopBar putih, search, list, nutup bottom nav
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    val filtered = friends.filter {
        query.isBlank() ||
            it.displayName.contains(query, ignoreCase = true) ||
            it.username.contains(query, ignoreCase = true)
    }.sortedBy { it.displayName.lowercase() }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        ) {
            androidx.activity.compose.BackHandler(onBack = onDismiss)
            androidx.compose.material3.Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    NusaTopAppBar(
                        title = { Text("Chat baru", fontWeight = FontWeight.SemiBold) },
                        navigationIcon = {
                            NusaIconButton(onClick = onDismiss) {
                                androidx.compose.material3.Icon(
                                    painter = androidx.compose.ui.res.painterResource(id = id.nusachat.app.ui.theme.NusaIcons.Back),
                                    contentDescription = "Kembali"
                                )
                            }
                        },
                        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            ) { padding ->
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    id.nusachat.app.ui.components.NusaSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Cari teman",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    if (friends.isEmpty()) {
                        id.nusachat.app.ui.components.EmptyState(
                            icon = id.nusachat.app.ui.theme.NusaIcons.PersonAdd,
                            title = "Belum ada teman",
                            subtitle = "Tambahkan teman lewat tab Kontak terlebih dahulu."
                        )
                    } else if (filtered.isEmpty()) {
                        id.nusachat.app.ui.components.EmptyState(
                            icon = id.nusachat.app.ui.theme.NusaIcons.Search,
                            title = "Tidak ditemukan",
                            subtitle = "Coba kata kunci lain."
                        )
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filtered, key = { it.id }) { friend ->
                                id.nusachat.app.ui.components.UserRow(
                                    user = friend,
                                    subtitle = "@${friend.username}",
                                    onClick = { onPick(friend) },
                                )
                                androidx.compose.material3.HorizontalDivider(
                                    modifier = Modifier.padding(start = 72.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                            item { Spacer(Modifier.height(12.dp)) }
                        }
                    }
                }
            }
        }
    }
}
