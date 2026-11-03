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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import id.nusachat.app.data.Message
import id.nusachat.app.ui.theme.NusaIcons

/** Kategori galeri media di info chat. */
object MediaCategory {
    const val VISUAL = "visual"
    const val FILE = "file"
    const val AUDIO = "audio"
    const val LOCATION = "location"
}

/**
 * Section "Media" ala WhatsApp/LINE untuk info kontak/grup.
 *
 * Preview: header + grid foto/video terbaru + ringkasan per kategori.
 * Ketuk apa pun membuka [MediaGalleryScreen] — di sana SEMUA media tampil
 * tanpa dipotong, dikelompokkan per tab.
 */
@Composable
fun ConversationMediaCard(
    media: List<Message>,
    onImageClick: (String) -> Unit,
    onVideoClick: (String) -> Unit,
    onOpenGallery: (String) -> Unit = {},
) {
    if (media.isEmpty()) return
    val photos = media.filter { it.type == "image" && !it.attachmentUrl.isNullOrBlank() }
    val videos = media.filter { it.type == "video" && !it.attachmentUrl.isNullOrBlank() }
    val visual = photos + videos
    val voices = media.filter { it.type == "audio" && !it.attachmentUrl.isNullOrBlank() }
    val files = media.filter {
        it.type != "text" && it.type != "image" && it.type != "video" &&
            it.type != "audio" && it.type != "location" && it.type != "system" && !it.attachmentUrl.isNullOrBlank()
    }
    val locations = media.filter { it.type == "location" && it.lat != null && it.lng != null }

    // Section flat full-width ala LINE/WhatsApp — tanpa kartu & margin samping.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            // Header ala WhatsApp: judul + jumlah + chevron.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOpenGallery(
                            when {
                                visual.isNotEmpty() -> MediaCategory.VISUAL
                                files.isNotEmpty() -> MediaCategory.FILE
                                voices.isNotEmpty() -> MediaCategory.AUDIO
                                else -> MediaCategory.LOCATION
                            },
                        )
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Media",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Foto, video, file, suara & lokasi dari chat ini",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = "${media.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    painter = painterResource(id = NusaIcons.Next),
                    contentDescription = "Lihat semua media",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Preview grid foto/video terbaru (maks 6) — sisanya di galeri.
            if (visual.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                visual.takeLast(6).reversed().chunked(3).forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 1.5.dp),
                    ) {
                        row.forEach { item ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
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
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(50),
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(5.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                painter = painterResource(id = NusaIcons.Play),
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(10.dp),
                                            )
                                            Spacer(Modifier.width(3.dp))
                                            Text(
                                                text = "Video",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                color = Color.White,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Ringkasan per kategori — ketuk membuka tab-nya di galeri.
            if (visual.isNotEmpty()) {
                MediaCategoryRow(
                    icon = NusaIcons.Photo,
                    title = "Foto & video",
                    count = visual.size,
                    onClick = { onOpenGallery(MediaCategory.VISUAL) },
                )
            }
            if (files.isNotEmpty()) {
                MediaCategoryRow(
                    icon = NusaIcons.File,
                    title = "File",
                    count = files.size,
                    onClick = { onOpenGallery(MediaCategory.FILE) },
                )
            }
            if (voices.isNotEmpty()) {
                MediaCategoryRow(
                    icon = NusaIcons.AudioFile,
                    title = "Pesan suara",
                    count = voices.size,
                    onClick = { onOpenGallery(MediaCategory.AUDIO) },
                )
            }
            if (locations.isNotEmpty()) {
                MediaCategoryRow(
                    icon = NusaIcons.Pin,
                    title = "Lokasi",
                    count = locations.size,
                    onClick = { onOpenGallery(MediaCategory.LOCATION) },
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.7.dp,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOpenGallery(
                            when {
                                visual.isNotEmpty() -> MediaCategory.VISUAL
                                files.isNotEmpty() -> MediaCategory.FILE
                                voices.isNotEmpty() -> MediaCategory.AUDIO
                                else -> MediaCategory.LOCATION
                            },
                        )
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Lihat semua media",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun MediaCategoryRow(
    icon: Int,
    title: String,
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            painter = painterResource(id = NusaIcons.Next),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}
