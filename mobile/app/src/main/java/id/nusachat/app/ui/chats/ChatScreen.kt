package id.nusachat.app.ui.chats
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaFilledIconButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.components.NusaBottomSheet
import id.nusachat.app.ui.components.NusaProgress

import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import android.Manifest
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.foundation.interaction.MutableInteractionSource
import id.nusachat.app.data.Message
import id.nusachat.app.data.User
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.chats.components.MessageBubble
import id.nusachat.app.ui.chats.components.MessageInfoDialog
import id.nusachat.app.ui.chats.components.TypingBubble
import id.nusachat.app.ui.components.NusaOnlineGreen
import id.nusachat.app.ui.theme.NusaIcons
import id.nusachat.app.ui.ChatUiState
import id.nusachat.app.ui.components.EmptyState
import id.nusachat.app.ui.components.NusaActionSheet
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaConfirmSheet
import id.nusachat.app.ui.components.SheetAction
import id.nusachat.app.ui.components.formatClockTime
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: AppViewModel,
    state: ChatUiState,
    onBack: () -> Unit,
) {
    val conversation = state.conversation ?: return
    val session by vm.sessionState.collectAsStateWithLifecycle()
    val me = session.user
    val friends by vm.friends.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    val chatBackground = if (dark) Color(0xFF0B100E) else Color(0xFFEDEDED)
    val listState = rememberLazyListState()
    var draft by rememberSaveable(conversation.id) { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }
    var showAddMembers by remember { mutableStateOf(false) }
    var messageMenu by remember { mutableStateOf<Message?>(null) }
    var infoMessage by remember { mutableStateOf<Message?>(null) }
    val dismissMenu: () -> Unit = { messageMenu = null }
    var audioPlayingKey by remember { mutableStateOf<String?>(null) }
    var imageViewerUrl by remember { mutableStateOf<String?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showE2eeInfo by remember { mutableStateOf(false) }
    var showMediaPicker by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.sendFile(it) }
    }
    val effectiveConversation = state.detail ?: conversation
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingCallPeer by remember { mutableStateOf<User?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val peer = pendingCallPeer
                ?: effectiveConversation.peer
                ?: effectiveConversation.members.firstOrNull { it.id != me?.id }
            if (peer != null) vm.startVoiceCall(peer)
        }
        pendingCallPeer = null
    }
    val sharingLocation by vm.sharingLocation.collectAsStateWithLifecycle()
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) vm.shareCurrentLocation()
    }
    val mediaPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) showMediaPicker = true
    }

    LaunchedEffect(state.messages.size, state.typingName) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    LaunchedEffect(conversation.id) {
        audioPlayingKey = null
        imageViewerUrl = null
    }

    LaunchedEffect(conversation.id) {
        while (true) {
            delay(7_000)
            vm.pollChat()
        }
    }

    LaunchedEffect(showInfo) {
        if (showInfo) vm.loadConversationDetail()
    }

    Scaffold(
        containerColor = chatBackground,
        topBar = {
            NusaTopAppBar(
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(painter = painterResource(id = NusaIcons.Back), contentDescription = "Kembali")
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showInfo = true },
                    ) {
                        NusaAvatar(
                            name = effectiveConversation.displayTitle,
                            url = effectiveConversation.avatarUrl ?: effectiveConversation.peer?.avatarUrl,
                            size = 40.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = effectiveConversation.displayTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = chatSubtitle(state, effectiveConversation.isGroup),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    if (!effectiveConversation.isGroup) {
                        NusaIconButton(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Icon(painter = painterResource(id = NusaIcons.Call), contentDescription = "Panggilan suara")
                        }
                    }
                    // Langsung ke info kontak/grup ala LINE & WhatsApp — tanpa menu lagi.
                    NusaIconButton(onClick = { showInfo = true }) {
                        Icon(painter = painterResource(id = NusaIcons.More), contentDescription = "Info percakapan")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            Column {
                if (sharingLocation) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NusaProgress(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Membagikan lokasi...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.replyTo?.let { reply ->
                    ReplyPreviewBar(
                        reply = reply,
                        messages = state.messages,
                        onCancel = { vm.setReply(null) },
                    )
                }
                ChatInputBar(
                    draft = draft,
                    onDraftChange = {
                        draft = it
                        if (it.isNotEmpty()) vm.notifyTyping()
                    },
                    sendingImage = state.sendingImage || state.sendingFile,
                    onPickImage = { showAttachMenu = true },
                    onSend = {
                        if (draft.isNotBlank()) {
                            vm.sendMessage(draft)
                            draft = ""
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.loading && state.messages.isEmpty() -> {
                    NusaProgress(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(26.dp),
                        strokeWidth = 2.dp,
                    )
                }
                state.messages.isEmpty() -> EmptyState(
                    icon = NusaIcons.Chat,
                    title = "Belum ada pesan",
                    subtitle = "Kirim pesan pertama untuk memulai percakapan.",
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                ) {
                    item(key = "e2ee") { E2eeBanner(onClick = { showE2eeInfo = true }) }
                    if (state.hasMore) {
                        item(key = "older") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                NusaTextButton(
                                    onClick = vm::loadOlderMessages,
                                    enabled = !state.loadingOlder,
                                ) {
                                    if (state.loadingOlder) {
                                        NusaProgress(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text("Pesan sebelumnya")
                                }
                            }
                        }
                    }
                    itemsIndexed(state.messages, key = { _, item -> item.id }) { index, message ->
                        val quoted = state.messages.firstOrNull { it.id == message.replyToId }
                        val prev = state.messages.getOrNull(index - 1)
                        val next = state.messages.getOrNull(index + 1)
                        val samePrev = prev?.sender?.id == message.sender?.id
                        val sameNext = next?.sender?.id == message.sender?.id
                        MessageBubble(
                            message = message,
                            quoted = quoted,
                            mine = message.sender?.id == me?.id,
                            isGroup = effectiveConversation.isGroup,
                            compactTop = samePrev,
                            compactBottom = sameNext,
                            onLongPress = { messageMenu = message },
                            onRetry = { vm.retryMessage(message.clientId.orEmpty()) },
                            menuOpen = messageMenu?.let { it.id == message.id && it.clientId == message.clientId } == true,
                            onDismissMenu = dismissMenu,
                            onReply = { vm.setReply(it); dismissMenu() },
                            onRevoke = { vm.revokeMessage(it); dismissMenu() },
                            onShowInfo = { infoMessage = it; dismissMenu() },
                            audioPlayingKey = audioPlayingKey,
                            onAudioToggle = { key ->
                                audioPlayingKey = if (audioPlayingKey == key) null else key
                            },
                            onImageClick = { imageViewerUrl = it },
                        )
                    }
                    if (state.typingName != null) {
                        item(key = "typing") {
                            TypingBubble(name = state.typingName.orEmpty())
                        }
                    }
                    item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
                }
            }
            imageViewerUrl?.let { url ->
                Dialog(
                    onDismissRequest = { imageViewerUrl = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                    ) {
                        AsyncImage(
                            model = url,
                            contentDescription = "Foto",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        NusaIconButton(
                            onClick = { imageViewerUrl = null },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp),
                        ) {
                            Icon(
                                painter = painterResource(id = NusaIcons.Close),
                                contentDescription = "Tutup",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
            }
            if (messageMenu != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { dismissMenu() },
                )
            }
        }
    }

    if (showAttachMenu) {
        NusaActionSheet(
            title = "Kirim lampiran",
            subtitle = "Foto dari galeri Line-style, video/audio/file via sistem.",
            actions = listOf(
                SheetAction("Foto", NusaIcons.Photo) {
                    showAttachMenu = false
                    val hasMedia = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    } else {
                        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (hasMedia) showMediaPicker = true
                    else mediaPermission.launch(
                        if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
                        else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    )
                },
                SheetAction("Video", NusaIcons.Video) {
                    showAttachMenu = false; filePicker.launch("video/*")
                },
                SheetAction("Audio", NusaIcons.AudioFile) {
                    showAttachMenu = false; filePicker.launch("audio/*")
                },
                SheetAction("File", NusaIcons.Attach) {
                    showAttachMenu = false; filePicker.launch("*/*")
                },
                SheetAction("Lokasi", NusaIcons.Pin) {
                    showAttachMenu = false
                    if (vm.hasLocationPermission()) vm.shareCurrentLocation()
                    else locationPermission.launch(
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                },
            ),
            onDismiss = { showAttachMenu = false },
        )
    }
    if (showMediaPicker) {
        id.nusachat.app.ui.components.MediaPickerSheet(
            maxSelection = 1,
            onDismiss = { showMediaPicker = false },
            onConfirm = { uris ->
                showMediaPicker = false
                uris.firstOrNull()?.let(vm::sendImage)
            },
        )
    }

    infoMessage?.let { info ->
        MessageInfoDialog(message = info, onDismiss = { infoMessage = null })
    }

    if (showE2eeInfo) {
        NusaBottomSheet(
            onDismissRequest = { showE2eeInfo = false },
            containerColor = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Lock),
                        contentDescription = null,
                        tint = Color(0xFF0B8F6A),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Enkripsi end-to-end", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Pesan di percakapan ini dienkripsi end-to-end. Hanya kamu dan lawan bicara yang bisa membacanya. NusaChat tidak menyimpan kunci.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Kunci AES-256 GCM dibuat per-percakapan dan disimpan aman di perangkatmu (EncryptedSharedPreferences). Jika kamu ganti HP, riwayat lama tetap sebagai ciphertext.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showInfo) {
        BackHandler { showInfo = false; showAddMembers = false }
        GroupInfoScreen(
            conversation = effectiveConversation,
            friends = friends,
            myId = me?.id ?: 0L,
            muting = state.muting,
            onBack = { showInfo = false },
            onToggleMute = { vm.toggleMute(!effectiveConversation.muted) },
            onAddMembers = { ids -> vm.addGroupMembers(ids) },
            onRemoveMember = { vm.removeGroupMember(it) },
            onLeave = { vm.leaveGroup(onBack) },
            onShowAddMembers = { showAddMembers = true },
            onCallPeer = { peer ->
                pendingCallPeer = peer
                showInfo = false
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            media = state.messages.filter {
                !it.revoked && (
                    (it.type != "text" && it.type.isNotBlank() && !it.attachmentUrl.isNullOrBlank()) ||
                        (it.type == "location" && it.lat != null && it.lng != null)
                    )
            },
            onImageClick = { imageViewerUrl = it },
            onVideoClick = { url ->
                runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(android.net.Uri.parse(url), "video/*")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            },
        )
    }

    if (showAddMembers) {
        AddMembersSheet(
            friends = friends,
            existingIds = effectiveConversation.members.map { it.id }.toSet(),
            onDismiss = { showAddMembers = false },
            onConfirm = { ids ->
                showAddMembers = false
                vm.addGroupMembers(ids)
            },
        )
    }
}

private fun chatSubtitle(state: ChatUiState, isGroup: Boolean): String {
    if (!state.typingName.isNullOrBlank()) {
        return "${state.typingName} sedang mengetik..."
    }
    if (isGroup) {
        return "${state.conversation?.memberCount ?: 0} anggota"
    }
    val peer = state.conversation?.peer ?: return "Offline"
    return when {
        peer.online -> "Online"
        peer.lastSeenAt != null -> "Terakhir dilihat " + formatClockTime(peer.lastSeenAt)
        else -> "Offline"
    }
}

@Composable
private fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    sendingImage: Boolean,
    onPickImage: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            NusaIconButton(
                onClick = onPickImage,
                enabled = !sendingImage,
            ) {
                if (sendingImage) {
                    NusaProgress(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        painter = painterResource(id = NusaIcons.AddCircle),
                        contentDescription = "Kirim foto",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(27.dp),
                    )
                }
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    text = "Tulis pesan...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            Spacer(Modifier.width(6.dp))
            NusaFilledIconButton(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.Send),
                    contentDescription = "Kirim",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ReplyPreviewBar(
    reply: Message,
    messages: List<Message>,
    onCancel: () -> Unit,
) {
    val quoted = messages.firstOrNull { it.id == reply.id } ?: reply
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = quoted.sender?.displayName ?: "Pesan",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = quoted.body?.take(80).orEmpty().ifBlank { "[${quoted.type}]" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            NusaIconButton(onClick = onCancel, modifier = Modifier.size(30.dp)) {
                Icon(painter = painterResource(id = NusaIcons.Close), contentDescription = "Batalkan balasan")
            }
        }
    }
}

@Composable
private fun E2eeBanner(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFE8F5E9),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = NusaIcons.Lock),
                contentDescription = null,
                tint = Color(0xFF0B8F6A),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Pesan terenkripsi end-to-end. Ketuk untuk info.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF2E7D32),
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(id = NusaIcons.Info),
                contentDescription = null,
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
