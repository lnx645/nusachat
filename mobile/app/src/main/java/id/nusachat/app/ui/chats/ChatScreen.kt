package id.nusachat.app.ui.chats
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaFilledIconButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.components.NusaBottomSheet
import id.nusachat.app.ui.components.NusaButton
import id.nusachat.app.ui.components.NusaMenu
import id.nusachat.app.ui.components.NusaProgress
import id.nusachat.app.ui.components.PinSheet
import id.nusachat.app.ui.components.RedPacketCreateSheet
import id.nusachat.app.ui.components.RedPacketSheet
import id.nusachat.app.ui.components.ReportSheet
import id.nusachat.app.ui.components.StickerPickerSheet

import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import android.Manifest
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.foundation.interaction.MutableInteractionSource
import id.nusachat.app.data.Conversation
import id.nusachat.app.data.Message
import id.nusachat.app.data.PinnedMessage
import id.nusachat.app.data.User
import id.nusachat.app.ui.AppViewModel
import id.nusachat.app.ui.chats.components.MessageBubble
import id.nusachat.app.ui.chats.components.MessageInfoDialog
import id.nusachat.app.ui.chats.components.MsgAction
import id.nusachat.app.ui.chats.components.QUICK_REACT_EMOJIS
import id.nusachat.app.ui.chats.components.availableMsgActions
import id.nusachat.app.ui.chats.components.messageCopyText
import id.nusachat.app.ui.chats.components.messagePreviewText
import id.nusachat.app.ui.chats.components.TypingBubble
import id.nusachat.app.ui.components.NusaOnlineGreen
import id.nusachat.app.ui.theme.NusaIcons
import id.nusachat.app.ui.theme.chatPillColors
import id.nusachat.app.ui.theme.doodleDrawableName
import id.nusachat.app.ui.theme.resolveChatTheme
import id.nusachat.app.ui.ChatUiState
import id.nusachat.app.ui.components.EmptyState
import id.nusachat.app.ui.chats.components.AttachPanel
import id.nusachat.app.ui.chats.components.EmojiPanel
import id.nusachat.app.ui.chats.components.GifPanel
import id.nusachat.app.ui.chats.components.SystemEventBubble
import id.nusachat.app.ui.profile.PublicProfileScreen
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaConfirmSheet
import id.nusachat.app.ui.components.dayKey
import id.nusachat.app.ui.components.formatClockTime
import id.nusachat.app.ui.components.formatDayLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    vm: AppViewModel,
    state: ChatUiState,
    onBack: () -> Unit,
) {
    val conversation = state.conversation ?: return
    val session by vm.sessionState.collectAsStateWithLifecycle()
    val online by vm.isOnline.collectAsStateWithLifecycle()
    val me = session.user
    val friends by vm.friends.collectAsStateWithLifecycle()
    // Samakan dengan akar tema (NusaChatApp): override terang/gelap menang,
    // sistem hanya fallback — jangan baca sistem langsung di sini.
    val themeMode by vm.settings.themeMode.collectAsStateWithLifecycle()
    val dark = when (themeMode) {
        id.nusachat.app.data.SettingsStore.THEME_DARK -> true
        id.nusachat.app.data.SettingsStore.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    // Latar ruang chat dari Latar Chat pilihan user (default: putih semu abu + doodle Nusa).
    val themeChoice by vm.chatTheme.collectAsStateWithLifecycle()
    val chatTheme = resolveChatTheme(themeChoice.wallpaperId, themeChoice.customUri, themeChoice.bubbleId, dark)
    val chatBackground = chatTheme.base
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var highlightedId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    var draft by rememberSaveable(conversation.id) { mutableStateOf(vm.settings.draftFor(conversation.id)) }
    val selected by vm.selectedMessages.collectAsStateWithLifecycle()
    val selectionMode = selected.isNotEmpty()
    // Satu pesan terpilih = contextual bar (aksi di app bar + tombol bawah).
    val singleSelected = remember(selected, state.messages) {
        if (selected.size == 1) state.messages.firstOrNull { it.id in selected } else null
    }
    // Seleksi basi (pesan hilang / id sementara terganti) = keluar mode seleksi.
    LaunchedEffect(selected, state.messages) {
        if (selected.isNotEmpty() && state.messages.none { it.id in selected }) {
            vm.clearSelection()
        }
    }
    if (selectionMode) {
        BackHandler { vm.clearSelection() }
    }
    var showAttachPanel by remember { mutableStateOf(false) }
    var showEmojiPanel by remember { mutableStateOf(false) }
    var showStickers by remember { mutableStateOf(false) }
    var showGif by remember { mutableStateOf(false) }
    val gifResults by vm.gifResults.collectAsStateWithLifecycle()
    val gifLoading by vm.gifLoading.collectAsStateWithLifecycle()
    var showRedPacketCreate by remember { mutableStateOf(false) }
    val openPacket by vm.redPacket.collectAsStateWithLifecycle()
    val packetLoading by vm.redPacketLoading.collectAsStateWithLifecycle()
    val walletBalance by vm.walletBalance.collectAsStateWithLifecycle()
    val walletHasPin by vm.walletHasPin.collectAsStateWithLifecycle()
    var reportMsg by remember { mutableStateOf<Message?>(null) }
    var showMultiForward by remember { mutableStateOf(false) }
    var pinPrompt by remember { mutableStateOf(false) }
    var pendingPacket by remember { mutableStateOf<Triple<Long, Int, String>?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var showAddMembers by remember { mutableStateOf(false) }
    var infoMessage by remember { mutableStateOf<Message?>(null) }
    val clipboard = LocalClipboardManager.current
    var audioPlayingKey by remember { mutableStateOf<String?>(null) }
    var imageViewerUrl by remember { mutableStateOf<String?>(null) }
    var showE2eeInfo by remember { mutableStateOf(false) }
    var showMediaPicker by remember { mutableStateOf(false) }
    var forwardMsg by remember { mutableStateOf<Message?>(null) }
    var showPins by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    // Baris list: separator tanggal + pesan + (indikator mengetik) + spasi bawah.
    // Dibangun sekali per perubahan pesan agar LazyColumn stabil.
    val rows = remember(state.messages, state.typingName) {
        buildChatRows(state.messages, state.typingName)
    }
    fun jumpToMessage(id: Long) {
        // Lompat memakai indeks absolut (banner + baris).
        scope.launch {
            val prefix = if (state.hasMore) 2 else 1
            val idx = rows.indexOfFirst { it is ChatRow.Msg && it.message.id == id }
            if (idx != -1) {
                highlightedId = id
                listState.animateScrollToItem(prefix + idx)
                delay(1600)
                if (highlightedId == id) highlightedId = null
            }
        }
    }
    LaunchedEffect(state.editing?.id) {
        state.editing?.body?.let { draft = it }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.sendFile(it) }
    }
    val effectiveConversation = state.detail ?: conversation
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val recentEmojis by vm.settings.recentEmojis.collectAsStateWithLifecycle()
    fun openPanel(attach: Boolean) {
        // Panel dan keyboard saling eksklusif ala WhatsApp.
        keyboard?.hide()
        focusManager.clearFocus()
        showAttachPanel = attach
        showEmojiPanel = !attach
        showGif = false
    }
    fun openGifPanel() {
        keyboard?.hide()
        focusManager.clearFocus()
        showAttachPanel = false
        showEmojiPanel = false
        showGif = true
        vm.loadTrendingGifs()
    }
    fun closePanels() {
        showAttachPanel = false
        showEmojiPanel = false
        if (showGif) {
            showGif = false
            vm.clearGifs()
        }
    }
    // Back menutup panel inline dulu, bukan keluar chat.
    if (showAttachPanel || showEmojiPanel || showGif) {
        BackHandler { closePanels() }
    }
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
    val groupCallPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startGroupCall(effectiveConversation.id)
    }
    var pendingVideoPeer by remember { mutableStateOf<User?>(null) }
    val videoPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            val peer = pendingVideoPeer
                ?: effectiveConversation.peer
                ?: effectiveConversation.members.firstOrNull { it.id != me?.id }
            if (peer != null) vm.startVideoCall(peer)
        }
        pendingVideoPeer = null
    }
    // Backend canonical (kolom di Conversation) adalah sumber kebenaran untuk izin grup
    val canSendInGroup = when {
        !effectiveConversation.isGroup -> true
        !effectiveConversation.onlyAdminsCanSend -> true
        else -> (effectiveConversation.createdBy == me?.id || effectiveConversation.myRole == "owner")
    }
    val requests by vm.requests.collectAsStateWithLifecycle()
    var directRequested by remember(effectiveConversation.peer?.id) { mutableStateOf(false) }
    val isPendingOutgoing = requests.outgoing.any { it.user.id == effectiveConversation.peer?.id } || directRequested
    val isDirectFriend = if (effectiveConversation.isGroup) true else {
        val peerId = effectiveConversation.peer?.id
        peerId == null || friends.any { it.id == peerId } ||
            effectiveConversation.peer?.friendStatus == "accepted"
    }
    val canSend = canSendInGroup && isDirectFriend
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

    // Infinity scroll ke atas: saat mendekati paling atas, otomatis muat riwayat sebelumnya
    // agar tidak menumpuk semua pesan sekaligus di memori/UI.
    androidx.compose.runtime.LaunchedEffect(listState, state.hasMore, state.loadingOlder) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (index <= 2 && state.hasMore && !state.loadingOlder && state.messages.isNotEmpty()) {
                    vm.loadOlderMessages()
                }
            }
    }

    // Bottom-stick ala WhatsApp: snap instan ke bawah (tanpa animasi lambat).
    // - Buka chat / kirim pesan sendiri: selalu snap.
    // - Pesan masuk saat baca riwayat: jangan tarik paksa, tandai pill "Pesan baru".
    // - Keyboard dibuka: snap agar pesan terbaru kelihatan di atas keyboard.
    var hasNewBelow by remember(conversation.id) { mutableStateOf(false) }
    var initialSnapDone by remember(conversation.id) { mutableStateOf(false) }
    var lastSeenId by remember(conversation.id) { mutableStateOf(0L) }
    suspend fun snapToBottom() {
        if (rows.isNotEmpty()) {
            // Indeks absolut = banner e2ee (+ riwayat) + baris; baris terakhir
            // selalu spasi bawah sehingga mendarat di paling bawah list.
            val prefix = if (state.hasMore) 2 else 1
            runCatching { listState.scrollToItem(prefix + rows.lastIndex) }
        }
        hasNewBelow = false
    }
    fun isAtBottom(): Boolean {
        val info = listState.layoutInfo
        if (info.totalItemsCount == 0) return true
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return true
        return lastVisible >= info.totalItemsCount - 2
    }

    LaunchedEffect(state.messages.size, state.typingName, state.keySyncing) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        val latest = state.messages.lastOrNull()
        val latestId = latest?.id ?: 0L
        val grewAtEnd = latestId != lastSeenId
        lastSeenId = latestId
        if (!initialSnapDone) {
            if (state.keySyncing) {
                // Masih cache / delta jaringan belum termuat: snap ke bawah
                // cache saja. initialUnread bisa menunjuk pesan yang belum ada.
                snapToBottom()
                return@LaunchedEffect
            }
            initialSnapDone = true
            // Lompat ke pesan belum dibaca pertama bila ada (dan termuat).
            val unread = state.initialUnread
            if (unread > 0 && unread < state.messages.size) {
                val target = state.messages[state.messages.size - unread]
                val prefix = if (state.hasMore) 2 else 1
                val idx = rows.indexOfFirst { it is ChatRow.Msg && it.message.id == target.id }
                if (idx != -1) {
                    runCatching { listState.scrollToItem(prefix + idx) }
                    return@LaunchedEffect
                }
            }
            snapToBottom()
            return@LaunchedEffect
        }
        if (grewAtEnd) {
            val isOwn = latest?.sender?.id == me?.id
            if (isOwn || isAtBottom()) snapToBottom()
            else hasNewBelow = true
        } else if (state.typingName != null && isAtBottom()) {
            snapToBottom()
        }
        // Riwayat lama dimuat ke atas: diam, posisi baca aman.
    }

    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible, conversation.id) {
        if (imeVisible) snapToBottom()
    }
    // Panel inline dibuka: pesan terbaru tetap kelihatan di atas panel.
    LaunchedEffect(showAttachPanel, showEmojiPanel, showGif) {
        if (showAttachPanel || showEmojiPanel || showGif) snapToBottom()
    }

    // Pill "Pesan baru" ikut hilang saat user scroll manual sampai bawah.
    var atBottom by remember(conversation.id) { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            info.totalItemsCount to (info.visibleItemsInfo.lastOrNull()?.index ?: -1)
        }.collect { (total, last) ->
            val bottom = total > 0 && last >= total - 2
            atBottom = bottom
            if (bottom) hasNewBelow = false
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

    var showPublicProfile by remember { mutableStateOf(false) }

    LaunchedEffect(showInfo) {
        if (showInfo) vm.loadConversationDetail()
    }
    LaunchedEffect(showPublicProfile) {
        if (showPublicProfile) vm.refreshMoments()
    }

    // Satu dispatcher untuk semua aksi pesan tunggal (app bar, overflow, tombol bawah).
    val onSingleAction: (MsgAction, Message) -> Unit = { action, msg ->
        when (action) {
            MsgAction.REPLY -> vm.setReply(msg)
            MsgAction.FORWARD -> forwardMsg = msg
            MsgAction.EDIT -> vm.setEditing(msg)
            MsgAction.PIN -> if (state.pins.any { it.message.id == msg.id }) vm.unpinMessage(msg) else vm.pinMessage(msg)
            MsgAction.COPY -> messageCopyText(msg)?.let { clipboard.setText(AnnotatedString(it)) }
            MsgAction.STAR -> vm.starMessage(msg)
            MsgAction.SAVE -> msg.attachmentUrl?.let { vm.downloadMedia(it, null) }
            MsgAction.SHARE -> messageCopyText(msg)?.let { vm.shareText(it, "Bagikan pesan") }
            MsgAction.HIDE -> vm.hideMessage(msg)
            MsgAction.REPORT -> reportMsg = msg
            MsgAction.REVOKE -> vm.revokeMessage(msg)
            MsgAction.RETRY -> msg.clientId?.let { vm.retryMessage(it) }
            MsgAction.INFO -> infoMessage = msg
            MsgAction.REACT -> { /* lewat strip emoji bawah */ }
        }
        vm.clearSelection()
    }

    // Wallpaper dianchor ke ukuran layar penuh (bukan kotak konten) supaya
    // tidak ikut menciut/bergeser saat keyboard buka atau area list berubah.
    // Scaffold transparan; top/bottom bar yang opaque menutup sisanya.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(chatBackground),
    ) {
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenHeight)
                .align(Alignment.TopCenter),
        ) {
            ChatWallpaperLayer(
                wallpaperId = themeChoice.wallpaperId,
                customUri = chatTheme.customUri,
                scrim = chatTheme.customScrim,
            )
        }
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                NusaTopAppBar(
                    navigationIcon = {
                        NusaIconButton(onClick = { if (selectionMode) vm.clearSelection() else onBack() }) {
                            Icon(
                                painter = painterResource(id = if (selectionMode) NusaIcons.Close else NusaIcons.Back),
                                contentDescription = if (selectionMode) "Batalkan pilihan" else "Kembali",
                            )
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(enabled = !selectionMode) {
                                if (effectiveConversation.isGroup) showInfo = true
                                else if (effectiveConversation.peer != null) showPublicProfile = true
                            },
                        ) {
                            if (!selectionMode) {
                                NusaAvatar(
                                    name = effectiveConversation.displayTitle,
                                    url = effectiveConversation.avatarUrl ?: effectiveConversation.peer?.avatarUrl,
                                    gender = effectiveConversation.peer?.gender,
                                    seed = effectiveConversation.peer?.id,
                                    size = 40.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Column {
                                Text(
                                    text = if (selectionMode) "${selected.size} dipilih" else effectiveConversation.displayTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (singleSelected != null) "Ketuk pesan lain untuk pilih banyak"
                                    else if (selectionMode) "Pilih aksi di atas"
                                    else chatSubtitle(state, effectiveConversation, me?.id),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                    actions = {
                        val single = singleSelected
                        if (single != null) {
                            val singleMine = single.sender?.id == me?.id
                            key(single.id, single.clientId) {
                                SingleMessageBarActions(
                                    message = single,
                                    mine = singleMine,
                                    pinned = state.pins.any { it.message.id == single.id },
                                    onAction = { action -> onSingleAction(action, single) },
                                )
                            }
                        } else if (selectionMode) {
                            NusaIconButton(onClick = { showMultiForward = true }) {
                                Icon(painter = painterResource(id = NusaIcons.Forward), contentDescription = "Teruskan pilihan")
                            }
                            NusaIconButton(onClick = { vm.starSelected() }) {
                                Icon(painter = painterResource(id = NusaIcons.Star), contentDescription = "Bintangi pilihan")
                            }
                            NusaIconButton(onClick = { vm.deleteSelectedForMe() }) {
                                Icon(painter = painterResource(id = NusaIcons.Delete), contentDescription = "Hapus pilihan untuk saya")
                            }
                        } else {
                            NusaIconButton(onClick = {
                                showSearch = !showSearch
                                if (!showSearch) {
                                    searchText = ""
                                    vm.clearChatSearch()
                                }
                            }) {
                                Icon(painter = painterResource(id = NusaIcons.Search), contentDescription = "Cari pesan")
                            }
                            if (!effectiveConversation.isGroup) {
                                NusaIconButton(onClick = {
                                    videoPermission.launch(
                                        arrayOf(
                                            Manifest.permission.RECORD_AUDIO,
                                            Manifest.permission.CAMERA,
                                        )
                                    )
                                }) {
                                    Icon(painter = painterResource(id = NusaIcons.Video), contentDescription = "Panggilan video")
                                }
                                NusaIconButton(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) {
                                    Icon(painter = painterResource(id = NusaIcons.Call), contentDescription = "Panggilan suara")
                                }
                            }
                            // Langsung ke info kontak/grup ala LINE & WhatsApp — tanpa menu lagi.
                            NusaIconButton(onClick = { showInfo = true }) {
                                Icon(painter = painterResource(id = NusaIcons.More), contentDescription = "Info percakapan")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
            bottomBar = {
                val singleCtx = singleSelected
                if (singleCtx != null) {
                    SingleContextBottomBar(
                        message = singleCtx,
                        mine = singleCtx.sender?.id == me?.id,
                        onReact = { emoji -> vm.reactMessage(singleCtx, emoji); vm.clearSelection() },
                        onReply = { vm.setReply(singleCtx); vm.clearSelection() },
                        onForward = { forwardMsg = singleCtx; vm.clearSelection() },
                    )
                } else {
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
                        state.editing?.let { editing ->
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(id = NusaIcons.Edit),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Edit pesan",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = editing.body?.take(80).orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    NusaIconButton(onClick = { vm.setEditing(null); draft = "" }, modifier = Modifier.size(30.dp)) {
                                        Icon(painter = painterResource(id = NusaIcons.Close), contentDescription = "Batalkan edit")
                                    }
                                }
                            }
                        }
                        // Saran @mention saat mengetik @nama di akhir draf (mode kirim saja;
                        // edit pesan tidak menghitung ulang mention di server).
                        val mentionQuery = remember(draft) { trailingMentionQuery(draft) }
                        if (mentionQuery != null && isDirectFriend && canSendInGroup && state.editing == null) {
                            val candidates = remember(mentionQuery, effectiveConversation.members, me?.id) {
                                mentionCandidates(
                                    effectiveConversation.members,
                                    mentionQuery,
                                    me?.id ?: 0L,
                                    effectiveConversation.isGroup,
                                )
                            }
                            if (candidates.isNotEmpty()) {
                                MentionSuggestBox(
                                    candidates = candidates,
                                    onPick = { username ->
                                        val at = draft.lastIndexOf('@')
                                        val next = (if (at >= 0) draft.substring(0, at) else draft) + "@$username "
                                        draft = next
                                        vm.settings.saveDraft(conversation.id, next)
                                        if (next.isNotEmpty()) vm.notifyTyping()
                                    },
                                )
                            }
                        }
                        if (!isDirectFriend) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .navigationBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(id = NusaIcons.Lock),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Anda tidak dapat mengirim pesan karena Anda tidak saling berteman",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                effectiveConversation.peer?.let { peer ->
                                    if (isPendingOutgoing) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                painter = painterResource(id = NusaIcons.Time),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "Meminta",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    } else {
                                        NusaTextButton(onClick = {
                                            vm.sendFriendRequest(peer)
                                            directRequested = true
                                        }) { Text("Tambah") }
                                    }
                                }
                            }
                        } else if (!canSendInGroup) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .navigationBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(id = NusaIcons.Lock),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Hanya admin yang dapat mengirim pesan",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            // Kolom ketik + panel inline: panel tumbuh di bawah kolom,
                            // daftar pesan (weight 1f) menciut dan terdorong ke atas.
                            Column(Modifier.navigationBarsPadding()) {
                                ChatInputBar(
                                    draft = draft,
                                    onDraftChange = {
                                        draft = it
                                        vm.settings.saveDraft(conversation.id, it)
                                        if (it.isNotEmpty()) vm.notifyTyping()
                                    },
                                    sendingImage = state.sendingImage || state.sendingFile,
                                    onPickImage = {
                                        if (showAttachPanel) closePanels() else openPanel(attach = true)
                                    },
                                    onSticker = { showStickers = true },
                                    onEmojiToggle = {
                                        if (showEmojiPanel) closePanels() else openPanel(attach = false)
                                    },
                                    onFocusChange = { focused ->
                                        if (focused) closePanels()
                                    },
                                    onGif = {
                                        if (showGif) closePanels() else openGifPanel()
                                    },
                                    onVoice = { uri -> vm.sendVoiceFile(uri) },
                                    onSend = {
                                        if (draft.isNotBlank()) {
                                            if (state.editing != null) {
                                                vm.confirmEdit(draft)
                                            } else {
                                                vm.sendMessage(draft)
                                            }
                                            draft = ""
                                            vm.settings.clearDraft(conversation.id)
                                        }
                                    },
                                )
                                AnimatedVisibility(
                                    visible = showAttachPanel,
                                    enter = expandVertically(),
                                    exit = shrinkVertically(),
                                ) {
                                    AttachPanel(
                                        onFoto = {
                                            closePanels()
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
                                        onVideo = { closePanels(); filePicker.launch("video/*") },
                                        onAudio = { closePanels(); filePicker.launch("audio/*") },
                                        onFile = { closePanels(); filePicker.launch("*/*") },
                                        onLokasi = {
                                            closePanels()
                                            if (vm.hasLocationPermission()) vm.shareCurrentLocation()
                                            else locationPermission.launch(
                                                arrayOf(
                                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                                )
                                            )
                                        },
                                        onAmplop = {
                                            closePanels()
                                            vm.loadWalletFull()
                                            showRedPacketCreate = true
                                        },
                                    )
                                }
                                AnimatedVisibility(
                                    visible = showEmojiPanel,
                                    enter = expandVertically(),
                                    exit = shrinkVertically(),
                                ) {
                                    EmojiPanel(
                                        recents = recentEmojis,
                                        onPick = { emoji ->
                                            draft += emoji
                                            vm.settings.saveDraft(conversation.id, draft)
                                            vm.settings.pushRecentEmoji(emoji)
                                            vm.notifyTyping()
                                        },
                                        onBackspace = {
                                            if (draft.isNotEmpty()) {
                                                val cut = draft.offsetByCodePoints(draft.length, -1)
                                                draft = draft.substring(0, cut)
                                                vm.settings.saveDraft(conversation.id, draft)
                                            }
                                        },
                                    )
                                }
                                AnimatedVisibility(
                                    visible = showGif,
                                    enter = expandVertically(),
                                    exit = shrinkVertically(),
                                ) {
                                    GifPanel(
                                        results = gifResults,
                                        loading = gifLoading,
                                        hasKey = id.nusachat.app.data.GifClient.hasKey,
                                        onSearch = { vm.searchGifs(it) },
                                        onPick = { gif ->
                                            closePanels()
                                            vm.sendGif(gif.fullUrl)
                                        },
                                        onManualUrl = { url ->
                                            closePanels()
                                            vm.sendGif(url)
                                        },
                                        onClose = { closePanels() },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Bar offline: pesan tetap bisa dibaca & ditulis, terkirim otomatis.
                if (!online) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(id = NusaIcons.WifiOff),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Offline — menampilkan pesan tersimpan",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showSearch) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            BasicTextField(
                                value = searchText,
                                onValueChange = {
                                    searchText = it
                                    vm.searchInChat(it)
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                decorationBox = { inner ->
                                    Box {
                                        if (searchText.isEmpty()) {
                                            Text(
                                                text = "Cari pesan (min. 2 huruf)...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                            if (state.searchQuery.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (state.searching) "Mencari..." else "${state.searchResults.size} hasil",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                state.searchResults.take(5).forEach { result ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { jumpToMessage(result.id) }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            painter = painterResource(id = NusaIcons.Search),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = messagePreviewText(result, 60),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.pins.isNotEmpty()) {
                    val latestPin = state.pins.firstOrNull()
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (state.pins.size == 1) {
                                latestPin?.let { jumpToMessage(it.message.id) }
                            } else {
                                showPins = true
                            }
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(id = NusaIcons.Pin),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (state.pins.size == 1) {
                                    "Disematkan: ${latestPin?.message?.let { messagePreviewText(it, 50) }.orEmpty()}"
                                } else {
                                    "${state.pins.size} pesan disematkan"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                when {
                    state.loading && state.messages.isEmpty() -> {
                        // Loading di atas (bukan tengah/pinggir): pill + spinner.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LoadingPill(text = "Memuat pesan...")
                            if (state.keySyncing) {
                                Spacer(Modifier.height(8.dp))
                                LoadingPill(text = "Sinkronisasi pesan, mungkin butuh waktu lama...")
                            }
                        }
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
                        item(key = "e2ee") { E2eeBanner(onClick = { showE2eeInfo = true }, wallpaperDark = chatTheme.isDark) }
                        if (state.hasMore) {
                            item(key = "older") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    if (state.loadingOlder) {
                                        NusaProgress(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Memuat riwayat...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        Text("Tarik untuk riwayat sebelumnya", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    }
                                }
                            }
                        }
                        itemsIndexed(
                            rows,
                            key = { index, row ->
                                when (row) {
                                    is ChatRow.Day -> "day-$index-${row.label}"
                                    is ChatRow.Msg -> "msg-${row.message.id}"
                                    is ChatRow.Typing -> "typing"
                                    ChatRow.Space -> "bottom-space"
                                }
                            },
                        ) { _, row ->
                            when (row) {
                                is ChatRow.Day -> DateSeparator(label = row.label, wallpaperDark = chatTheme.isDark)
                                is ChatRow.Typing -> TypingBubble(
                                    name = row.name,
                                    bubble = chatTheme.bubbles.other,
                                    text = chatPillColors(chatTheme.isDark).second,
                                )
                                ChatRow.Space -> Spacer(Modifier.height(8.dp))
                                is ChatRow.Msg -> {
                                    val message = row.message
                                    val index = row.index
                                    if (message.type == "system") {
                                        SystemEventBubble(
                                            message = message,
                                            members = state.detail?.members.orEmpty()
                                                .ifEmpty { conversation.members },
                                            wallpaperDark = chatTheme.isDark,
                                        )
                                    } else {
                                val quoted = state.messages.firstOrNull { it.id == message.replyToId }
                                val prev = state.messages.getOrNull(index - 1)
                                val next = state.messages.getOrNull(index + 1)
                                // Grup bubble putus saat ganti hari (walau pengirim sama).
                                val sameDayPrev = prev?.let { dayKey(it.createdAt) } == dayKey(message.createdAt)
                                val sameDayNext = next?.let { dayKey(it.createdAt) } == dayKey(message.createdAt)
                                val samePrev = prev?.sender?.id == message.sender?.id && prev?.type != "system" && sameDayPrev
                                val sameNext = next?.sender?.id == message.sender?.id && next?.type != "system" && sameDayNext
                                MessageBubble(
                                message = message,
                                quoted = quoted,
                                mine = message.sender?.id == me?.id,
                                myId = me?.id ?: 0L,
                                isGroup = effectiveConversation.isGroup,
                                compactTop = samePrev,
                                compactBottom = sameNext,
                                onLongPress = { vm.toggleSelectMessage(message.id) },
                                onBubbleClick = {
                                    if (selectionMode) vm.toggleSelectMessage(message.id)
                                },
                                selected = message.id in selected,
                                selectionMode = selectionMode,
                                onPacketClick = { vm.loadRedPacket(it) },
                                onRetry = { vm.retryMessage(message.clientId.orEmpty()) },
                                onReply = { vm.setReply(it); vm.clearSelection() },
                                audioPlayingKey = audioPlayingKey,
                                onAudioToggle = { key ->
                                    audioPlayingKey = if (audioPlayingKey == key) null else key
                                },
                                onImageClick = { imageViewerUrl = it },
                                isHighlighted = highlightedId == message.id,
                                bubbles = chatTheme.bubbles,
                                wallpaperDark = chatTheme.isDark,
                                onQuotedClick = { qid ->
                                    if (rows.none { it is ChatRow.Msg && it.message.id == qid } && state.hasMore) {
                                        vm.loadOlderMessages()
                                    } else {
                                        jumpToMessage(qid)
                                    }
                                },
                            )
                                    }
                                }
                            }
                        }
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
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp),
                            ) {
                                NusaIconButton(onClick = { vm.downloadMedia(url, null) }) {
                                    Icon(
                                        painter = painterResource(id = NusaIcons.Download),
                                        contentDescription = "Simpan",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                                NusaIconButton(onClick = { vm.shareText(url, "Bagikan foto") }) {
                                    Icon(
                                        painter = painterResource(id = NusaIcons.Share),
                                        contentDescription = "Bagikan",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (!atBottom && !hasNewBelow && state.messages.isNotEmpty()) {
                    NusaFilledIconButton(
                        onClick = { scope.launch { snapToBottom() } },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 10.dp)
                            .size(44.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Down),
                            contentDescription = "Ke pesan terbaru",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (hasNewBelow && state.messages.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                            .clickable { scope.launch { snapToBottom() } },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Pesan baru",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                painter = painterResource(id = NusaIcons.Down),
                                contentDescription = "Ke bawah",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
                }
            }
        }
    
        forwardMsg?.let { msg ->
            NusaBottomSheet(onDismissRequest = { forwardMsg = null }) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Text(
                        text = "Teruskan ke...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(conversations.filter { it.id != conversation.id }, key = { it.id }) { conv ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.forwardMessage(msg, conv.id)
                                        forwardMsg = null
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NusaAvatar(
                                    name = conv.displayTitle,
                                    url = conv.avatarUrl,
                                    size = 40.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = conv.displayTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    
        if (showPins) {
            NusaBottomSheet(onDismissRequest = { showPins = false }) {
                PinnedListSheet(
                    pins = state.pins,
                    onJump = { id ->
                        showPins = false
                        jumpToMessage(id)
                    },
                    onUnpin = { vm.unpinMessage(it) },
                )
            }
        }
    
        if (showStickers) {
            StickerPickerSheet(
                onPick = { emoji ->
                    showStickers = false
                    vm.sendSticker(emoji)
                },
                onDismiss = { showStickers = false },
            )
        }
    
        if (showMultiForward) {
            NusaBottomSheet(onDismissRequest = { showMultiForward = false }) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Text(
                        text = "Teruskan ${selected.size} pesan ke...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(conversations.filter { it.id != conversation.id }, key = { it.id }) { conv ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.forwardSelected(conv.id) { showMultiForward = false }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NusaAvatar(name = conv.displayTitle, url = conv.avatarUrl, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = conv.displayTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    
        if (showRedPacketCreate) {
            RedPacketCreateSheet(
                balance = walletBalance,
                memberCount = effectiveConversation.members.size.takeIf { it > 0 }
                    ?: effectiveConversation.memberCount.takeIf { it > 0 } ?: 2,
                hasPin = walletHasPin,
                onSubmit = { total, count, note, pin ->
                    showRedPacketCreate = false
                    pendingPacket = Triple(total, count, note)
                    vm.createRedPacket(total, count, note, pin.ifBlank { null }, onPinRequired = { pinPrompt = true })
                },
                onDismiss = { showRedPacketCreate = false },
            )
        }
    
        if (pinPrompt) {
            PinSheet(
                title = "PIN dompet",
                subtitle = "Pengeluaran koin dilindungi PIN.",
                confirmLabel = "Kirim",
                onSubmit = { pin, _ ->
                    pinPrompt = false
                    pendingPacket?.let { (total, count, note) ->
                        vm.createRedPacket(
                            total, count, note, pin,
                            onPinRequired = { pinPrompt = true },
                            onDone = { pendingPacket = null },
                        )
                    }
                },
                onDismiss = { pinPrompt = false; pendingPacket = null },
            )
        }
    
        openPacket?.let { packet ->
            RedPacketSheet(
                packet = packet,
                myId = me?.id ?: 0L,
                loading = packetLoading,
                onClaim = { vm.claimRedPacket(packet.id) },
                onDismiss = { vm.clearRedPacket() },
            )
        }
    
        reportMsg?.let { msg ->
            ReportSheet(
                onSubmit = { reason, details ->
                    vm.createReport("message", msg.id, reason, details)
                    reportMsg = null
                },
                onDismiss = { reportMsg = null },
            )
        }
    
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

    val readsUsers by vm.readsUsers.collectAsStateWithLifecycle()
    val readsLoading by vm.readsLoading.collectAsStateWithLifecycle()
    LaunchedEffect(infoMessage?.id, infoMessage?.clientId) {
        infoMessage?.let { vm.loadMessageReads(conversation.id, it) }
    }

    infoMessage?.let { info ->
        MessageInfoDialog(
            message = info,
            readers = readsUsers,
            readersLoading = readsLoading,
            onDismiss = { infoMessage = null; vm.clearMessageReads() },
        )
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Enkripsi end-to-end", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Pesan dan kunci pengaman di chat ini terenkripsi secara end-to-end. Tidak seorang pun di luar chat ini, bahkan NusaChat, yang dapat membacanya.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Setiap perangkat punya kunci identitas sendiri; sesi chat memakai pertukaran kunci X3DH dan Double Ratchet dengan forward secrecy. Server hanya meneruskan ciphertext dan menghapusnya setelah terkirim.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showPublicProfile && effectiveConversation.peer != null) {
        BackHandler { showPublicProfile = false }
        PublicProfileScreen(
            user = effectiveConversation.peer!!,
            vm = vm,
            onBack = { showPublicProfile = false },
            onChat = { showPublicProfile = false },
        )
        return
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
            onSetRole = { userId, role -> vm.setMemberRole(userId, role) },
            onLeave = { vm.leaveGroup(onBack) },
            onShowAddMembers = { showAddMembers = true },
            vm = vm,
            onCallPeer = { peer ->
                pendingCallPeer = peer
                showInfo = false
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            onGroupCall = {
                showInfo = false
                groupCallPermission.launch(Manifest.permission.RECORD_AUDIO)
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
            hasMoreMedia = state.hasMore,
            onLoadOlderMedia = vm::loadOlderMessages,
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

/**
 * Baris list chat: separator tanggal ala WhatsApp + pesan + indikator
 * mengetik + spasi bawah (selalu terakhir agar snap mendarat di bawah).
 */
private sealed interface ChatRow {
    data class Day(val label: String) : ChatRow
    data class Msg(val message: Message, val index: Int) : ChatRow
    data class Typing(val name: String) : ChatRow
    data object Space : ChatRow
}

private fun buildChatRows(messages: List<Message>, typingName: String?): List<ChatRow> {
    val out = mutableListOf<ChatRow>()
    var prevDay: String? = null
    messages.forEachIndexed { index, message ->
        val day = dayKey(message.createdAt)
        if (day != null && day != prevDay) {
            out.add(ChatRow.Day(formatDayLabel(message.createdAt)))
            prevDay = day
        }
        out.add(ChatRow.Msg(message, index))
    }
    if (typingName != null) out.add(ChatRow.Typing(typingName))
    out.add(ChatRow.Space)
    return out
}

/**
 * Lapisan wallpaper di belakang daftar pesan: doodle Nusa (bawaan),
 * atau gambar galeri pilihan user + scrim agar bubble tetap terbaca.
 */
@Composable
private fun ChatWallpaperLayer(wallpaperId: String, customUri: String?, scrim: Float) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val doodleRes = remember(wallpaperId) {
        doodleDrawableName(wallpaperId)?.let { name ->
            context.resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 }
        }
    }
    if (doodleRes == null && customUri.isNullOrBlank()) return
    Box(modifier = Modifier.fillMaxSize()) {
        if (doodleRes != null) {
            Image(
                painter = painterResource(id = doodleRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!customUri.isNullOrBlank()) {
            AsyncImage(
                model = customUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (scrim > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrim)),
                )
            }
        }
    }
}

@Composable
private fun DateSeparator(label: String, wallpaperDark: Boolean) {
    val (pillBg, pillFg) = chatPillColors(wallpaperDark)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = pillBg,
            shadowElevation = 1.dp,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = pillFg,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun LoadingPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NusaProgress(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun chatSubtitle(state: ChatUiState, conversation: Conversation, myId: Long?): String {
    if (!state.typingName.isNullOrBlank()) {
        return "${state.typingName} sedang mengetik..."
    }
    if (conversation.isGroup) {
        val members = state.detail?.members.orEmpty().ifEmpty { conversation.members }
        val total = members.size.takeIf { it > 0 } ?: conversation.memberCount
        val online = members.count { it.id != myId && it.online }
        return if (online > 0) "$total anggota, $online online" else "$total anggota"
    }
    val peer = state.conversation?.peer ?: return "Offline"
    return when {
        peer.online -> "Online"
        peer.lastSeenAt != null -> "Terakhir dilihat " + formatClockTime(peer.lastSeenAt)
        else -> "Offline"
    }
}

/** Query @mention di akhir draf: "@" diawali spasi/awal teks + 0-20 char kata. Null = tidak aktif. */
private fun trailingMentionQuery(draft: String): String? {
    val match = Regex("""(?:^|\s)@([A-Za-z0-9._]{0,20})$""").find(draft) ?: return null
    return match.groupValues[1]
}

/**
 * Kandidat mention: anggota (kecuali saya) yang username/nama cocok query,
 * ditambah "semua" di grup. Id -1 = pseudo @semua, bukan user beneran.
 */
private fun mentionCandidates(
    members: List<User>,
    query: String,
    myId: Long,
    isGroup: Boolean,
): List<User> {
    val q = query.lowercase()
    val matched = members
        .filter { it.id != myId }
        .filter {
            q.isBlank() ||
                it.username.contains(q, ignoreCase = true) ||
                it.displayName.contains(q, ignoreCase = true)
        }
        .sortedWith(
            compareBy(
                { !it.username.startsWith(q, ignoreCase = true) },
                { it.displayName.lowercase() },
            )
        )
        .take(5)
    val out = matched.toMutableList()
    if (isGroup && (q.isBlank() || "semua".contains(q) || "all".contains(q))) {
        out.add(0, User(id = -1L, username = "semua", displayName = "Semua anggota"))
    }
    return out.take(6)
}

/** Kotak saran @mention di atas kolom input — ketuk untuk sisipkan @username. */
@Composable
private fun MentionSuggestBox(
    candidates: List<User>,
    onPick: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 248.dp),
        ) {
            items(candidates, key = { it.id }) { user ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(user.username) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (user.id == -1L) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(id = NusaIcons.AtSign),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        id.nusachat.app.ui.components.NusaAvatar(
                            name = user.displayName,
                            url = user.avatarUrl,
                            gender = user.gender,
                            seed = user.id,
                            size = 36.dp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "@${user.username}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    sendingImage: Boolean,
    onPickImage: () -> Unit,
    onSticker: () -> Unit = {},
    onEmojiToggle: () -> Unit = {},
    onFocusChange: (Boolean) -> Unit = {},
    onGif: () -> Unit = {},
    onVoice: (android.net.Uri) -> Unit = {},
    onSend: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val recorder = remember { id.nusachat.app.data.VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && recorder.start()) {
            recording = true
            seconds = 0
        }
    }
    androidx.compose.runtime.LaunchedEffect(recording) {
        while (recording) {
            delay(1000)
            seconds++
            if (seconds >= 300) {
                recorder.stop()?.let { file ->
                    recorder.outputUri()?.let(onVoice)
                }
                recording = false
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { recorder.cancel() }
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        if (recording) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(50)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = String.format("%d:%02d", seconds / 60, seconds % 60),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                NusaTextButton(onClick = { recorder.cancel(); recording = false }) { Text("Batal") }
                NusaButton(
                    onClick = {
                        recorder.stop()
                        recorder.outputUri()?.let(onVoice)
                        recording = false
                    },
                    modifier = Modifier.height(40.dp),
                ) {
                    Text("Kirim", fontWeight = FontWeight.Bold)
                }
            }
            return@Surface
        }
        // Ala WhatsApp: ikon kecil di DALAM field (emoji kiri, GIF+lampiran
        // kanan); mic/kirim tetap di luar. Hijau hanya tombol kirim.
        val composerTint = MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                enabled = !sendingImage,
                                onClick = onEmojiToggle,
                                onLongClick = onSticker,
                            ),
                    ) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Smile),
                            contentDescription = "Emoji (tahan: stiker)",
                            tint = composerTint,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSend = { onSend() },
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { onFocusChange(it.isFocused) }
                            .padding(vertical = 11.dp),
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
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !sendingImage, onClick = onGif),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .border(1.dp, composerTint, RoundedCornerShape(5.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "GIF",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = composerTint,
                            )
                        }
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !sendingImage, onClick = onPickImage),
                    ) {
                        if (sendingImage) {
                            NusaProgress(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                painter = painterResource(id = NusaIcons.AddCircle),
                                contentDescription = "Kirim lampiran",
                                tint = composerTint,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            if (draft.isBlank()) {
                NusaIconButton(
                    onClick = {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.RECORD_AUDIO,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            if (recorder.start()) {
                                recording = true
                                seconds = 0
                            }
                        } else {
                            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !sendingImage,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Mic),
                        contentDescription = "Rekam pesan suara",
                        tint = composerTint,
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else {
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
}

@Composable
private fun PinnedListSheet(
    pins: List<PinnedMessage>,
    onJump: (Long) -> Unit,
    onUnpin: (Message) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            text = "Pesan disematkan (${pins.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            items(pins, key = { it.message.id }) { pin ->
                val msg = pin.message
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(msg.id) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Pin),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = msg.sender?.displayName ?: "Pesan",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = messagePreviewText(msg, 60),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    NusaIconButton(
                        onClick = { onUnpin(msg) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Close),
                            contentDescription = "Lepas sematan",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
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
                    text = messagePreviewText(quoted),
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
private fun E2eeBanner(onClick: () -> Unit, wallpaperDark: Boolean) {
    // Notifikasi enkripsi ala WhatsApp: bubble sistem center kuning,
    // teks center dengan gembok di depan, ketuk untuk info.
    // Varian gelap/terang mengikuti WALLPAPER, bukan tema sistem/aplikasi.
    val bg = if (wallpaperDark) Color(0xFF182229) else Color(0xFFFFF3C4)
    val fg = if (wallpaperDark) Color(0xFF8696A0) else Color(0xFF54656F)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "\uD83D\uDD12 Pesan terenkripsi secara end-to-end. " +
                "Tidak seorang pun di luar chat ini, bahkan NusaChat, " +
                "yang dapat membacanya. Ketuk untuk mempelajari info selengkapnya.",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
            ),
            color = fg,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * Aksi pesan tunggal di app bar: ikon langsung untuk yang muat
 * (Edit, Sematkan, Bintang, Salin), sisanya di menu vertikal ⋮.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.SingleMessageBarActions(
    message: Message,
    mine: Boolean,
    pinned: Boolean,
    onAction: (MsgAction) -> Unit,
) {
    val actions = remember(message, mine) { availableMsgActions(message, mine) }
    var showOverflow by remember { mutableStateOf(false) }
    if (MsgAction.EDIT in actions) {
        NusaIconButton(onClick = { onAction(MsgAction.EDIT) }) {
            Icon(painter = painterResource(id = NusaIcons.Edit), contentDescription = "Edit")
        }
    }
    if (MsgAction.PIN in actions) {
        NusaIconButton(onClick = { onAction(MsgAction.PIN) }) {
            Icon(
                painter = painterResource(id = NusaIcons.Pin),
                contentDescription = if (pinned) "Lepas sematan" else "Sematkan",
            )
        }
    }
    if (MsgAction.STAR in actions) {
        NusaIconButton(onClick = { onAction(MsgAction.STAR) }) {
            Icon(painter = painterResource(id = NusaIcons.Star), contentDescription = "Bintangi")
        }
    }
    if (MsgAction.COPY in actions) {
        NusaIconButton(onClick = { onAction(MsgAction.COPY) }) {
            Icon(painter = painterResource(id = NusaIcons.Copy), contentDescription = "Salin")
        }
    }
    val overflow = remember(actions) {
        listOf(
            MsgAction.SHARE, MsgAction.SAVE, MsgAction.INFO, MsgAction.HIDE,
            MsgAction.REPORT, MsgAction.REVOKE, MsgAction.RETRY,
        ).filter { it in actions }
    }
    if (overflow.isNotEmpty()) {
        Box {
            NusaIconButton(onClick = { showOverflow = true }) {
                Icon(painter = painterResource(id = NusaIcons.More), contentDescription = "Aksi lainnya")
            }
            NusaMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                overflow.forEach { action ->
                    val danger = action == MsgAction.REVOKE
                    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    DropdownMenuItem(
                        text = { Text(overflowLabel(action), color = tint) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = overflowIcon(action)),
                                contentDescription = null,
                                tint = tint,
                            )
                        },
                        onClick = {
                            showOverflow = false
                            onAction(action)
                        },
                    )
                }
            }
        }
    }
}

private fun overflowLabel(action: MsgAction): String = when (action) {
    MsgAction.SHARE -> "Bagikan"
    MsgAction.SAVE -> "Simpan"
    MsgAction.INFO -> "Info"
    MsgAction.HIDE -> "Hapus untuk saya"
    MsgAction.REPORT -> "Laporkan"
    MsgAction.REVOKE -> "Tarik pesan"
    MsgAction.RETRY -> "Kirim ulang"
    else -> action.name
}

private fun overflowIcon(action: MsgAction): Int = when (action) {
    MsgAction.SHARE -> NusaIcons.Share
    MsgAction.SAVE -> NusaIcons.Download
    MsgAction.INFO -> NusaIcons.Info
    MsgAction.HIDE -> NusaIcons.Close
    MsgAction.REPORT -> NusaIcons.Flag
    MsgAction.REVOKE -> NusaIcons.Delete
    MsgAction.RETRY -> NusaIcons.Refresh
    else -> NusaIcons.More
}

/**
 * Bar bawah saat satu pesan dipilih: strip reaksi cepat + tombol Balas dan
 * Teruskan. Menggantikan kolom ketik sampai seleksi dibatalkan.
 */
@Composable
private fun SingleContextBottomBar(
    message: Message,
    mine: Boolean,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
) {
    val actions = remember(message, mine) { availableMsgActions(message, mine) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (MsgAction.REACT in actions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QUICK_REACT_EMOJIS.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 24.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onReact(emoji) }
                                .padding(vertical = 2.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onReply)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Reply),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Balas",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (MsgAction.FORWARD in actions) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onForward)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Teruskan",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            painter = painterResource(id = NusaIcons.Forward),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}
