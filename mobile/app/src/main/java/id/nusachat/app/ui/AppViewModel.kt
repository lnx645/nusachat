package id.nusachat.app.ui

import android.app.Application
import android.media.AudioManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.nusachat.app.BuildConfig
import id.nusachat.app.data.ApiClient
import id.nusachat.app.data.ApiException
import id.nusachat.app.data.CallRepository
import id.nusachat.app.data.ChatRepository
import id.nusachat.app.data.ChatSocket
import id.nusachat.app.data.ChatThemeStore
import id.nusachat.app.data.GoogleAuthHelper
import id.nusachat.app.data.GoogleCancelled
import id.nusachat.app.data.GoogleProfile
import id.nusachat.app.data.toUserList
import id.nusachat.app.data.Conversation
import id.nusachat.app.data.E2eeManager
import id.nusachat.app.data.FriendRequest
import id.nusachat.app.data.LocationHelper
import id.nusachat.app.data.Message
import id.nusachat.app.data.GroupJoinRequest
import id.nusachat.app.data.Moment
import id.nusachat.app.data.MomentComment
import id.nusachat.app.data.PinnedMessage
import id.nusachat.app.data.Reaction
import id.nusachat.app.data.CallForegroundService
import id.nusachat.app.data.PushManager
import id.nusachat.app.data.SessionStore
import id.nusachat.app.data.User
import id.nusachat.app.data.VoiceCall
import id.nusachat.app.data.WebRtcCallManager
import id.nusachat.app.data.toMessage
import id.nusachat.app.data.toUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class SessionState(
    val user: User? = null,
    val checked: Boolean = false,
)

data class RequestsState(
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
)

data class ChatUiState(
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val loading: Boolean = false,
    val keySyncing: Boolean = false,
    val loadingOlder: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val typingName: String? = null,
    val sendingImage: Boolean = false,
    val sendingFile: Boolean = false,
    val replyTo: Message? = null,
    val editing: Message? = null,
    val pins: List<PinnedMessage> = emptyList(),
    val searching: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Message> = emptyList(),
    val detail: Conversation? = null,
    val muting: Boolean = false,
)

data class NearbyState(
    val users: List<User> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    /** true bila daftar datang dari push Erlang (realtime), bukan snapshot REST. */
    val live: Boolean = false,
)

/** Pilihan latar + bubble ruang chat (disimpan di ChatThemeStore). */
data class ChatThemeChoice(
    val wallpaperId: String = id.nusachat.app.data.ChatWallpapers.DEFAULT,
    val customUri: String = "",
    val bubbleId: String = id.nusachat.app.data.ChatBubbles.AUTO,
)

data class CallUiState(
    val call: VoiceCall? = null,
    val peer: User? = null,
    val outgoing: Boolean = false,
    val status: String = "idle", // idle | dialing | ringing | active | ended
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val remoteSdp: String? = null,
)

data class GroupCallUiState(
    val room: id.nusachat.app.data.GroupCallRoom? = null,
    val conversationId: Long? = null,
    val title: String = "Panggilan grup",
    val status: String = "idle", // idle | ringing | active | ended
    val muted: Boolean = false,
    val speaker: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val session = SessionStore(application)
    private val api = ApiClient(BuildConfig.API_BASE_URL, session)
    private val repository = ChatRepository(application, api, session)
    private val calls = CallRepository(api)
    private val locationHelper = LocationHelper(application)
    private val chatThemeStore = ChatThemeStore(application)
    private val rtc = WebRtcCallManager(application.applicationContext)
    val e2ee = E2eeManager(application.applicationContext)

    val sessionState = MutableStateFlow(SessionState())
    val authLoading = MutableStateFlow(false)
    val authError = MutableStateFlow<String?>(null)

    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversationsLoading = MutableStateFlow(false)
    val friends = MutableStateFlow<List<User>>(emptyList())
    val blocked = MutableStateFlow<List<User>>(emptyList())
    val stories = MutableStateFlow<List<id.nusachat.app.data.Story>>(emptyList())
    val walletBalance = MutableStateFlow(0L)
    val walletTxns = MutableStateFlow<List<id.nusachat.app.data.WalletTxn>>(emptyList())
    val requests = MutableStateFlow(RequestsState())
    val moments = MutableStateFlow<List<Moment>>(emptyList())
    val momentsLoading = MutableStateFlow(false)
    val searchResults = MutableStateFlow<List<User>>(emptyList())
    val searchLoading = MutableStateFlow(false)
    val nearby = MutableStateFlow(NearbyState())
    val chat = MutableStateFlow(ChatUiState())
    val comments = MutableStateFlow<List<MomentComment>>(emptyList())
    val commentsLoading = MutableStateFlow(false)
    val commentsMomentId = MutableStateFlow<Long?>(null)
    val message = MutableStateFlow<String?>(null)
    val realtimeConnected = MutableStateFlow(false)
    val uploadingAvatar = MutableStateFlow(false)
    val creatingMoment = MutableStateFlow(false)
    val momentsHasMore = MutableStateFlow(true)
    val momentsLoadingMore = MutableStateFlow(false)
    val contactsSyncing = MutableStateFlow(false)
    val contactsMatched = MutableStateFlow<List<User>>(emptyList())
    val nearbyRadius = MutableStateFlow(loadSavedRadius())
    val chatTheme = MutableStateFlow(loadSavedChatTheme())
    private var nearbyLiveActive = false
    val sharingLocation = MutableStateFlow(false)
    val callState = MutableStateFlow(CallUiState())
    val groupCall = MutableStateFlow(GroupCallUiState())
    val pushReady = MutableStateFlow(false)
    val inviteLink = MutableStateFlow<String?>(null)
    val joinRequests = MutableStateFlow<List<GroupJoinRequest>>(emptyList())

    private var socket: ChatSocket? = null
    private var activeChatId: Long? = null
    private var typingResetJob: Job? = null
    private var lastTypingSent = 0L
    private val readUpTo = mutableMapOf<Long, Long>()

    init {
        val saved = session.user
        if (session.isLoggedIn) {
            sessionState.value = SessionState(saved, false)
            viewModelScope.launch {
                try {
                    val user = repository.me()
                    sessionState.value = SessionState(user, true)
                    startRealtime()
                    loadInitial()
                } catch (e: Exception) {
                    if (e is ApiException && e.statusCode == 401) {
                        session.clear()
                        sessionState.value = SessionState(null, true)
                    } else {
                        sessionState.value = SessionState(saved, true)
                        startRealtime()
                        loadInitial()
                    }
                }
            }
        } else {
            sessionState.value = SessionState(null, true)
        }
    }

    fun login(identifier: String, password: String) {
        if (identifier.isBlank() || password.isBlank()) {
            authError.value = "Isi nama pengguna dan kata sandi."
            return
        }
        authLoading.value = true
        authError.value = null
        viewModelScope.launch {
            try {
                val user = repository.login(identifier.trim(), password)
                sessionState.value = SessionState(user, true)
                startRealtime()
                loadInitial()
            } catch (e: Exception) {
                authError.value = e.message ?: "Gagal masuk."
            } finally {
                authLoading.value = false
            }
        }
    }

    fun register(username: String, password: String, displayName: String, phone: String) {
        if (username.isBlank() || password.isBlank() || displayName.isBlank()) {
            authError.value = "Nama tampilan, username, dan kata sandi wajib diisi."
            return
        }
        authLoading.value = true
        authError.value = null
        viewModelScope.launch {
            try {
                val user = repository.register(
                    username = username.trim().lowercase(Locale.US),
                    password = password,
                    displayName = displayName.trim(),
                    phone = phone.trim(),
                )
                sessionState.value = SessionState(user, true)
                startRealtime()
                loadInitial()
            } catch (e: Exception) {
                authError.value = e.message ?: "Gagal mendaftar."
            } finally {
                authLoading.value = false
            }
        }
    }

    fun clearAuthError() {
        authError.value = null
    }

    // ------------------------------------------------- login Google (OAuth)

    /** Profil Google yang butuh dibuatkan akun NusaChat (register bertahap). */
    val googlePending = MutableStateFlow<GoogleProfile?>(null)
    private var googleIdToken: String? = null
    /** Foto galeri yang diunggah tepat setelah akun Google dibuat. */
    var pendingAvatarUri: Uri? = null

    fun loginWithGoogle(idToken: String) {
        authLoading.value = true
        authError.value = null
        viewModelScope.launch {
            try {
                when (val result = repository.authGoogle(idToken)) {
                    is ChatRepository.GoogleAuthResult.LoggedIn -> {
                        googleIdToken = null
                        googlePending.value = null
                        sessionState.value = SessionState(result.user, true)
                        startRealtime()
                        loadInitial()
                    }
                    ChatRepository.GoogleAuthResult.NeedRegister -> {
                        googleIdToken = idToken
                        googlePending.value = GoogleAuthHelper.parsePublic(idToken)
                    }
                }
            } catch (e: GoogleCancelled) {
                // User membatalkan: diam saja.
            } catch (e: Exception) {
                authError.value = e.message ?: "Gagal masuk dengan Google."
            } finally {
                authLoading.value = false
            }
        }
    }

    fun registerWithGoogle(username: String, displayName: String, password: String, avatarUrl: String) {
        val token = googleIdToken
        if (token.isNullOrBlank()) {
            authError.value = "Sesi Google kedaluwarsa. Pilih akun Google lagi."
            return
        }
        if (username.isBlank() || password.isBlank() || displayName.isBlank()) {
            authError.value = "Nama, username, dan kata sandi wajib diisi."
            return
        }
        authLoading.value = true
        authError.value = null
        viewModelScope.launch {
            try {
                when (val result = repository.authGoogle(
                    idToken = token,
                    username = username.trim().lowercase(Locale.US),
                    displayName = displayName.trim(),
                    password = password,
                    avatarUrl = avatarUrl,
                )) {
                    is ChatRepository.GoogleAuthResult.LoggedIn -> {
                        googleIdToken = null
                        googlePending.value = null
                        sessionState.value = SessionState(result.user, true)
                        pendingAvatarUri?.let { uri ->
                            pendingAvatarUri = null
                            runCatching {
                                val url = repository.upload(uri)
                                val updated = repository.updateProfile(JSONObject().put("avatar_url", url))
                                sessionState.value = SessionState(updated, true)
                            }
                        }
                        startRealtime()
                        loadInitial()
                    }
                    ChatRepository.GoogleAuthResult.NeedRegister -> {
                        authError.value = "Profil belum lengkap."
                    }
                }
            } catch (e: GoogleCancelled) {
            } catch (e: Exception) {
                authError.value = e.message ?: "Gagal mendaftar."
            } finally {
                authLoading.value = false
            }
        }
    }

    fun authGoogleError(msg: String) {
        authError.value = msg
    }

    fun cancelGoogle() {
        googleIdToken = null
        googlePending.value = null
        pendingAvatarUri = null
        authError.value = null
    }

    fun logout() {
        viewModelScope.launch {
            runCatching {
                PushManager.unregister(api, getApplication())
            }
            runCatching { repository.logout() }
            stopRealtime()
            endCallLocal()
            resetLocalState()
            sessionState.value = SessionState(null, true)
        }
    }

    fun registerPush() {
        if (sessionState.value.user == null) return
        viewModelScope.launch {
            runCatching {
                PushManager.ensureFirebase(getApplication())
                PushManager.createChannels(getApplication())
                PushManager.fetchAndRegister(api, getApplication())
                pushReady.value = true
            }
        }
    }

    private fun resetLocalState() {
        activeChatId = null
        chat.value = ChatUiState()
        conversations.value = emptyList()
        friends.value = emptyList()
        requests.value = RequestsState()
        moments.value = emptyList()
        nearby.value = NearbyState()
        searchResults.value = emptyList()
        comments.value = emptyList()
        commentsMomentId.value = null
        callState.value = CallUiState()
        readUpTo.clear()
    }

    private fun loadInitial() {
        refreshConversations()
        loadFriends()
        loadRequests()
        refreshMoments()
        registerPush()
        publishDeviceKey()
    }

    private fun publishDeviceKey() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.publishDeviceKey(e2ee.deviceId(), e2ee.publicKeyB64())
            }
        }
    }

    /**
     * Sinkronisasi kunci sesi E2EE antar perangkat/anggota:
     * buka kunci milikku bila ada, tetapkan milikku untuk semua bila belum ada,
     * bungkus untuk anggota yang belum punya bila aku memegang kunci kanonis.
     */
    fun syncConversationKeys(conversationId: Long) {
        if (sessionState.value.user == null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { syncKeysNow(conversationId) }
        }
    }

    private suspend fun syncKeysNow(conversationId: Long) {
        val me = sessionState.value.user?.id ?: session.user?.id
        if (me == null) return
        val deviceId = e2ee.deviceId()
        val keys = runCatching { repository.convKeys(conversationId, deviceId) }.getOrNull()
        keys ?: return
        val haveKey = keys.myKey != null && e2ee.unwrapSessionKey(conversationId, keys.myKey)
        if (!haveKey && keys.devices.isEmpty()) {
            // Belum ada kunci kanonis sama sekali: tetapkan kunci lokal untuk semua.
            e2ee.ensureKey(conversationId)
        }
        // Selalu heal: menutup perangkat/anggota yang belum punya blob
        // (termasuk diri sendiri di device baru = self-heal; idempoten bila lengkap).
        // Bila aku belum punya kunci dan tak bisa membungkus: tunggu keys_updated.
        healMissingKeys(conversationId, keys.devices, includeSelf = true)
    }

    private suspend fun healMissingKeys(
        conversationId: Long,
        holders: List<id.nusachat.app.data.KeyHolder>,
        includeSelf: Boolean = false,
    ) {
        val me = session.user?.id ?: return
        val detail = runCatching { repository.conversationDetail(conversationId) }.getOrNull() ?: return
        var memberIds = detail.members.map { it.id }
        if (memberIds.isEmpty()) {
            // Chat 1-1: turunkan anggota dari peer + diri sendiri.
            val peerId = detail.peer?.id ?: 0L
            memberIds = listOfNotNull(me, peerId.takeIf { it > 0 })
        }
        val have = holders.associate { (it.userId to it.deviceId) to it.keyFp }
        val wrapped = mutableListOf<Triple<Long, String, String>>()
        for (userId in memberIds.distinct()) {
            if (userId == me && !includeSelf) continue
            val devices = runCatching { repository.deviceKeys(userId) }.getOrNull() ?: continue
            // Tanpa baris device_keys (client lama): fallback ke blob per-user.
            val targets = devices.ifEmpty { listOf(id.nusachat.app.data.DeviceKey("", "")) }
            for (dev in targets) {
                if (dev.deviceId.isBlank()) {
                    if (have.keys.any { it.first == userId }) continue
                } else {
                    // Segar hanya bila fingerprint cocok: reinstall = RSA baru
                    // dengan device_id sama harus dibungkus ulang.
                    val fpNow = e2ee.fingerprint(dev.publicKey)
                    val stored = have[userId to dev.deviceId]
                    if (stored != null && stored.isNotBlank() && stored == fpNow) continue
                }
                val blob = if (dev.publicKey.isBlank()) {
                    null
                } else {
                    e2ee.wrapSessionKey(conversationId, dev.publicKey)
                } ?: continue
                wrapped.add(Triple(userId, dev.deviceId, blob))
            }
        }
        if (wrapped.isNotEmpty()) {
            runCatching { repository.setConvKeys(conversationId, wrapped) }
        }
    }

    /**
     * Perangkat baru milik lawan bicara (event device_added via Erlang):
     * bungkus ulang kunci sesi untuknya bila chat-nya sedang dibuka.
     */
    private fun onDeviceAdded(userId: Long) {
        val conv = chat.value.detail ?: chat.value.conversation ?: return
        if (conv.id != activeChatId) return
        val involves = conv.peer?.id == userId || conv.members.any { it.id == userId }
        if (!involves) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { syncKeysNow(conv.id) }
        }
    }

    fun periodicSync() {
        if (sessionState.value.user == null) return
        refreshConversations()
        loadFriends()
        loadRequests()
    }

    // ---------------------------------------------------------------- realtime

    private fun startRealtime() {
        socket?.disconnect()
        socket = ChatSocket(
            wsUrl = BuildConfig.WS_URL,
            tokenProvider = { session.accessToken },
            onEvent = ::handleRealtimeEvent,
            onStateChange = { connected -> realtimeConnected.value = connected },
        ).also { it.connect() }
    }

    private fun stopRealtime() {
        socket?.disconnect()
        socket = null
        realtimeConnected.value = false
    }

    private fun handleRealtimeEvent(type: String, data: JSONObject) {
        when (type) {
            "message" -> {
                val conversationId = data.optLong("conversation_id")
                val raw = data.optJSONObject("message")?.toMessage() ?: return
                val message = e2ee.decryptMessage(raw)
                incomingMessage(conversationId, message)
            }
            "read" -> {
                val conversationId = data.optLong("conversation_id")
                val readerId = data.optLong("user_id")
                val messageId = data.optLong("message_id")
                if (conversationId == activeChatId && readerId != session.user?.id) {
                    val state = chat.value
                    chat.value = state.copy(
                        messages = state.messages.map {
                            if (it.sender?.id == session.user?.id && it.id <= messageId) {
                                it.copy(status = "read")
                            } else it
                        }
                    )
                }
            }
            "typing" -> {
                val conversationId = data.optLong("conversation_id")
                if (conversationId == activeChatId) {
                    val name = data.optString("display_name")
                    chat.value = chat.value.copy(typingName = name)
                    typingResetJob?.cancel()
                    typingResetJob = viewModelScope.launch {
                        delay(3_000)
                        chat.value = chat.value.copy(typingName = null)
                    }
                }
            }
            "message_revoked" -> {
                val conversationId = data.optLong("conversation_id")
                val messageId = data.optLong("message_id")
                if (conversationId == activeChatId) {
                    markRevokedLocal(messageId)
                }
            }
            "message_edited" -> {
                val conversationId = data.optLong("conversation_id")
                val raw = data.optJSONObject("message")?.toMessage() ?: return
                val message = e2ee.decryptMessage(raw)
                if (conversationId == activeChatId) {
                    val state = chat.value
                    chat.value = state.copy(
                        messages = state.messages.map { if (it.id == message.id) message else it }
                    )
                }
                refreshConversations()
            }
            "message_reaction" -> {
                val conversationId = data.optLong("conversation_id")
                val messageId = data.optLong("message_id")
                if (conversationId == activeChatId) {
                    val reactions = mutableListOf<Reaction>()
                    data.optJSONArray("reactions")?.let { array ->
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
                    val state = chat.value
                    chat.value = state.copy(
                        messages = state.messages.map {
                            if (it.id == messageId) it.copy(reactions = reactions) else it
                        }
                    )
                }
            }
            "conversation_pinned", "conversation_unpinned" -> {
                val conversationId = data.optLong("conversation_id")
                if (conversationId == activeChatId) loadPins()
            }
            "friend_request", "friend_accepted" -> {
                loadRequests()
                loadFriends()
                refreshConversations()
            }
            "conversation_updated" -> refreshConversations()
            "join_requested" -> {
                val conversationId = data.optLong("conversation_id")
                if (conversationId == activeChatId) loadJoinRequests(conversationId)
            }
            "join_reviewed" -> refreshConversations()
            "moment_new", "moment_liked", "moment_comment" -> refreshMoments()
            "story_new" -> loadStories()
            "wallet_received" -> {
                loadWallet()
                message.value = "Koin ${data.optLong("amount")} diterima."
            }
            "keys_updated" -> onKeysUpdated(data)
            "presence" -> {
                val userId = data.optLong("user_id")
                if (userId > 0) onPresenceChanged(userId)
            }
            "device_added" -> {
                val userId = data.optLong("user_id")
                if (userId > 0) onDeviceAdded(userId)
            }
            "nearby.result" -> {
                val users = data.optJSONArray("users")?.toUserList().orEmpty()
                nearby.value = NearbyState(users = users, loading = false, loaded = true, live = true)
            }
            "nearby.changed" -> {
                val user = data.optJSONObject("user")?.toUser() ?: return
                val merged = (nearby.value.users.filterNot { it.id == user.id } + user)
                    .sortedWith(compareBy(nullsLast<Double>()) { it.distanceKm })
                nearby.value = nearby.value.copy(users = merged, loading = false, loaded = true, live = true)
            }
            "nearby.error" -> {
                nearby.value = nearby.value.copy(
                    loading = false,
                    loaded = true,
                    live = false,
                    error = data.optString("message", "Lokasi tidak valid."),
                )
            }
            "call_offer" -> onIncomingOffer(data)
            "call_answer" -> onCallAnswer(data)
            "call_ice" -> onCallIce(data)
            "call_end" -> onCallEnded(data)
            "call_room_offer" -> onRoomOffer(data)
            "call_room_signal" -> onRoomSignal(data)
            "call_room_joined", "call_room_left" -> {
                val callId = data.optJSONObject("call")?.optLong("id") ?: data.optLong("call_id")
                if (callId > 0) refreshGroupRoom(callId)
            }
            "call_room_ended" -> onRoomEnded(data)
        }
    }

    // ------------------------------------------------------------ voice call

    private val pendingIce = mutableListOf<JSONObject>()
    private var callIceJob: Job? = null

    fun startVoiceCall(peer: User) {
        if (callState.value.status in listOf("dialing", "active", "ringing")) {
            message.value = "Sudah ada panggilan aktif."
            return
        }
        if (groupCall.value.status == "active") {
            message.value = "Sudah ada panggilan grup aktif."
            return
        }
        if (!rtc.isSupported()) {
            message.value = "Panggilan suara tidak didukung di perangkat ini."
            return
        }
        callState.value = CallUiState(call = null, peer = peer, outgoing = true, status = "dialing")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rtc.ensureFactory()
                runCatching { rtc.applyIceServersJson(calls.iceServers()) }
                // Pasang handler ICE SEBELUM membuat offer: kandidat host
                // sering muncul segera setelah setLocalDescription. Kandidat
                // yang muncul sebelum call id ada ditampung lalu dikirim
                // setelah offer terkirim.
                val earlyIce = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
                fun sendIce(callId: Long, json: JSONObject) {
                    viewModelScope.launch {
                        runCatching { calls.ice(callId, json) }
                    }
                }
                rtc.onIceCandidate = { candidate ->
                    val json = JSONObject()
                        .put("candidate", candidate.sdp)
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    val active = callState.value.call
                    if (active == null) earlyIce.add(json) else sendIce(active.id, json)
                }
                val offer = withContext(Dispatchers.IO) { rtc.startAsCaller() }
                    ?: throw IllegalStateException("Gagal membuat offer WebRTC.")
                rtc.onConnected = { markCallActive() }
                rtc.onDisconnected = {
                    if (callState.value.status == "active") endCallLocal()
                }
                val created = calls.offer(peer.id, offer.description, "audio")
                callState.value = callState.value.copy(
                    call = created,
                    status = "dialing",
                )
                withContext(Dispatchers.Main) { startCallService() }
                pendingIce.forEach { c ->
                    runCatching { calls.ice(created.id, c) }
                }
                pendingIce.clear()
                while (true) {
                    val queued = earlyIce.poll() ?: break
                    sendIce(created.id, queued)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callState.value = CallUiState()
                    handleError(e)
                }
            }
        }
    }

    fun answerVoiceCall() {
        val current = callState.value
        val call = current.call ?: return
        val sdp = current.remoteSdp ?: return
        if (!rtc.isSupported()) {
            message.value = "Panggilan suara tidak didukung di perangkat ini."
            endCallLocal()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rtc.ensureFactory()
                runCatching { rtc.applyIceServersJson(calls.iceServers()) }
                rtc.onIceCandidate = { candidate ->
                    viewModelScope.launch {
                        runCatching {
                            calls.ice(
                                call.id,
                                JSONObject()
                                    .put("candidate", candidate.sdp)
                                    .put("sdpMid", candidate.sdpMid)
                                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                            )
                        }
                    }
                }
                rtc.onConnected = { markCallActive() }
                withContext(Dispatchers.IO) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var answer: org.webrtc.SessionDescription? = null
                    rtc.handleOffer(sdp) { desc ->
                        answer = desc
                        latch.countDown()
                    }
                    latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                    val desc = answer ?: throw IllegalStateException("Gagal membuat answer.")
                    calls.answer(call.id, desc.description)
                    withContext(Dispatchers.Main) {
                        markCallActive()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { handleError(e) }
            }
        }
    }

    fun rejectVoiceCall() {
        val call = callState.value.call ?: run { endCallLocal(); return }
        viewModelScope.launch {
            runCatching { calls.reject(call.id) }
            endCallLocal()
        }
    }

    fun endVoiceCall() {
        val call = callState.value.call
        viewModelScope.launch {
            if (call != null) runCatching { calls.end(call.id) }
            withContext(Dispatchers.Main) { endCallLocal() }
        }
    }

    fun toggleCallMute() {
        val muted = !callState.value.muted
        rtc.setMuted(muted)
        callState.value = callState.value.copy(muted = muted)
    }

    fun toggleCallSpeaker() {
        val app = getApplication<Application>()
        val audio = app.getSystemService(Application.AUDIO_SERVICE) as? AudioManager ?: return
        val speaker = !callState.value.speaker
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = speaker
        callState.value = callState.value.copy(speaker = speaker)
    }

    // ------------------------------------------------------------ group call

    private val mesh by lazy {
        id.nusachat.app.data.GroupCallMesh(getApplication(), calls, viewModelScope)
    }

    fun startGroupCall(conversationId: Long) {
        if (callState.value.status in listOf("dialing", "active", "ringing")) {
            message.value = "Sudah ada panggilan aktif."
            return
        }
        if (groupCall.value.status == "active") return
        if (!mesh.isSupported()) {
            message.value = "Panggilan grup tidak didukung di perangkat ini."
            return
        }
        val title = conversations.value.firstOrNull { it.id == conversationId }?.displayTitle
            ?: "Panggilan grup"
        viewModelScope.launch {
            try {
                val room = calls.roomCreate(conversationId)
                mesh.setRoom(room.call.id)
                groupCall.value = GroupCallUiState(
                    room = room, conversationId = conversationId,
                    title = title, status = "active",
                )
                room.participants.filter { it.status == "joined" && it.user.id != session.user?.id }
                    .forEach { mesh.offerTo(it.user.id) }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun acceptGroupCall() {
        val pending = groupCall.value
        val roomId = pending.room?.call?.id ?: return
        viewModelScope.launch {
            try {
                val room = calls.roomJoin(roomId)
                mesh.setRoom(roomId)
                groupCall.value = pending.copy(room = room, status = "active")
                room.participants.filter { it.status == "joined" && it.user.id != session.user?.id }
                    .forEach { mesh.offerTo(it.user.id) }
            } catch (e: Exception) {
                handleError(e)
                groupCall.value = GroupCallUiState()
            }
        }
    }

    fun declineGroupCall() {
        mesh.closeAll()
        groupCall.value = GroupCallUiState()
    }

    fun leaveGroupCall() {
        val roomId = groupCall.value.room?.call?.id
        groupCall.value = GroupCallUiState(status = "ended")
        mesh.closeAll()
        if (roomId != null && roomId > 0) {
            viewModelScope.launch {
                runCatching { calls.roomLeave(roomId) }
            }
        }
        viewModelScope.launch {
            delay(800)
            if (groupCall.value.status == "ended") groupCall.value = GroupCallUiState()
        }
    }

    fun toggleGroupMute() {
        val muted = !groupCall.value.muted
        mesh.setMuted(muted)
        groupCall.value = groupCall.value.copy(muted = muted)
    }

    fun toggleGroupSpeaker() {
        val app = getApplication<Application>()
        val audio = app.getSystemService(Application.AUDIO_SERVICE) as? AudioManager ?: return
        val speaker = !groupCall.value.speaker
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = speaker
        groupCall.value = groupCall.value.copy(speaker = speaker)
    }

    private fun onRoomOffer(data: JSONObject) {
        if (callState.value.status in listOf("dialing", "active", "ringing")) return
        if (groupCall.value.status == "active") return
        val callJson = data.optJSONObject("call") ?: return
        val convId = callJson.optLong("conversation_id")
        val from = data.optJSONObject("from")?.toUser()
        val title = conversations.value.firstOrNull { it.id == convId }?.displayTitle
            ?: (from?.displayName?.let { "Panggilan grup $it" } ?: "Panggilan grup")
        viewModelScope.launch {
            val room = runCatching { calls.roomState(callJson.optLong("id")) }.getOrNull()
            groupCall.value = GroupCallUiState(
                room = room,
                conversationId = convId,
                title = title,
                status = "ringing",
            )
        }
    }

    private fun refreshGroupRoom(callId: Long) {
        viewModelScope.launch {
            val room = runCatching { calls.roomState(callId) }.getOrNull() ?: return@launch
            val current = groupCall.value
            if (current.room?.call?.id == callId && current.status == "active") {
                groupCall.value = current.copy(room = room)
            }
        }
    }

    private fun onRoomSignal(data: JSONObject) {
        val callJson = data.optJSONObject("call") ?: return
        if (groupCall.value.room?.call?.id != callJson.optLong("id")) return
        if (groupCall.value.status != "active") return
        val fromId = data.optLong("from_user_id")
        when (data.optString("signal")) {
            "offer" -> mesh.answerTo(fromId, data.optString("sdp"))
            "answer" -> mesh.onAnswer(fromId, data.optString("sdp"))
        }
    }

    /**
     * Kunci E2EE baru dibagikan untukku: unduh lalu dekrip ulang chat yang
     * sedang dibuka + daftar percakapan (menyembuhkan "[Pesan terenkripsi]").
     */
    private fun onKeysUpdated(data: JSONObject) {
        val conversationId = data.optLong("conversation_id")
        if (conversationId <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { syncKeysNow(conversationId) }
            withContext(Dispatchers.Main) {
                if (conversationId == activeChatId) {
                    val state = chat.value
                    chat.value = state.copy(messages = state.messages.map { e2ee.decryptMessage(it) })
                }
                refreshConversations()
            }
        }
    }

    private fun onIncomingOffer(data: JSONObject) {
        val callJson = data.optJSONObject("call") ?: return
        val fromJson = data.optJSONObject("from")
        val sdp = data.optString("sdp")
        if (callState.value.status in listOf("dialing", "active", "ringing")) return
        if (groupCall.value.status == "active") return
        val call = VoiceCall(
            id = callJson.optLong("id"),
            uuid = callJson.optString("uuid"),
            callerId = callJson.optLong("caller_id"),
            calleeId = callJson.optLong("callee_id"),
            status = callJson.optString("status", "ringing"),
            kind = callJson.optString("kind", "audio"),
        )
        val peer = fromJson?.toUser()
        callState.value = CallUiState(
            call = call, peer = peer, outgoing = false,
            status = "ringing", remoteSdp = sdp.ifBlank { null },
        )
        startCallService()
    }

    private fun onCallAnswer(data: JSONObject) {
        val sdp = data.optString("sdp")
        if (sdp.isBlank()) return
        if (callState.value.status != "dialing") return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { rtc.handleAnswer(sdp) }
            withContext(Dispatchers.Main) {
                callState.value = callState.value.copy(status = "active")
            }
        }
    }

    private fun onCallIce(data: JSONObject) {
        val candidate = data.optJSONObject("candidate") ?: return
        // Room ICE memakai event yang sama; bedakan lewat call.conversation_id.
        val callJson = data.optJSONObject("call")
        if (callJson != null && callJson.optLong("conversation_id") != 0L) {
            val roomId = callJson.optLong("id")
            if (groupCall.value.room?.call?.id != roomId) return
            if (groupCall.value.status != "active") return
            mesh.onIce(data.optLong("from_user_id"), candidate)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { rtc.handleRemoteIce(candidate) }
        }
    }

    private fun onRoomEnded(data: JSONObject) {
        val callId = data.optJSONObject("call")?.optLong("id") ?: data.optLong("call_id")
        if (callId <= 0 || groupCall.value.room?.call?.id != callId) return
        mesh.closeAll()
        groupCall.value = GroupCallUiState(status = "ended")
        viewModelScope.launch {
            delay(800)
            if (groupCall.value.status == "ended") groupCall.value = GroupCallUiState()
        }
    }

    private fun onCallEnded(data: JSONObject) {
        endCallLocal()
    }

    /**
     * Dipanggil saat media tersambung (atau jawaban terkirim): rute audio
     * komunikasi (earpiece default ala WhatsApp) + foreground service
     * agar telepon tetap jalan di background.
     */
    private fun markCallActive() {
        runCatching {
            val audio = getApplication<Application>().getSystemService(Application.AUDIO_SERVICE) as? AudioManager
            if (audio != null) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                audio.isSpeakerphoneOn = false
            }
        }
        callState.value = callState.value.copy(status = "active", speaker = false)
        startCallService()
    }

    private fun endCallLocal() {
        runCatching { rtc.close() }
        callIceJob?.cancel()
        pendingIce.clear()
        stopCallService()
        val app = getApplication<Application>()
        runCatching {
            val audio = app.getSystemService(Application.AUDIO_SERVICE) as? AudioManager
            audio?.mode = AudioManager.MODE_NORMAL
            audio?.isSpeakerphoneOn = false
        }
        if (callState.value.status != "idle") {
            callState.value = CallUiState(status = "ended")
            viewModelScope.launch {
                delay(800)
                if (callState.value.status == "ended") callState.value = CallUiState()
            }
        }
    }

    private fun incomingMessage(conversationId: Long, incoming: Message) {
        val current = chat.value
        if (current.conversation?.id == conversationId) {
            val cleaned = current.messages.filterNot { existing ->
                existing.id == incoming.id ||
                    (incoming.clientId != null && existing.clientId == incoming.clientId)
            }
            chat.value = current.copy(
                messages = (cleaned + incoming).distinctBy { it.id }.sortedBy { it.id },
                typingName = null,
            )
            if (incoming.sender?.id != session.user?.id) {
                markReadInternal(conversationId, incoming.id)
            }
        }
        refreshConversations()
    }

    // -------------------------------------------------------------------- chats

    fun refreshConversations() {
        if (sessionState.value.user == null) return
        conversationsLoading.value = true
        viewModelScope.launch {
            try {
                val list = repository.conversations()
                conversations.value = list.map { conv ->
                    val last = conv.lastMessage
                    if (last != null && last.type == "text") {
                        conv.copy(lastMessage = e2ee.decryptMessage(last))
                    } else conv
                }
            } catch (e: Exception) {
                handleError(e)
            } finally {
                conversationsLoading.value = false
            }
        }
    }

    fun openChat(conversation: Conversation) {
        activeChatId = conversation.id
        // Chat dibuka: hapus notifnya bila ada.
        runCatching { PushManager.cancelForConversation(getApplication(), conversation.id) }
        chat.value = ChatUiState(conversation = conversation, loading = true, keySyncing = true)
        viewModelScope.launch {
            try {
                // Tunggu kunci E2EE siap dulu (device baru!) sebelum mendekrip,
                // kalau tidak pesan terlanjur tampil terenkripsi.
                runCatching { syncKeysNow(conversation.id) }
                chat.value = chat.value.copy(keySyncing = false)
                val page = repository.messages(conversation.id, limit = 40)
                val dec = page.messages.map { e2ee.decryptMessage(it) }
                chat.value = ChatUiState(
                    conversation = conversation,
                    messages = dec,
                    loading = false,
                    hasMore = page.hasMore,
                )
                page.messages.lastOrNull()?.let { markReadInternal(conversation.id, it.id) }
                refreshConversations()
                loadPins()
            } catch (e: Exception) {
                handleError(e)
                chat.value = chat.value.copy(
                    loading = false,
                    keySyncing = false,
                    error = e.message ?: "Gagal memuat pesan.",
                )
            }
        }
    }

    /**
     * User lain baru online (event presence via Erlang): segarkan detail
     * chat yang sedang dibuka agar "x anggota, y online" / "Online"
     * terasa realtime tanpa polling.
     */
    private fun onPresenceChanged(userId: Long) {
        val current = chat.value
        val conv = current.detail ?: current.conversation ?: return
        if (conv.id != activeChatId) return
        val involves = conv.peer?.id == userId || conv.members.any { it.id == userId }
        if (!involves) return
        viewModelScope.launch {
            val updated = runCatching { repository.conversationDetail(conv.id) }.getOrNull()
                ?: return@launch
            if (chat.value.conversation?.id == conv.id) {
                chat.value = chat.value.copy(detail = updated)
            }
        }
    }

    fun openChatWith(user: User) {
        viewModelScope.launch {
            try {
                openChat(repository.openDirect(user.id))
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    /** Buka chat dari deep-link notifikasi (PushDeepLink). */
    fun openChatById(conversationId: Long) {
        viewModelScope.launch {
            try {
                openChat(repository.conversationDetail(conversationId))
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    /** Aksi Jawab/Tolak/Tutup dari notifikasi telepon. */
    fun onNotificationCallAction(action: String, callId: Long) {
        val current = callState.value.call
        viewModelScope.launch {
            when (action) {
                "answer" -> {
                    if (current?.id == callId && callState.value.status == "ringing") {
                        answerVoiceCall()
                    }
                }
                "reject" -> {
                    if (current?.id == callId) rejectVoiceCall() else endCallById(callId)
                }
                else -> {
                    if (current?.id == callId) endVoiceCall() else endCallById(callId)
                }
            }
        }
    }

    private fun endCallById(callId: Long) {
        viewModelScope.launch {
            runCatching { calls.end(callId) }
            if (callState.value.call?.id == callId) {
                withContext(Dispatchers.Main) { endCallLocal() }
            } else {
                stopCallService()
            }
        }
    }

    /** Nyalakan foreground service telepon (microphone) agar jalan di background. */
    private fun startCallService() {
        val app = getApplication<Application>()
        val current = callState.value
        val callId = current.call?.id ?: 0L
        val intent = android.content.Intent(app, CallForegroundService::class.java).apply {
            action = if (current.status == "ringing" && !current.outgoing) {
                CallForegroundService.ACTION_INCOMING
            } else {
                CallForegroundService.ACTION_START
            }
            putExtra(CallForegroundService.EXTRA_CALL_ID, callId)
            putExtra(CallForegroundService.EXTRA_PEER, current.peer?.displayName.orEmpty())
            putExtra(CallForegroundService.EXTRA_STATUS, current.status)
        }
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(app, intent)
        }
    }

    private fun stopCallService() {
        runCatching {
            getApplication<Application>().stopService(
                android.content.Intent(getApplication(), CallForegroundService::class.java)
            )
        }
    }

    fun createGroup(title: String, memberIds: List<Long>) {
        viewModelScope.launch {
            try {
                openChat(repository.createGroup(title, memberIds))
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun closeChat() {
        activeChatId = null
        chat.value = ChatUiState()
    }

    fun setReply(message: Message?) {
        chat.value = chat.value.copy(replyTo = message)
    }

    fun sendMessage(text: String) {
        val state = chat.value
        val conversation = state.conversation ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        val me = session.user ?: return
        val clientId = UUID.randomUUID().toString()
        val replyToId = state.replyTo?.takeIf { it.id > 0 }?.id
        val pending = Message(
            id = -System.currentTimeMillis(),
            conversationId = conversation.id,
            sender = me,
            type = "text",
            body = body,
            attachmentUrl = null,
            status = "sending",
            createdAt = nowIso(),
            clientId = clientId,
            pending = true,
            replyToId = replyToId,
        )
        chat.value = state.copy(messages = state.messages + pending, replyTo = null)
        lastTypingSent = 0L

        // Bila kunci lokal baru dibuat saat mengirim (mis. habis reinstall),
        // sebarkan ke anggota/perangkat lain agar pesan terbaca semua pihak.
        val hadKey = e2ee.isEnabled(conversation.id)
        viewModelScope.launch {
            try {
                val toSend = e2ee.encrypt(conversation.id, body)
                val sent = repository.sendMessage(conversation.id, toSend, clientId, replyToId)
                val dec = e2ee.decryptMessage(sent)
                mergeSentMessage(clientId, dec)
                refreshConversations()
                if (!hadKey) {
                    launch(Dispatchers.IO) { runCatching { syncKeysNow(conversation.id) } }
                }
            } catch (e: Exception) {
                val current = chat.value
                chat.value = current.copy(
                    messages = current.messages.map {
                        if (it.clientId == clientId) it.copy(status = "failed", pending = false) else it
                    }
                )
                handleError(e)
            }
        }
    }

    fun retryMessage(clientId: String) {
        val state = chat.value
        val pending = state.messages.firstOrNull { it.clientId == clientId } ?: return
        val conversation = state.conversation ?: return
        chat.value = state.copy(
            messages = state.messages.map {
                if (it.clientId == clientId) it.copy(status = "sending", pending = true) else it
            }
        )
        viewModelScope.launch {
            try {
                val sentRaw = when (pending.type) {
                    "text" -> {
                        val enc = e2ee.encrypt(conversation.id, pending.body.orEmpty())
                        repository.sendMessage(conversation.id, enc, clientId, pending.replyToId)
                    }
                    "location" -> {
                        val lat = pending.lat ?: throw IllegalStateException("Lokasi tidak valid.")
                        val lng = pending.lng ?: throw IllegalStateException("Lokasi tidak valid.")
                        repository.sendLocation(conversation.id, lat, lng, pending.body.orEmpty(), clientId, pending.replyToId)
                    }
                    else -> {
                        val url = pending.attachmentUrl.orEmpty()
                        if (url.isBlank()) throw IllegalStateException("Lampiran tidak valid.")
                        repository.sendAttachment(conversation.id, url, pending.type, clientId, pending.body.orEmpty(), pending.replyToId)
                    }
                }
                val sent = e2ee.decryptMessage(sentRaw)
                mergeSentMessage(clientId, sent)
                refreshConversations()
            } catch (e: Exception) {
                val current = chat.value
                chat.value = current.copy(
                    messages = current.messages.map {
                        if (it.clientId == clientId) it.copy(status = "failed", pending = false) else it
                    }
                )
                handleError(e)
            }
        }
    }

    fun revokeMessage(message: Message) {
        if (message.id <= 0) return
        viewModelScope.launch {
            try {
                repository.revokeMessage(message.id)
                markRevokedLocal(message.id)
                refreshConversations()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    /**
     * Samarkan pesan yang ditarik di semua state lokal: daftar pesan, bar
     * sematan, pratinjau balasan, dan hasil pencarian — isi aslinya tidak
     * boleh muncul lagi di mana pun.
     */
    private fun markRevokedLocal(messageId: Long) {
        val scrubbed = { m: Message ->
            if (m.id == messageId) m.copy(revoked = true, body = null, attachmentUrl = null) else m
        }
        val current = chat.value
        chat.value = current.copy(
            messages = current.messages.map(scrubbed),
            pins = current.pins.map { pin ->
                if (pin.message.id == messageId) pin.copy(message = scrubbed(pin.message)) else pin
            },
            replyTo = current.replyTo?.let(scrubbed),
            searchResults = current.searchResults.map(scrubbed),
        )
    }

    fun setEditing(message: Message?) {
        chat.value = chat.value.copy(editing = message, replyTo = if (message != null) null else chat.value.replyTo)
    }

    fun confirmEdit(body: String) {
        val target = chat.value.editing ?: return
        if (target.id <= 0) return
        val text = body.trim()
        if (text.isEmpty()) return
        val conversation = chat.value.conversation ?: return
        viewModelScope.launch {
            try {
                val encrypted = e2ee.encrypt(conversation.id, text)
                val updated = e2ee.decryptMessage(repository.editMessage(target.id, encrypted))
                chat.value = chat.value.copy(
                    editing = null,
                    messages = chat.value.messages.map { if (it.id == updated.id) updated else it }
                )
                refreshConversations()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun reactMessage(message: Message, emoji: String) {
        if (message.id <= 0) return
        viewModelScope.launch {
            try {
                val (_, reactions) = repository.reactMessage(message.id, emoji)
                chat.value = chat.value.copy(
                    messages = chat.value.messages.map {
                        if (it.id == message.id) it.copy(reactions = reactions) else it
                    }
                )
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun forwardMessage(msg: Message, toConversationId: Long) {
        if (msg.id <= 0) return
        viewModelScope.launch {
            try {
                runCatching { e2ee.ensureKey(toConversationId) }
                // Enkripsi ulang isi untuk kunci percakapan tujuan bila beda obrolan.
                val plain = e2ee.decryptMessage(msg).body
                val activeId = chat.value.conversation?.id
                val bodyOverride = if (toConversationId != activeId && msg.type == "text" && plain != null) {
                    e2ee.encrypt(toConversationId, plain)
                } else null
                val sent = repository.forwardMessage(msg.id, toConversationId, UUID.randomUUID().toString(), bodyOverride)
                if (toConversationId == activeId) {
                    val dec = if (bodyOverride != null) e2ee.decryptMessage(sent) else sent
                    mergeSentMessage(sent.clientId.orEmpty(), dec)
                }
                refreshConversations()
                message.value = "Pesan diteruskan."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun loadPins() {
        val conversation = chat.value.conversation ?: return
        viewModelScope.launch {
            try {
                val pins = repository.pins(conversation.id).map { pin ->
                    pin.copy(message = e2ee.decryptMessage(pin.message))
                }
                chat.value = chat.value.copy(pins = pins)
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun pinMessage(message: Message) {
        val conversation = chat.value.conversation ?: return
        if (message.id <= 0) return
        viewModelScope.launch {
            try {
                val pins = repository.pinMessage(conversation.id, message.id).map { pin ->
                    pin.copy(message = e2ee.decryptMessage(pin.message))
                }
                chat.value = chat.value.copy(pins = pins)
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun unpinMessage(message: Message) {
        val conversation = chat.value.conversation ?: return
        if (message.id <= 0) return
        viewModelScope.launch {
            try {
                repository.unpinMessage(conversation.id, message.id)
                loadPins()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun archiveActiveChat(archived: Boolean) {
        val conversation = chat.value.conversation ?: return
        viewModelScope.launch {
            try {
                repository.archiveConversation(conversation.id, archived)
                refreshConversations()
                message.value = if (archived) "Obrolan diarsipkan." else "Obrolan dikeluarkan dari arsip."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun searchInChat(query: String) {
        val conversation = chat.value.conversation ?: return
        val q = query.trim()
        if (q.length < 2) {
            chat.value = chat.value.copy(searching = false, searchQuery = "", searchResults = emptyList())
            return
        }
        chat.value = chat.value.copy(searching = true, searchQuery = q)
        viewModelScope.launch {
            try {
                val remote = repository.searchMessages(conversation.id, q).map { e2ee.decryptMessage(it) }
                val local = chat.value.messages.filter {
                    !it.revoked && it.body?.contains(q, ignoreCase = true) == true && remote.none { r -> r.id == it.id }
                }
                chat.value = chat.value.copy(searching = false, searchResults = remote + local)
            } catch (e: Exception) {
                val local = chat.value.messages.filter {
                    !it.revoked && it.body?.contains(q, ignoreCase = true) == true
                }
                chat.value = chat.value.copy(searching = false, searchResults = local)
            }
        }
    }

    fun clearChatSearch() {
        chat.value = chat.value.copy(searching = false, searchQuery = "", searchResults = emptyList())
    }

    fun sendImage(uri: Uri) {
        sendFile(uri, "image")
    }

    fun sendFile(uri: Uri, preferredType: String = "auto", caption: String = "") {
        val conversation = chat.value.conversation ?: return
        if (chat.value.sendingImage || chat.value.sendingFile) return
        chat.value = chat.value.copy(sendingImage = true, sendingFile = true)
        viewModelScope.launch {
            try {
                val url = repository.upload(uri)
                val clientId = UUID.randomUUID().toString()
                val type = resolveAttachmentType(uri, preferredType)
                val replyToId = chat.value.replyTo?.takeIf { it.id > 0 }?.id
                val sent = repository.sendAttachment(conversation.id, url, type, clientId, caption, replyToId)
                chat.value = chat.value.copy(replyTo = null)
                mergeSentMessage(clientId, sent)
                refreshConversations()
            } catch (e: Exception) {
                handleError(e)
            } finally {
                chat.value = chat.value.copy(sendingImage = false, sendingFile = false)
            }
        }
    }

    private fun resolveAttachmentType(uri: Uri, preferred: String): String {
        if (preferred != "auto") return preferred
        val resolver = getApplication<Application>().contentResolver
        val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        return when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            else -> "file"
        }
    }

    fun loadConversationDetail() {
        val conversation = chat.value.conversation ?: return
        viewModelScope.launch {
            try {
                val detail = repository.conversationDetail(conversation.id)
                if (chat.value.conversation?.id == conversation.id) {
                    chat.value = chat.value.copy(detail = detail)
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun addGroupMembers(userIds: List<Long>) {
        val conversation = chat.value.conversation ?: return
        viewModelScope.launch {
            try {
                repository.addGroupMembers(conversation.id, userIds)
                loadConversationDetail()
                refreshConversations()
                message.value = "Anggota ditambahkan."
                syncConversationKeys(conversation.id)
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun removeGroupMember(userId: Long) {
        val conversation = chat.value.conversation ?: return
        viewModelScope.launch {
            try {
                repository.removeGroupMember(conversation.id, userId)
                loadConversationDetail()
                refreshConversations()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun setMemberRole(userId: Long, role: String) {
        val conversation = chat.value.conversation ?: return
        viewModelScope.launch {
            try {
                repository.setMemberRole(conversation.id, userId, role)
                loadConversationDetail()
                refreshConversations()
                message.value = if (role == "admin") "Anggota diangkat menjadi admin." else "Role admin dicabut."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    suspend fun fetchInviteLink(conversationId: Long, rotate: Boolean = false): String? {
        return try {
            val (_, link) = repository.inviteLink(conversationId, rotate)
            inviteLink.value = link.ifBlank { null }
            inviteLink.value
        } catch (e: Exception) {
            handleError(e)
            null
        }
    }

    fun loadJoinRequests(conversationId: Long) {
        viewModelScope.launch {
            try {
                joinRequests.value = repository.joinRequests(conversationId)
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun reviewJoinRequest(request: GroupJoinRequest, approve: Boolean) {
        viewModelScope.launch {
            try {
                repository.reviewJoinRequest(request.requestId, approve)
                joinRequests.value = repository.joinRequests(request.conversationId)
                loadConversationDetail()
                refreshConversations()
                message.value = if (approve) "Permintaan gabung disetujui." else "Permintaan gabung ditolak."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun joinGroupByToken(token: String, onJoined: (() -> Unit)? = null) {
        val clean = token.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                val (status, conversation) = repository.joinGroup(clean)
                when (status) {
                    "pending" -> message.value = "Permintaan gabung terkirim. Menunggu persetujuan admin."
                    else -> {
                        refreshConversations()
                        if (conversation != null) openChat(conversation)
                        message.value = "Kamu bergabung ke grup."
                    }
                }
                onJoined?.invoke()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun leaveGroup(onLeft: () -> Unit = {}) {
        val conversation = chat.value.conversation ?: return
        val meId = session.user?.id ?: return
        viewModelScope.launch {
            try {
                repository.removeGroupMember(conversation.id, meId)
                closeChat()
                refreshConversations()
                message.value = "Kamu keluar dari grup."
                onLeft()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun toggleMute(muted: Boolean) {
        val conversation = chat.value.conversation ?: return
        chat.value = chat.value.copy(muting = true)
        viewModelScope.launch {
            try {
                val result = repository.muteConversation(conversation.id, muted)
                val current = chat.value
                val updated = current.conversation?.copy(muted = result)
                chat.value = current.copy(
                    conversation = updated,
                    detail = current.detail?.copy(muted = result),
                    muting = false,
                )
                refreshConversations()
            } catch (e: Exception) {
                chat.value = chat.value.copy(muting = false)
                handleError(e)
            }
        }
    }

    suspend fun updateGroupSettings(conversationId: Long, fields: Map<String, Any>) {
        repository.updateGroupSettings(conversationId, fields)
        val updated = repository.conversationDetail(conversationId)
        if (chat.value.conversation?.id == conversationId) {
            chat.value = chat.value.copy(conversation = updated, detail = updated)
        }
        refreshConversations()
    }

    suspend fun updateGroupInfo(conversationId: Long, title: String? = null, avatarUrl: String? = null) {
        val updated = repository.updateGroupInfo(conversationId, title, avatarUrl)
        if (chat.value.conversation?.id == conversationId) {
            chat.value = chat.value.copy(conversation = updated, detail = updated)
        }
        refreshConversations()
    }

    suspend fun uploadGroupAvatar(uri: android.net.Uri): String {
        val url = repository.upload(uri)
        // avatarUrl disimpan via update_group agar kena cek izin + system event
        return url
    }

    fun clearHistory(conversationId: Long) {
        viewModelScope.launch {
            try {
                repository.clearHistory(conversationId)
                if (chat.value.conversation?.id == conversationId) {
                    chat.value = chat.value.copy(messages = emptyList(), hasMore = false)
                }
                refreshConversations()
                message.value = "Riwayat chat dibersihkan."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    private fun mergeSentMessage(clientId: String, sent: Message) {
        val current = chat.value
        val cleaned = current.messages.filterNot {
            it.clientId == clientId || it.id == sent.id
        }
        chat.value = current.copy(
            messages = (cleaned + sent).distinctBy { it.id }.sortedBy { it.id }
        )
    }

    fun loadOlderMessages() {
        val state = chat.value
        val conversation = state.conversation ?: return
        val oldest = state.messages.firstOrNull { it.id > 0 }?.id ?: return
        if (state.loadingOlder || !state.hasMore) return
        chat.value = state.copy(loadingOlder = true)
        viewModelScope.launch {
            try {
                val page = repository.messages(conversation.id, beforeId = oldest, limit = 30)
                val dec = page.messages.map { e2ee.decryptMessage(it) }
                val current = chat.value
                chat.value = current.copy(
                    messages = (dec + current.messages)
                        .distinctBy { it.id }
                        .sortedBy { it.id },
                    loadingOlder = false,
                    hasMore = page.hasMore,
                )
            } catch (e: Exception) {
                chat.value = chat.value.copy(loadingOlder = false)
                handleError(e)
            }
        }
    }

    fun pollChat() {
        val state = chat.value
        val conversation = state.conversation ?: return
        val afterId = state.messages.filter { it.id > 0 }.maxOfOrNull { it.id } ?: return
        viewModelScope.launch {
            try {
                val page = repository.messages(conversation.id, afterId = afterId, limit = 50)
                val dec = page.messages.map { e2ee.decryptMessage(it) }
                val current = chat.value
                if (current.conversation?.id != conversation.id || dec.isEmpty()) return@launch
                val extra = dec.filter { incoming ->
                    current.messages.none { it.id == incoming.id }
                }
                if (extra.isNotEmpty()) {
                    chat.value = current.copy(
                        messages = (current.messages + extra).distinctBy { it.id }.sortedBy { it.id }
                    )
                    extra.lastOrNull()?.let { markReadInternal(conversation.id, it.id) }
                    refreshConversations()
                }
            } catch (_: Exception) {
                // Polling is a fallback; the socket will reconnect on its own.
            }
        }
    }

    fun notifyTyping() {
        val conversationId = activeChatId ?: return
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 2_500L) return
        lastTypingSent = now
        viewModelScope.launch {
            runCatching { repository.typing(conversationId) }
        }
    }

    private fun markReadInternal(conversationId: Long, messageId: Long) {
        val previous = readUpTo[conversationId] ?: 0L
        if (messageId <= previous) return
        readUpTo[conversationId] = messageId
        conversations.value = conversations.value.map {
            if (it.id == conversationId) it.copy(unreadCount = 0) else it
        }
        viewModelScope.launch {
            runCatching { repository.markRead(conversationId, messageId) }
        }
    }

    // ------------------------------------------------------------------ friends

    fun loadFriends() {
        if (sessionState.value.user == null) return
        viewModelScope.launch {
            try {
                friends.value = repository.friends()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun loadRequests() {
        if (sessionState.value.user == null) return
        viewModelScope.launch {
            try {
                val (incoming, outgoing) = repository.friendRequests()
                requests.value = RequestsState(incoming, outgoing)
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun searchUsers(query: String) {
        if (query.trim().length < 2) {
            searchResults.value = emptyList()
            return
        }
        searchLoading.value = true
        viewModelScope.launch {
            try {
                searchResults.value = repository.searchUsers(query.trim())
            } catch (e: Exception) {
                handleError(e)
            } finally {
                searchLoading.value = false
            }
        }
    }

    fun clearSearch() {
        searchResults.value = emptyList()
    }

    fun sendFriendRequest(user: User) {
        viewModelScope.launch {
            try {
                repository.sendFriendRequest(user.id)
                message.value = "Permintaan pertemanan dikirim ke ${user.displayName}."
                searchResults.value = searchResults.value.map {
                    if (it.id == user.id) it.copy(friendStatus = "pending") else it
                }
                nearby.value = nearby.value.copy(
                    users = nearby.value.users.map {
                        if (it.id == user.id) it.copy(friendStatus = "pending") else it
                    }
                )
                loadRequests()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun respondToRequest(request: FriendRequest, accept: Boolean) {
        viewModelScope.launch {
            try {
                repository.respondFriendRequest(request.requestId, accept)
                message.value = if (accept) {
                    "Sekarang kamu berteman dengan ${request.user.displayName}."
                } else {
                    "Permintaan pertemanan ditolak."
                }
                loadRequests()
                loadFriends()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun removeFriend(user: User) {
        viewModelScope.launch {
            try {
                repository.removeFriend(user.id)
                friends.value = friends.value.filterNot { it.id == user.id }
                loadFriends()
                loadRequests()
                refreshConversations()
                message.value = "${user.displayName} dihapus dari teman."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun blockUser(user: User) {
        viewModelScope.launch {
            try {
                repository.blockUser(user.id)
                loadFriends()
                loadRequests()
                refreshConversations()
                message.value = "${user.displayName} diblokir."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun unblockUser(user: User) {
        viewModelScope.launch {
            try {
                repository.unblockUser(user.id)
                blocked.value = blocked.value.filterNot { it.id == user.id }
                loadFriends()
                message.value = "${user.displayName} dibuka blokirnya."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun loadBlocked() {
        viewModelScope.launch {
            try {
                blocked.value = repository.blockedUsers()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun deleteAccount(password: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                if (repository.deleteAccount(password)) {
                    logout()
                    onDeleted()
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun loadWallet() {
        viewModelScope.launch {
            try {
                walletBalance.value = repository.wallet()
                walletTxns.value = repository.walletHistory()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun walletTopup(amount: Long) {
        viewModelScope.launch {
            try {
                walletBalance.value = repository.walletTopup(amount)
                walletTxns.value = repository.walletHistory()
                message.value = "Top up berhasil."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun walletTransfer(toUser: User, amount: Long, note: String) {
        viewModelScope.launch {
            try {
                walletBalance.value = repository.walletTransfer(toUser.id, amount, note.ifBlank { null })
                walletTxns.value = repository.walletHistory()
                message.value = "Terkirim ke ${toUser.displayName}."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun syncPhoneContacts() {
        if (contactsSyncing.value) return
        contactsSyncing.value = true
        viewModelScope.launch {
            try {
                val pairs = readPhoneContacts()
                if (pairs.isEmpty()) {
                    message.value = "Tidak ada kontak dengan nomor HP di perangkat ini."
                    return@launch
                }
                val (received, matched) = repository.syncContacts(pairs)
                contactsMatched.value = matched
                message.value = if (matched.isEmpty()) {
                    "Dicek $received kontak, belum ada yang memakai NusaChat."
                } else {
                    "Ditemukan ${matched.size} kontak memakai NusaChat."
                }
            } catch (e: Exception) {
                handleError(e)
            } finally {
                contactsSyncing.value = false
            }
        }
    }

    private fun readPhoneContacts(): List<Pair<String, String>> {
        val resolver = getApplication<Application>().contentResolver
        val out = mutableListOf<Pair<String, String>>()
        runCatching {
            val cursor = resolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null, null,
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && out.size < 2000) {
                    val name = if (nameIdx >= 0) it.getString(nameIdx).orEmpty() else ""
                    val phone = if (phoneIdx >= 0) it.getString(phoneIdx).orEmpty() else ""
                    if (phone.isNotBlank()) out.add(name.ifBlank { phone } to phone)
                }
            }
        }
        return out.distinctBy { it.second }.take(2000)
    }

    // ------------------------------------------------------------------- nearby

    fun hasLocationPermission(): Boolean = locationHelper.hasPermission()

    fun setNearbyRadius(km: Int) {
        val clamped = km.coerceIn(1, 100)
        nearbyRadius.value = clamped
        getApplication<Application>().getSharedPreferences("nusachat.nearby", android.content.Context.MODE_PRIVATE)
            .edit().putInt("radius_km", clamped).apply()
        if (nearbyLiveActive) startNearbyLive() else loadNearby(force = true)
    }

    /**
     * Orang Terdekat realtime via Erlang: lokasi dikirim lewat WS,
     * daftar awal dibalas `nearby.result`, perubahan masuk sebagai
     * `nearby.changed` tanpa polling. Jatuh kembali ke REST bila
     * socket belum tersambung.
     */
    fun startNearbyLive() {
        if (!locationHelper.hasPermission()) {
            nearby.value = nearby.value.copy(
                error = "Izin lokasi diperlukan untuk melihat orang di sekitar.",
                loaded = true,
            )
            return
        }
        nearbyLiveActive = true
        nearby.value = nearby.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val location = locationHelper.currentLocation()
                if (location == null) {
                    nearby.value = NearbyState(
                        loading = false,
                        loaded = true,
                        error = "Lokasi tidak tersedia. Aktifkan GPS lalu coba lagi.",
                    )
                    return@launch
                }
                val radius = nearbyRadius.value.coerceIn(1, 100)
                val payload = JSONObject()
                    .put("lat", location.first)
                    .put("lng", location.second)
                    .put("radius", radius)
                val ws = socket
                if (ws?.isOpen == true) {
                    ws.send("location.update", payload)
                    runCatching { repository.updatePresence(location.first, location.second) }
                    // Hasil datang via nearby.result; timeout pengaman 12 dtk.
                    delay(12_000)
                    if (nearbyLiveActive && nearby.value.loading && !nearby.value.loaded) {
                        loadNearby(force = true)
                    }
                } else {
                    loadNearby(force = true)
                }
            } catch (e: Exception) {
                loadNearby(force = true)
            }
        }
    }

    fun stopNearbyLive() {
        nearbyLiveActive = false
        runCatching { socket?.send("nearby.unsubscribe") }
    }

    // ------------------------------------------------- latar chat (wallpaper + bubble)

    private fun loadSavedChatTheme(): ChatThemeChoice {
        return try {
            ChatThemeChoice(
                wallpaperId = chatThemeStore.wallpaperId,
                customUri = chatThemeStore.customUri,
                bubbleId = chatThemeStore.bubbleId,
            )
        } catch (e: Exception) {
            ChatThemeChoice()
        }
    }

    fun setChatWallpaper(wallpaperId: String, customUri: String = "") {
        chatThemeStore.wallpaperId = wallpaperId
        if (customUri.isNotBlank()) chatThemeStore.customUri = customUri
        chatTheme.value = chatTheme.value.copy(wallpaperId = wallpaperId, customUri = chatThemeStore.customUri)
    }

    fun setChatBubble(bubbleId: String) {
        chatThemeStore.bubbleId = bubbleId
        chatTheme.value = chatTheme.value.copy(bubbleId = bubbleId)
    }

    fun resetChatTheme() {
        chatThemeStore.reset()
        chatTheme.value = ChatThemeChoice()
    }

    private fun loadSavedRadius(): Int {
        val saved = getApplication<Application>().getSharedPreferences("nusachat.nearby", android.content.Context.MODE_PRIVATE)
            .getInt("radius_km", 25)
        return saved.coerceIn(1, 100)
    }

    fun loadNearby(force: Boolean = false) {
        if (nearby.value.loading && !force) return
        if (!locationHelper.hasPermission()) {
            nearby.value = nearby.value.copy(
                error = "Izin lokasi diperlukan untuk melihat orang di sekitar.",
                loaded = true,
            )
            return
        }
        nearby.value = nearby.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val location = locationHelper.currentLocation()
                if (location == null) {
                    nearby.value = NearbyState(
                        loading = false,
                        loaded = true,
                        error = "Lokasi tidak tersedia. Aktifkan GPS lalu coba lagi.",
                    )
                    return@launch
                }
                val radius = nearbyRadius.value.coerceIn(1, 100)
                val users = repository.nearby(location.first, location.second, radius)
                runCatching { repository.updatePresence(location.first, location.second) }
                nearby.value = NearbyState(users = users, loading = false, loaded = true, live = false)
            } catch (e: Exception) {
                nearby.value = NearbyState(
                    loading = false,
                    loaded = true,
                    error = e.message ?: "Gagal memuat orang terdekat.",
                )
                handleError(e)
            }
        }
    }

    fun shareCurrentLocation() {
        val conversation = chat.value.conversation ?: return
        if (sharingLocation.value) return
        if (!locationHelper.hasPermission()) {
            message.value = "Izin lokasi diperlukan untuk berbagi lokasi."
            return
        }
        sharingLocation.value = true
        // Optimistic pending bubble
        val me = session.user ?: return
        val clientId = UUID.randomUUID().toString()
        val replyToId = chat.value.replyTo?.takeIf { it.id > 0 }?.id
        viewModelScope.launch {
            try {
                val loc = locationHelper.currentLocation()
                if (loc == null) {
                    message.value = "Lokasi tidak tersedia. Aktifkan GPS."
                    sharingLocation.value = false
                    return@launch
                }
                val pending = Message(
                    id = -System.currentTimeMillis(),
                    conversationId = conversation.id,
                    sender = me,
                    type = "location",
                    body = "Lokasi saya",
                    attachmentUrl = null,
                    status = "sending",
                    createdAt = nowIso(),
                    clientId = clientId,
                    pending = true,
                    replyToId = replyToId,
                    lat = loc.first,
                    lng = loc.second,
                )
                chat.value = chat.value.copy(messages = chat.value.messages + pending, replyTo = null)
                val sent = repository.sendLocation(conversation.id, loc.first, loc.second, "Lokasi saya", clientId, replyToId)
                mergeSentMessage(clientId, sent)
                refreshConversations()
            } catch (e: Exception) {
                val current = chat.value
                chat.value = current.copy(
                    messages = current.messages.map {
                        if (it.clientId == clientId) it.copy(status = "failed", pending = false) else it
                    }
                )
                handleError(e)
            } finally {
                sharingLocation.value = false
            }
        }
    }

    // ------------------------------------------------------------------ moments

    fun refreshMoments() {
        if (sessionState.value.user == null) return
        momentsLoading.value = true
        viewModelScope.launch {
            try {
                val (items, hasMore) = repository.moments()
                moments.value = items
                momentsHasMore.value = hasMore
            } catch (e: Exception) {
                handleError(e)
            } finally {
                momentsLoading.value = false
            }
        }
        loadStories()
    }

    fun loadStories() {
        if (sessionState.value.user == null) return
        viewModelScope.launch {
            try {
                stories.value = repository.stories()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun createStory(text: String, uris: List<android.net.Uri>) {
        val trimmed = text.trim()
        if (trimmed.isBlank() && uris.isEmpty()) {
            message.value = "Tulis sesuatu atau pilih foto."
            return
        }
        if (uris.size > 4) {
            message.value = "Maksimal 4 media per story."
            return
        }
        viewModelScope.launch {
            try {
                val media = uris.take(4).map { repository.upload(it) }
                val story = repository.createStory(trimmed, media)
                stories.value = listOf(story) + stories.value
                message.value = "Story dibagikan, hilang dalam 24 jam."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun deleteStory(story: id.nusachat.app.data.Story) {
        viewModelScope.launch {
            try {
                repository.deleteStory(story.id)
                stories.value = stories.value.filterNot { it.id == story.id }
                message.value = "Story dihapus."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun reshareMoment(moment: Moment) {
        if (moment.id <= 0) return
        viewModelScope.launch {
            try {
                val fresh = repository.reshareMoment(moment.id)
                moments.value = listOf(fresh) + moments.value
                message.value = "Momen dibagikan ulang."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun shareMomentToChat(moment: Moment, conversationId: Long) {
        viewModelScope.launch {
            try {
                runCatching { e2ee.ensureKey(conversationId) }
                if (moment.body.isNotBlank()) {
                    val encrypted = e2ee.encrypt(conversationId, moment.body)
                    val clientId = java.util.UUID.randomUUID().toString()
                    val sent = repository.sendMessage(conversationId, encrypted, clientId, null)
                    if (conversationId == chat.value.conversation?.id) {
                        mergeSentMessage(clientId, e2ee.decryptMessage(sent))
                    }
                }
                moment.media.forEach { url ->
                    val type = if (id.nusachat.app.data.isVideoUrl(url)) "video" else "image"
                    val sent = repository.sendAttachment(
                        conversationId, type, url, null, java.util.UUID.randomUUID().toString()
                    )
                    if (conversationId == chat.value.conversation?.id) {
                        mergeSentMessage(sent.clientId.orEmpty(), e2ee.decryptMessage(sent))
                    }
                }
                refreshConversations()
                message.value = "Momen dibagikan ke chat."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun loadMoreMoments() {
        if (momentsLoadingMore.value || !momentsHasMore.value) return
        val oldest = moments.value.minOfOrNull { it.id } ?: return
        momentsLoadingMore.value = true
        viewModelScope.launch {
            try {
                val (items, hasMore) = repository.moments(beforeId = oldest)
                moments.value = (moments.value + items).distinctBy { it.id }.sortedByDescending { it.id }
                momentsHasMore.value = hasMore
            } catch (e: Exception) {
                handleError(e)
            } finally {
                momentsLoadingMore.value = false
            }
        }
    }

    fun createMoment(text: String, imageUri: Uri?) {
        createMoment(text, imageUri?.let { listOf(it) } ?: emptyList())
    }

    fun createMoment(text: String, imageUris: List<Uri>) {
        val trimmed = text.trim()
        if (trimmed.isBlank() && imageUris.isEmpty()) {
            message.value = "Tulis sesuatu atau pilih foto."
            return
        }
        if (imageUris.size > 9) {
            message.value = "Maksimal 9 foto per momen."
            return
        }
        creatingMoment.value = true
        viewModelScope.launch {
            try {
                val media = imageUris.take(9).map { repository.upload(it) }
                val moment = repository.createMoment(trimmed, media)
                moments.value = listOf(moment) + moments.value
                message.value = "Momen dibagikan."
            } catch (e: Exception) {
                handleError(e)
            } finally {
                creatingMoment.value = false
            }
        }
    }

    fun deleteMoment(moment: Moment) {
        viewModelScope.launch {
            try {
                repository.deleteMoment(moment.id)
                moments.value = moments.value.filterNot { it.id == moment.id }
                message.value = "Momen dihapus."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun toggleLike(moment: Moment) {
        val liked = !moment.likedByMe
        moments.value = moments.value.map {
            if (it.id == moment.id) {
                it.copy(
                    likedByMe = liked,
                    likeCount = (it.likeCount + if (liked) 1 else -1).coerceAtLeast(0),
                )
            } else it
        }
        viewModelScope.launch {
            try {
                if (liked) repository.likeMoment(moment.id) else repository.unlikeMoment(moment.id)
            } catch (e: Exception) {
                moments.value = moments.value.map {
                    if (it.id == moment.id) {
                        it.copy(
                            likedByMe = moment.likedByMe,
                            likeCount = moment.likeCount,
                        )
                    } else it
                }
                handleError(e)
            }
        }
    }

    fun openComments(momentId: Long) {
        commentsMomentId.value = momentId
        comments.value = emptyList()
        commentsLoading.value = true
        viewModelScope.launch {
            try {
                comments.value = repository.comments(momentId)
            } catch (e: Exception) {
                handleError(e)
            } finally {
                commentsLoading.value = false
            }
        }
    }

    fun closeComments() {
        commentsMomentId.value = null
        comments.value = emptyList()
    }

    fun addComment(body: String, parentId: Long? = null) {
        val momentId = commentsMomentId.value ?: return
        if (body.trim().isEmpty()) return
        viewModelScope.launch {
            try {
                val comment = repository.addComment(momentId, body.trim(), parentId)
                comments.value = comments.value + comment
                moments.value = moments.value.map {
                    if (it.id == momentId) it.copy(commentCount = it.commentCount + 1) else it
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    // ------------------------------------------------------------------ profile

    fun updateProfile(displayName: String, bio: String, phone: String) {
        updateProfileExtended(displayName, bio, phone, null, null, null)
    }

    fun updateProfileExtended(
        displayName: String,
        bio: String,
        phone: String,
        gender: String?,
        birthday: String?,
        discoverable: Boolean?,
    ) {
        viewModelScope.launch {
            try {
                val fields = JSONObject()
                    .put("display_name", displayName.trim())
                    .put("bio", bio.trim())
                if (phone.isNotBlank()) fields.put("phone", phone.trim())
                if (gender != null) fields.put("gender", gender)
                if (birthday != null) fields.put("birthday", birthday)
                if (discoverable != null) fields.put("discoverable", discoverable)
                val user = repository.updateProfile(fields)
                sessionState.value = SessionState(user, true)
                message.value = "Profil diperbarui."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun changePassword(oldPassword: String, newPassword: String) {
        viewModelScope.launch {
            try {
                repository.changePassword(oldPassword, newPassword)
                stopRealtime()
                resetLocalState()
                sessionState.value = SessionState(null, true)
                message.value = "Kata sandi diganti. Silakan masuk kembali."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun uploadAvatar(uri: Uri) {
        if (uploadingAvatar.value) return
        uploadingAvatar.value = true
        viewModelScope.launch {
            try {
                val url = repository.upload(uri)
                val user = repository.updateProfile(JSONObject().put("avatar_url", url))
                sessionState.value = SessionState(user, true)
                message.value = "Foto profil diperbarui."
            } catch (e: Exception) {
                handleError(e)
            } finally {
                uploadingAvatar.value = false
            }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun handleError(error: Throwable) {
        if (error is ApiException && error.statusCode == 401) {
            onSessionExpired()
            return
        }
        message.value = error.message ?: "Terjadi kesalahan."
    }

    private fun onSessionExpired() {
        if (sessionState.value.user == null) return
        stopRealtime()
        session.clear()
        resetLocalState()
        sessionState.value = SessionState(null, true)
        message.value = "Sesi berakhir. Silakan masuk kembali."
    }

    private fun nowIso(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
