package id.nusachat.app.ui.chats.components
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaMenu

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusachat.app.data.Message
import id.nusachat.app.ui.components.formatClockTime
import id.nusachat.app.ui.theme.NusaIcons

/**
 * Satu bubble chat ala LINE.
 *
 * Aturan ekor: HANYA text murni yang berekor, dan cuma pesan pertama dalam
 * rentetan pengirim yang sama ([compactTop] = false). Maps/location, image,
 * video, voice(audio) & file selalu kartu rounded penuh tanpa ekor.
 * Di grup, bubble lawan menampilkan avatar + nama pengirim.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    quoted: Message?,
    mine: Boolean,
    isGroup: Boolean,
    compactTop: Boolean = false,
    compactBottom: Boolean = false,
    onLongPress: () -> Unit = {},
    onRetry: () -> Unit = {},
    menuOpen: Boolean = false,
    onDismissMenu: () -> Unit = {},
    onReply: (Message) -> Unit = {},
    onRevoke: (Message) -> Unit = {},
    onShowInfo: (Message) -> Unit = {},
    audioPlayingKey: String? = null,
    onAudioToggle: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
) {
    // Gaya LINE: mine hijau segar, other putih/abu, tanpa gembok.
    val bubbleColor = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val textColor = if (mine) Color.White else MaterialTheme.colorScheme.onSurface
    val isTextBubble = (message.type == "text" || message.type.isBlank()) &&
        message.attachmentUrl.isNullOrBlank()
    val hasTail = isTextBubble && !compactTop
    val haptic = LocalHapticFeedback.current
    val shape = when {
        // Sudut sisi ekor dibuat hampir runcing (2.dp) supaya tepi atas
        // bubble mengalir lurus ke ujung ekor — sambungan mulus, tidak ber-nat.
        hasTail && mine -> RoundedCornerShape(topStart = 18.dp, topEnd = 2.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
        hasTail -> RoundedCornerShape(topStart = 2.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
        !isTextBubble -> RoundedCornerShape(16.dp) // media/maps/voice/file: no tail
        else -> RoundedCornerShape(12.dp)
    }
    val verticalPad = if (compactTop || compactBottom) 1.dp else 3.dp

    val showAvatar = isGroup && !mine
    val avatarVisible = showAvatar && !compactBottom
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = verticalPad),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (showAvatar) {
            if (avatarVisible) {
                id.nusachat.app.ui.components.NusaAvatar(
                    name = message.sender?.displayName ?: "?",
                    url = message.sender?.avatarUrl,
                    gender = message.sender?.gender,
                    seed = message.sender?.id,
                    size = 32.dp,
                )
            } else {
                Spacer(modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(7.dp))
        }

        Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            if (!mine && isGroup && message.sender != null && !compactTop) {
                Text(
                    text = message.sender.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }

            // Gutter 6.dp di sisi ekor supaya ujung tail tidak kepotong tepi.
            // Tail digambar DULU lalu bubble menutup 2.dp pangkalnya -> sambungan mulus.
            // shadowElevation 0 saat normal: bayangan cuma di badan bikin ekor kelihatan misah.
            Box(
                modifier = if (hasTail) {
                    if (mine) Modifier.padding(end = 6.dp) else Modifier.padding(start = 6.dp)
                } else {
                    Modifier
                },
            ) {
                if (hasTail) {
                    BubbleTail(
                        mine = mine,
                        color = bubbleColor,
                        modifier = Modifier
                            .align(if (mine) Alignment.TopEnd else Alignment.TopStart)
                            .offset(
                                x = if (mine) 6.dp else (-6).dp,
                                y = 0.dp,
                            ),
                    )
                }
                val bubbleShape = shape
                Surface(
                    shape = bubbleShape,
                    color = bubbleColor,
                    tonalElevation = 0.dp,
                    shadowElevation = if (menuOpen) 4.dp else 0.dp,
                    modifier = Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        },
                    ),
                ) {
                    BubbleContent(
                        message = message,
                        quoted = quoted,
                        mine = mine,
                        textColor = textColor,
                        onRetry = onRetry,
                        audioPlayingKey = audioPlayingKey,
                        onAudioToggle = onAudioToggle,
                        onImageClick = onImageClick,
                    )
                }
                // Menu apung custom yang ditempel LANGSUNG ke bubble ini
                // (DropdownMenu = anchor otomatis, dijamin nempel).
                NusaMenu(
                    expanded = menuOpen,
                    onDismissRequest = onDismissMenu,
                    shape = RoundedCornerShape(24.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 8.dp,
                    tonalElevation = 0.dp,
                    modifier = Modifier.width(216.dp),
                ) {
                    MessageMenuContent(
                        message = message,
                        mine = mine,
                        onReply = onReply,
                        onRevoke = onRevoke,
                        onRetry = onRetry,
                        onShowInfo = onShowInfo,
                        onDismiss = onDismissMenu,
                    )
                }
            }

            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatClockTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    // Jam di atas background chat biru LINE — pakai token kontras
                    // (inverseOnSurface) agar terbaca di light & dark.
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                if (mine) {
                    Spacer(Modifier.width(3.dp))
                    MessageStatus(status = message.status, pending = message.pending, light = true)
                }
            }
        }

        if (mine) {
            Spacer(Modifier.width(7.dp))
            // LINE tidak tampilkan avatar untuk pesan sendiri — ruang kosong biar sejajar
            Spacer(Modifier.width(2.dp))
        }
    }
}

/** Isi bubble: quoted reply, konten media, teks/caption, tombol kirim ulang. */
@Composable
private fun BubbleContent(
    message: Message,
    quoted: Message?,
    mine: Boolean,
    textColor: Color,
    onRetry: () -> Unit,
    audioPlayingKey: String?,
    onAudioToggle: (String) -> Unit,
    onImageClick: (String) -> Unit,
) {
    // Image / video / location: flush ke tepi bubble (tanpa padding/ekor).
    // Voice & file: row langsung di atas warna bubble, ala LINE.
    val isImageBubble = message.type == "image" && !message.attachmentUrl.isNullOrBlank()
    val isFlushMedia = isImageBubble ||
        (message.type == "video" && !message.attachmentUrl.isNullOrBlank()) ||
        (message.type == "location" && message.lat != null && message.lng != null)
    Column(modifier = if (isFlushMedia) Modifier else Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
        if (message.revoked) {
            Text(
                text = "Pesan ini telah ditarik",
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = textColor.copy(alpha = 0.6f),
            )
        } else {
            if (quoted != null && message.replyToId != null) {
                // LINE: kotak reply dengan garis hijau tebal di kiri
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (mine) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(48.dp)
                                .background(
                                    if (mine) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                                )
                        )
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).weight(1f)) {
                            Text(
                                text = quoted.sender?.displayName ?: "Pesan",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (mine) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                maxLines = 1,
                            )
                            Text(
                                text = quoted.body?.take(90).orEmpty().ifBlank { "[${quoted.type}]" },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (mine) {
                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (message.type == "location" && message.lat != null && message.lng != null) {
                LocationBubble(
                    message = message,
                    mine = mine,
                    textColor = textColor,
                )
            } else if (message.type == "image" && !message.attachmentUrl.isNullOrBlank()) {
                ImageBubble(
                    url = message.attachmentUrl,
                    onClick = { onImageClick(message.attachmentUrl) },
                )
                if (!message.body.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                }
            } else if (message.type == "video" && !message.attachmentUrl.isNullOrBlank()) {
                VideoBubble(url = message.attachmentUrl)
                if (!message.body.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                }
            } else if (message.type == "audio" && !message.attachmentUrl.isNullOrBlank()) {
                val key = message.clientId ?: "id-${message.id}"
                VoiceBubble(
                    url = message.attachmentUrl,
                    key = key,
                    mine = mine,
                    isPlaying = audioPlayingKey == key,
                    onToggle = { onAudioToggle(key) },
                )
            } else if (message.type != "text" && !message.attachmentUrl.isNullOrBlank()) {
                FileBubble(
                    url = message.attachmentUrl,
                    mine = mine,
                    textColor = textColor,
                )
                if (!message.body.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                }
            }
            // Location sudah menampilkan judul di kartunya — jangan duplikat.
            if (!message.body.isNullOrBlank() && message.type != "location") {
                val isCipher = message.type == "text" && message.body.length > 24 && message.body.matches(Regex("^[A-Za-z0-9+/=]+$")) && message.body.contains("=")
                if (isCipher) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Lock),
                            contentDescription = null,
                            tint = textColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "[Pesan terenkripsi]",
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = textColor.copy(alpha = 0.7f),
                        )
                    }
                } else {
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        // Caption di bawah image flush tetap punya padding.
                        modifier = if (isImageBubble) {
                            Modifier.padding(horizontal = 11.dp, vertical = 8.dp)
                        } else {
                            Modifier
                        },
                    )
                }
            }
            if (message.status == "failed") {
                Spacer(Modifier.height(4.dp))
                NusaTextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                    Text("Ketuk untuk kirim ulang", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Centang status ala LINE di bawah bubble sendiri. */
@Composable
fun MessageStatus(status: String, pending: Boolean, light: Boolean = false) {
    val tint = if (light) {
        if (status == "read") Color.White else Color.White.copy(alpha = 0.85f)
    } else if (status == "read") {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    when {
        pending || status == "sending" -> Icon(
            painter = painterResource(id = NusaIcons.Time),
            contentDescription = "Mengirim",
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
        status == "failed" -> Icon(
            painter = painterResource(id = NusaIcons.Error),
            contentDescription = "Gagal terkirim",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(13.dp),
        )
        status == "read" || status == "delivered" -> Icon(
            painter = painterResource(id = NusaIcons.Checks),
            contentDescription = status,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        else -> Icon(
            painter = painterResource(id = NusaIcons.Check),
            contentDescription = "Terkirim",
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
    }
}
