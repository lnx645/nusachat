package id.nusachat.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRTC call manager 1-1: voice + video. Signaling (offer/answer/ICE)
 * lewat REST API + websocket NusaChat; media peer-to-peer (STUN/TURN
 * Cloudflare dari server). Panggilan grup tetap mesh audio.
 */
class WebRtcCallManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var onLocalSdp: ((SessionDescription) -> Unit)? = null
    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onRemoteVideo: ((VideoTrack) -> Unit)? = null

    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private val iceLock = Any()
    private var remoteDescriptionSet = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var remoteQueued = 0
    private var remoteApplied = 0
    private var localAudio: AudioTrack? = null

    /** Set true sebelum startAsCaller/handleOffer untuk panggilan video. */
    var videoEnabled: Boolean = false
    private var egl: EglBase? = null
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    private var iceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
    )

    /**
     * Pakai iceServers dari server (`GET /calls/ice_servers`, docs/TURN.md).
     * Bentuk: [{urls:[...], username?, credential?}]. Selalu valid: bila
     * kosong/rusak, kembali ke STUN Cloudflare statis.
     */
    fun applyIceServersJson(payload: JSONObject?) {
        val parsed = mutableListOf<PeerConnection.IceServer>()
        runCatching {
            val array = payload?.optJSONArray("iceServers") ?: return@runCatching
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val urls = mutableListOf<String>()
                entry.optJSONArray("urls")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        arr.optString(j).takeIf { it.isNotBlank() }?.let { urls.add(it) }
                    }
                }
                entry.optString("url").takeIf { it.isNotBlank() }?.let { urls.add(it) }
                if (urls.isEmpty()) continue
                val builder = PeerConnection.IceServer.builder(urls)
                val user = entry.optString("username")
                val pass = entry.optString("credential")
                if (user.isNotBlank() && pass.isNotBlank()) {
                    builder.setUsername(user).setPassword(pass)
                }
                parsed.add(builder.createIceServer())
            }
        }
        if (parsed.isNotEmpty()) {
            iceServers = parsed
            Log.d("NusaRtc", "ice servers: ${parsed.size} entries")
        }
    }

    /** True when the native WebRTC library loads on this device. */
    fun isSupported(): Boolean = runCatching {
        ensureFactory()
        factory != null
    }.getOrDefault(false)

    fun ensureFactory() {
        if (factory != null) return
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)
        // Sengaja pakai pemrosesan audio software bawaan WebRTC (AEC/NS):
        // pemaksa hardware di banyak HP tidak didukung sehingga gema/feedback.
        val audioModule = JavaAudioDeviceModule.builder(context)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
    }

    fun startAsCaller(): SessionDescription? {
        ensureFactory()
        createPeer()
        val constraints = MediaConstraints()
        var offer: SessionDescription? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        peer?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                Log.d("NusaRtc", "offer sdp=" + desc.description)
                peer?.setLocalDescription(SimpleSdpObserver(), desc)
                offer = desc
                onLocalSdp?.invoke(desc)
                latch.countDown()
            }
            override fun onCreateFailure(error: String) { latch.countDown() }
        }, constraints)
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        return offer
    }

    fun handleOffer(remoteSdp: String, onAnswerReady: (SessionDescription) -> Unit) {
        ensureFactory()
        createPeer()
        val offer = SessionDescription(SessionDescription.Type.OFFER, remoteSdp)
        peer?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                drainPendingIce()
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (videoEnabled) "true" else "false"))
                }
                peer?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        peer?.setLocalDescription(SimpleSdpObserver(), desc)
                        onLocalSdp?.invoke(desc)
                        onAnswerReady(desc)
                    }
                }, constraints)
            }
        }, offer)
    }

    fun handleAnswer(remoteSdp: String) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, remoteSdp)
        peer?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                drainPendingIce()
            }
        }, answer)
    }

    fun handleRemoteIce(candidate: JSONObject) {
        runCatching {
            val sdpMid = candidate.optString("sdpMid")
            val sdpMLineIndex = candidate.optInt("sdpMLineIndex", 0)
            val sdp = candidate.optString("candidate")
            if (sdp.isBlank()) return@runCatching
            val ice = IceCandidate(sdpMid.ifBlank { "0" }, sdpMLineIndex, sdp)
            // Trickle candidates arriving before the remote description is
            // applied would be dropped by addIceCandidate; queue them instead.
            synchronized(iceLock) {
                val target = peer
                if (!remoteDescriptionSet || target == null) {
                    if (pendingRemoteIce.size < 128) pendingRemoteIce.add(ice)
                    remoteQueued++
                    Log.d("NusaRtc", "remote ice queued total=$remoteQueued")
                    return@runCatching
                }
                remoteApplied++
                Log.d("NusaRtc", "remote ice applied total=$remoteApplied host=" + sdp.contains("typ host"))
                target.addIceCandidate(ice)
            }
        }
    }

    private fun drainPendingIce() {
        synchronized(iceLock) {
            remoteDescriptionSet = true
            val target = peer ?: return
            val queued = pendingRemoteIce.toList()
            pendingRemoteIce.clear()
            queued.forEach { runCatching { target.addIceCandidate(it) } }
        }
    }

    fun setMuted(muted: Boolean) {
        localAudio?.setEnabled(!muted)
    }

    // ---------------------------------------------------------------- video

    fun eglContext(): EglBase.Context {
        val existing = egl
        if (existing != null) return existing.eglBaseContext
        val created = EglBase.create()
        egl = created
        return created.eglBaseContext
    }

    /**
     * Pasang renderer lokal (PiP) + remote (layar penuh). Dipanggil dari UI
     * setelah SurfaceViewRenderer dibuat; aman dipanggil ulang (reattach).
     */
    fun attachRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer = local
        remoteRenderer = remote
        val ctx = runCatching { eglContext() }.getOrNull() ?: return
        local?.let { view ->
            runCatching {
                view.init(ctx, null)
                localVideo?.let { track ->
                    runCatching { track.removeSink(view) }
                    track.addSink(view)
                }
            }
        }
        remote?.let { view ->
            runCatching {
                view.init(ctx, null)
                remoteVideo?.let { track ->
                    runCatching { track.removeSink(view) }
                    track.addSink(view)
                }
            }
        }
    }

    fun detachRenderers() {
        localRenderer?.let { view ->
            runCatching { localVideo?.removeSink(view) }
            runCatching { view.release() }
        }
        remoteRenderer?.let { view ->
            runCatching { remoteVideo?.removeSink(view) }
            runCatching { view.release() }
        }
        localRenderer = null
        remoteRenderer = null
    }

    fun setVideoMuted(muted: Boolean) {
        localVideo?.setEnabled(!muted)
    }

    fun switchCamera() {
        runCatching { (capturer as? org.webrtc.CameraVideoCapturer)?.switchCamera(null) }
    }

    fun close() {
        runCatching { localAudio?.dispose() }
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        peer = null
        localAudio = null
        closeVideo()
    }

    private fun closeVideo() {
        detachRenderers()
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { localVideo?.dispose() }
        capturer = null
        surfaceHelper = null
        videoSource = null
        localVideo = null
        remoteVideo = null
        videoEnabled = false
    }

    private fun createPeer() {
        closePeerOnly()
        synchronized(iceLock) {
            remoteDescriptionSet = false
            pendingRemoteIce.clear()
            remoteQueued = 0
            remoteApplied = 0
        }
        val constraints = MediaConstraints()
        peer = factory?.createPeerConnection(iceServers, constraints, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidate?.invoke(candidate)
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> scope.launch(Dispatchers.Main) { onConnected?.invoke() }
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.CLOSED -> scope.launch(Dispatchers.Main) { onDisconnected?.invoke() }
                    else -> Unit
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d("NusaRtc", "ice=$state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track() as? VideoTrack ?: return
                remoteVideo = track
                scope.launch(Dispatchers.Main) {
                    remoteRenderer?.let { view ->
                        runCatching {
                            track.removeSink(view)
                            track.addSink(view)
                        }
                    }
                    onRemoteVideo?.invoke(track)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        })
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val source: AudioSource = factory!!.createAudioSource(audioConstraints)
        localAudio = factory!!.createAudioTrack("ARDAMSa0", source).apply { setEnabled(true) }
        peer?.addTrack(localAudio, listOf("ARDAMS"))
        if (videoEnabled) {
            startLocalVideo()
        }
    }

    private fun startLocalVideo() {
        val capturer = createCameraCapturer() ?: return
        this.capturer = capturer
        val helper = SurfaceTextureHelper.create("NusaCapture", runCatching { eglContext() }.getOrNull())
            ?: return
        surfaceHelper = helper
        val source = factory!!.createVideoSource(capturer.isScreencast)
        videoSource = source
        capturer.initialize(helper, context, source.capturerObserver)
        runCatching { capturer.startCapture(640, 480, 24) }
        localVideo = factory!!.createVideoTrack("ARDAMSv0", source).apply { setEnabled(true) }
        peer?.addTrack(localVideo, listOf("ARDAMS"))
        localRenderer?.let { view ->
            runCatching {
                localVideo?.removeSink(view)
                localVideo?.addSink(view)
            }
        }
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera1Enumerator(false)
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
        }
        for (name in enumerator.deviceNames) {
            if (!enumerator.isFrontFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
        }
        return null
    }

    private fun closePeerOnly() {
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        peer = null
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
