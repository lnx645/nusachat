package id.nusachat.app.ui
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
import id.nusachat.app.data.PushManager
import id.nusachat.app.data.User
import id.nusachat.app.ui.auth.AuthScreen
import id.nusachat.app.ui.call.IncomingCallScreen
import id.nusachat.app.ui.call.VoiceCallScreen
import id.nusachat.app.ui.chats.ChatScreen
import id.nusachat.app.ui.chats.ChatsScreen
import id.nusachat.app.ui.moments.MomentsScreen
import id.nusachat.app.ui.people.ContactsScreen
import id.nusachat.app.ui.people.FriendRequestsScreen
import id.nusachat.app.ui.people.NearbyScreen
import id.nusachat.app.ui.profile.EditProfileScreen
import id.nusachat.app.ui.profile.MeScreen
import id.nusachat.app.ui.theme.NusaChatTheme
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext

private data class TabItem(val label: String, val icon: Int)

private val tabs = listOf(
    TabItem("Obrolan", NusaIcons.Chat),
    TabItem("Kontak", NusaIcons.Contacts),
    TabItem("Jelajah", NusaIcons.Explore),
    TabItem("Saya", NusaIcons.Person),
)

private enum class Overlay { MOMENTS, NEARBY, FRIENDS, EDIT_PROFILE }

@Composable
fun NusaChatApp(vm: AppViewModel = viewModel()) {
    val session by vm.sessionState.collectAsStateWithLifecycle()
    NusaChatTheme {
        when {
            !session.checked -> SplashScreen()
            session.user == null -> AuthScreen(vm)
            else -> MainShell(vm, session.user!!)
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
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(0) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.registerPush()
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

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

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            call.status == "ringing" && !call.outgoing -> {
                BackHandler { vm.rejectVoiceCall() }
                IncomingCallScreen(
                    state = call,
                    onAnswer = {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        vm.answerVoiceCall()
                    },
                    onReject = vm::rejectVoiceCall,
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
            chatState.conversation != null -> {
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
            else -> {
                val unread = conversations.sumOf { it.unreadCount }
                val incoming = requests.incoming.size
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp,
                        ) {
                            tabs.forEachIndexed { index, item ->
                                val badgeCount = when (index) {
                                    0 -> unread
                                    1 -> incoming
                                    else -> 0
                                }
                                NavigationBarItem(
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
                                            Icon(painter = painterResource(id = item.icon), contentDescription = item.label)
                                        }
                                    },
                                    label = { Text(item.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
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
                            0 -> ChatsScreen(vm = vm, onOpenChat = vm::openChat)
                            1 -> ContactsScreen(
                                vm = vm,
                                onOpenFriends = { overlay = Overlay.FRIENDS },
                                onOpenChatWith = vm::openChatWith,
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
