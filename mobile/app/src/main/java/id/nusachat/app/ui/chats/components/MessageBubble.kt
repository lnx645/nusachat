package id.nusachat.app.ui.chats.components
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaMenu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import id.nusachat.app.data.Message
import id.nusachat.app.ui.components.formatClockTime
import id.nusachat.app.ui.theme.NusaIcons

/**
 * Teks pratinjau pesan untuk bar sematan, pratinjau balasan, kutipan di
 * bubble, dan hasil pencarian. Pesan yang ditarik selalu tampil sebagai
 * "Pesan ini telah ditarik" — isi aslinya tidak pernah bocor. Tipe media
 * memakai label Indonesia, bukan kode mentah seperti [img].
 */
fun messagePreviewText(message: Message, maxLen: Int = 80): String {
    if (message.revoked) return "Pesan ini telah ditarik"
    message.body?.take(maxLen)?.takeIf { it.isNotBlank() }?.let { return it }
    return when (message.type) {
        "image" -> "[Foto]"
        "video" -> "[Video]"
        "audio" -> "[Pesan suara]"
        "location" -> "[Lokasi]"
        "file" -> "[File]"
        else -> "[Pesan]"
    }
}

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
    onEdit: (Message) -> Unit = {},
    onForward: (Message) -> Unit = {},
    onReact: (Message, String) -> Unit = { _, _ -> },
    onPin: (Message) -> Unit = {},
    pinned: Boolean = false,
    onRevoke: (Message) -> Unit = {},
    onShowInfo: (Message) -> Unit = {},
    audioPlayingKey: String? = null,
    onAudioToggle: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onQuotedClick: ((Long) -> Unit)? = null,
    isHighlighted: Boolean = false,
    /** Pasangan bubble dari Latar Chat; null = ikut token tema (hijau Nusa + putih). */
    bubbles: id.nusachat.app.ui.theme.ResolvedBubbles? = null,
    /** Wallpaper gelap? Menentukan warna jam + centang di atas wallpaper. Null = ikut tema sistem. */
    wallpaperDark: Boolean? = null,
) {
    val onWallpaperDark = wallpaperDark ?: androidx.compose.foundation.isSystemInDarkTheme()
    // Jam + centang duduk di atas wallpaper (bukan di bubble).
    val metaColor = if (onWallpaperDark) Color.White.copy(alpha = 0.85f) else Color(0xFF5B6B76)
    // Gaya LINE: mine hijau, other putih. Highlight canggih: animasi warna + border + scale agar tetap nyambung warna bubble asal
    val baseMine = bubbles?.mine ?: MaterialTheme.colorScheme.primary
    val baseOther = bubbles?.other ?: MaterialTheme.colorScheme.surface
    val targetBubbleColor = if (mine) baseMine else baseOther
    val bubbleColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isHighlighted) {
            if (mine) baseMine.copy(alpha = 0.88f) else baseOther.copy(alpha = 0.82f)
        } else targetBubbleColor,
        animationSpec = androidx.compose.animation.core.tween(280),
        label = "bubbleHighlight",
    )
    val highlightScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isHighlighted) 1.03f else 1f,
        animationSpec = androidx.compose.animation.core.tween(280),
        label = "bubbleScale",
    )
    val textColor = if (mine) (bubbles?.mineText ?: Color.White) else (bubbles?.otherText ?: MaterialTheme.colorScheme.onSurface)
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
    // Swipe horizontal ala WhatsApp: geser bubble melewati ambang untuk membalas.
    val density = LocalDensity.current
    val thresholdPx = with(density) { 56.dp.toPx() }
    val maxPx = with(density) { 96.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    var released by remember { mutableStateOf(true) }
    val shownX by animateFloatAsState(
        targetValue = if (released) 0f else dragX,
        animationSpec = if (released) tween(220) else snap(),
        label = "swipeReply",
    )
    val swipeProgress = (abs(shownX) / thresholdPx).coerceIn(0f, 1f)
    Box(modifier = Modifier.fillMaxWidth()) {
        // Ikon balas tersingkap di sisi yang terbuka saat digeser.
        if (swipeProgress > 0.02f) {
            Box(
                modifier = Modifier
                    .align(if (shownX > 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 18.dp)
                    .alpha(swipeProgress),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        painter = painterResource(id = NusaIcons.Reply),
                        contentDescription = "Balas",
                        tint = Color.White,
                        modifier = Modifier.padding(8.dp).size(16.dp),
                    )
                }
            }
        }
        Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(shownX.roundToInt(), 0) }
            .draggable(
                state = rememberDraggableState { delta ->
                    dragX = (dragX + delta).coerceIn(-maxPx, maxPx)
                },
                orientation = Orientation.Horizontal,
                onDragStarted = { released = false },
                onDragStopped = { _ ->
                    released = true
                    if (abs(dragX) > thresholdPx) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onReply(message)
                    }
                    dragX = 0f
                },
            )
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
            // Gutter 6.dp di sisi ekor supaya ujung tail tidak kepotong tepi.
            // Tail digambar DULU lalu bubble menutup 2.dp pangkalnya -> sambungan mulus.
            // Highlight: scale + warna animasi agar tetap nyambung warna bubble asal
            Box(
                modifier = Modifier
                    .graphicsLayer(scaleX = highlightScale, scaleY = highlightScale)
                    .then(
                        if (hasTail) {
                            if (mine) Modifier.padding(end = 6.dp) else Modifier.padding(start = 6.dp)
                        } else Modifier
                    ),
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
                    shadowElevation = if (isHighlighted) 6.dp else if (menuOpen) 4.dp else 0.dp,
                    border = if (isHighlighted) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
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
                        onQuotedClick = onQuotedClick,
                        groupName = if (!mine && isGroup && message.sender != null && !compactTop) {
                            message.sender.displayName
                        } else null,
                        groupNameColor = memberNameColor(message.sender?.id),
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
                        pinned = pinned,
                        onReply = onReply,
                        onEdit = onEdit,
                        onForward = onForward,
                        onReact = onReact,
                        onPin = onPin,
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
                    color = metaColor,
                )
                if (mine) {
                    Spacer(Modifier.width(3.dp))
                    MessageStatus(status = message.status, pending = message.pending, light = onWallpaperDark)
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
    onQuotedClick: ((Long) -> Unit)? = null,
    groupName: String? = null,
    groupNameColor: Color = Color.Unspecified,
) {
    // Image / video / location: flush ke tepi bubble (tanpa padding/ekor).
    // Voice & file: row langsung di atas warna bubble, ala LINE.
    val isImageBubble = message.type == "image" && !message.attachmentUrl.isNullOrBlank()
    val isFlushMedia = isImageBubble ||
        (message.type == "video" && !message.attachmentUrl.isNullOrBlank()) ||
        (message.type == "location" && message.lat != null && message.lng != null)
    Column(modifier = if (isFlushMedia) Modifier else Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
        // Nama pengirim grup ala WhatsApp: di dalam bubble, warna khas per user.
        if (groupName != null) {
            Text(
                text = groupName,
                style = MaterialTheme.typography.labelSmall,
                color = groupNameColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (isFlushMedia) {
                    Modifier.padding(start = 11.dp, end = 11.dp, top = 8.dp, bottom = 2.dp)
                } else {
                    Modifier.padding(bottom = 2.dp)
                },
            )
        }
        if (message.revoked) {
            Text(
                text = "Pesan ini telah ditarik",
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = textColor.copy(alpha = 0.6f),
            )
        } else {
            if (message.forwarded) {
                Text(
                    text = "Diteruskan",
                    style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                    color = textColor.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(2.dp))
            }
            if (quoted != null && message.replyToId != null) {
                // LINE: kotak reply dengan garis hijau tebal di kiri
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (mine) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = onQuotedClick != null) { message.replyToId?.let { onQuotedClick?.invoke(it) } },
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
                                text = messagePreviewText(quoted, 90),
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
            if (message.editedAt != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "diedit",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontStyle = FontStyle.Italic),
                    color = textColor.copy(alpha = 0.6f),
                )
            }
            if (message.reactions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    message.reactions.forEach { reaction ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (mine) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Text(
                                text = if (reaction.count > 1) "${reaction.emoji} ${reaction.count}" else reaction.emoji,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Centang status ala LINE di bawah bubble sendiri. */
@Composable
fun MessageStatus(status: String, pending: Boolean, light: Boolean = false) {
    // Ala WhatsApp: 1 centang = terkirim, 2 centang abu = diterima,
    // 2 centang biru = dibaca. Biru WA (#53BDEB) tetap terbaca di atas
    // bubble hijau sendiri (putih vs biru jauh lebih jelas).
    val base = if (light) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val sentTint = base.copy(alpha = 0.75f)
    val readTint = Color(0xFF53BDEB)
    when {
        pending || status == "sending" -> Icon(
            painter = painterResource(id = NusaIcons.Time),
            contentDescription = "Mengirim",
            tint = sentTint,
            modifier = Modifier.size(12.dp),
        )
        status == "failed" -> Icon(
            painter = painterResource(id = NusaIcons.Error),
            contentDescription = "Gagal terkirim",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(13.dp),
        )
        status == "read" -> WaChecks(
            double = true,
            color = readTint,
            desc = "Dibaca",
        )
        status == "delivered" -> WaChecks(
            double = true,
            color = sentTint,
            desc = "Diterima",
        )
        else -> WaChecks(
            double = false,
            color = sentTint,
            desc = "Terkirim",
        )
    }
}

/**
 * Warna nama pengirim grup ala WhatsApp: deterministik per user dari
 * palet yang terbaca di terang & gelap.
 */
private val GroupNamePalette = listOf(
    Color(0xFFD3397C),
    Color(0xFF8E44AD),
    Color(0xFF3F51B5),
    Color(0xFF1E88E5),
    Color(0xFF00897B),
    Color(0xFF689F38),
    Color(0xFFE67E22),
    Color(0xFF795548),
)

private fun memberNameColor(seed: Long?): Color {
    val n = GroupNamePalette.size
    if (seed == null) return GroupNamePalette[0]
    val idx = ((seed % n) + n) % n
    return GroupNamePalette[idx.toInt()]
}

/**
 * Centang ala WhatsApp yang digambar manual: stroke membulat dengan
 * proporsi khas (centang kedua menimpa ekor yang pertama).
 */
@Composable
private fun WaChecks(
    double: Boolean,
    color: Color,
    desc: String,
) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .size(if (double) 18.dp else 13.dp)
            .semantics { contentDescription = desc },
    ) {
        val w = size.width
        val h = size.height
        val stroke = 1.7.dp.toPx()
        val style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        fun drawOne(x0: Float, x1: Float, x2: Float) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * x0, h * 0.54f)
                lineTo(w * x1, h * 0.74f)
                lineTo(w * x2, h * 0.26f)
            }
            drawPath(path = path, color = color, style = style)
        }
        if (double) {
            drawOne(0.02f, 0.20f, 0.55f)
            drawOne(0.33f, 0.51f, 0.95f)
        } else {
            drawOne(0.12f, 0.38f, 0.88f)
        }
    }
}
