package id.nusachat.app.ui.call
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaFilledIconButton

import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import id.nusachat.app.ui.theme.NusaIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusachat.app.ui.CallUiState
import id.nusachat.app.ui.components.NusaAvatar
import kotlinx.coroutines.delay

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
    val statusText = when (state.status) {
        "dialing" -> if (state.outgoing) "Memanggil..." else "Panggilan masuk..."
        "ringing" -> "Panggilan masuk..."
        "active" -> "Terhubung"
        "ended" -> "Berakhir"
        else -> ""
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NusaAvatar(name = peerName, url = state.peer?.avatarUrl, gender = state.peer?.gender, seed = state.peer?.id, size = 110.dp)
            Spacer(Modifier.height(18.dp))
            Text(peerName, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            state.peer?.let {
                Text("@${it.username}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(36.dp))
            if (state.status == "ringing" && !state.outgoing) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    NusaFilledIconButton(
                        onClick = onReject,
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(painter = painterResource(id = NusaIcons.CallEnd), contentDescription = "Tolak", modifier = Modifier.size(28.dp))
                    }
                    NusaFilledIconButton(
                        onClick = onAnswer,
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(painter = painterResource(id = NusaIcons.Call), contentDescription = "Jawab", modifier = Modifier.size(28.dp))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        NusaIconButton(
                            onClick = onToggleMute,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.muted) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (state.muted) NusaIcons.MicOff else NusaIcons.Mic
                                ),
                                contentDescription = "Bisukan",
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Text(if (state.muted) "Senyap" else "Mic", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        NusaFilledIconButton(onClick = onEnd, modifier = Modifier.size(64.dp)) {
                            Icon(painter = painterResource(id = NusaIcons.CallEnd), contentDescription = "Akhiri", modifier = Modifier.size(28.dp))
                        }
                        Text("Akhiri", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        NusaIconButton(
                            onClick = onToggleSpeaker,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.speaker) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                        ) {
                            Icon(painter = painterResource(id = NusaIcons.VolumeUp), contentDescription = "Speaker", modifier = Modifier.size(26.dp))
                        }
                        Text("Speaker", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun IncomingCallScreen(
    state: CallUiState,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
) {
    var dots by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            dots = if (dots.length >= 3) "" else dots + "•"
        }
    }
    val peerName = state.peer?.displayName ?: "Penelepon"
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Panggilan suara masuk",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            NusaAvatar(name = peerName, url = state.peer?.avatarUrl, gender = state.peer?.gender, seed = state.peer?.id, size = 120.dp)
            Spacer(Modifier.height(20.dp))
            Text(peerName, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            state.peer?.let {
                Text("@${it.username}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Menghubungimu$dots",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NusaFilledIconButton(
                        onClick = onReject,
                        modifier = Modifier.size(68.dp),
                    ) {
                        Icon(painter = painterResource(id = NusaIcons.CallEnd), contentDescription = "Tolak", modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Tolak", style = MaterialTheme.typography.labelMedium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    NusaFilledIconButton(
                        onClick = onAnswer,
                        modifier = Modifier.size(68.dp),
                    ) {
                        Icon(painter = painterResource(id = NusaIcons.Call), contentDescription = "Jawab", modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Jawab", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
