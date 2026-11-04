package id.nusachat.app.ui
import id.nusachat.app.ui.components.NusaNavItem
import id.nusachat.app.ui.components.NusaNavigationBar
import id.nusachat.app.ui.components.NusaProgress

import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.painterResource
import id.nusachat.app.ui.theme.NusaIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.nusachat.app.data.PushDeepLink
import id.nusachat.app.data.PushManager
import id.nusachat.app.data.User
import id.nusachat.app.data.SettingsStore
import id.nusachat.app.ui.auth.AuthScreen
import id.nusachat.app.ui.call.CallHistoryScreen
import id.nusachat.app.ui.call.GroupCallScreen
import id.nusachat.app.ui.call.IncomingCallScreen
import id.nusachat.app.ui.call.VideoCallScreen
import id.nusachat.app.ui.call.VoiceCallScreen
import id.nusachat.app.ui.chats.ChatScreen
import id.nusachat.app.ui.chats.ChatsScreen
import id.nusachat.app.ui.chats.StarredScreen
import id.nusachat.app.ui.moments.MomentsScreen
import id.nusachat.app.ui.people.ContactsScreen
import id.nusachat.app.ui.people.FriendRequestsScreen
import id.nusachat.app.ui.people.QrScanScreen
import id.nusachat.app.ui.people.NearbyScreen
import id.nusachat.app.ui.profile.EditProfileScreen
import id.nusachat.app.ui.profile.MeScreen
import id.nusachat.app.ui.profile.SettingsScreen
import id.nusachat.app.ui.theme.NusaChatTheme
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext

private data class TabItem(val label: String, val icon: Int)

@Composable
private fun rememberTabs(): List<TabItem> {
    val s = id.nusachat.app.ui.i18n.t()
    return remember(s) {
        listOf(
            TabItem(s.tabChats, NusaIcons.Chat),
            TabItem(s.tabContacts, NusaIcons.Contacts),
            TabItem(s.tabDiscover, NusaIcons.Explore),
            TabItem(s.tabMe, NusaIcons.Person),
        )
    }
}

private enum class Overlay { MOMENTS, NEARBY, FRIENDS, EDIT_PROFILE, HISTORY, STARRED, SETTINGS, SCAN }

@Composable
fun NusaChatApp(vm: AppViewModel = viewModel()) {
    val session by vm.sessionState.collectAsStateWithLifecycle()
    val restore by vm.restore.collectAsStateWithLifecycle()
    val themeMode by vm.settings.themeMode.collectAsStateWithLifecycle()
    val fontScale by vm.settings.fontScale.collectAsStateWithLifecycle()
    val language by vm.settings.language.collectAsStateWithLifecycle()
    val dark = when (themeMode) {
        SettingsStore.THEME_DARK -> true
        SettingsStore.THEME_LIGHT -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    NusaChatTheme(darkTheme = dark) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                density = density.density,
                fontScale = density.fontScale * fontScale,
            ),
            id.nusachat.app.ui.i18n.LocalStrings provides id.nusachat.app.ui.i18n.stringsFor(language),
        ) {
            when {
                !session.checked -> SplashScreen()
                session.user == null -> AuthScreen(vm)
                restore != null -> RestoreScreen(state = restore!!, onSkip = vm::skipRestore)
                else -> MainShell(vm, session.user!!)
            }
        }
    }
}

/** Panel kanan kosong di mode dua-panel (tablet/landscape). */
@Composable
private fun ChatSplitPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(id = NusaIcons.Chat),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Pilih obrolan",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Percakapan tampil di sini",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.Chat),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "NusaChat",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(18.dp))
            NusaProgress(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun MainShell(vm: AppViewModel, user: User) {
    val chatState by vm.chat.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val requests by vm.requests.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val call by vm.callState.collectAsStateWithLifecycle()
    val groupCall by vm.groupCall.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(0) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    var scanSeed by remember { mutableStateOf<String?>(null) }
    // Tablet & landscape lebar: daftar + isi chat tampil berdampingan.
    val wideTwoPane = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
    val showChatSplit = wideTwoPane && tab == 0 && overlay == null
    val context = LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) overlay = Overlay.SCAN
    }
    fun openScan() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) overlay = Overlay.SCAN
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.registerPush()
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val answerPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.answerVoiceCall()
    }
    val answerVideoPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) vm.answerVoiceCall()
    }
    fun hasCallPermissions(video: Boolean = false): Boolean {
        val audio = androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!video) return audio
        val camera = androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return audio && camera
    }

    LaunchedEffect(Unit) {
        PushManager.ensureFirebase(context)
        PushManager.createChannels(context)
        if (Build.VERSION.SDK_INT >= 33 && !PushManager.hasNotificationPermission(context)) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.registerPush()
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            vm.periodicSync()
        }
    }
    LaunchedEffect(Unit) {
        // Deep-link sekali pakai dari ketukan notifikasi: buka chat tujuan.
        delay(1_500)
        PushDeepLink.consume()?.let { id ->
            if (id > 0) vm.openChatById(id)
        }
        PushDeepLink.consumeCallAction()?.let { (action, callId) ->
            vm.onNotificationCallAction(action, callId)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            call.status == "ringing" && !call.outgoing -> {
                BackHandler { vm.rejectVoiceCall() }
                IncomingCallScreen(
                    state = call,
                    onAnswer = {
                        if (hasCallPermissions(call.video)) {
                            vm.answerVoiceCall()
                        } else if (call.video) {
                            answerVideoPermission.launch(
                                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                            )
                        } else {
                            answerPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onReject = vm::rejectVoiceCall,
                )
            }
            call.status in listOf("dialing", "active") && call.video -> {
                BackHandler { /* block back during call, use end button */ }
                VideoCallScreen(
                    state = call,
                    rtc = vm.rtcManager(),
                    onToggleMute = vm::toggleCallMute,
                    onToggleVideo = vm::toggleVideoMute,
                    onSwitchCamera = vm::switchCallCamera,
                    onEnd = vm::endVoiceCall,
                )
            }
            call.status in listOf("dialing", "active") -> {
                BackHandler { /* block back during call, use end button */ }
                VoiceCallScreen(
                    state = call,
                    onAnswer = vm::answerVoiceCall,
                    onReject = vm::rejectVoiceCall,
                    onEnd = vm::endVoiceCall,
                    onToggleMute = vm::toggleCallMute,
                    onToggleSpeaker = vm::toggleCallSpeaker,
                )
            }
            groupCall.status == "ringing" -> {
                BackHandler { vm.declineGroupCall() }
                GroupCallScreen(
                    state = groupCall,
                    onAccept = {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        vm.acceptGroupCall()
                    },
                    onReject = vm::declineGroupCall,
                    onLeave = vm::leaveGroupCall,
                    onToggleMute = vm::toggleGroupMute,
                    onToggleSpeaker = vm::toggleGroupSpeaker,
                )
            }
            groupCall.status == "active" -> {
                BackHandler { /* block back during call, use end button */ }
                GroupCallScreen(
                    state = groupCall,
                    onAccept = {},
                    onReject = vm::declineGroupCall,
                    onLeave = vm::leaveGroupCall,
                    onToggleMute = vm::toggleGroupMute,
                    onToggleSpeaker = vm::toggleGroupSpeaker,
                )
            }
            chatState.conversation != null && !showChatSplit -> {
                BackHandler { vm.closeChat() }
                ChatScreen(vm = vm, state = chatState, onBack = vm::closeChat)
            }
            overlay == Overlay.MOMENTS -> {
                BackHandler { overlay = null }
                MomentsScreen(vm = vm, onBack = { overlay = null })
            }
            overlay == Overlay.NEARBY -> {
                BackHandler { overlay = null }
                NearbyScreen(vm = vm, onBack = { overlay = null })
            }
            overlay == Overlay.FRIENDS -> {
                BackHandler { overlay = null }
                FriendRequestsScreen(vm = vm, onBack = { overlay = null })
            }
            overlay == Overlay.EDIT_PROFILE -> {
                BackHandler { overlay = null }
                EditProfileScreen(vm = vm, user = user, onBack = { overlay = null })
            }
            overlay == Overlay.HISTORY -> {
                BackHandler { overlay = null }
                CallHistoryScreen(
                    vm = vm,
                    onBack = { overlay = null },
                    onCallPeer = { peer ->
                        overlay = null
                        vm.startVoiceCall(peer)
                    },
                    onVideoPeer = { peer ->
                        overlay = null
                        vm.startVideoCall(peer)
                    },
                )
            }
            overlay == Overlay.STARRED -> {
                BackHandler { overlay = null }
                StarredScreen(
                    vm = vm,
                    onBack = { overlay = null },
                    onOpenChat = { id ->
                        overlay = null
                        vm.openChatById(id)
                    },
                )
            }
            overlay == Overlay.SETTINGS -> {
                BackHandler { overlay = null }
                SettingsScreen(vm = vm, onBack = { overlay = null })
            }
            overlay == Overlay.SCAN -> {
                QrScanScreen(
                    onScanned = { raw ->
                        overlay = null
                        tab = 1
                        scanSeed = raw
                    },
                    onBack = { overlay = null },
                )
            }
            else -> {
                val unread = conversations.sumOf { it.unreadCount }
                val incoming = requests.incoming.size
                val tabs = rememberTabs()
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NusaNavigationBar {
                            tabs.forEachIndexed { index, item ->
                                val badgeCount = when (index) {
                                    0 -> unread
                                    1 -> incoming
                                    else -> 0
                                }
                                NusaNavItem(
                                    selected = tab == index,
                                    onClick = { tab = index },
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (badgeCount > 0) {
                                                    Badge {
                                                        Text(if (badgeCount > 99) "99+" else badgeCount.toString())
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                painter = painterResource(id = item.icon),
                                                contentDescription = item.label,
                                                modifier = Modifier.size(if (tab == index) 22.dp else 20.dp),
                                            )
                                        }
                                    },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        when (tab) {
                            0 -> if (showChatSplit) {
                                if (chatState.conversation != null) {
                                    BackHandler { vm.closeChat() }
                                }
                                androidx.compose.foundation.layout.Row(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(360.dp)
                                            .fillMaxSize()
                                    ) {
                                        ChatsScreen(
                                            vm = vm,
                                            onOpenChat = vm::openChat,
                                            onShowHistory = { overlay = Overlay.HISTORY },
                                            onShowStarred = { overlay = Overlay.STARRED },
                                        )
                                    }
                                    androidx.compose.material3.VerticalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                        if (chatState.conversation != null) {
                                            ChatScreen(vm = vm, state = chatState, onBack = vm::closeChat)
                                        } else {
                                            ChatSplitPlaceholder()
                                        }
                                    }
                                }
                            } else {
                                ChatsScreen(
                                    vm = vm,
                                    onOpenChat = vm::openChat,
                                    onShowHistory = { overlay = Overlay.HISTORY },
                                    onShowStarred = { overlay = Overlay.STARRED },
                                )
                            }
                            1 -> ContactsScreen(
                                vm = vm,
                                onOpenFriends = { overlay = Overlay.FRIENDS },
                                onOpenChatWith = vm::openChatWith,
                                onOpenProfile = { tab = 3 },
                                onScanQr = { openScan() },
                                scanSeed = scanSeed,
                                onScanConsumed = { scanSeed = null },
                            )
                            2 -> DiscoverScreen(
                                vm = vm,
                                onOpenMoments = { overlay = Overlay.MOMENTS },
                                onOpenNearby = { overlay = Overlay.NEARBY },
                            )
                            else -> MeScreen(
                                vm = vm,
                                user = user,
                                onEditProfile = { overlay = Overlay.EDIT_PROFILE },
                                onOpenSettings = { overlay = Overlay.SETTINGS },
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
        )
    }
}
