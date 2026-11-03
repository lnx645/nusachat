package id.nusachat.app.ui.chats.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import id.nusachat.app.data.Message
import id.nusachat.app.ui.theme.NusaIcons

/**
 * Kartu "Media" ala LINE/WhatsApp untuk layar info kontak/grup.
 * Menampilkan SEMUA yang pernah dikirim: foto & video (grid),
 * pesan suara, file, dan lokasi — masing-masing reuse bubble yang sama
 * seperti di chat sehingga tampilan konsisten.
 */
@Composable
fun ConversationMediaCard(
    media: List<Message>,
    onImageClick: (String) -> Unit,
    onVideoClick: (String) -> Unit,
) {
    if (media.isEmpty()) return
    var playingAudioKey by remember { mutableStateOf<String?>(null) }
    val photos = media.filter { it.type == "image" && !it.attachmentUrl.isNullOrBlank() }
    val videos = media.filter { it.type == "video" && !it.attachmentUrl.isNullOrBlank() }
    val voices = media.filter { it.type == "audio" && !it.attachmentUrl.isNullOrBlank() }
    val files = media.filter {
        it.type != "text" && it.type != "image" && it.type != "video" &&
            it.type != "audio" && it.type != "location" && !it.attachmentUrl.isNullOrBlank()
    }
    val locations = media.filter { it.type == "location" && it.lat != null && it.lng != null }
    val textColor = MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Media",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${media.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (photos.isNotEmpty() || videos.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                MediaSectionLabel("Foto & video")
                Spacer(Modifier.height(6.dp))
                (photos + videos).takeLast(9).reversed().chunked(3).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                    ) {
                        row.forEach { item ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        if (item.type == "video") {
                                            item.attachmentUrl?.let(onVideoClick)
                                        } else {
                                            item.attachmentUrl?.let(onImageClick)
                                        }
                                    },
                            ) {
                                AsyncImage(
                                    model = item.attachmentUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (item.type == "video") {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.55f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(id = NusaIcons.Play),
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                        repeat(3 - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (voices.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                MediaSectionLabel("Pesan suara")
                voices.takeLast(5).reversed().forEach { item ->
                    val key = item.clientId ?: "id-${item.id}"
                    VoiceBubble(
                        url = item.attachmentUrl!!,
                        key = key,
                        mine = false,
                        isPlaying = playingAudioKey == key,
                        onToggle = {
                            playingAudioKey = if (playingAudioKey == key) null else key
                        },
                    )
                }
            }

            if (files.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                MediaSectionLabel("File")
                files.takeLast(5).reversed().forEach { item ->
                    FileBubble(
                        url = item.attachmentUrl!!,
                        mine = false,
                        textColor = textColor,
                    )
                }
            }

            if (locations.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                MediaSectionLabel("Lokasi")
                locations.takeLast(3).reversed().forEach { item ->
                    LocationBubble(
                        message = item,
                        mine = false,
                        textColor = textColor,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun MediaSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
