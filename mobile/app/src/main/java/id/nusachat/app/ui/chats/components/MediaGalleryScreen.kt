package id.nusachat.app.ui.chats.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import id.nusachat.app.data.Message
import id.nusachat.app.ui.components.NusaIconButton
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaTopAppBar
import id.nusachat.app.ui.theme.NusaIcons

/**
 * Galeri media full-screen ala WhatsApp: tab Foto & video / File /
 * Pesan suara / Lokasi. Menampilkan SEMUA media (terbaru dulu),
 * tidak dipotong seperti preview di info chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGalleryScreen(
    media: List<Message>,
    initialCategory: String,
    onBack: () -> Unit,
    onImageClick: (String) -> Unit,
    onVideoClick: (String) -> Unit,
    hasMore: Boolean = false,
    onLoadOlder: () -> Unit = {},
) {
    val photos = media.filter { it.type == "image" && !it.attachmentUrl.isNullOrBlank() }
    val videos = media.filter { it.type == "video" && !it.attachmentUrl.isNullOrBlank() }
    val visual = (photos + videos).reversed()
    val voices = media.filter { it.type == "audio" && !it.attachmentUrl.isNullOrBlank() }.reversed()
    val files = media.filter {
        it.type != "text" && it.type != "image" && it.type != "video" &&
            it.type != "audio" && it.type != "location" && !it.attachmentUrl.isNullOrBlank()
    }.reversed()
    val locations = media.filter { it.type == "location" && it.lat != null && it.lng != null }.reversed()

    val categories = buildList {
        if (visual.isNotEmpty()) add(MediaCategory.VISUAL to "Foto & video")
        if (files.isNotEmpty()) add(MediaCategory.FILE to "File")
        if (voices.isNotEmpty()) add(MediaCategory.AUDIO to "Pesan suara")
        if (locations.isNotEmpty()) add(MediaCategory.LOCATION to "Lokasi")
    }
    var selected by remember(initialCategory) {
        mutableStateOf(
            categories.indexOfFirst { it.first == initialCategory }.coerceAtLeast(0),
        )
    }
    var playingAudioKey by remember { mutableStateOf<String?>(null) }
    val textColor = MaterialTheme.colorScheme.onSurface

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NusaTopAppBar(
                title = {
                    Column {
                        Text("Media")
                        Text(
                            text = "${media.size} item dari chat ini",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    NusaIconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Back),
                            contentDescription = "Kembali",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Chip tab custom (bukan TabRow bawaan).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEachIndexed { index, (key, label) ->
                    val active = index == selected
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.clickable { selected = index },
                    ) {
                        Text(
                            text = "$label (${
                                when (key) {
                                    MediaCategory.VISUAL -> visual.size
                                    MediaCategory.FILE -> files.size
                                    MediaCategory.AUDIO -> voices.size
                                    else -> locations.size
                                }
                            })",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            when (categories.getOrNull(selected)?.first) {
                MediaCategory.VISUAL -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(visual, key = { "visual-${it.id}-${it.clientId}" }) { item ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
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
                                        .size(34.dp)
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
                    if (hasMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                NusaTextButton(onClick = onLoadOlder) {
                                    Text("Muat dari pesan lama")
                                }
                            }
                        }
                    }
                }

                MediaCategory.FILE -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(files, key = { "file-${it.id}-${it.clientId}" }) { item ->
                        FileBubble(
                            url = item.attachmentUrl!!,
                            mine = false,
                            textColor = textColor,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    if (hasMore) {
                        item(key = "more") {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                NusaTextButton(onClick = onLoadOlder) {
                                    Text("Muat dari pesan lama")
                                }
                            }
                        }
                    }
                }

                MediaCategory.AUDIO -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(voices, key = { "audio-${it.id}-${it.clientId}" }) { item ->
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
                        Spacer(Modifier.height(6.dp))
                    }
                    if (hasMore) {
                        item(key = "more") {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                NusaTextButton(onClick = onLoadOlder) {
                                    Text("Muat dari pesan lama")
                                }
                            }
                        }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(locations, key = { "loc-${it.id}-${it.clientId}" }) { item ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            LocationBubble(
                                message = item,
                                mine = false,
                                textColor = textColor,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (hasMore) {
                        item(key = "more") {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                NusaTextButton(onClick = onLoadOlder) {
                                    Text("Muat dari pesan lama")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
