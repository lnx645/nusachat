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
import id.nusachat.app.data.toVoiceCall
import id.nusachat.app.data.Conversation
import id.nusachat.app.data.E2eeManager
import id.nusachat.app.data.FriendRequest
import id.nusachat.app.data.LocationHelper
import id.nusachat.app.data.Message
import id.nusachat.app.data.capChatWindow
import id.nusachat.app.data.mergeServerDelta
import id.nusachat.app.data.GroupJoinRequest
import id.nusachat.app.data.KeyHolder
import id.nusachat.app.data.Moment
import id.nusachat.app.data.MomentComment
import id.nusachat.app.data.PinnedMessage
import id.nusachat.app.data.Reaction
import id.nusachat.app.data.SessionCrypto
import id.nusachat.app.data.SessionCryptoException
import id.nusachat.app.ui.chats.components.renderSystemText
import id.nusachat.app.data.CallForegroundService
import id.nusachat.app.data.voice.NusaVoiceEngine
import id.nusachat.app.data.voice.NvpBase64
import id.nusachat.app.data.voice.NvpCallParams
import id.nusachat.app.data.voice.NvpCodec
import id.nusachat.app.data.voice.NvpCodecs
import id.nusachat.app.data.voice.NvpMarkers
import id.nusachat.app.data.voice.NvpOffer
import java.security.SecureRandom
import org.json.JSONArray
import id.nusachat.app.data.CallHistoryItem
import id.nusachat.app.data.MediaHelper
import id.nusachat.app.data.NetworkMonitor
import id.nusachat.app.data.PushManager
import id.nusachat.app.data.local.ChatCache
import id.nusachat.app.data.local.ChatDatabase
import id.nusachat.app.data.RedPacket
import id.nusachat.app.data.ReportItem
import id.nusachat.app.data.SessionItem
import id.nusachat.app.data.SessionStore
import id.nusachat.app.data.SettingsStore
import id.nusachat.app.data.StoryViewer
import id.nusachat.app.data.GifClient
import id.nusachat.app.data.User
import id.nusachat.app.data.VoiceCall
import id.nusachat.app.data.WalletRequestItem
import id.nusachat.app.data.WebRtcCallManager
import id.nusachat.app.data.toMessage
import id.nusachat.app.data.toUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Jumlah percakapan yang jendela pesannya ditahan di RAM untuk buka instan. */
private const val RAM_CHAT_CONVERSATIONS = 24
/** Percakapan teratas yang cache-nya dipanaskan setelah daftar chat dimuat. */
private const val RAM_PREFETCH_CONVERSATIONS = 12

data class SessionState(
    val user: User? = null,
    val checked: Boolean = false,
)

data class RequestsState(
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
)

/** Progres pemulihan chat device baru (ala WhatsApp): 0–100%. */
data class RestoreState(
    val done: Int,
    val total: Int,
    val label: String,
) {
    val fraction: Float = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val percent: Int = (fraction * 100).toInt()
}

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
    val initialUnread: Int = 0,
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
    val video: Boolean = false,
    val videoMuted: Boolean = false,
    // --- Jalur NVP/1 (voice tanpa WebRTC; docs/VOICE-PROTOCOL.md).
    val nvp: Boolean = false,
    val nvpCodecs: List<String>? = null,
    val nvpKeyB64: String? = null,
    val nvpSaltB64: String? = null,
    val nvpSlot: Int = -1,
    val nvpToken: Long = 0L,
    val nvpRelayPort: Int = 8092,
    val nvpCodec: String? = null,
)

data class GroupCallUiState(
    val room: id.nusachat.app.data.GroupCallRoom? = null,
    val conversationId: Long? = null,
    val title: String = "Panggilan grup",
    val status: String = "idle", // idle | ringing | active | ended
    val muted: Boolean = false,
    val speaker: Boolean = false,
    // --- Jalur NVP/1 (room selalu NVP; codec tetap amr-wb).
    val nvp: Boolean = false,
    val nvpSlot: Int = -1,
    val nvpToken: Long = 0L,
    val nvpRelayPort: Int = 8092,
    val nvpKeyB64: String? = null,
    val nvpSaltB64: String? = null,
    /** userId → slot (dari event room_joined). */
    val nvpSlots: Map<Long, Int> = emptyMap(),
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val session = SessionStore(application)
    private val api = ApiClient(BuildConfig.API_BASE_URL, session)
    private val repository = ChatRepository(application, api, session)
    private val calls = CallRepository(api)
    private val locationHelper = LocationHelper(application)
    private val chatThemeStore = ChatThemeStore(application)
    private val rtc = WebRtcCallManager(application.applicationContext)
    private val nvpEngine by lazy { NusaVoiceEngine(getApplication()) }
    private data class PendingNvpOffer(
        val peer: User,
        val codecs: List<String>,
        val keyB64: String,
        val saltB64: String,
    )
    private var pendingNvpOffer: PendingNvpOffer? = null
    private var nvpPreparedArrived = false
    private var nvpFallbackUsed = false
    private var nvpAnswerFallbackUsed = false
    private var nvpRoomFallbackUsed = false

    /** Host relay UDP = host server (WS/API sama mesin). */
    private fun relayHost(): String =
        runCatching { Uri.parse(BuildConfig.WS_URL).host }.getOrNull()
            ?: runCatching { Uri.parse(BuildConfig.API_BASE_URL).host }.getOrNull()
            ?: "127.0.0.1"
    val e2ee = E2eeManager(application.applicationContext)
    private val sessCrypto = SessionCrypto(application.applicationContext, repository, e2ee)
    val settings = SettingsStore(application)
    // Cache chat lokal terenkripsi + monitor koneksi (buka instan, baca offline).
    private val chatCache = ChatCache(application)
    private val networkMonitor = NetworkMonitor(application)
    val isOnline = networkMonitor.online

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

    // ------------------------------------------------------- state 0.2.0 ---
    val globalSearching = MutableStateFlow(false)
    val globalSearchResults = MutableStateFlow<List<Message>>(emptyList())
    val starredMessages = MutableStateFlow<List<Message>>(emptyList())
    val starredLoading = MutableStateFlow(false)
    val selectedMessages = MutableStateFlow<Set<Long>>(emptySet())
    val callHistory = MutableStateFlow<List<CallHistoryItem>>(emptyList())
    val callHistoryLoading = MutableStateFlow(false)
    val missedCalls = MutableStateFlow(0)
    val walletHasPin = MutableStateFlow(false)
    val walletIncoming = MutableStateFlow<List<WalletRequestItem>>(emptyList())
    val walletOutgoing = MutableStateFlow<List<WalletRequestItem>>(emptyList())
    val redPacket = MutableStateFlow<RedPacket?>(null)
    val redPacketLoading = MutableStateFlow(false)
    val sessions = MutableStateFlow<List<SessionItem>>(emptyList())
    val sessionsLoading = MutableStateFlow(false)
    val likers = MutableStateFlow<List<User>>(emptyList())
    val likersMomentId = MutableStateFlow<Long?>(null)
    val storyViewerCount = MutableStateFlow(0)
    val storyViewers = MutableStateFlow<List<StoryViewer>>(emptyList())
    val storyViewersId = MutableStateFlow<Long?>(null)
    val myReports = MutableStateFlow<List<ReportItem>>(emptyList())
    val downloading = MutableStateFlow<String?>(null)
    val gifResults = MutableStateFlow<List<GifClient.Gif>>(emptyList())
    val gifLoading = MutableStateFlow(false)

    private var socket: ChatSocket? = null
    private var activeChatId: Long? = null
    private var typingResetJob: Job? = null
    private var lastTypingSent = 0L
    private val readUpTo = mutableMapOf<Long, Long>()
    private val ackUpTo = mutableMapOf<Long, Long>()

    /**
     * Jendela pesan terakhir per percakapan di RAM. Dibaca SINKRON saat
     * [openChat] supaya pesan sudah ada di frame pertama, tanpa menunggu
     * Room (SQLCipher) atau jaringan. Isinya plaintext yang sama dengan cache.
     */
    private val chatRam = object : LinkedHashMap<Long, List<Message>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, List<Message>>?,
        ): Boolean = size > RAM_CHAT_CONVERSATIONS
    }

    init {
        networkMonitor.start()
        // Jendela RAM mengikuti setiap perubahan pesan chat aktif (kirim,
        // edit, hapus, delta) agar buka ulang tetap instan.
        viewModelScope.launch {
            var lastConv: Long? = null
            var lastMessages: List<Message> = emptyList()
            chat.collect { st ->
                val convId = st.conversation?.id ?: return@collect
                if (convId == lastConv && st.messages === lastMessages) return@collect
                lastConv = convId
                lastMessages = st.messages
                rememberChatRam(convId, st.messages)
            }
        }
        // Online kembali → kirim antre offline.
        viewModelScope.launch {
            var wasOnline = true
            networkMonitor.online.collect { online ->
                if (online && !wasOnline) flushOutbox()
                wasOnline = online
            }
        }
        // WS nyambung kembali → kejar pesan yang terlewat + penanda.
        viewModelScope.launch {
            var wasRealtime = false
            realtimeConnected.collect { connected ->
                if (connected && !wasRealtime) onRealtimeReconnect()
                wasRealtime = connected
            }
        }
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
            // Cabut device v2 + buang materi kripto lokal (sebelum sesi mati).
            runCatching { sessCrypto.revokeAndWipe() }
            runCatching { repository.logout() }
            stopRealtime()
            endCallLocal()
            resetLocalState()
            // Hapus cache chat agar tak tertinggal untuk akun berikutnya.
            runCatching { ChatDatabase.delete(getApplication()) }
            // Kunci backup milik akun ini: buang + matikan jadwal.
            runCatching { id.nusachat.app.data.BackupKeys(getApplication()).clear() }
            runCatching { settings.setBackupAuto(false) }
            runCatching { id.nusachat.app.data.BackupWorker.ensureScheduled(getApplication(), false) }
            localBackupPrompt.value = null
            localBackups.value = emptyList()
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
        chatRam.clear()
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
        ackUpTo.clear()
        globalSearchResults.value = emptyList()
        starredMessages.value = emptyList()
        selectedMessages.value = emptySet()
        callHistory.value = emptyList()
        missedCalls.value = 0
        walletIncoming.value = emptyList()
        walletOutgoing.value = emptyList()
        redPacket.value = null
        sessions.value = emptyList()
    }

    private fun loadInitial() {
        refreshConversations()
        loadFriends()
        loadRequests()
        refreshMoments()
        registerPush()
        publishDeviceKey()
        provisionV2()
        loadCallHistory()
        // Terapkan jadwal backup tersimpan + tawarkan restore file lokal bila install baru.
        id.nusachat.app.data.BackupWorker.ensureScheduled(getApplication(), settings.backupAuto.value)
        scanLocalBackups()
        checkLocalBackupPrompt()
        // Sapu bangkai placeholder peninggalan versi lama (kosong & fresh).
        viewModelScope.launch(Dispatchers.IO) { runCatching { chatCache.pruneDead() } }
        startRestoreIfNeeded()
        healSweep()
    }

    /**
     * Sapu heal saat launch: menutup event device_added yang terlewat saat
     * offline (lawan reinstall selagi aku mati → aku holder yang heal dia).
     * Restore (bila jalan) sudah mencakup ini → lewati.
     */
    private fun healSweep() {
        viewModelScope.launch(Dispatchers.IO) {
            delay(8000)
            if (_restore.value != null) return@launch
            if (sessionState.value.user == null || !isOnline.value) return@launch
            for (conv in conversations.value.take(10)) {
                runCatching { syncKeysNow(conv.id) }
            }
        }
    }

    private val _restore = MutableStateFlow<RestoreState?>(null)
    val restore = _restore.asStateFlow()
    private var restoreJob: Job? = null

    // --------------------------- backup lokal otomatis .updb (tanpa cloud) ---
    val backupWorking = MutableStateFlow(false)
    /** Kunci baru — tampilkan sekali, suruh catat (tersimpan terenkripsi di HP). */
    val backupNewKey = MutableStateFlow<String?>(null)
    val backupError = MutableStateFlow<String?>(null)
    /** File .updb di Download/NusaChat. */
    val localBackups = MutableStateFlow<List<id.nusachat.app.data.local.BackupFileEntry>>(emptyList())
    /** Prompt restore otomatis di install baru (file ditemukan, cache kosong). */
    val localBackupPrompt = MutableStateFlow<id.nusachat.app.data.local.BackupFileEntry?>(null)

    fun clearBackupNewKey() {
        backupNewKey.value = null
        backupError.value = null
    }

    /** Nyalakan/matikan backup harian. Menyalakan menyiapkan kunci + jadwal. */
    fun setBackupAuto(enabled: Boolean) {
        settings.setBackupAuto(enabled)
        id.nusachat.app.data.BackupWorker.ensureScheduled(getApplication(), enabled)
        if (enabled) {
            viewModelScope.launch(Dispatchers.IO) {
                val (secret, isNew) = runCatching {
                    id.nusachat.app.data.BackupKeys(getApplication()).getOrCreateDigits()
                }.getOrNull() ?: return@launch
                if (isNew) backupNewKey.value = secret.value
                scanLocalBackupsNow()
            }
        }
    }

    /** Kunci tersimpan (untuk tombol "Salin kunci"). Null bila belum ada. */
    fun storedBackupKey(): String? =
        runCatching { id.nusachat.app.data.BackupKeys(getApplication()).get()?.value }.getOrNull()

    fun scanLocalBackups() {
        viewModelScope.launch(Dispatchers.IO) { scanLocalBackupsNow() }
    }

    private suspend fun scanLocalBackupsNow() {
        val files = runCatching {
            id.nusachat.app.data.local.BackupFiles(getApplication()).list()
        }.getOrDefault(emptyList())
        localBackups.value = files
    }

    /**
     * Ganti kunci backup (mode digits/pass). Backup berikutnya memakai kunci
     * baru; file lama tetap butuh kunci lama. Kunci baru ditampilkan sekali.
     */
    fun setBackupKey(mode: String, secret: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val keys = id.nusachat.app.data.BackupKeys(getApplication())
                if (mode == "pass") keys.setPassphrase(secret.trim())
                else keys.setDigits(secret.trim())
                backupNewKey.value = secret.trim()
                withContext(Dispatchers.Main) {
                    message.value = "Kunci backup diperbarui. Catat baik-baik."
                }
            } catch (e: Exception) {
                backupError.value = e.message ?: "Kunci tidak valid."
                withContext(Dispatchers.Main) { handleError(e) }
            }
        }
    }

    /**
     * Cadangkan sekarang ke folder tetap Download/NusaChat (tanpa picker).
     * Pakai kunci tersimpan; buat digits baru + tampilkan bila belum ada.
     */
    fun backupNow() {
        if (backupWorking.value) return
        backupWorking.value = true
        backupError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (secret, isNew) = id.nusachat.app.data.BackupKeys(getApplication()).getOrCreateDigits()
                val msgs = runCatching { chatCache.exportAll() }.getOrDefault(emptyList())
                if (msgs.isEmpty()) throw IllegalStateException("Belum ada chat untuk dicadangkan.")
                val convMeta = if (conversations.value.isNotEmpty()) {
                    conversations.value.take(500).map {
                        id.nusachat.app.data.ChatBackup.BackupConv(it.id, it.displayTitle, it.type)
                    }
                } else {
                    msgs.groupBy { it.conversationId }.map { (convId, list) ->
                        val title = list.firstNotNullOfOrNull {
                            it.conversationTitle?.takeIf { t -> t.isNotBlank() }
                        } ?: "Chat"
                        id.nusachat.app.data.ChatBackup.BackupConv(convId, title, "direct")
                    }
                }
                val payload = id.nusachat.app.data.ChatBackup.Payload(
                    userId = myId(),
                    exportedAt = nowIso(),
                    conversations = convMeta,
                    messages = msgs,
                    e2eeKeys = e2ee.exportKeys(),
                )
                val blob = if (secret.mode == "pass") {
                    id.nusachat.app.data.ChatBackup.createBlob(payload, "pass", passphrase = secret.value)
                } else {
                    id.nusachat.app.data.ChatBackup.createBlob(payload, "digits", digitsInput = secret.value)
                }
                val files = id.nusachat.app.data.local.BackupFiles(getApplication())
                val bytes = blob.blobB64.toByteArray(Charsets.UTF_8)
                files.saveFixed(id.nusachat.app.data.local.BackupNames.LATEST, bytes)
                files.saveFixed(id.nusachat.app.data.local.BackupNames.stampedNow(), bytes)
                val stamped = files.list().map { it.name }
                    .filter { id.nusachat.app.data.local.BackupNames.isStamped(it) }
                val byName = files.list().associateBy { it.name }
                id.nusachat.app.data.local.BackupNames.prune(stamped)
                    .forEach { byName[it]?.let { e -> files.delete(e) } }
                if (isNew) backupNewKey.value = secret.value
                scanLocalBackupsNow()
                withContext(Dispatchers.Main) {
                    message.value = "Backup ${blob.msgCount} pesan tersimpan di Download/NusaChat."
                }
            } catch (e: Exception) {
                backupError.value = e.message ?: "Backup gagal."
                withContext(Dispatchers.Main) { handleError(e) }
            } finally {
                backupWorking.value = false
            }
        }
    }

    /**
     * Pulihkan dari isi file .updb + kunci. Menimpa cache per id (pesan baru
     * yang belum ada di backup tidak tertimpa).
     */
    fun restoreLocalBlob(blobB64: String, keyInput: String, onDone: (restored: Int) -> Unit = {}) {
        if (backupWorking.value) return
        val key = keyInput.trim()
        if (key.isEmpty()) {
            backupError.value = "Masukkan kunci 64 digit atau passphrase."
            onDone(0)
            return
        }
        backupWorking.value = true
        backupError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val payload = id.nusachat.app.data.ChatBackup.openBlob(blobB64.trim(), key)
                runCatching { e2ee.importKeys(payload.e2eeKeys) }
                val n = runCatching { chatCache.importAll(payload.messages) }.getOrDefault(0)
                if (n == 0) throw IllegalStateException("Backup kosong atau tidak cocok.")
                withContext(Dispatchers.Main) {
                    message.value = "Dipulihkan $n pesan dari file."
                    refreshConversations()
                    onDone(n)
                }
            } catch (e: Exception) {
                backupError.value = e.message ?: "Restore gagal."
                withContext(Dispatchers.Main) { handleError(e) }
                withContext(Dispatchers.Main) { onDone(0) }
            } finally {
                backupWorking.value = false
            }
        }
    }

    /** Pulihkan dari entri file folder tetap. */
    fun restoreLocalEntry(
        entry: id.nusachat.app.data.local.BackupFileEntry,
        keyInput: String,
        onDone: (restored: Int) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching {
                id.nusachat.app.data.local.BackupFiles(getApplication()).read(entry)
            }.getOrElse {
                withContext(Dispatchers.Main) { handleError(it) }
                withContext(Dispatchers.Main) { onDone(0) }
                return@launch
            }
            restoreLocalBlob(text, keyInput, onDone)
        }
    }

    fun dismissBackupPrompt() {
        localBackupPrompt.value = null
    }

    /** Salin file backup terbaru ke lokasi pilihan (SAF) — untuk pindah HP / Drive. */
    fun exportLatestCopyTo(uri: android.net.Uri, onDone: (ok: Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val files = id.nusachat.app.data.local.BackupFiles(getApplication())
                val latest = files.list().firstOrNull { it.name == id.nusachat.app.data.local.BackupNames.LATEST }
                    ?: files.list().firstOrNull()
                    ?: throw IllegalStateException("Belum ada file backup. Cadangkan dulu.")
                val text = files.read(latest)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Tidak bisa menulis ke lokasi itu.")
                withContext(Dispatchers.Main) {
                    message.value = "Salinan ${latest.name} tersimpan."
                    onDone(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { handleError(e) }
                withContext(Dispatchers.Main) { onDone(false) }
            }
        }
    }

    /**
     * Install baru + cache kosong + ada file .updb valid di folder tetap →
     * tawarkan restore otomatis (sekali per install). Dipanggil tiap login.
     */
    private fun checkLocalBackupPrompt() {
        viewModelScope.launch(Dispatchers.IO) {
            if (runCatching { chatCache.totalCount() }.getOrDefault(-1) != 0) return@launch
            if (settings.backupPromptShown()) return@launch
            val files = runCatching {
                id.nusachat.app.data.local.BackupFiles(getApplication()).list()
            }.getOrDefault(emptyList())
            localBackups.value = files
            val latest = files.firstOrNull { it.name == id.nusachat.app.data.local.BackupNames.LATEST }
                ?: files.firstOrNull() ?: return@launch
            val text = runCatching {
                id.nusachat.app.data.local.BackupFiles(getApplication()).read(latest)
            }.getOrDefault("").trim()
            if (text.isEmpty()) return@launch
            val (msgCount, _, _) = id.nusachat.app.data.ChatBackup.peekInfo(text)
            if (msgCount <= 0) return@launch
            settings.markBackupPromptShown()
            localBackupPrompt.value = latest
        }
    }

    /** Lewati pemulihan (lanjut tanpa cache; tiap chat sync saat dibuka). */
    fun skipRestore() {
        restoreJob?.cancel()
        restoreJob = null
        _restore.value = null
    }

    /**
     * Device baru + server punya riwayat + cache kosong (habis reinstall):
     * pulihkan semua percakapan berprogres (kunci → pesan → cache).
     * No-op bila cache sudah terisi (buka aplikasi biasa).
     */
    private fun startRestoreIfNeeded() {
        if (restoreJob?.isActive == true || _restore.value != null) return
        restoreJob = viewModelScope.launch(Dispatchers.IO) {
            if (!isOnline.value) return@launch
            var waits = 0
            while (conversations.value.isEmpty() && waits++ < 30 && isOnline.value) delay(500)
            val convs = conversations.value.take(50)
            if (convs.isEmpty()) return@launch
            if (runCatching { chatCache.totalCount() }.getOrDefault(-1) != 0) return@launch
            _restore.value = RestoreState(0, convs.size, "Menyiapkan kunci keamanan…")
            convs.forEachIndexed { i, conv ->
                ensureActive()
                runCatching { syncKeysNow(conv.id) }
                val page = runCatching { repository.messages(conv.id, limit = 40) }.getOrNull()
                if (page != null) {
                    val dec = decAll(page.messages)
                    runCatching { chatCache.saveMessages(dec) }
                    val maxId = dec.filter { it.id > 0 }.maxOfOrNull { it.id } ?: 0L
                    runCatching { chatCache.saveCursor(conv.id, maxId) }
                }
                _restore.value = RestoreState(i + 1, convs.size, conv.displayTitle)
            }
            _restore.value = null
            refreshConversations()
        }
    }

    private fun publishDeviceKey() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.publishDeviceKey(e2ee.deviceId(), e2ee.publicKeyB64())
            }
        }
    }

    /** Provision E2EE v2 per-device (registrasi + prekey + rotasi/isi ulang). */
    private fun provisionV2() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sessCrypto.provision() }
        }
    }

    private fun myId(): Long = session.user?.id ?: 0L

    /** Dekrip satu pesan jaringan; null = SKDM (sembunyikan). */
    private fun dec(msg: Message): Message? = sessCrypto.decryptOne(msg, myId())

    /** Permanen tak-terbaca (habis install ulang): buang, tampil kosong & fresh. */
    private fun isDeadMessage(m: Message): Boolean =
        m.type == "text" && E2eeManager.isDeadBody(m.body)

    /** Dekrip daftar jaringan (SKDM dibuang). */
    private fun decAll(list: List<Message>): List<Message> = sessCrypto.processIncoming(list, myId())

    /**
     * Id aman untuk cursor/ack: berhenti SEBELUM placeholder mati pertama.
     * Placeholder tidak masuk cache; kalau cursor/ack melewatinya, server
     * menyapu ciphertext yang belum pernah terbaca → pesan hilang permanen.
     */
    private fun safeSyncId(messages: List<Message>, maxId: Long): Long {
        val firstDead = messages.asSequence()
            .filter { it.id > 0 && isDeadMessage(it) }
            .minOfOrNull { it.id } ?: return maxId
        return minOf(maxId, firstDead - 1)
    }

    /** Ingat plaintext terbaca agar gema sendiri (selfEcho) bisa resolved. */
    private fun rememberReadable(messages: List<Message>) {
        for (m in messages) {
            if (m.id > 0 && m.type == "text" && m.body != null &&
                E2eeManager.isReadableBody(m.body)
            ) {
                sessCrypto.rememberPlain(m.id, m.body!!)
            }
        }
    }

    /**
     * Tambal placeholder milik sendiri ("Pesan terkirim." + ikon kunci) dengan
     * plaintext yang sudah diketahui (RAM/cache/pending). Tanpa ini pesan
     * sendiri tampil terkunci setelah restart / memori 500 habis.
     */
    private fun healOwnPlaceholders(
        list: List<Message>,
        known: Map<Long, Message> = emptyMap(),
    ): List<Message> {
        if (list.isEmpty()) return list
        val me = myId()
        if (me <= 0) return list
        var patched = false
        val out = list.map { m ->
            if (m.type == "text" && isDeadMessage(m) && m.sender?.id == me) {
                val local = known[m.id]?.body
                    ?.takeIf { E2eeManager.isReadableBody(it) }
                    ?: sessCrypto.previewPlaintext(m, me)?.takeIf { E2eeManager.isReadableBody(it) }
                if (local != null) {
                    patched = true
                    sessCrypto.rememberPlain(m.id, local)
                    m.copy(body = local)
                } else m
            } else m
        }
        if (patched) rememberReadable(out)
        return out
    }

    /**
     * Pesan yang bisa ditampilkan SINKRON saat chat dibuka: jendela RAM
     * (riwayat yang sudah pernah dibaca) + pesan terakhir dari daftar chat.
     * Tidak menyentuh Room/jaringan supaya frame pertama tidak kosong.
     */
    private fun lastMessageSeed(conversation: Conversation): Message? =
        conversation.lastMessage?.takeIf { m ->
            m.id > 0 && !isDeadMessage(m) &&
                (m.type != "text" || (m.body != null && E2eeManager.isReadableBody(m.body)))
        }

    private fun seedMessagesFor(conversation: Conversation): List<Message> {
        val ram = chatRam[conversation.id].orEmpty()
        val last = lastMessageSeed(conversation)
        val base = if (last != null) {
            (ram + last).distinctBy { it.id }.sortedBy { it.id }
        } else {
            ram
        }
        return base.capChatWindow()
    }

    /** Simpan/ gabung jendela pesan RAM; placeholder mati tidak ikut. */
    private fun rememberChatRam(conversationId: Long, messages: List<Message>) {
        if (conversationId <= 0 || messages.isEmpty()) return
        val merged = (chatRam[conversationId].orEmpty() + messages)
            .filterNot { isDeadMessage(it) }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .capChatWindow()
        if (merged.isNotEmpty()) chatRam[conversationId] = merged
    }

    /**
     * Panaskan RAM dari cache lokal untuk percakapan teratas, sehingga saat
     * diketuk riwayatnya sudah siap tanpa query Room di jalur buka chat.
     * Hanya baca cache (tanpa jaringan/kunci), jadi aman dipanggil sering.
     */
    private fun warmChatRam(list: List<Conversation>) {
        val targets = list.asSequence()
            .filter { it.id != activeChatId }
            .filter { conv ->
                val ram = chatRam[conv.id]
                val ramLast = ram?.lastOrNull()?.id ?: 0L
                val lastId = conv.lastMessage?.id ?: 0L
                ram == null || lastId > ramLast
            }
            .take(RAM_PREFETCH_CONVERSATIONS)
            .toList()
        if (targets.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (conv in targets) {
                val recent = runCatching { chatCache.recent(conv.id) }.getOrDefault(emptyList())
                if (recent.isEmpty()) continue
                withContext(Dispatchers.Main.immediate) { rememberChatRam(conv.id, recent) }
            }
        }
    }

    /**
     * Isi pesan untuk pratinjau (daftar chat, pin) TANPA mendekrip ulang
     * ciphertext yang kunci ratchet-nya sudah terpakai. Urutan: RAM → cache
     * → dekrip kering. Null = tak terbaca; pemanggil jangan tampilkan
     * placeholder "tidak tersedia".
     */
    private suspend fun previewMessageFor(message: Message, cached: Message? = null): Message? {
        if (message.type != "text") return message
        val fromRam = if (message.id > 0) {
            chat.value.messages.firstOrNull { it.id == message.id }?.body
        } else null
        val local = sequenceOf(fromRam, cached?.body)
            .filterNotNull()
            .firstOrNull { it.isNotBlank() && E2eeManager.isReadableBody(it) }
        if (local != null) return message.copy(body = local)
        val dry = sessCrypto.previewPlaintext(message, myId()) ?: return null
        if (!E2eeManager.isReadableBody(dry)) return null
        return message.copy(body = dry)
    }

    /** Enkripsi teks untuk percakapan (v2, fallback legacy). */
    private suspend fun encryptFor(conversationId: Long, plain: String): String {
        val me = session.user?.id ?: return e2ee.encrypt(conversationId, plain)
        val conv = conversations.value.firstOrNull { it.id == conversationId }
            ?: runCatching { repository.conversationDetail(conversationId) }.getOrNull()
            ?: return e2ee.encrypt(conversationId, plain)
        return sessCrypto.encryptText(conv, me, plain)
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
        if (haveKey) {
            // Kunci untuk mendekrip chat ini sudah ada. Heal perangkat lain
            // hanya perlu untuk penerima lain — jangan blokir buka chat
            // dengan beberapa request berurutan.
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { healMissingKeys(conversationId, keys.devices, includeSelf = true) }
            }
            return
        }
        if (keys.devices.isEmpty()) {
            // Belum ada kunci kanonis sama sekali: tetapkan kunci lokal untuk semua.
            e2ee.ensureKey(conversationId)
            healMissingKeys(conversationId, keys.devices, includeSelf = true)
            return
        }
        // Keyless + blob ada: bungkus self-heal MUSTAHIL (tak punya kunci) —
        // jangan buang request heal; putuskan menunggu vs re-key.
        if (anyFreshBlob(keys.devices)) return // holder hidup mungkin ada; tunggu keys_updated
        // SEMUA blob basi = tak ada holder hidup (basi ⟺ pemilik reinstall =
        // kunci ikut hilang). Aman re-key, TAPI hanya userId terkecil agar
        // dua pihak keyless tak re-key bersamaan (K2 ganda = deadlock).
        val members = rekeyMemberIds(conversationId, me)
        if (E2eeManager.shouldRekey(me, members)) {
            e2ee.ensureKey(conversationId)
            healMissingKeys(conversationId, keys.devices, includeSelf = true)
        }
    }

    /**
     * True bila ada satu blob yang fingerprint-nya cocok pubkey pemilik saat
     * ini (pemilik mungkin masih memegang kunci kanonis → tunggu heal-nya).
     * Blob device-kosong (client lama) DIABAIKAN: lookup server strict
     * per-device sehingga tak bisa dibuka siapa pun (dead weight).
     */
    private suspend fun anyFreshBlob(holders: List<KeyHolder>): Boolean {
        val me = sessionState.value.user?.id ?: session.user?.id ?: 0L
        val myDevice = e2ee.deviceId()
        val now = System.currentTimeMillis()
        val fpNow = mutableMapOf<Pair<Long, String>, String?>()
        for (userId in holders.map { it.userId }.distinct()) {
            val devices = runCatching { repository.deviceKeys(userId) }.getOrDefault(emptyList())
            for (dev in devices) {
                // Device sendiri yang bukan saat ini: abaikan (tak ada
                // self-heal lintas-device; menunggu = deadlock).
                if (userId == me && dev.deviceId != myDevice) continue
                // Publish >60 hari: ganti HP / mati = holder mati.
                if (!E2eeManager.isFreshDevice(dev.updatedAt, now)) continue
                fpNow[userId to dev.deviceId] = e2ee.fingerprint(dev.publicKey)
            }
        }
        return E2eeManager.anyFreshHolder(holders) { h -> fpNow[h.userId to h.deviceId] }
    }

    private suspend fun rekeyMemberIds(conversationId: Long, me: Long): List<Long> {
        val detail = runCatching { repository.conversationDetail(conversationId) }.getOrNull()
        val ids = detail?.members?.map { it.id }.orEmpty()
        if (ids.isNotEmpty()) return ids.distinct()
        val peerId = detail?.peer?.id ?: 0L
        return listOfNotNull(me, peerId.takeIf { it > 0 })
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
     * bungkus ulang kunci sesi untuk SEMUA percakapan bersamanya (bukan
     * cuma chat terbuka) agar pemulihan reinstall otomatis tanpa lawan
     * harus membuka tiap chat satu per satu.
     */
    private fun onDeviceAdded(userId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val shared = conversations.value.filter { conv ->
                conv.peer?.id == userId || conv.members.any { it.id == userId }
            }.take(20)
            val open = (chat.value.detail ?: chat.value.conversation)
                ?.takeIf { it.id == activeChatId }
                ?.takeIf { shared.none { s -> s.id == it.id } }
            for (conv in shared + listOfNotNull(open)) {
                runCatching { syncKeysNow(conv.id) }
            }
            val active = chat.value.conversation?.takeIf { it.id == activeChatId }
            if (active != null &&
                (active.peer?.id == userId || active.members.any { it.id == userId })
            ) {
                retryPlaceholders(active.id)
            }
        }
    }

    fun periodicSync() {
        if (sessionState.value.user == null) return
        refreshConversations()
        loadFriends()
        loadRequests()
    }

    /**
     * Coba lagi placeholder tak-terdekrip dengan fetch ulang dari server.
     * Dipanggil saat kunci berubah (device_added / keys_updated): tanpa
     * ini placeholder diam selamanya karena kursor sudah lewat.
     */
    private fun retryPlaceholders(conversationId: Long) {
        if (conversationId != activeChatId) return
        val stale = chat.value.messages
            .filter { isDeadMessage(it) && it.id > 0 }
            .takeLast(100)
        if (stale.isEmpty()) return
        val fromId = stale.minOf { it.id } - 1
        viewModelScope.launch {
            val page = runCatching {
                repository.messages(conversationId, afterId = fromId, limit = 100)
            }.getOrNull() ?: return@launch
            val st = chat.value
            if (st.conversation?.id != conversationId) return@launch
            val fresh = decAll(page.messages)
            if (fresh.isEmpty()) return@launch
            runCatching { chatCache.saveMessages(fresh) }
            val keepPending = st.messages.filter { it.pending }
            val preCap = mergeServerDelta(
                st.messages.filterNot { it.pending }, fresh, keepPending,
            ) { existing, incoming -> ChatCache.shouldKeepExisting(existing, incoming) }
            val merged = preCap.capChatWindow()
            chat.value = st.copy(messages = merged)
            // Bila placeholder pulih, cursor/ack boleh maju lagi.
            val maxId = merged.filter { it.id > 0 }.maxOfOrNull { it.id } ?: 0L
            val syncId = safeSyncId(merged, maxId)
            if (syncId > 0) {
                runCatching { chatCache.saveCursor(conversationId, syncId) }
                ackInternal(conversationId, syncId)
            }
        }
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
                // Placeholder tak-terdekrip dipertahankan (jangan dibuang):
                // pengguna harus tahu ada pesan + bisa dicoba lagi saat
                // kunci berubah. SKDM (null) tetap gugur.
                val message = dec(raw) ?: return
                incomingMessage(conversationId, message)
            }
            "read" -> {
                val conversationId = data.optLong("conversation_id")
                val readerId = data.optLong("user_id")
                val messageId = data.optLong("message_id")
                // Server baru mengirim fully_read (biru = dibaca SEMUA);
                // null = server lama: birukan semua seperti dulu.
                val fullyRead: Set<Long>? = data.optJSONArray("fully_read")?.let { arr ->
                    buildSet { for (i in 0 until arr.length()) add(arr.optLong(i)) }
                }
                if (conversationId == activeChatId && readerId != session.user?.id) {
                    val state = chat.value
                    chat.value = state.copy(
                        messages = state.messages.map {
                            if (it.sender?.id == session.user?.id && it.id > 0 && it.id <= messageId) {
                                when {
                                    fullyRead == null || it.id in fullyRead -> it.copy(status = "read")
                                    it.status == "sent" -> it.copy(status = "delivered")
                                    else -> it
                                }
                            } else it
                        }
                    )
                }
                if (conversationId > 0 && messageId > 0 && readerId != session.user?.id) {
                    persistReadUpTo(conversationId, messageId, fullyRead)
                }
            }
            "ack" -> {
                val conversationId = data.optLong("conversation_id")
                val ackerId = data.optLong("user_id")
                val messageId = data.optLong("message_id")
                if (conversationId == activeChatId && ackerId != session.user?.id) {
                    val state = chat.value
                    chat.value = state.copy(
                        messages = state.messages.map {
                            // Naik saja: sent→delivered; read tak pernah turun.
                            if (it.sender?.id == session.user?.id && it.id <= messageId && it.status == "sent") {
                                it.copy(status = "delivered")
                            } else it
                        }
                    )
                }
                if (conversationId > 0 && messageId > 0 && ackerId != session.user?.id) {
                    persistDeliveredUpTo(conversationId, messageId)
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
                val message = dec(raw) ?: return
                if (message.id > 0) {
                    viewModelScope.launch { runCatching { chatCache.saveMessage(message) } }
                }
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
                    state.messages.firstOrNull { it.id == messageId }?.let { original ->
                        viewModelScope.launch {
                            runCatching { chatCache.saveMessage(original.copy(reactions = reactions)) }
                        }
                    }
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
            "wallet_requested" -> {
                loadWalletRequests()
                message.value = "Tagihan koin masuk."
            }
            "wallet_request_paid" -> {
                loadWallet()
                loadWalletRequests()
                message.value = "Tagihan ${data.optLong("amount")} koin dibayar."
            }
            "wallet_request_declined" -> loadWalletRequests()
            "redpacket_claimed" -> {
                val packetId = data.optLong("packet_id")
                if (packetId > 0 && redPacket.value?.id == packetId) {
                    loadRedPacket(packetId)
                }
                loadWallet()
            }
            "story_viewed" -> {
                val storyId = data.optLong("story_id")
                if (storyId > 0 && storyViewersId.value == storyId) {
                    loadStoryViewers(storyId)
                }
                stories.value = stories.value.map {
                    if (it.id == storyId) it.copy(viewCount = it.viewCount + 1) else it
                }
            }
            "keys_updated" -> onKeysUpdated(data)
            "presence" -> {
                val userId = data.optLong("user_id")
                // Payload lama tak punya `online' dan selalu berarti online.
                val online = if (data.has("online")) data.optBoolean("online") else true
                val lastSeen = data.optString("last_seen_at").ifBlank { null }
                if (userId > 0) onPresenceChanged(userId, online, lastSeen)
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
            "call.prepared" -> onNvpPrepared(data)
            "call.peer_offline" -> {
                if (callState.value.nvp && callState.value.status == "dialing") nvpFallbackOffer()
            }
            "call.error" -> onNvpError(data)
            "call_answer" -> onCallAnswer(data)
            "call_ice" -> onCallIce(data)
            "call_end" -> onCallEnded(data)
            "call_room_offer" -> onRoomOffer(data)
            "call_room_signal" -> onRoomSignal(data)
            "call_room_joined", "call_room_left" -> {
                val callId = data.optJSONObject("call")?.optLong("id") ?: data.optLong("call_id")
                if (callId > 0) refreshGroupRoom(callId)
                onNvpRoomMembership(type, data, callId)
            }
            "call_room_ended" -> onRoomEnded(data)
        }
    }

    // ------------------------------------------------------------ voice call

    private val pendingIce = mutableListOf<JSONObject>()
    private var callIceJob: Job? = null
    private var callFocusRequest: android.media.AudioFocusRequest? = null

    // ------------------------------------------------------------ NVP/1 voice (tanpa WebRTC)

    fun startVoiceCall(peer: User) {
        // 1-1 voice full WebRTC audio (NVP dinonaktifkan untuk 1-1;
        // engine NVP dipertahankan hanya untuk group call).
        startCall(peer, "audio")
    }

    /** Fallback REST offer (WS mati / callee offline): sdp = kurir nvp1:. */
    private fun nvpFallbackOffer() {
        val pending = pendingNvpOffer ?: return
        if (nvpFallbackUsed) return
        nvpFallbackUsed = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sdp = NvpMarkers.offerToSdp(pending.codecs, pending.keyB64, pending.saltB64)
                val created = calls.offer(pending.peer.id, sdp, "audio")
                val creds = calls.nvpJoin(created.id)
                withContext(Dispatchers.Main) {
                    if (callState.value.status != "dialing") return@withContext
                    callState.value = callState.value.copy(
                        call = created,
                        nvpSlot = creds.slot, nvpToken = creds.token,
                        nvpRelayPort = creds.relayPort,
                    )
                    nvpPreparedArrived = true
                    maybeStartNvpEngine()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callState.value = CallUiState()
                    handleError(e)
                }
            }
        }
    }

    private fun onNvpPrepared(data: JSONObject) {
        val callJson = data.optJSONObject("call")
        val callId = callJson?.optLong("id") ?: data.optLong("call_id")
        if (callId <= 0) return
        val slot = data.optInt("slot", -1)
        val token = data.optLong("token")
        val relayPort = data.optInt("relay_port", 8092)
        val isRoom = callJson != null && callJson.has("conversation_id") && !callJson.isNull("conversation_id")
        if (isRoom) {
            onNvpRoomPrepared(
                callId, slot, token, relayPort,
                data.optString("key_ct").ifBlank { null },
                data.optString("salt").ifBlank { null },
            )
            return
        }
        if (nvpFallbackUsed) {
            // Balapan fallback menang duluan: tutup baris WS yatim.
            socket?.send("call.end", JSONObject().put("call_id", callId).put("reason", "hangup"))
            return
        }
        nvpPreparedArrived = true
        val current = callState.value
        if (!current.nvp || current.status !in listOf("dialing", "ringing", "active")) return
        val call = runCatching { callJson?.toVoiceCall() }.getOrNull() ?: current.call
        callState.value = current.copy(
            call = call, nvpSlot = slot, nvpToken = token, nvpRelayPort = relayPort,
        )
        // Callee: prepared = jawaban diproses → nyalakan engine.
        // Caller: tetap dialing sampai call.answer.
        if (!current.outgoing) maybeStartNvpEngine()
    }

    private fun onNvpError(data: JSONObject) {
        val msg = data.optString("message", "Panggilan gagal.").ifBlank { "Panggilan gagal." }
        message.value = msg
        if (callState.value.nvp && callState.value.status in listOf("dialing", "ringing")) {
            endCallLocal()
        }
        if (groupCall.value.nvp && groupCall.value.status != "idle") {
            leaveGroupCall()
        }
    }

    /** Nyalakan engine bila (call, slot, codec, kunci) lengkap. Idempoten. */
    private fun maybeStartNvpEngine() {
        val s = callState.value
        if (!s.nvp || s.status !in listOf("dialing", "ringing", "active")) return
        val codec = s.nvpCodec ?: return
        val key = NvpBase64.decode(s.nvpKeyB64 ?: return) ?: return
        val salt = NvpBase64.decode(s.nvpSaltB64 ?: return) ?: return
        val callId = s.call?.id ?: return
        if (s.nvpSlot < 0 || s.nvpToken == 0L || key.size != 32 || salt.size != 4) return
        if (nvpEngine.isRunning()) return
        nvpEngine.start(
            NvpCallParams(callId, s.nvpSlot, s.nvpToken, relayHost(), s.nvpRelayPort, key, salt, codec),
        )
        nvpEngine.setMuted(s.muted)
        nvpEngine.setSpeakerphone(s.speaker)
        markCallActive()
    }

    private fun answerNvpCall() {
        val current = callState.value
        val call = current.call ?: return
        val offerCodecs = current.nvpCodecs ?: return
        val codec = NvpCodec.negotiate(offerCodecs, NvpCodecs.probeEncoders())
        if (codec == null) {
            message.value = "Codec tidak cocok dengan penelepon."
            rejectVoiceCall()
            return
        }
        callState.value = current.copy(nvpCodec = codec)
        nvpAnswerFallbackUsed = false
        val sent = socket?.send(
            "call.answer",
            JSONObject().put("call_id", call.id).put("accept", true).put("codec", codec),
        ) == true
        if (!sent) {
            answerNvpFallback(call.id, codec)
            return
        }
        // WS path: engine nyala saat call.prepared tiba; timeout → REST.
        viewModelScope.launch {
            delay(8000)
            if (!nvpEngine.isRunning() && !nvpAnswerFallbackUsed &&
                callState.value.status == "ringing" && callState.value.nvp
            ) {
                answerNvpFallback(call.id, codec)
            }
        }
    }

    private fun answerNvpFallback(callId: Long, codec: String) {
        if (nvpAnswerFallbackUsed) return
        nvpAnswerFallbackUsed = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                calls.answer(callId, NvpMarkers.answerToSdp(codec))
                val creds = calls.nvpJoin(callId)
                withContext(Dispatchers.Main) {
                    callState.value = callState.value.copy(
                        nvpSlot = creds.slot, nvpToken = creds.token,
                        nvpRelayPort = creds.relayPort,
                    )
                    maybeStartNvpEngine()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { handleError(e); endCallLocal() }
            }
        }
    }

    fun startVideoCall(peer: User) = startCall(peer, "video")

    fun rtcManager(): WebRtcCallManager = rtc

    fun toggleVideoMute() {
        val muted = !callState.value.videoMuted
        rtc.setVideoMuted(muted)
        callState.value = callState.value.copy(videoMuted = muted)
    }

    fun switchCallCamera() = rtc.switchCamera()

    private fun startCall(peer: User, kind: String) {
        if (callState.value.status in listOf("dialing", "active", "ringing")) {
            message.value = "Sudah ada panggilan aktif."
            return
        }
        if (groupCall.value.status == "active") {
            message.value = "Sudah ada panggilan grup aktif."
            return
        }
        if (!rtc.isSupported()) {
            message.value = "Panggilan tidak didukung di perangkat ini."
            return
        }
        rtc.videoEnabled = kind == "video"
        callState.value = CallUiState(call = null, peer = peer, outgoing = true, status = "dialing", video = kind == "video")
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
                val created = calls.offer(peer.id, offer.description, kind)
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
        if (callState.value.nvp) {
            answerNvpCall()
            return
        }
        val current = callState.value
        val call = current.call ?: return
        val sdp = current.remoteSdp ?: return
        if (!rtc.isSupported()) {
            message.value = "Panggilan tidak didukung di perangkat ini."
            endCallLocal()
            return
        }
        rtc.videoEnabled = call.kind == "video"
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
        if (callState.value.nvp) {
            socket?.send("call.reject", JSONObject().put("call_id", call.id))
        }
        viewModelScope.launch {
            runCatching { calls.reject(call.id) }
            endCallLocal()
        }
    }

    fun endVoiceCall() {
        val call = callState.value.call
        if (callState.value.nvp && call != null) {
            socket?.send("call.end", JSONObject().put("call_id", call.id).put("reason", "hangup"))
        }
        viewModelScope.launch {
            if (call != null) runCatching { calls.end(call.id) }
            withContext(Dispatchers.Main) { endCallLocal() }
        }
    }

    fun toggleCallMute() {
        val muted = !callState.value.muted
        if (callState.value.nvp) nvpEngine.setMuted(muted) else rtc.setMuted(muted)
        callState.value = callState.value.copy(muted = muted)
    }

    fun toggleCallSpeaker() {
        val app = getApplication<Application>()
        val audio = app.getSystemService(Application.AUDIO_SERVICE) as? AudioManager ?: return
        val speaker = !callState.value.speaker
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = speaker
        if (callState.value.nvp) nvpEngine.setSpeakerphone(speaker)
        callState.value = callState.value.copy(speaker = speaker)
    }

    // ------------------------------------------------------------ group call

    // ------------------------------------------------------------ NVP/1 room (codec tetap amr-wb)

    fun startGroupCall(conversationId: Long) {
        if (callState.value.status in listOf("dialing", "active", "ringing")) {
            message.value = "Sudah ada panggilan aktif."
            return
        }
        if (groupCall.value.status == "active") return
        if (NvpCodecs.probeEncoders().isEmpty()) {
            message.value = "Panggilan grup tidak didukung di perangkat ini."
            return
        }
        val title = conversations.value.firstOrNull { it.id == conversationId }?.displayTitle
            ?: "Panggilan grup"
        viewModelScope.launch {
            try {
                val room = calls.roomCreate(conversationId)
                groupCall.value = GroupCallUiState(
                    room = room, conversationId = conversationId,
                    title = title, status = "active", nvp = true,
                )
                nvpRoomJoin(room.call.id)
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun acceptGroupCall() {
        val pending = groupCall.value
        val roomId = pending.room?.call?.id ?: return
        groupCall.value = pending.copy(nvp = true)
        nvpRoomFallbackUsed = false
        val sent = socket?.send("call.room_join", JSONObject().put("call_id", roomId)) == true
        if (sent) return // engine nyala saat call.prepared tiba
        nvpRoomJoin(roomId)
    }

    /** Gabung room NVP: WS-first, fallback REST (roomJoin + nvpJoin). */
    private fun nvpRoomJoin(callId: Long) {
        val sent = socket?.send("call.room_join", JSONObject().put("call_id", callId)) == true
        if (sent) {
            // Server lama/WS putus: tak ada prepared → fallback REST.
            viewModelScope.launch {
                delay(8000)
                val g = groupCall.value
                if (g.nvp && g.room?.call?.id == callId && g.nvpSlot < 0 && !nvpRoomFallbackUsed) {
                    nvpRoomJoinRest(callId)
                }
            }
            return
        }
        nvpRoomJoinRest(callId)
    }

    private fun nvpRoomJoinRest(callId: Long) {
        if (nvpRoomFallbackUsed) return
        nvpRoomFallbackUsed = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val room = calls.roomJoin(callId)
                val creds = calls.nvpJoin(callId)
                withContext(Dispatchers.Main) {
                    groupCall.value = groupCall.value.copy(room = room)
                    onNvpRoomPrepared(
                        callId, creds.slot, creds.token, creds.relayPort,
                        creds.keyCt, creds.salt,
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    handleError(e)
                    groupCall.value = GroupCallUiState()
                }
            }
        }
    }

    private fun onNvpRoomPrepared(
        callId: Long,
        slot: Int,
        token: Long,
        relayPort: Int,
        keyB64: String?,
        saltB64: String?,
    ) {
        val current = groupCall.value
        if (!current.nvp || current.room?.call?.id != callId) return
        if (keyB64 == null || saltB64 == null || slot < 0 || token == 0L) return
        groupCall.value = current.copy(
            nvpSlot = slot, nvpToken = token, nvpRelayPort = relayPort,
            nvpKeyB64 = keyB64, nvpSaltB64 = saltB64, status = "active",
        )
        startNvpRoomEngine()
    }

    private fun startNvpRoomEngine() {
        val g = groupCall.value
        val key = NvpBase64.decode(g.nvpKeyB64 ?: return) ?: return
        val salt = NvpBase64.decode(g.nvpSaltB64 ?: return) ?: return
        val callId = g.room?.call?.id ?: return
        if (g.nvpSlot < 0 || g.nvpToken == 0L || key.size != 32 || salt.size != 4) return
        if (nvpEngine.isRunning()) return
        nvpEngine.start(
            NvpCallParams(
                callId, g.nvpSlot, g.nvpToken, relayHost(), g.nvpRelayPort,
                key, salt, id.nusachat.app.data.voice.Nvp.CODEC_AMR_WB,
            ),
        )
        nvpEngine.setMuted(g.muted)
        nvpEngine.setSpeakerphone(g.speaker)
    }

    fun declineGroupCall() {
        runCatching { nvpEngine.stop() }
        groupCall.value = GroupCallUiState()
    }

    fun leaveGroupCall() {
        val roomId = groupCall.value.room?.call?.id
        if (groupCall.value.nvp && roomId != null && roomId > 0) {
            socket?.send("call.room_leave", JSONObject().put("call_id", roomId))
        }
        groupCall.value = GroupCallUiState(status = "ended")
        runCatching { nvpEngine.stop() }
        nvpRoomFallbackUsed = false
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
        nvpEngine.setMuted(muted)
        groupCall.value = groupCall.value.copy(muted = muted)
    }

    fun toggleGroupSpeaker() {
        val app = getApplication<Application>()
        val audio = app.getSystemService(Application.AUDIO_SERVICE) as? AudioManager ?: return
        val speaker = !groupCall.value.speaker
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = speaker
        nvpEngine.setSpeakerphone(speaker)
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
        // Jalur WebRTC lama: room NVP tak memakai SDP/ICE sama sekali.
        // Dipertahankan sebagai no-op agar event basi tak merusak state.
    }

    /**
     * Kunci E2EE baru dibagikan untukku: unduh lalu dekrip ulang chat yang
     * sedang dibuka + daftar percakapan (menyembuhkan fallback cipher).
     */
    private fun onKeysUpdated(data: JSONObject) {
        val conversationId = data.optLong("conversation_id")
        if (conversationId <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { syncKeysNow(conversationId) }
            // Antre kirim yang tertahan KEY_SYNC_NEEDED kini bisa jalan.
            runCatching { flushOutbox() }
            withContext(Dispatchers.Main) {
                if (conversationId == activeChatId) {
                    val state = chat.value
                    chat.value = state.copy(messages = decAll(state.messages))
                    retryPlaceholders(conversationId)
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
        // Jalur NVP: offer WS langsung (codecs) atau kurir REST (nvp1:).
        val nvpOffer = data.optJSONArray("codecs")?.let { arr ->
            val codecs = (0 until arr.length()).mapNotNull {
                arr.optString(it).takeIf { c -> c.isNotBlank() }
            }
            val key = data.optString("key_ct").takeIf { it.isNotBlank() }
            val salt = data.optString("salt").takeIf { it.isNotBlank() }
            if (codecs.isNotEmpty() && key != null && salt != null) {
                NvpOffer(codecs, key, salt)
            } else {
                null
            }
        } ?: NvpMarkers.parseOfferSdp(sdp)
        callState.value = CallUiState(
            call = call, peer = peer, outgoing = false,
            status = "ringing", remoteSdp = sdp.ifBlank { null },
            video = call.kind == "video",
            nvp = nvpOffer != null && call.kind == "audio",
            nvpCodecs = nvpOffer?.codecs,
            nvpKeyB64 = nvpOffer?.keyB64,
            nvpSaltB64 = nvpOffer?.saltB64,
        )
        startCallService()
    }

    private fun onCallAnswer(data: JSONObject) {
        // Jalur NVP: jawaban WS ({codec}) atau kurir REST (sdp nvp1:).
        val nvpCodec = data.optString("codec").takeIf { it.isNotBlank() }
            ?: NvpMarkers.parseAnswerSdp(data.optString("sdp"))
        if (nvpCodec != null && callState.value.nvp) {
            val current = callState.value
            if (current.status != "dialing" || !current.outgoing) return
            if (current.nvpCodecs != null && nvpCodec !in current.nvpCodecs) {
                endVoiceCall() // codec di luar offer → tutup
                return
            }
            callState.value = current.copy(nvpCodec = nvpCodec)
            maybeStartNvpEngine()
            return
        }
        val sdp = data.optString("sdp")
        if (sdp.isBlank()) return
        if (callState.value.status != "dialing") return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { rtc.handleAnswer(sdp) }
            withContext(Dispatchers.Main) {
                // Samakan dengan callee: routing earpiece + focus + service.
                // Tanpa ini penelepon tidak MODE_IN_COMMUNICATION → mic tidak
                // tertangkap (HP2 sunyi) dan echo suara sendiri.
                markCallActive()
            }
        }
    }

    private fun onCallIce(data: JSONObject) {
        val candidate = data.optJSONObject("candidate") ?: return
        // Room NVP tak memakai ICE: abaikan kandidat room (jalur WebRTC lama).
        val callJson = data.optJSONObject("call")
        if (callJson != null && callJson.optLong("conversation_id") != 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { rtc.handleRemoteIce(candidate) }
        }
    }

    /** Sinkron slot engine dari event join/leave room (NVP). */
    private fun onNvpRoomMembership(type: String, data: JSONObject, callId: Long) {
        val g = groupCall.value
        if (!g.nvp || g.room?.call?.id != callId || callId <= 0) return
        if (type == "call_room_joined") {
            val userId = data.optJSONObject("user")?.optLong("id") ?: 0L
            val slot = data.optInt("slot", -1)
            if (userId > 0 && slot >= 0 && userId != session.user?.id) {
                groupCall.value = g.copy(nvpSlots = g.nvpSlots + (userId to slot))
                nvpEngine.addSlot(slot)
            }
        } else {
            val userId = data.optLong("user_id")
            val slot = g.nvpSlots[userId]
            if (userId > 0 && slot != null) {
                nvpEngine.removeSlot(slot)
                groupCall.value = g.copy(nvpSlots = g.nvpSlots - userId)
            }
        }
    }

    private fun onRoomEnded(data: JSONObject) {
        val callId = data.optJSONObject("call")?.optLong("id") ?: data.optLong("call_id")
        if (callId <= 0 || groupCall.value.room?.call?.id != callId) return
        runCatching { nvpEngine.stop() }
        groupCall.value = GroupCallUiState(status = "ended")
        viewModelScope.launch {
            delay(800)
            if (groupCall.value.status == "ended") groupCall.value = GroupCallUiState()
        }
    }

    private fun onCallEnded(data: JSONObject) {
        endCallLocal()
        loadCallHistory()
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
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attrs)
                        .build()
                    callFocusRequest = req
                    audio.requestAudioFocus(req)
                } else {
                    @Suppress("DEPRECATION")
                    audio.requestAudioFocus(
                        null, AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                    )
                }
            }
        }
        callState.value = callState.value.copy(status = "active", speaker = false)
        startCallService()
    }

    private fun endCallLocal() {
        runCatching { rtc.close() }
        runCatching { nvpEngine.stop() }
        pendingNvpOffer = null
        nvpPreparedArrived = false
        nvpFallbackUsed = false
        nvpAnswerFallbackUsed = false
        callIceJob?.cancel()
        pendingIce.clear()
        stopCallService()
        val app = getApplication<Application>()
        runCatching {
            val audio = app.getSystemService(Application.AUDIO_SERVICE) as? AudioManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                callFocusRequest?.let { audio?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audio?.abandonAudioFocus(null)
            }
            callFocusRequest = null
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
        loadCallHistory()
    }

    private fun incomingMessage(conversationId: Long, incoming: Message) {
        // Gema kiriman sendiri dari jaringan: jangan timpa cache plaintext
        // (pengirim adalah sumber kebenaran plaintext-nya sendiri).
        val own = incoming.sender?.id != null && incoming.sender?.id == session.user?.id
        val current = chat.value
        // Echo sendiri tak bisa didekrip dari ciphertext (kunci kirim tak bisa
        // dibuka). Bila memori plaintext hilang, jangan timpa pending/cache
        // terbaca dengan placeholder "Pesan terkirim."
        val resolved = if (own && isDeadMessage(incoming)) {
            val pendingMatch = current.messages.firstOrNull { e ->
                e.id == incoming.id ||
                    (incoming.clientId != null && e.clientId == incoming.clientId)
            }
            val known = pendingMatch?.body?.takeIf { E2eeManager.isReadableBody(it) }
                ?: sessCrypto.previewPlaintext(incoming, myId())?.takeIf { E2eeManager.isReadableBody(it) }
            if (known != null) {
                sessCrypto.rememberPlain(incoming.id, known)
                incoming.copy(body = known)
            } else if (pendingMatch != null) {
                return
            } else {
                return
            }
        } else {
            incoming
        }
        if (resolved.id > 0 && !isDeadMessage(resolved)) {
            if (!own) {
                viewModelScope.launch { runCatching { chatCache.saveMessage(resolved) } }
            } else {
                // Echo sendiri yang sudah resolved = plaintext valid, simpan juga.
                viewModelScope.launch { runCatching { chatCache.saveMessage(resolved) } }
            }
        }
        // Pesan realtime tetap ditahan di RAM walau chat lain sedang dibuka,
        // supaya saat chat ini diketuk pesannya sudah ada di frame pertama.
        if (!isDeadMessage(resolved)) rememberChatRam(conversationId, listOf(resolved))
        val active = current.conversation?.id == conversationId
        var merged = current.messages
        if (active) {
            val cleaned = current.messages.filterNot { existing ->
                existing.id == resolved.id ||
                    (resolved.clientId != null && existing.clientId == resolved.clientId)
            }
            val all = (cleaned + resolved).distinctBy { it.id }.sortedBy { it.id }
            val capped = all.capChatWindow()
            merged = capped
            chat.value = current.copy(
                messages = capped,
                typingName = null,
                // Pesan lama yang terbuang dari RAM tetap bisa digali via beforeId.
                hasMore = current.hasMore || capped.size < all.size,
            )
            if (resolved.sender?.id != session.user?.id) {
                markReadInternal(conversationId, resolved.id)
            }
        }
        if (resolved.id > 0 && !own) {
            // Placeholder mati tidak boleh di-ack: ciphertext harus tetap
            // tersedia di server sampai berhasil didekrip (heal/retry).
            val basis = if (active) merged else listOf(incoming)
            if (active || !isDeadMessage(incoming)) {
                val maxId = maxOf(
                    incoming.id,
                    basis.filter { it.id > 0 }.maxOfOrNull { it.id } ?: 0L,
                )
                val syncId = safeSyncId(basis, maxId)
                if (syncId > 0) {
                    viewModelScope.launch {
                        runCatching { chatCache.saveCursor(conversationId, syncId) }
                    }
                    // Sampai di perangkat = delivered — foreground maupun
                    // background — hanya sampai id yang aman.
                    ackInternal(conversationId, syncId)
                }
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
                // Plaintext cache diambil sekaligus agar pratinjau tidak
                // mendekrip ulang ciphertext yang kuncinya sudah terpakai.
                val previewIds = list.mapNotNull { conv ->
                    conv.lastMessage?.takeIf { it.type == "text" && it.id > 0 }?.id
                }
                val cachedById = runCatching { chatCache.byIds(previewIds) }
                    .getOrDefault(emptyList())
                    .associateBy { it.id }
                conversations.value = list.map { conv ->
                    val last = conv.lastMessage
                    if (last != null && last.type == "text") {
                        conv.copy(lastMessage = previewMessageFor(last, cachedById[last.id]))
                    } else conv
                }
                warmChatRam(conversations.value)
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
        // Frame pertama: RAM + pesan terakhir dari daftar chat. Semua sinkron,
        // jadi layar chat tidak pernah sempat kosong / menampilkan spinner.
        val lastSeed = lastMessageSeed(conversation)
        val seeded = seedMessagesFor(conversation)
        chat.value = ChatUiState(
            conversation = conversation,
            messages = seeded,
            loading = seeded.isEmpty(),
            keySyncing = true,
        )
        viewModelScope.launch {
            // 0. Pesan sementara: pangkas cache kedaluwarsa sebelum tampil.
            if (conversation.disappearingDays > 0) {
                runCatching { chatCache.pruneDisappearing(conversation.id, conversation.disappearingDays) }
            }
            // 1. Cache dulu: tampil instan + tetap bisa dibaca saat offline.
            val cached = runCatching { chatCache.recent(conversation.id) }.getOrDefault(emptyList())
            val queuedHere = runCatching {
                chatCache.queued().filter { it.conversationId == conversation.id }
            }.getOrDefault(emptyList())
            if (cached.isNotEmpty() || queuedHere.isNotEmpty()) {
                val current = chat.value
                if (current.conversation?.id != conversation.id) return@launch
                // Gabung dengan seed (RAM + pesan terakhir) agar pesan baru
                // dari daftar chat tidak hilang saat cache belum memuatnya.
                val merged = mergeServerDelta(
                    current.messages.filterNot { it.pending },
                    cached,
                    current.messages.filter { it.pending } + queuedHere,
                ) { existing, incoming -> ChatCache.shouldKeepExisting(existing, incoming) }
                rememberReadable(merged)
                rememberChatRam(conversation.id, merged)
                chat.value = current.copy(
                    messages = merged.capChatWindow(),
                    loading = false,
                    hasMore = cached.size >= 40,
                )
            } else if (seeded.isNotEmpty()) {
                chat.value = chat.value.copy(loading = false)
            }
            try {
                // 2. Delta dari cursor bila cache ada; kalau tidak, halaman penuh.
                val cursor = runCatching { chatCache.cursor(conversation.id) }.getOrNull()
                val useDelta = cursor != null && cursor > 0 && cached.isNotEmpty()
                // Ambil pesan PARALEL dengan sinkronisasi kunci: dua-duanya
                // jaringan dan tidak saling bergantung. Dekrip tetap menunggu
                // syncKeysNow selesai.
                val pageDeferred = async {
                    if (useDelta) {
                        repository.messages(conversation.id, afterId = cursor, limit = 100)
                    } else {
                        repository.messages(conversation.id, limit = 40)
                    }
                }
                // Tunggu kunci E2EE siap dulu (device baru!) sebelum mendekrip,
                // kalau tidak pesan terlanjur tampil terenkripsi.
                runCatching { syncKeysNow(conversation.id) }
                val page = pageDeferred.await()
                val decRaw = decAll(page.messages)
                val knownDec = (chat.value.messages + cached).associateBy { it.id }
                val dec = healOwnPlaceholders(decRaw, knownDec)
                rememberReadable(dec)
                runCatching { chatCache.saveMessages(dec) }
                val current = chat.value
                if (current.conversation?.id != conversation.id) return@launch
                val keepPending = current.messages.filter { it.pending }
                // Seed tetap dipertahankan: pesan terakhir dari daftar chat
                // mungkin belum ikut halaman jaringan (mis. >100 delta).
                val base = current.messages.filterNot { it.pending }
                // Server menang (status segar); cache terbaca dipertahankan
                // bila delta gagal dekrip (statusnya tetap dari server).
                val merged = mergeServerDelta(base, dec, keepPending) { existing, incoming ->
                    ChatCache.shouldKeepExisting(existing, incoming)
                }
                rememberChatRam(conversation.id, merged)
                val displayMaxId = merged.filter { it.id > 0 }.maxOfOrNull { it.id } ?: 0L
                // Cursor/ack tidak boleh melewati pesan yang belum benar-benar
                // termuat. Seed dari daftar chat (yang mungkin melompati pesan
                // lain) hanya dipakai untuk tampilan/read, bukan cursor.
                val cachedIds = cached.map { it.id }.toSet()
                val decIds = dec.map { it.id }.toSet()
                val seedOnly = lastSeed
                    ?.takeIf { it.id !in cachedIds && it.id !in decIds }
                    ?.id
                val syncMax = merged
                    .filter { it.id > 0 && it.id != seedOnly }
                    .maxOfOrNull { it.id } ?: 0L
                // Cursor/ack tidak boleh melewati pesan yang gagal disimpan.
                val syncId = safeSyncId(merged, syncMax)
                runCatching { chatCache.saveCursor(conversation.id, syncId) }
                chat.value = ChatUiState(
                    conversation = conversation,
                    messages = merged,
                    loading = false,
                    hasMore = if (useDelta) current.hasMore else page.hasMore,
                    initialUnread = conversation.unreadCount,
                )
                // Force-resend (abaikan dedup): setiap buka chat menutup
                // tulis-buta yang hilang sebelumnya (anti macet abu).
                if (displayMaxId > 0) {
                    resendMarkers(conversation.id, displayMaxId, ackMaxId = syncId)
                }
                refreshConversations()
                loadPins()
                // Anggota + username untuk saran @mention (juga dipakai header grup).
                loadConversationDetail()
                // Kunci bisa sudah pulih sejak placeholder dibuat (mis. HP
                // sempat mati saat heal dikirim) — coba lagi tiap buka chat.
                retryPlaceholders(conversation.id)
            } catch (e: Exception) {
                // Offline + ada cache = tetap tampil, tanpa error menakutkan.
                if (isNetworkError(e) && chat.value.messages.isNotEmpty()) {
                    chat.value = chat.value.copy(loading = false, keySyncing = false, error = null)
                    message.value = "Offline — menampilkan pesan tersimpan."
                } else {
                    handleError(e)
                    chat.value = chat.value.copy(
                        loading = false,
                        keySyncing = false,
                        error = e.message ?: "Gagal memuat pesan.",
                    )
                }
            }
        }
    }

    /**
     * Presence real-time (online + offline): tambal SEMUA state lokal yang
     * memegang user ini — tanpa fetch — agar titik hijau, subtitle
     * "Online"/"x anggota, y online", dan kontak sinkron seketika.
     */
    private fun onPresenceChanged(userId: Long, online: Boolean, lastSeenAt: String?) {
        fun patch(u: User): User =
            if (u.id == userId) u.copy(online = online, lastSeenAt = lastSeenAt ?: u.lastSeenAt) else u
        fun patchConv(c: Conversation): Conversation {
            val peer = c.peer?.let(::patch)
            val members = c.members.map(::patch)
            if (peer == c.peer && members == c.members) return c
            return c.copy(peer = peer, members = members)
        }
        val st = chat.value
        val detail = st.detail?.let(::patchConv)
        val conv = st.conversation?.let(::patchConv)
        if (detail != st.detail || conv != st.conversation) {
            chat.value = st.copy(detail = detail, conversation = conv)
        }
        conversations.value = conversations.value.map(::patchConv)
        friends.value = friends.value.map(::patch)
        searchResults.value = searchResults.value.map(::patch)
        nearby.value = nearby.value.copy(users = nearby.value.users.map(::patch))
        val req = requests.value
        requests.value = req.copy(
            incoming = req.incoming.map { r -> r.copy(user = patch(r.user)) },
            outgoing = req.outgoing.map { r -> r.copy(user = patch(r.user)) },
        )
    }

    /** Tulis status read/ack ke cache agar tak balik saat buka ulang. */
    private fun persistReadUpTo(conversationId: Long, messageId: Long, readIds: Set<Long>?) {
        val me = session.user?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatCache.markReadUpTo(conversationId, me, messageId, readIds) }
        }
    }

    private fun persistDeliveredUpTo(conversationId: Long, messageId: Long) {
        val me = session.user?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatCache.markOwnUpTo(conversationId, me, messageId, "delivered") }
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
        // Daftar chat sudah memegang metadata + pratinjau plaintext: buka
        // seketika, jangan tunggu round-trip /conversations/show.
        val local = conversations.value.firstOrNull { it.id == conversationId }
        if (local != null) {
            openChat(local)
            return
        }
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

        // Bila kunci lokal baru dibuat saat mengirim (percakapan baru),
        // sebarkan ke anggota/perangkat lain agar pesan terbaca semua pihak.
        // Kunci tandingan habis-reinstall TIDAK PERNAH dibuat: encryptText
        // melempar KEY_SYNC_NEEDED bila kanonis ada di pihak lain.
        val hadKey = e2ee.isEnabled(conversation.id)
        viewModelScope.launch {
            if (!isOnline.value) {
                // Antre plaintext langsung TANPA enkripsi: kunci dinilai ulang
                // saat flush online (hindari kunci tandingan saat offline).
                val current = chat.value
                chat.value = current.copy(
                    messages = current.messages.map {
                        if (it.clientId == clientId) it.copy(status = "queued", pending = true) else it
                    }
                )
                current.messages.firstOrNull { it.clientId == clientId }?.let { queued ->
                    runCatching { chatCache.updateQueued(queued.copy(status = "queued", pending = true)) }
                }
                message.value = "Offline — pesan dikirim otomatis saat online."
                return@launch
            }
            try {
                val toSend = sessCrypto.encryptText(conversation, me.id, body)
                val sent = repository.sendMessage(conversation.id, toSend, clientId, replyToId)
                mergeSentMessage(clientId, sent.copy(body = body))
                refreshConversations()
                if (!hadKey) {
                    launch(Dispatchers.IO) { runCatching { syncKeysNow(conversation.id) } }
                }
            } catch (e: Exception) {
                val current = chat.value
                if (isNetworkError(e)) {
                    // Offline: antrekan, kirim otomatis saat online.
                    chat.value = current.copy(
                        messages = current.messages.map {
                            if (it.clientId == clientId) it.copy(status = "queued", pending = true) else it
                        }
                    )
                    current.messages.firstOrNull { it.clientId == clientId }?.let { queued ->
                        runCatching { chatCache.updateQueued(queued.copy(status = "queued", pending = true)) }
                    }
                    message.value = "Offline — pesan dikirim otomatis saat online."
                } else {
                    chat.value = current.copy(
                        messages = current.messages.map {
                            if (it.clientId == clientId) it.copy(status = "failed", pending = false) else it
                        }
                    )
                    if (e is SessionCryptoException && e.message == SessionCrypto.KEY_SYNC_NEEDED) {
                        message.value = "Menunggu sinkronisasi kunci keamanan…"
                        launch(Dispatchers.IO) { runCatching { syncKeysNow(conversation.id) } }
                    } else {
                        handleError(e)
                    }
                }
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
                        val enc = sessCrypto.encryptText(conversation, myId(), pending.body.orEmpty())
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
                val sent = sentRaw.copy(body = pending.body.orEmpty())
                mergeSentMessage(clientId, sent)
                refreshConversations()
            } catch (e: Exception) {
                val current = chat.value
                if (isNetworkError(e)) {
                    // Tetap antre (media tanpa URL tak bisa antre → failed biasa).
                    val queued = current.messages.firstOrNull { it.clientId == clientId }
                    val canQueue = queued != null && (queued.type == "text" || queued.type == "location")
                    if (canQueue) {
                        chat.value = current.copy(
                            messages = current.messages.map {
                                if (it.clientId == clientId) it.copy(status = "queued", pending = true) else it
                            }
                        )
                        runCatching { chatCache.updateQueued(queued.copy(status = "queued", pending = true)) }
                        message.value = "Offline — pesan dikirim otomatis saat online."
                    } else {
                        chat.value = current.copy(
                            messages = current.messages.map {
                                if (it.clientId == clientId) it.copy(status = "failed", pending = false) else it
                            }
                        )
                        handleError(e)
                    }
                } else {
                    chat.value = current.copy(
                        messages = current.messages.map {
                            if (it.clientId == clientId) it.copy(status = "failed", pending = false) else it
                        }
                    )
                    if (e is SessionCryptoException && e.message == SessionCrypto.KEY_SYNC_NEEDED) {
                        message.value = "Menunggu sinkronisasi kunci keamanan…"
                        launch(Dispatchers.IO) { runCatching { syncKeysNow(conversation.id) } }
                    } else {
                        handleError(e)
                    }
                }
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
        // Samarkan juga di cache agar isi tarikan tak tersisa di device.
        current.messages.firstOrNull { it.id == messageId }?.let { original ->
            viewModelScope.launch { runCatching { chatCache.saveMessage(scrubbed(original)) } }
        }
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
                val encrypted = sessCrypto.encryptText(conversation, myId(), text)
                val updated = repository.editMessage(target.id, encrypted).copy(body = text)
                chat.value = chat.value.copy(
                    editing = null,
                    messages = chat.value.messages.map { if (it.id == updated.id) updated else it }
                )
                runCatching { chatCache.saveMessage(updated) }
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
                runCatching { chatCache.saveMessage(message.copy(reactions = reactions)) }
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
                // Relay: sertakan tipe agar fallback cache jalan; se-obrolan tanpa
                // body (server menyalin ciphertext asli — jangan plaintext tampilan).
                val plain = dec(msg)?.body
                val activeId = chat.value.conversation?.id
                val target = runCatching { repository.conversationDetail(toConversationId) }.getOrNull()
                val bodyOverride = if (toConversationId != activeId && msg.type == "text" && plain != null && target != null) {
                    sessCrypto.encryptText(target, myId(), plain)
                } else null
                val sent = repository.forwardMessage(msg.id, toConversationId, UUID.randomUUID().toString(), bodyOverride, msg.type)
                if (toConversationId == activeId) {
                    val decSent = if (bodyOverride != null) sent.copy(body = plain) else dec(sent) ?: sent
                    mergeSentMessage(sent.clientId.orEmpty(), decSent)
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
                    val preview = previewMessageFor(pin.message)
                        ?: pin.message.copy(body = E2eeManager.PLACEHOLDER_SYNC)
                    pin.copy(message = preview)
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
                    val preview = previewMessageFor(pin.message)
                        ?: pin.message.copy(body = E2eeManager.PLACEHOLDER_SYNC)
                    pin.copy(message = preview)
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
                val remote = decAll(repository.searchMessages(conversation.id, q))
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
        if (updated.disappearingDays > 0) {
            runCatching { chatCache.pruneDisappearing(conversationId, updated.disappearingDays) }
        }
        if (chat.value.conversation?.id == conversationId) {
            chat.value = chat.value.copy(conversation = updated, detail = updated)
        }
        refreshConversations()
    }

    suspend fun updateGroupInfo(conversationId: Long, title: String? = null, avatarUrl: String? = null, description: String? = null) {
        val updated = repository.updateGroupInfo(conversationId, title, avatarUrl, description)
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
                runCatching { chatCache.deleteConversation(conversationId) }
                chatRam.remove(conversationId)
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
        // Ingat plaintext kiriman sendiri (gema server tak bisa didekrip).
        if (sent.type == "text" && sent.body != null) sessCrypto.rememberPlain(sent.id, sent.body)
        val current = chat.value
        val cleaned = current.messages.filterNot {
            it.clientId == clientId || it.id == sent.id
        }
        val merged = (cleaned + sent).distinctBy { it.id }.sortedBy { it.id }
        val capped = merged.capChatWindow()
        chat.value = current.copy(
            messages = capped,
            hasMore = current.hasMore || capped.size < merged.size,
        )
        viewModelScope.launch {
            runCatching { chatCache.markSent(clientId, sent) }
            if (sent.id > 0) runCatching { chatCache.saveCursor(sent.conversationId, sent.id) }
        }
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
                val knownOlder = chat.value.messages.associateBy { it.id }
                val dec = healOwnPlaceholders(decAll(page.messages), knownOlder)
                rememberReadable(dec)
                runCatching { chatCache.saveMessages(dec) }
                val current = chat.value
                if (current.conversation?.id != conversation.id) return@launch
                // Server menang, tapi pesan terbaca TIDAK boleh ditimpa
                // placeholder (itu sumber ikon kunci + "Pesan terkirim.").
                val merged = mergeServerDelta(
                    current.messages, dec, emptyList(),
                ) { existing, incoming -> ChatCache.shouldKeepExisting(existing, incoming) }
                val newIds = merged.map { it.id }.toSet() - current.messages.map { it.id }.toSet()
                val maxId = merged.filter { it.id > 0 }.maxOfOrNull { it.id } ?: 0L
                val syncId = safeSyncId(merged, maxId)
                if (syncId > 0) ackInternal(conversation.id, syncId)
                chat.value = current.copy(
                    messages = merged,
                    loadingOlder = false,
                    // Hentikan loop bila server tidak memberi kemajuan
                    // (dec kosong / semua duplikat) walau flag server true.
                    hasMore = page.hasMore && newIds.isNotEmpty(),
                )
            } catch (e: Exception) {
                // Offline: gali cache lokal dulu sebelum menyerah.
                if (isNetworkError(e)) {
                    val cached = runCatching {
                        chatCache.older(conversation.id, oldest)
                    }.getOrDefault(emptyList())
                    if (cached.isNotEmpty()) {
                        val current = chat.value
                        chat.value = current.copy(
                            messages = (cached + current.messages)
                                .distinctBy { it.id }
                                .sortedBy { it.id },
                            loadingOlder = false,
                            hasMore = cached.size >= 30,
                        )
                        return@launch
                    }
                }
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
                val knownPoll = chat.value.messages.associateBy { it.id }
                val dec = healOwnPlaceholders(decAll(page.messages), knownPoll)
                rememberReadable(dec)
                val current = chat.value
                if (current.conversation?.id != conversation.id) return@launch
                if (dec.isEmpty()) {
                    // Tak ada yang baru: tetap kirim ulang penanda agar
                    // centang pengirim pulih bila tulis-buta sempat hilang.
                    val syncId = safeSyncId(current.messages, afterId)
                    resendMarkers(conversation.id, afterId, ackMaxId = syncId)
                    return@launch
                }
                val extra = dec.filter { incoming ->
                    current.messages.none { it.id == incoming.id }
                }
                if (extra.isNotEmpty()) {
                    val healed = healOwnPlaceholders(extra, current.messages.associateBy { it.id })
                    val merged = mergeServerDelta(
                        current.messages, healed, emptyList(),
                    ) { existing, incoming -> ChatCache.shouldKeepExisting(existing, incoming) }
                    val capped = merged.capChatWindow()
                    chat.value = current.copy(
                        messages = capped,
                        hasMore = current.hasMore || capped.size < merged.size,
                    )
                    runCatching { chatCache.saveMessages(healed) }
                    val newMax = capped.filter { it.id > 0 }.maxOfOrNull { it.id } ?: afterId
                    val syncId = safeSyncId(capped, newMax)
                    runCatching { chatCache.saveCursor(conversation.id, syncId) }
                    resendMarkers(conversation.id, newMax, ackMaxId = syncId)
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
            val sent = socket?.send(
                "chat.typing",
                JSONObject().put("conversation_id", conversationId),
            ) == true
            if (!sent) runCatching { repository.typing(conversationId) }
        }
    }

    /**
     * Tulis-buta hibrida: coba socket dulu, fallback REST bila tutup.
     * Ketiganya idempotent — kirim ganda aman.
     */
    private fun blindWrite(
        wsType: String,
        conversationId: Long,
        messageId: Long,
        restFallback: suspend () -> Unit,
    ) {
        val payload = JSONObject()
            .put("conversation_id", conversationId)
            .put("message_id", messageId)
        viewModelScope.launch {
            val sent = socket?.send(wsType, payload) == true
            if (!sent) runCatching { restFallback() }
        }
    }

    private fun markReadInternal(conversationId: Long, messageId: Long) {
        val previous = readUpTo[conversationId] ?: 0L
        if (messageId <= previous) return
        readUpTo[conversationId] = messageId
        conversations.value = conversations.value.map {
            if (it.id == conversationId) it.copy(unreadCount = 0) else it
        }
        blindWrite("message.read", conversationId, messageId) {
            repository.markRead(conversationId, messageId)
        }
    }

    /**
     * Ack relay: pesan ≤ messageId sudah tersimpan di cache perangkat.
     * Foreground maupun background (badge unread tak terpengaruh ack —
     * badge dihitung dari penanda baca, bukan ack).
     */
    private fun ackInternal(conversationId: Long, messageId: Long) {
        if (messageId <= 0) return
        val previous = ackUpTo[conversationId] ?: 0L
        if (messageId <= previous) return
        ackUpTo[conversationId] = messageId
        blindWrite("message.ack", conversationId, messageId) {
            repository.ack(conversationId, messageId)
        }
    }

    /** WS nyambung kembali: kejar pesan yang terlewat saat putus. */
    private fun onRealtimeReconnect() {
        if (sessionState.value.user == null) return
        refreshConversations()
        val convId = activeChatId ?: return
        viewModelScope.launch {
            val before = chat.value
            if (before.conversation?.id != convId) return@launch
            val maxId = before.messages.filter { it.id > 0 }.maxOfOrNull { it.id } ?: 0L
            val page = runCatching {
                repository.messages(convId, afterId = maxId, limit = 100)
            }.getOrNull()
            val st = chat.value
            if (st.conversation?.id != convId) return@launch
            val fresh = decAll(page?.messages.orEmpty())
            if (fresh.isEmpty()) {
                val syncId = safeSyncId(before.messages, maxId)
                resendMarkers(convId, maxId, ackMaxId = syncId)
                return@launch
            }
            runCatching { chatCache.saveMessages(fresh) }
            val keepPending = st.messages.filter { it.pending }
            val preCap = mergeServerDelta(
                st.messages.filterNot { it.pending }, fresh, keepPending,
            ) { existing, incoming -> ChatCache.shouldKeepExisting(existing, incoming) }
            val merged = preCap.capChatWindow()
            val newMax = merged.filter { it.id > 0 }.maxOfOrNull { it.id } ?: maxId
            val syncId = safeSyncId(merged, newMax)
            runCatching { chatCache.saveCursor(convId, syncId) }
            chat.value = st.copy(
                messages = merged,
                typingName = null,
                hasMore = st.hasMore || merged.size < preCap.size,
            )
            resendMarkers(convId, newMax, ackMaxId = syncId)
            refreshConversations()
            // Kunci mungkin pulih saat offline — coba placeholder sekali lagi.
            retryPlaceholders(convId)
        }
    }

    /**
     * Kirim ulang penanda terakhir (idempotent): menutup tulis-buta yang
     * hilang saat socket setengah-nyambung (send() true tapi tak diproses).
     */
    private fun resendMarkers(conversationId: Long, maxId: Long, ackMaxId: Long? = null) {
        if (maxId <= 0) return
        val readId = maxOf(readUpTo[conversationId] ?: 0L, maxId)
        // Ack tidak boleh melewati placeholder mati (server akan menyapunya).
        // ackMaxId eksplisit 0 = belum ada id aman: jangan majukan ack.
        val safeAck = ackMaxId ?: maxId
        val ackId = maxOf(ackUpTo[conversationId] ?: 0L, safeAck)
        readUpTo[conversationId] = readId
        ackUpTo[conversationId] = ackId
        conversations.value = conversations.value.map {
            if (it.id == conversationId) it.copy(unreadCount = 0) else it
        }
        blindWrite("message.read", conversationId, readId) {
            repository.markRead(conversationId, readId)
        }
        if (ackId > 0) {
            blindWrite("message.ack", conversationId, ackId) {
                repository.ack(conversationId, ackId)
            }
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

    fun createStory(text: String, uris: List<android.net.Uri>, style: String? = null) {
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
                val story = repository.createStory(trimmed, media, style.orEmpty())
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
                    val encrypted = encryptFor(conversationId, moment.body)
                    val clientId = java.util.UUID.randomUUID().toString()
                    val sent = repository.sendMessage(conversationId, encrypted, clientId, null)
                    if (conversationId == chat.value.conversation?.id) {
                        mergeSentMessage(clientId, sent.copy(body = moment.body))
                    }
                }
                moment.media.forEach { url ->
                    val type = if (id.nusachat.app.data.isVideoUrl(url)) "video" else "image"
                    val sent = repository.sendAttachment(
                        conversationId, type, url, null, java.util.UUID.randomUUID().toString()
                    )
                    if (conversationId == chat.value.conversation?.id) {
                        mergeSentMessage(sent.clientId.orEmpty(), dec(sent) ?: sent)
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
        val applyLike: (Moment) -> Moment = {
            if (it.id == moment.id) {
                it.copy(
                    likedByMe = liked,
                    likeCount = (it.likeCount + if (liked) 1 else -1).coerceAtLeast(0),
                )
            } else it
        }
        moments.value = moments.value.map(applyLike)
        tagFeed.value = tagFeed.value.map(applyLike)
        viewModelScope.launch {
            try {
                if (liked) repository.likeMoment(moment.id) else repository.unlikeMoment(moment.id)
            } catch (e: Exception) {
                val revert: (Moment) -> Moment = {
                    if (it.id == moment.id) {
                        it.copy(
                            likedByMe = moment.likedByMe,
                            likeCount = moment.likeCount,
                        )
                    } else it
                }
                moments.value = moments.value.map(revert)
                tagFeed.value = tagFeed.value.map(revert)
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

    override fun onCleared() {
        networkMonitor.stop()
        super.onCleared()
    }

    /** Gagal jaringan (bukan salah server) → layak antre offline / fallback cache. */
    private fun isNetworkError(error: Throwable): Boolean =
        error is ApiException && error.code == "network_error"

    /**
     * Kirim semua pesan antre offline. Berhenti di gagal-jaringan pertama
     * (dicoba lagi saat online berikutnya); gagal permanen (validasi/ditolak)
     * ditandai failed agar tidak diulang selamanya.
     */
    private fun flushOutbox() {
        if (sessionState.value.user == null) return
        viewModelScope.launch {
            val queued = runCatching { chatCache.queued() }.getOrDefault(emptyList())
            if (queued.isEmpty()) return@launch
            var sent = 0
            for (q in queued) {
                val clientId = q.clientId ?: continue
                try {
                    val sentRaw = when (q.type) {
                        "text" -> {
                            repository.sendMessage(
                                q.conversationId,
                                encryptFor(q.conversationId, q.body.orEmpty()),
                                clientId,
                                q.replyToId,
                            )
                        }
                        "location" -> {
                            val lat = q.lat ?: throw IllegalStateException("Lokasi tidak valid.")
                            val lng = q.lng ?: throw IllegalStateException("Lokasi tidak valid.")
                            repository.sendLocation(
                                q.conversationId, lat, lng, q.body.orEmpty(), clientId, q.replyToId
                            )
                        }
                        // Media butuh upload dulu (mustahil offline) → jangan antre.
                        else -> throw IllegalStateException("Lampiran harus dikirim manual.")
                    }
                    val dec = sentRaw.copy(body = q.body.orEmpty())
                    if (dec.body != null) sessCrypto.rememberPlain(dec.id, dec.body)
                    runCatching { chatCache.markSent(clientId, dec) }
                    if (q.conversationId == activeChatId) mergeSentMessage(clientId, dec)
                    sent++
                } catch (e: Exception) {
                    if (isNetworkError(e)) return@launch
                    // Kunci belum sync: biarkan antre (coba flush berikut),
                    // jangan failed permanen dan JANGAN buat kunci tandingan.
                    if (e is SessionCryptoException && e.message == SessionCrypto.KEY_SYNC_NEEDED) {
                        launch(Dispatchers.IO) { runCatching { syncKeysNow(q.conversationId) } }
                        return@launch
                    }
                    runCatching { chatCache.updateQueued(q.copy(status = "failed", pending = false)) }
                    if (q.conversationId == activeChatId) {
                        val current = chat.value
                        chat.value = current.copy(
                            messages = current.messages.map {
                                if (it.clientId == clientId) it.copy(status = "failed", pending = false) else it
                            }
                        )
                    }
                }
            }
            if (sent > 0) {
                refreshConversations()
                if (queued.any { it.conversationId == activeChatId }) {
                    message.value = if (sent == 1) "Pesan offline terkirim." else "$sent pesan offline terkirim."
                }
            }
        }
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
        runCatching { ChatDatabase.delete(getApplication()) }
        sessionState.value = SessionState(null, true)
        message.value = "Sesi berakhir. Silakan masuk kembali."
    }

    private fun nowIso(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    // ------------------------------------------------------------- lupa sandi

    /** onSent dipanggil dengan token (server dev) atau null (produksi: cek SMS/email). */
    fun forgotPassword(login: String, onSent: (String?) -> Unit) {
        if (login.isBlank()) {
            authError.value = "Isi nama pengguna atau nomor HP."
            return
        }
        authLoading.value = true
        viewModelScope.launch {
            try {
                onSent(repository.forgotPassword(login.trim()))
            } catch (e: Exception) {
                authError.value = e.message ?: "Gagal meminta reset."
            } finally {
                authLoading.value = false
            }
        }
    }

    fun resetPassword(token: String, newPassword: String, onDone: () -> Unit) {
        if (token.isBlank() || newPassword.length < 6) {
            authError.value = "Token dan sandi baru (min. 6 karakter) wajib diisi."
            return
        }
        authLoading.value = true
        viewModelScope.launch {
            try {
                repository.resetPassword(token.trim(), newPassword)
                onDone()
            } catch (e: Exception) {
                authError.value = e.message ?: "Reset gagal."
            } finally {
                authLoading.value = false
            }
        }
    }

    // ------------------------------------------------------------------ sesi

    fun loadSessions() {
        sessionsLoading.value = true
        viewModelScope.launch {
            try {
                sessions.value = repository.sessions()
            } catch (e: Exception) {
                handleError(e)
            } finally {
                sessionsLoading.value = false
            }
        }
    }

    fun revokeSession(item: SessionItem) {
        viewModelScope.launch {
            try {
                repository.revokeSession(item.jti)
                sessions.value = sessions.value.filterNot { it.jti == item.jti }
                // Bila sesi perangkat ini yang dicabut, keluar sekalian.
                val mine = session.refreshToken?.substringBefore(".")
                if (!mine.isNullOrBlank() && mine == item.jti) {
                    logout()
                } else {
                    message.value = "Sesi dicabut."
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun setMutedById(conversation: Conversation, muted: Boolean) {
        viewModelScope.launch {
            try {
                repository.muteConversation(conversation.id, muted)
                conversations.value = conversations.value.map {
                    if (it.id == conversation.id) it.copy(muted = muted) else it
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun setArchivedById(conversation: Conversation, archived: Boolean) {
        viewModelScope.launch {
            try {
                repository.archiveConversation(conversation.id, archived)
                conversations.value = conversations.value.map {
                    if (it.id == conversation.id) it.copy(archived = archived) else it
                }
                refreshConversations()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun clearHistoryById(conversation: Conversation) {
        viewModelScope.launch {
            try {
                repository.clearHistory(conversation.id)
                runCatching { chatCache.deleteConversation(conversation.id) }
                chatRam.remove(conversation.id)
                refreshConversations()
                message.value = "Riwayat dihapus untuk saya."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    // ------------------------------------------------------- pin & belum dibaca

    fun togglePinChat(conversation: Conversation) {
        viewModelScope.launch {
            try {
                val pinned = repository.pinChat(conversation.id, !conversation.pinned)
                conversations.value = conversations.value.map {
                    if (it.id == conversation.id) it.copy(pinned = pinned) else it
                }.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.lastMessageAt ?: "" })
                message.value = if (pinned) "Chat disematkan ke atas." else "Sematan chat dilepas."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun markChatUnread(conversation: Conversation) {
        viewModelScope.launch {
            try {
                val count = repository.markUnread(conversation.id)
                conversations.value = conversations.value.map {
                    if (it.id == conversation.id) it.copy(unreadCount = count) else it
                }
                message.value = "Ditandai belum dibaca."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun searchAllChats(query: String) {
        if (query.trim().length < 2) {
            globalSearchResults.value = emptyList()
            return
        }
        globalSearching.value = true
        viewModelScope.launch {
            try {
                globalSearchResults.value = decAll(repository.searchAll(query.trim()))
            } catch (e: Exception) {
                handleError(e)
            } finally {
                globalSearching.value = false
            }
        }
    }

    fun clearGlobalSearch() {
        globalSearchResults.value = emptyList()
    }

    // ------------------------------------------------- pilih banyak & bintang

    fun toggleSelectMessage(messageId: Long) {
        val current = selectedMessages.value.toMutableSet()
        if (!current.add(messageId)) current.remove(messageId)
        selectedMessages.value = current
    }

    fun clearSelection() {
        selectedMessages.value = emptySet()
    }

    fun forwardSelected(toConversationId: Long, onDone: () -> Unit = {}) {
        val ids = selectedMessages.value.toList()
        if (ids.isEmpty()) return
        val state = chat.value
        viewModelScope.launch {
            try {
                val target = runCatching { repository.conversationDetail(toConversationId) }.getOrNull()
                ids.mapNotNull { id -> state.messages.firstOrNull { it.id == id } }
                    .sortedBy { it.id }
                    .forEach { msg ->
                        val body = if (msg.type == "text" && target != null) {
                            sessCrypto.encryptText(target, myId(), msg.body.orEmpty())
                        } else null
                        repository.forwardMessage(msg.id, toConversationId, UUID.randomUUID().toString(), body, msg.type)
                    }
                clearSelection()
                refreshConversations()
                message.value = "${ids.size} pesan diteruskan."
                onDone()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun starSelected() {
        val ids = selectedMessages.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                ids.forEach { runCatching { repository.starMessage(it) } }
                clearSelection()
                message.value = "${ids.size} pesan dibintangi."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun deleteSelectedForMe() {
        val ids = selectedMessages.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                ids.forEach { runCatching { repository.hideMessage(it) } }
                chat.value = chat.value.copy(messages = chat.value.messages.filterNot { it.id in ids })
                clearSelection()
                refreshConversations()
                message.value = "${ids.size} pesan dihapus untuk saya."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun starMessage(msg: Message) {
        viewModelScope.launch {
            try {
                repository.starMessage(msg.id)
                message.value = "Pesan dibintangi."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun loadStarred() {
        starredLoading.value = true
        viewModelScope.launch {
            try {
                starredMessages.value = decAll(repository.starredMessages())
            } catch (e: Exception) {
                handleError(e)
            } finally {
                starredLoading.value = false
            }
        }
    }

    fun unstarMessage(msg: Message) {
        viewModelScope.launch {
            try {
                repository.unstarMessage(msg.id)
                starredMessages.value = starredMessages.value.filterNot { it.id == msg.id }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun hideMessage(msg: Message) {
        viewModelScope.launch {
            try {
                repository.hideMessage(msg.id)
                chat.value = chat.value.copy(messages = chat.value.messages.filterNot { it.id == msg.id })
                refreshConversations()
                message.value = "Pesan dihapus untuk saya."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    // ------------------------------------------------------ stiker & suara

    fun sendSticker(emoji: String) {
        val state = chat.value
        val conversation = state.conversation ?: return
        if (emoji.isBlank()) return
        val me = session.user ?: return
        val clientId = UUID.randomUUID().toString()
        val replyToId = state.replyTo?.takeIf { it.id > 0 }?.id
        val pending = Message(
            id = -System.currentTimeMillis(),
            conversationId = conversation.id,
            sender = me,
            type = "sticker",
            body = emoji,
            attachmentUrl = null,
            status = "sending",
            createdAt = nowIso(),
            clientId = clientId,
            pending = true,
            replyToId = replyToId,
        )
        chat.value = state.copy(messages = state.messages + pending, replyTo = null)
        viewModelScope.launch {
            try {
                val sent = repository.sendSticker(conversation.id, emoji, clientId, replyToId)
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

    fun sendVoiceFile(uri: Uri, replyToId: Long? = null) {
        val conversation = chat.value.conversation ?: return
        chat.value = chat.value.copy(sendingFile = true)
        viewModelScope.launch {
            try {
                val url = repository.upload(uri)
                val sent = repository.sendAttachment(
                    conversation.id, url, "audio",
                    UUID.randomUUID().toString(), "Pesan suara", replyToId,
                )
                incomingMessage(conversation.id, sent)
                refreshConversations()
            } catch (e: Exception) {
                handleError(e)
            } finally {
                chat.value = chat.value.copy(sendingFile = false)
            }
        }
    }

    // ------------------------------------------------------------ GIF Giphy

    fun loadTrendingGifs() {
        if (!GifClient.hasKey) return
        gifLoading.value = true
        viewModelScope.launch {
            try {
                gifResults.value = GifClient.trending()
            } catch (e: Exception) {
                handleError(e)
            } finally {
                gifLoading.value = false
            }
        }
    }

    fun searchGifs(query: String) {
        if (!GifClient.hasKey || query.trim().length < 2) return
        gifLoading.value = true
        viewModelScope.launch {
            try {
                gifResults.value = GifClient.search(query)
            } catch (e: Exception) {
                handleError(e)
            } finally {
                gifLoading.value = false
            }
        }
    }

    fun clearGifs() {
        gifResults.value = emptyList()
    }

    /**
     * Kirim GIF: unduh ke cache, unggah sebagai lampiran image, kirim.
     * Server memvalidasi kepemilikan upload, jadi URL remote mentah ditolak.
     */
    fun sendGif(remoteUrl: String) {
        val conversation = chat.value.conversation ?: return
        chat.value = chat.value.copy(sendingFile = true)
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val name = "gif_${System.currentTimeMillis()}.gif"
                val file = MediaHelper.downloadToCache(app, remoteUrl, name)
                val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
                val url = repository.upload(uri)
                runCatching { file.delete() }
                val sent = repository.sendAttachment(
                    conversation.id, url, "image",
                    UUID.randomUUID().toString(), "GIF", null,
                )
                incomingMessage(conversation.id, sent)
                refreshConversations()
            } catch (e: Exception) {
                handleError(e)
            } finally {
                chat.value = chat.value.copy(sendingFile = false)
            }
        }
    }

    // ------------------------------------------------------- unduh & bagikan

    fun downloadMedia(url: String, nameHint: String?, onSaved: (Uri, String) -> Unit = { _, _ -> }) {
        if (downloading.value != null) return
        downloading.value = url
        viewModelScope.launch {
            try {
                val saved = MediaHelper.download(getApplication(), url, nameHint)
                message.value = "Tersimpan: ${saved.displayName}."
                onSaved(saved.uri, saved.mime)
            } catch (e: Exception) {
                handleError(e)
            } finally {
                downloading.value = null
            }
        }
    }

    fun exportChat(conversation: Conversation) {
        viewModelScope.launch {
            try {
                // Relay: server hanya menyimpan antrean — ekspor dari cache perangkat.
                val cached = runCatching { chatCache.recent(conversation.id, 100) }.getOrDefault(emptyList())
                val liveNames = runCatching { repository.conversationDetail(conversation.id) }
                    .getOrNull()?.members.orEmpty()
                    .associate { it.id to it.displayName }
                val lines = cached.map { e2ee.decryptMessage(it) }.map { msg ->
                    val name = msg.sender?.familiarName ?: "?"
                    val text = when (msg.type) {
                        "text" -> msg.body.orEmpty()
                        "sticker" -> msg.body.orEmpty()
                        "image" -> "[Foto]"
                        "video" -> "[Video]"
                        "audio" -> "[Audio]"
                        "location" -> "[Lokasi]"
                        "redpacket" -> "[Amplop merah]"
                        "system" -> renderSystemText(msg.body, liveNames)
                        else -> "[Lampiran]"
                    }
                    "[${msg.createdAt}] $name: $text"
                }
                val header = "Riwayat chat: ${conversation.displayTitle}\nDiekspor ${nowIso()} via NusaChat\n\n"
                MediaHelper.shareText(getApplication(), header + lines.joinToString("\n"), "Ekspor chat")
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun shareText(text: String, title: String = "Bagikan") {
        MediaHelper.shareText(getApplication(), text, title)
    }

    // ---------------------------------------------------------------- laporan

    fun createReport(targetType: String, targetId: Long, reason: String, details: String = "") {
        viewModelScope.launch {
            try {
                repository.createReport(targetType, targetId, reason, details)
                message.value = "Laporan terkirim. Terima kasih."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun loadReports() {
        viewModelScope.launch {
            try {
                myReports.value = repository.myReports()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    // ------------------------------------------------- momen & story lanjutan

    fun createMomentAudience(text: String, imageUris: List<Uri>, audience: String) {
        if (creatingMoment.value) return
        creatingMoment.value = true
        viewModelScope.launch {
            try {
                val urls = imageUris.map { repository.upload(it) }
                val moment = repository.createMoment(text.trim(), urls, audience)
                moments.value = listOf(moment) + moments.value
                message.value = if (audience == "private") "Momen privat tersimpan." else "Momen dibagikan."
            } catch (e: Exception) {
                handleError(e)
            } finally {
                creatingMoment.value = false
            }
        }
    }

    fun editMoment(moment: Moment, body: String) {
        viewModelScope.launch {
            try {
                val updated = repository.editMoment(moment.id, body.trim())
                moments.value = moments.value.map { if (it.id == moment.id) it.copy(body = updated.body, editedAt = updated.editedAt) else it }
                message.value = "Momen diubah."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun loadLikers(momentId: Long) {
        likersMomentId.value = momentId
        viewModelScope.launch {
            try {
                likers.value = repository.momentLikers(momentId)
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun clearLikers() {
        likersMomentId.value = null
        likers.value = emptyList()
    }

    fun deleteComment(comment: MomentComment) {
        viewModelScope.launch {
            try {
                repository.deleteComment(comment.id)
                comments.value = comments.value.filterNot { it.id == comment.id || it.parentId == comment.id }
                commentsMomentId.value?.let { mid ->
                    moments.value = moments.value.map {
                        if (it.id == mid) it.copy(commentCount = (it.commentCount - 1).coerceAtLeast(0)) else it
                    }
                }
                message.value = "Komentar dihapus."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun toggleCommentLike(comment: MomentComment) {
        val like = !comment.likedByMe
        comments.value = comments.value.map {
            if (it.id == comment.id) it.copy(likedByMe = like, likeCount = (it.likeCount + if (like) 1 else -1).coerceAtLeast(0)) else it
        }
        viewModelScope.launch {
            try {
                val count = repository.toggleCommentLike(comment.id, like)
                comments.value = comments.value.map {
                    if (it.id == comment.id) it.copy(likedByMe = like, likeCount = count) else it
                }
            } catch (e: Exception) {
                comments.value = comments.value.map { if (it.id == comment.id) comment else it }
                handleError(e)
            }
        }
    }

    // ------------------------------------------------------- info dibaca pesan
    val readsTitle = MutableStateFlow("")
    val readsUsers = MutableStateFlow<List<id.nusachat.app.data.User>>(emptyList())
    val readsLoading = MutableStateFlow(false)

    fun loadMessageReads(conversationId: Long, message: id.nusachat.app.data.Message) {
        readsTitle.value = message.body?.take(60)?.ifBlank { null }
            ?: when (message.type) {
                "image" -> "Foto"
                "video" -> "Video"
                "audio", "voice" -> "Pesan suara"
                "file" -> "Berkas"
                "location" -> "Lokasi"
                "contact" -> "Kontak"
                "sticker" -> "Stiker"
                "redpacket" -> "Angpao"
                else -> "Pesan"
            }
        readsUsers.value = emptyList()
        readsLoading.value = true
        viewModelScope.launch {
            try {
                readsUsers.value = repository.messageReads(conversationId, message.id)
            } catch (e: Exception) {
                handleError(e)
            } finally {
                readsLoading.value = false
            }
        }
    }

    fun clearMessageReads() {
        readsTitle.value = ""
        readsUsers.value = emptyList()
    }

    // ------------------------------------------------------------- umpan tagar
    val tagFeedTag = MutableStateFlow<String?>(null)
    val tagFeed = MutableStateFlow<List<id.nusachat.app.data.Moment>>(emptyList())
    val tagFeedLoading = MutableStateFlow(false)

    fun openTagFeed(tag: String) {
        tagFeedTag.value = tag
        tagFeed.value = emptyList()
        tagFeedLoading.value = true
        viewModelScope.launch {
            try {
                tagFeed.value = repository.momentsByTag(tag)
            } catch (e: Exception) {
                handleError(e)
            } finally {
                tagFeedLoading.value = false
            }
        }
    }

    fun closeTagFeed() {
        tagFeedTag.value = null
        tagFeed.value = emptyList()
    }

    fun viewStory(story: id.nusachat.app.data.Story) {
        stories.value = stories.value.map {
            if (it.id == story.id) it.copy(viewedByMe = true) else it
        }
        viewModelScope.launch {
            runCatching { repository.viewStory(story.id) }
        }
    }

    fun loadStoryViewers(storyId: Long) {
        storyViewersId.value = storyId
        viewModelScope.launch {
            try {
                val (count, viewers) = repository.storyViewers(storyId)
                storyViewerCount.value = count
                storyViewers.value = viewers
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun clearStoryViewers() {
        storyViewersId.value = null
        storyViewers.value = emptyList()
        storyViewerCount.value = 0
    }

    fun shareStoryToChat(story: id.nusachat.app.data.Story, conversationId: Long) {
        viewModelScope.launch {
            try {
                val text = buildString {
                    append("Story ${story.author.familiarName}")
                    if (story.body.isNotBlank()) append(": ${story.body}")
                    story.media.firstOrNull()?.let { append("\n$it") }
                }
                repository.sendMessage(conversationId, encryptFor(conversationId, text), UUID.randomUUID().toString())
                refreshConversations()
                message.value = "Story dibagikan ke chat."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    // ------------------------------------------------- teman & grup lanjutan

    fun sendFriendRequestWithMessage(user: User, greeting: String) {
        viewModelScope.launch {
            try {
                repository.sendFriendRequest(user.id, greeting.trim())
                searchResults.value = searchResults.value.map {
                    if (it.id == user.id) it.copy(friendStatus = "pending") else it
                }
                loadRequests()
                message.value = "Permintaan dikirim ke ${user.displayName}."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun setRemark(userId: Long, remark: String?) {
        viewModelScope.launch {
            try {
                repository.setRemark(userId, remark?.trim()?.ifBlank { null })
                loadFriends()
                refreshConversations()
                message.value = if (remark.isNullOrBlank()) "Nama panggilan dihapus." else "Nama panggilan disimpan."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun updateGroupDescription(description: String) {
        val conversation = chat.value.conversation ?: chat.value.detail ?: return
        viewModelScope.launch {
            try {
                val updated = repository.updateGroupInfo(conversation.id, null, null, description.trim())
                chat.value = chat.value.copy(detail = updated, conversation = updated)
                refreshConversations()
                message.value = "Deskripsi grup diperbarui."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun transferOwnership(userId: Long) {
        val conversation = chat.value.conversation ?: chat.value.detail ?: return
        viewModelScope.launch {
            try {
                repository.transferOwnership(conversation.id, userId)
                loadConversationDetail()
                refreshConversations()
                message.value = "Kepemilikan grup dialihkan."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    // ------------------------------------------------------------- riwayat call

    fun loadCallHistory() {
        if (sessionState.value.user == null) return
        callHistoryLoading.value = true
        viewModelScope.launch {
            try {
                val list = calls.history()
                callHistory.value = list
                val seen = settings.lastSeenCallId()
                missedCalls.value = list.count { it.missed && it.call.id > seen }
            } catch (e: Exception) {
                // Riwayat gagal dimuat: jangan ganggu layar lain.
            } finally {
                callHistoryLoading.value = false
            }
        }
    }

    fun markCallsSeen() {
        val maxId = callHistory.value.maxOfOrNull { it.call.id } ?: 0L
        settings.markCallsSeen(maxId)
        missedCalls.value = 0
    }

    // ------------------------------------------------- dompet: PIN & tagihan

    fun loadWalletFull() {
        viewModelScope.launch {
            try {
                val (balance, hasPin) = repository.walletInfo()
                walletBalance.value = balance
                walletHasPin.value = hasPin
                walletTxns.value = repository.walletHistory()
                loadWalletRequests()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun loadWalletRequests() {
        viewModelScope.launch {
            try {
                val (incoming, outgoing) = repository.walletRequests()
                walletIncoming.value = incoming
                walletOutgoing.value = outgoing
            } catch (e: Exception) {
                // Tagihan opsional: jangan gagalkan layar dompet.
            }
        }
    }

    fun setWalletPin(pin: String, oldPin: String? = null, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                walletHasPin.value = repository.setWalletPin(pin, oldPin)
                message.value = "PIN dompet disimpan."
                onDone()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun walletTransferPin(
        toUser: User,
        amount: Long,
        note: String,
        pin: String?,
        onPinRequired: () -> Unit,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                walletBalance.value = repository.walletTransfer(toUser.id, amount, note.ifBlank { null }, pin)
                walletTxns.value = repository.walletHistory()
                message.value = "Terkirim ke ${toUser.displayName}."
                onDone()
            } catch (e: Exception) {
                if (e is ApiException && e.code == "pin_required") onPinRequired()
                else handleError(e)
            }
        }
    }

    fun requestMoney(toUser: User, amount: Long, note: String) {
        viewModelScope.launch {
            try {
                repository.requestMoney(toUser.id, amount, note.ifBlank { null })
                loadWalletRequests()
                message.value = "Tagihan dikirim ke ${toUser.displayName}."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun payWalletRequest(item: WalletRequestItem, pin: String?, onPinRequired: () -> Unit) {
        viewModelScope.launch {
            try {
                walletBalance.value = repository.payWalletRequest(item.id, pin)
                walletTxns.value = repository.walletHistory()
                loadWalletRequests()
                message.value = "Tagihan dibayar."
            } catch (e: Exception) {
                if (e is ApiException && e.code == "pin_required") onPinRequired()
                else handleError(e)
            }
        }
    }

    fun declineWalletRequest(item: WalletRequestItem) {
        viewModelScope.launch {
            try {
                repository.declineWalletRequest(item.id)
                loadWalletRequests()
                message.value = "Tagihan ditolak."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun cancelWalletRequest(item: WalletRequestItem) {
        viewModelScope.launch {
            try {
                repository.cancelWalletRequest(item.id)
                loadWalletRequests()
                message.value = "Tagihan dibatalkan."
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    // ------------------------------------------------------------- amplop merah

    fun createRedPacket(total: Long, count: Int, note: String, pin: String?, onPinRequired: () -> Unit, onDone: () -> Unit = {}) {
        val conversation = chat.value.conversation ?: return
        viewModelScope.launch {
            try {
                val (packet, msg) = repository.createRedPacket(
                    conversation.id, total, count, note.ifBlank { null }, pin,
                    UUID.randomUUID().toString(),
                )
                incomingMessage(conversation.id, msg)
                walletBalance.value = repository.wallet()
                redPacket.value = packet
                onDone()
            } catch (e: Exception) {
                if (e is ApiException && e.code == "pin_required") onPinRequired()
                else handleError(e)
            }
        }
    }

    fun loadRedPacket(packetId: Long) {
        redPacketLoading.value = true
        viewModelScope.launch {
            try {
                redPacket.value = repository.redPacket(packetId)
            } catch (e: Exception) {
                handleError(e)
            } finally {
                redPacketLoading.value = false
            }
        }
    }

    fun clearRedPacket() {
        redPacket.value = null
    }

    fun claimRedPacket(packetId: Long) {
        viewModelScope.launch {
            try {
                val (status, amount, packet) = repository.claimRedPacket(packetId)
                redPacket.value = packet
                walletBalance.value = repository.wallet()
                message.value = when (status) {
                    "claimed" -> "Dapat $amount koin!"
                    "already" -> "Kamu sudah mengambil $amount koin."
                    else -> "Amplop sudah habis."
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    // ------------------------------------------------- profil: username & crop

    fun updateUsername(username: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val user = repository.updateProfile(JSONObject().put("username", username.trim().lowercase()))
                sessionState.value = SessionState(user, true)
                message.value = "Username diganti."
                onDone()
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    /** Potong tengah persegi sebelum diunggah agar avatar selalu rapi. */
    fun uploadAvatarCropped(uri: Uri) {
        if (uploadingAvatar.value) return
        uploadingAvatar.value = true
        viewModelScope.launch {
            try {
                val cropped = cropCenterSquare(getApplication(), uri)
                val url = repository.upload(cropped)
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

    private suspend fun cropCenterSquare(app: Application, uri: Uri): Uri =
        withContext(Dispatchers.IO) {
            val fallback = { uri }
            try {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
                val w = opts.outWidth
                val h = opts.outHeight
                if (w <= 0 || h <= 0) return@withContext fallback()
                if (w == h && w <= 1024) return@withContext fallback()
                var sample = 1
                while (w / sample > 1024 || h / sample > 1024) sample *= 2
                val decode = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = app.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, decode)
                } ?: return@withContext fallback()
                val side = minOf(bmp.width, bmp.height)
                val cropped = android.graphics.Bitmap.createBitmap(
                    bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side
                )
                val out = java.io.File(app.cacheDir, "avatar_${System.currentTimeMillis()}.jpg")
                out.outputStream().use { cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                if (cropped != bmp) bmp.recycle()
                androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".fileprovider", out)
            } catch (e: Exception) {
                fallback()
            }
        }
}
