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
import id.nusachat.app.data.Conversation
import id.nusachat.app.data.E2eeManager
import id.nusachat.app.data.FriendRequest
import id.nusachat.app.data.LocationHelper
import id.nusachat.app.data.Message
import id.nusachat.app.data.Moment
import id.nusachat.app.data.MomentComment
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
    val loadingOlder: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val typingName: String? = null,
    val sendingImage: Boolean = false,
    val sendingFile: Boolean = false,
    val replyTo: Message? = null,
    val detail: Conversation? = null,
    val muting: Boolean = false,
)

data class NearbyState(
    val users: List<User> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
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

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val session = SessionStore(application)
    private val api = ApiClient(BuildConfig.API_BASE_URL, session)
    private val repository = ChatRepository(application, api, session)
    private val calls = CallRepository(api)
    private val locationHelper = LocationHelper(application)
    private val rtc = WebRtcCallManager(application.applicationContext)
    val e2ee = E2eeManager(application.applicationContext)

    val sessionState = MutableStateFlow(SessionState())
    val authLoading = MutableStateFlow(false)
    val authError = MutableStateFlow<String?>(null)

    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversationsLoading = MutableStateFlow(false)
    val friends = MutableStateFlow<List<User>>(emptyList())
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
    val sharingLocation = MutableStateFlow(false)
    val callState = MutableStateFlow(CallUiState())
    val pushReady = MutableStateFlow(false)

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
                    val state = chat.value
                    chat.value = state.copy(
                        messages = state.messages.map {
                            if (it.id == messageId) {
                                it.copy(revoked = true, body = null, attachmentUrl = null)
                            } else it
                        }
                    )
                }
            }
            "friend_request", "friend_accepted" -> {
                loadRequests()
                loadFriends()
                refreshConversations()
            }
            "conversation_updated" -> refreshConversations()
            "moment_new", "moment_liked", "moment_comment" -> refreshMoments()
            "call_offer" -> onIncomingOffer(data)
            "call_answer" -> onCallAnswer(data)
            "call_ice" -> onCallIce(data)
            "call_end" -> onCallEnded(data)
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
        if (!rtc.isSupported()) {
            message.value = "Panggilan suara tidak didukung di perangkat ini."
            return
        }
        callState.value = CallUiState(call = null, peer = peer, outgoing = true, status = "dialing")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                rtc.ensureFactory()
                val offer = withContext(Dispatchers.IO) { rtc.startAsCaller() }
                    ?: throw IllegalStateException("Gagal membuat offer WebRTC.")
                rtc.onIceCandidate = { candidate ->
                    callState.value.call?.let { active ->
                        viewModelScope.launch {
                            runCatching {
                                calls.ice(
                                    active.id,
                                    JSONObject()
                                        .put("candidate", candidate.sdp)
                                        .put("sdpMid", candidate.sdpMid)
                                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                                )
                            }
                        }
                    }
                }
                rtc.onConnected = {
                    callState.value = callState.value.copy(status = "active")
                }
                rtc.onDisconnected = {
                    if (callState.value.status == "active") endCallLocal()
                }
                val created = calls.offer(peer.id, offer.description)
                callState.value = callState.value.copy(call = created, status = "dialing")
                pendingIce.forEach { c ->
                    runCatching { calls.ice(created.id, c) }
                }
                pendingIce.clear()
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
                rtc.onConnected = {
                    callState.value = callState.value.copy(status = "active")
                }
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
                        callState.value = callState.value.copy(status = "active")
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

    private fun onIncomingOffer(data: JSONObject) {
        val callJson = data.optJSONObject("call") ?: return
        val fromJson = data.optJSONObject("from")
        val sdp = data.optString("sdp")
        if (callState.value.status in listOf("dialing", "active", "ringing")) return
        val call = VoiceCall(
            id = callJson.optLong("id"),
            uuid = callJson.optString("uuid"),
            callerId = callJson.optLong("caller_id"),
            calleeId = callJson.optLong("callee_id"),
            status = callJson.optString("status", "ringing"),
        )
        val peer = fromJson?.toUser()
        callState.value = CallUiState(
            call = call, peer = peer, outgoing = false,
            status = "ringing", remoteSdp = sdp.ifBlank { null },
        )
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
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { rtc.handleRemoteIce(candidate) }
        }
    }

    private fun onCallEnded(data: JSONObject) {
        endCallLocal()
    }

    private fun endCallLocal() {
        runCatching { rtc.close() }
        callIceJob?.cancel()
        pendingIce.clear()
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
        chat.value = ChatUiState(conversation = conversation, loading = true)
        // siapkan kunci E2EE per-percakapan
        runCatching { e2ee.ensureKey(conversation.id) }
        viewModelScope.launch {
            try {
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
            } catch (e: Exception) {
                handleError(e)
                chat.value = chat.value.copy(
                    loading = false,
                    error = e.message ?: "Gagal memuat pesan.",
                )
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

        viewModelScope.launch {
            try {
                val toSend = e2ee.encrypt(conversation.id, body)
                val sent = repository.sendMessage(conversation.id, toSend, clientId, replyToId)
                val dec = e2ee.decryptMessage(sent)
                mergeSentMessage(clientId, dec)
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
                val current = chat.value
                chat.value = current.copy(
                    messages = current.messages.map {
                        if (it.id == message.id) it.copy(revoked = true, body = null, attachmentUrl = null) else it
                    }
                )
                refreshConversations()
            } catch (e: Exception) {
                handleError(e)
            }
        }
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
        loadNearby(force = true)
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
                nearby.value = NearbyState(users = users, loading = false, loaded = true)
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

    fun addComment(body: String) {
        val momentId = commentsMomentId.value ?: return
        if (body.trim().isEmpty()) return
        viewModelScope.launch {
            try {
                val comment = repository.addComment(momentId, body.trim())
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
