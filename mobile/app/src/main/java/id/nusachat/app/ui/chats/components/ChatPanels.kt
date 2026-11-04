package id.nusachat.app.ui.chats.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusachat.app.ui.theme.NusaIcons

/**
 * Panel inline di bawah kolom ketik (pengganti bottom sheet "Kirim lampiran"
 * dan pemilih emoji): Foto, Video, Audio, File, Lokasi, Amplop merah.
 */
@Composable
fun AttachPanel(
    onFoto: () -> Unit,
    onVideo: () -> Unit,
    onAudio: () -> Unit,
    onFile: () -> Unit,
    onLokasi: () -> Unit,
    onAmplop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        Triple("Foto", NusaIcons.Photo, onFoto),
        Triple("Video", NusaIcons.Video, onVideo),
        Triple("Audio", NusaIcons.AudioFile, onAudio),
        Triple("File", NusaIcons.Attach, onFile),
        Triple("Lokasi", NusaIcons.Pin, onLokasi),
        Triple("Amplop merah", NusaIcons.Gift, onAmplop),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        items.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { (label, icon, onClick) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(onClick = onClick)
                            .padding(vertical = 10.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(52.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    CircleShape,
                                ),
                        ) {
                            Icon(
                                painter = painterResource(id = icon),
                                contentDescription = label,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Kategori panel emoji inline. */
private val EMOJI_TABS = listOf("Sering Digunakan", "Smiley", "Emoji")

/**
 * Panel emoji inline: sisipkan ke draf (bukan kirim langsung).
 * Kategori: Sering Digunakan, Smiley, Emoji.
 */
@Composable
fun EmojiPanel(
    recents: List<String>,
    onPick: (String) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(if (recents.isEmpty()) 1 else 0) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EMOJI_TABS.forEachIndexed { index, title ->
                val selected = tab == index
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { tab = index }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBackspace),
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.Backspace),
                    contentDescription = "Hapus",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        val emojis = when (tab) {
            0 -> recents
            1 -> SMILEY_EMOJIS
            else -> EXTRA_EMOJIS
        }
        if (emojis.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(232.dp)
                    .padding(horizontal = 32.dp),
            ) {
                Text(
                    text = "Belum ada — emoji yang dipakai muncul di sini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(232.dp)
                    .padding(horizontal = 8.dp),
            ) {
                items(emojis) { emoji ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onPick(emoji) }
                            .padding(vertical = 7.dp),
                    ) {
                        Text(text = emoji, fontSize = 26.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Panel GIF inline: cari + grid, kirim langsung saat dipilih.
 * Kolom cari boleh memanggil keyboard; panel tetap terbuka.
 */
@Composable
fun GifPanel(
    results: List<id.nusachat.app.data.GifClient.Gif>,
    loading: Boolean,
    hasKey: Boolean,
    onSearch: (String) -> Unit,
    onPick: (id.nusachat.app.data.GifClient.Gif) -> Unit,
    onManualUrl: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var manual by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "GIF",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.Close),
                    contentDescription = "Tutup",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (hasKey) {
            id.nusachat.app.ui.components.NusaTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (it.trim().length >= 2) onSearch(it)
                },
                placeholder = { Text("Cari GIF...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            if (loading) {
                id.nusachat.app.ui.components.NusaProgress(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp),
                )
            } else if (results.isEmpty()) {
                Text(
                    text = "Tidak ada hasil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.height(204.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(results, key = { it.id }) { gif ->
                        coil.compose.AsyncImage(
                            model = gif.previewUrl,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .height(96.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable { onPick(gif) },
                        )
                    }
                }
            }
        } else {
            Text(
                text = "Pencarian GIF butuh kunci API Giphy (build dengan -PGIF_API_KEY=...). Sementara itu, tempel tautan GIF langsung:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            id.nusachat.app.ui.components.NusaTextField(
                value = manual,
                onValueChange = { manual = it },
                placeholder = { Text("https://...gif") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            id.nusachat.app.ui.components.NusaButton(
                onClick = { onManualUrl(manual.trim()) },
                enabled = manual.trim().startsWith("http"),
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ) {
                Text("Kirim GIF", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private val SMILEY_EMOJIS = listOf(
    "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😎",
    "🥰", "😋", "😛", "🤑", "🤗", "🤔", "😐", "🙄",
    "😏", "😴", "😷", "🤒", "🤕", "😷", "🤧", "🥳",
    "😺", "😸", "😹", "😻", "😼", "😽", "🙀", "😿",
    "😾", "🙈", "🙉", "🙊", "👶", "🧒", "👦", "👧",
    "🧑", "👨", "👩", "🧓", "👴", "👵", "👮", "💂",
    "🕵", "👷", "🤴", "👸", "👳", "👲", "🧕", "🤰",
    "🤱", "👼", "🎅", "🤶", "🦸", "🦹", "🧙", "🧚",
)

private val EXTRA_EMOJIS = listOf(
    "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "👋",
    "🙏", "👏", "💪", "🙌", "👐", "🤲", "🤝", "✍️",
    "💅", "👂", "👃", "👣", "👀", "🧠", "🗣️", "👤",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
    "💔", "💕", "💖", "💗", "💘", "💝", "💞", "💟",
    "🎉", "🎊", "🎈", "🎁", "🏆", "🥇", "⚽", "🏀",
    "🌹", "🌷", "🌻", "🍎", "🍕", "🍔", "🍜", "☕",
    "🚗", "✈️", "🏠", "⏰", "💡", "🔥", "⭐", "✅",
)
