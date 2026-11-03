package id.nusachat.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Audio-only WebRTC call manager (voice call).
 * Signaling (offer/answer/ICE) goes through the NusaChat REST API +
 * realtime websocket; media is peer-to-peer (STUN).
 */
class WebRtcCallManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var onLocalSdp: ((SessionDescription) -> Unit)? = null
    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var localAudio: AudioTrack? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    )

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
        val audioModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
    }

    fun startAsCaller(): SessionDescription? {
        ensureFactory()
        createPeer()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        var offer: SessionDescription? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        peer?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
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
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
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
        peer?.setRemoteDescription(SimpleSdpObserver(), answer)
    }

    fun handleRemoteIce(candidate: JSONObject) {
        runCatching {
            val sdpMid = candidate.optString("sdpMid")
            val sdpMLineIndex = candidate.optInt("sdpMLineIndex", 0)
            val sdp = candidate.optString("candidate")
            if (sdp.isNotBlank()) {
                peer?.addIceCandidate(IceCandidate(sdpMid.ifBlank { "0" }, sdpMLineIndex, sdp))
            }
        }
    }

    fun setMuted(muted: Boolean) {
        localAudio?.setEnabled(!muted)
    }

    fun setSpeakerphone(speaker: Boolean) {
        // JavaAudioDeviceModule routes automatically; speaker toggle handled
        // via AudioManager in the UI layer. Kept here for symmetry.
    }

    fun close() {
        runCatching { localAudio?.dispose() }
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        peer = null
        localAudio = null
    }

    private fun createPeer() {
        closePeerOnly()
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
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        })
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val source: AudioSource = factory!!.createAudioSource(audioConstraints)
        localAudio = factory!!.createAudioTrack("ARDAMSa0", source).apply { setEnabled(true) }
        peer?.addTrack(localAudio, listOf("ARDAMS"))
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
