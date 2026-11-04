package id.nusachat.app.ui.call

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusachat.app.data.User
import id.nusachat.app.ui.CallUiState
import id.nusachat.app.ui.GroupCallUiState
import id.nusachat.app.ui.components.NusaAvatar
import id.nusachat.app.ui.components.NusaFilledIconButton
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.theme.NusaGreen
import id.nusachat.app.ui.theme.NusaIcons
import kotlinx.coroutines.delay

// --------------------------------------------------------------------- palette

private val CallRed = Color(0xFFFF4D5E)
private val CallNightTop = Color(0xFF06110C)
private val CallNightMid = Color(0xFF0B2117)
private val CallNightBottom = Color(0xFF030806)
private val CallTextDim = Color.White.copy(alpha = 0.62f)

private fun Modifier.callBackdrop(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(CallNightTop, CallNightMid, CallNightBottom)))

    // Cahaya hijau lembut di belakang avatar.
    val topGlow = Offset(size.width * 0.5f, size.height * 0.28f)
    val topRadius = size.width * 0.85f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                NusaGreen.copy(alpha = 0.28f),
                NusaGreen.copy(alpha = 0.07f),
                Color.Transparent,
            ),
            center = topGlow,
            radius = topRadius,
        ),
        radius = topRadius,
        center = topGlow,
    )

    // Aksen biru dingin di sudut bawah agar tidak monoton.
    val bottomGlow = Offset(size.width * 0.88f, size.height * 0.82f)
    val bottomRadius = size.width * 0.6f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF2F7BFF).copy(alpha = 0.13f), Color.Transparent),
            center = bottomGlow,
            radius = bottomRadius,
        ),
        radius = bottomRadius,
        center = bottomGlow,
    )
}

// ------------------------------------------------------------------ call screen

@Composable
fun VoiceCallScreen(
    state: CallUiState,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
) {
    val peerName = state.peer?.displayName ?: "Penelepon"
    val username = state.peer?.username?.takeIf { it.isNotBlank() }
    val isActive = state.status == "active"
    val duration = rememberCallDuration(state.status, state.call?.id)
    val dots = rememberDots(isActive.not() && state.status != "ended")

    val statusText = when (state.status) {
        "dialing" -> if (state.outgoing) "Memanggil$dots" else "Menghubungkan$dots"
        "ringing" -> "Panggilan masuk$dots"
        "active" -> "Terhubung"
        "ended" -> "Panggilan berakhir"
        else -> "Menyiapkan panggilan$dots"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .callBackdrop()
            .systemBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CallTopBar()

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "PANGGILAN SUARA",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.2.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    PulsingAvatar(peer = state.peer, name = peerName, active = isActive)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = peerName,
                        color = Color.White,
                        fontSize = 29.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.4).sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (username != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "@$username",
                            color = CallTextDim,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    CallStatusPill(
                        text = statusText,
                        timer = if (isActive) duration else null,
                        active = isActive,
                    )
                }

            CallActionDock(
                muted = state.muted,
                speaker = state.speaker,
                onEnd = onEnd,
                onToggleMute = onToggleMute,
                onToggleSpeaker = onToggleSpeaker,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun IncomingCallScreen(
    state: CallUiState,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
) {
    val peerName = state.peer?.displayName ?: "Penelepon"
    val username = state.peer?.username?.takeIf { it.isNotBlank() }
    val dots = rememberDots(active = true)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .callBackdrop()
            .systemBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CallTopBar()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (state.video) "PANGGILAN VIDEO MASUK" else "PANGGILAN MASUK",
                    color = NusaGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.2.sp,
                )
                Spacer(Modifier.height(18.dp))
                PulsingAvatar(peer = state.peer, name = peerName, active = false)
                Spacer(Modifier.height(24.dp))
                Text(
                    text = peerName,
                    color = Color.White,
                    fontSize = 29.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.4).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (username != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "@$username",
                        color = CallTextDim,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(16.dp))
                CallStatusPill(
                    text = "Menghubungimu$dots",
                    timer = null,
                    active = false,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 44.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IncomingAction(
                    icon = NusaIcons.CallEnd,
                    label = "Tolak",
                    container = CallRed,
                    contentColor = Color.White,
                    onClick = onReject,
                )
                IncomingAction(
                    icon = NusaIcons.Call,
                    label = "Jawab",
                    container = NusaGreen,
                    contentColor = Color(0xFF04220F),
                    onClick = onAnswer,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ------------------------------------------------------------------- building blocks

@Composable
private fun CallTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(NusaGreen),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "NusaChat",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = NusaIcons.Shield),
                contentDescription = null,
                tint = NusaGreen,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Terenkripsi",
                color = CallTextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
internal fun PulsingAvatar(
    peer: User?,
    name: String,
    active: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "call-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (active) 3200 else 2000,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse",
    )

    Box(
        modifier = Modifier.size(236.dp),
        contentAlignment = Alignment.Center,
    ) {
        repeat(3) { index ->
            val phase = (pulse + index / 3f) % 1f
            Box(
                modifier = Modifier
                    .size(146.dp + 86.dp * phase)
                    .border(
                        width = 1.dp,
                        color = NusaGreen.copy(alpha = (1f - phase) * 0.26f),
                        shape = CircleShape,
                    ),
            )
        }
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.04f)),
        )
        Box(
            modifier = Modifier
                .size(142.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.5.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        )
        NusaAvatar(
            name = name,
            url = peer?.avatarUrl,
            gender = peer?.gender,
            seed = peer?.id,
            size = 128.dp,
        )
    }
}

@Composable
private fun CallStatusPill(
    text: String,
    timer: String?,
    active: Boolean,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) NusaGreen else Color.White.copy(alpha = 0.7f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.sp,
        )
        if (timer != null) {
            Text(
                text = "  •  ",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 13.sp,
            )
            Text(
                text = timer,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
private fun CallActionDock(
    muted: Boolean,
    speaker: Boolean,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    endLabel: String = "Akhiri",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(32.dp))
            .padding(horizontal = 12.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        CallAction(
            icon = if (muted) NusaIcons.MicOff else NusaIcons.Mic,
            label = if (muted) "Senyap" else "Mic",
            active = muted,
            onClick = onToggleMute,
        )
        EndCallAction(onClick = onEnd)
        CallAction(
            icon = NusaIcons.VolumeUp,
            label = "Speaker",
            active = speaker,
            onClick = onToggleSpeaker,
        )
    }
}

// ------------------------------------------------------------------ group call

@Composable
fun GroupCallScreen(
    state: GroupCallUiState,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onLeave: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
) {
    val participants = state.room?.participants.orEmpty()
    val joined = participants.filter { it.status == "joined" }
    val isRinging = state.status == "ringing"
    val isActive = state.status == "active"
    val duration = rememberCallDuration(state.status, state.room?.call?.id)
    val dots = rememberDots(!isActive && state.status != "ended")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .callBackdrop()
            .systemBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CallTopBar()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "PANGGILAN GRUP",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.2.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = state.title,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                CallStatusPill(
                    text = when {
                        isRinging -> "Panggilan grup masuk$dots"
                        isActive -> "Terhubung • ${joined.size} anggota"
                        state.status == "ended" -> "Panggilan berakhir"
                        else -> "Menyiapkan panggilan$dots"
                    },
                    timer = if (isActive) duration else null,
                    active = isActive,
                )
                if (isActive && joined.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White.copy(alpha = 0.055f))
                            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        joined.take(6).forEach { member ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NusaAvatar(
                                    name = member.user.displayName,
                                    url = member.user.avatarUrl,
                                    gender = member.user.gender,
                                    seed = member.user.id,
                                    size = 30.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = member.user.displayName,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (joined.size > 6) {
                            Text(
                                text = "+${joined.size - 6} anggota lain",
                                color = CallTextDim,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            if (isRinging) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IncomingAction(
                        icon = NusaIcons.Call,
                        label = "Terima",
                        container = NusaGreen,
                        contentColor = Color.White,
                        onClick = onAccept,
                    )
                    IncomingAction(
                        icon = NusaIcons.CallEnd,
                        label = "Tolak",
                        container = CallRed,
                        contentColor = Color.White,
                        onClick = onReject,
                    )
                }
                Spacer(Modifier.height(28.dp))
            } else if (isActive) {
                CallActionDock(
                    muted = state.muted,
                    speaker = state.speaker,
                    onEnd = onLeave,
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker,
                    endLabel = "Keluar",
                )
                Spacer(Modifier.height(28.dp))
            } else {
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun CallAction(
    icon: Int,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (active) NusaGreen.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.07f)
                )
                .border(
                    width = 1.dp,
                    color = if (active) NusaGreen.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f),
                    shape = CircleShape,
                ),
        ) {
            NusaIconButton(
                onClick = onClick,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = label,
                    tint = if (active) NusaGreen else Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = if (active) NusaGreen else CallTextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EndCallAction(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        NusaFilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(62.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = CallRed,
                contentColor = Color.White,
            ),
        ) {
            Icon(
                painter = painterResource(id = NusaIcons.CallEnd),
                contentDescription = "Akhiri panggilan",
                modifier = Modifier.size(27.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Akhiri",
            color = CallRed,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun IncomingAction(
    icon: Int,
    label: String,
    container: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(container.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            NusaFilledIconButton(
                onClick = onClick,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = container,
                    contentColor = contentColor,
                ),
            ) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = label,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------- helpers

@Composable
private fun rememberCallDuration(status: String, callId: Long?): String {
    var seconds by remember(callId) { mutableIntStateOf(0) }
    LaunchedEffect(status, callId) {
        if (status != "active") return@LaunchedEffect
        while (true) {
            delay(1_000)
            seconds += 1
        }
    }
    val minutes = seconds / 60
    val remaining = seconds % 60
    return "%02d:%02d".format(minutes, remaining)
}

@Composable
private fun rememberDots(active: Boolean): String {
    var dots by remember { mutableStateOf("") }
    LaunchedEffect(active) {
        if (!active) {
            dots = ""
            return@LaunchedEffect
        }
        while (true) {
            delay(450)
            dots = if (dots.length >= 3) "" else dots + "."
        }
    }
    return dots
}
