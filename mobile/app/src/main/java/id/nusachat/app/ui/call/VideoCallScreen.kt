package id.nusachat.app.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import id.nusachat.app.data.WebRtcCallManager
import id.nusachat.app.ui.CallUiState
import id.nusachat.app.ui.theme.NusaIcons
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Layar panggilan video 1-1: video lawan layar penuh, PiP sendiri,
 * kontrol bisu/video/ganti kamera/akhiri.
 */
@Composable
fun VideoCallScreen(
    state: CallUiState,
    rtc: WebRtcCallManager,
    onToggleMute: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
    onEnd: () -> Unit,
) {
    val peerName = state.peer?.familiarName ?: "Penelepon"
    var hasRemoteFrame by remember(state.call?.id) { mutableStateOf(false) }
    var localView by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteView by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    LaunchedEffect(localView, remoteView) {
        val local = localView
        val remote = remoteView
        if (local != null && remote != null) {
            rtc.attachRenderers(local, remote)
        }
    }
    DisposableEffect(state.call?.id) {
        rtc.onRemoteVideo = { hasRemoteFrame = true }
        onDispose {
            rtc.onRemoteVideo = null
            rtc.detachRenderers()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setEnableHardwareScaler(true)
                    remoteView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!hasRemoteFrame) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "PANGGILAN VIDEO",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.2.sp,
                )
                Spacer(Modifier.height(18.dp))
                PulsingAvatar(peer = state.peer, name = peerName, active = state.status == "active")
                Spacer(Modifier.height(20.dp))
                Text(
                    text = peerName,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when (state.status) {
                        "active" -> "Menghubungkan video..."
                        "dialing" -> "Memanggil..."
                        else -> "Menyiapkan..."
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                )
            }
        }

        // PiP lokal.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 14.dp)
                .size(width = 104.dp, height = 148.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1B1B1B)),
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                        setMirror(true)
                        localView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (state.videoMuted) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B1B1B)), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = NusaIcons.VideoOff),
                        contentDescription = "Kamera mati",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }

        // Nama + kontrol bawah.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (hasRemoteFrame) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.45f),
                ) {
                    Text(
                        text = peerName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VideoAction(
                    icon = if (state.muted) NusaIcons.MicOff else NusaIcons.Mic,
                    label = if (state.muted) "Aktif" else "Bisukan",
                    active = !state.muted,
                    onClick = onToggleMute,
                    modifier = Modifier.weight(1f),
                )
                VideoAction(
                    icon = if (state.videoMuted) NusaIcons.VideoOff else NusaIcons.Video,
                    label = if (state.videoMuted) "Kamera" else "Matikan",
                    active = !state.videoMuted,
                    onClick = onToggleVideo,
                    modifier = Modifier.weight(1f),
                )
                VideoAction(
                    icon = NusaIcons.SwitchCamera,
                    label = "Balik",
                    active = true,
                    onClick = onSwitchCamera,
                    modifier = Modifier.weight(1f),
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFE5484D),
                        onClick = onEnd,
                        modifier = Modifier.size(62.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = NusaIcons.CallEnd),
                                contentDescription = "Akhiri",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Akhiri", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun VideoAction(
    icon: Int,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Surface(
            shape = CircleShape,
            color = if (active) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f),
            onClick = onClick,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(23.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}
