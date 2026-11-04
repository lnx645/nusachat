package id.nusachat.app.ui.chats.components
import id.nusachat.app.ui.components.NusaTextButton

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
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
/** Pola sorot @mention di bubble — cermin pola server (extractMentions). */
private val MentionSpanRegex = Regex("@((?:semua|all)\\b|[A-Za-z0-9._]{4,20})", RegexOption.IGNORE_CASE)

fun messagePreviewText(message: Message, maxLen: Int = 80): String {
    if (message.revoked) return "Pesan ini telah ditarik"
    message.body?.let { id.nusachat.app.data.E2eeManager.fallbackTextFor(it)?.take(maxLen) }?.let { return it }
    message.body?.take(maxLen)?.takeIf { it.isNotBlank() }?.let { return it }
    return when (message.type) {
        "image" -> "[Foto]"
        "video" -> "[Video]"
        "audio" -> "[Pesan suara]"
        "location" -> "[Lokasi]"
        "file" -> "[File]"
        "redpacket" -> "[Amplop merah]"
        else -> "[Pesan]"
    }
}

/** Keputusan arbitrasi swipe-vs-scroll per event gerakan. */
internal enum class SwipeArbitration { CLAIM, ABORT, WAIT }

/**
 * Arbitrasi swipe-to-reply, dievaluasi KONTINU setiap event gerakan (bukan
 * sekali saat lepas slop): klaim segera saat gerakan searah reply dominan,
 * serahkan ke scroll daftar saat vertikal menang atau arah berlawanan,
 * tunggu bila masih ragu. Arah reply tergantung sisi bubble ([forward]:
 * +1 geser kanan, -1 geser kiri) — mulai dari ruang lega, dorong ke tepi.
 */
internal fun arbitrateSwipe(totalDx: Float, totalDy: Float, slop: Float, forward: Float): SwipeArbitration {
    val dx = totalDx * forward
    val adx = abs(totalDx)
    val ady = abs(totalDy)
    if (dx < -slop) return SwipeArbitration.ABORT
    if (ady > slop && ady > adx) return SwipeArbitration.ABORT
    if (dx > slop && dx > ady) return SwipeArbitration.CLAIM
    return SwipeArbitration.WAIT
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
    /** Swipe-to-reply (gesek bubble); terpisah dari contextual bar. */
    onReply: (Message) -> Unit = {},
    audioPlayingKey: String? = null,
    onAudioToggle: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onQuotedClick: ((Long) -> Unit)? = null,
    isHighlighted: Boolean = false,
    onBubbleClick: () -> Unit = {},
    myId: Long = 0L,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onPacketClick: (Long) -> Unit = {},
    /** Pasangan bubble dari Latar Chat; null = ikut token tema (hijau Nusa + putih). */
    bubbles: id.nusachat.app.ui.theme.ResolvedBubbles? = null,
    /** Wallpaper gelap? Menentukan warna jam + centang di atas wallpaper. Null = ikut tema sistem. */
    wallpaperDark: Boolean? = null,
) {
    val haptic = LocalHapticFeedback.current
    // Stiker: emoji raksasa tanpa bubble.
    if (message.type == "sticker" && !message.revoked) {
        StickerMessage(
            message = message,
            mine = mine,
            isGroup = isGroup,
            onLongPress = onLongPress,
            onBubbleClick = onBubbleClick,
            selected = selected,
            wallpaperDark = wallpaperDark,
        )
        return
    }
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
    // Swipe-to-reply searah sisi bubble: milik sendiri (kanan) hanya
    // kiri→kanan, pesan lawan (kiri) hanya kanan→kiri. Gesek pendek
    // (40.dp) dari mana saja selebar baris langsung membalas.
    val density = LocalDensity.current
    val thresholdPx = with(density) { 40.dp.toPx() }
    val maxPx = with(density) { 96.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    var released by remember { mutableStateOf(true) }
    val currentOnReply by rememberUpdatedState(onReply)
    val currentMessage by rememberUpdatedState(message)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val fwd = (if (mine) 1f else -1f) *
        (if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f)
    val shownX by animateFloatAsState(
        targetValue = if (released) 0f else dragX,
        animationSpec = if (released) tween(220) else snap(),
        label = "swipeReply",
    )
    val swipeProgress = (shownX / thresholdPx).coerceIn(0f, 1f)
    Box(modifier = Modifier.fillMaxWidth()) {
        // Ikon balas tersingkap di sisi berlawanan arah geser.
        if (swipeProgress > 0.02f) {
            Box(
                modifier = Modifier
                    .align(if (fwd > 0) Alignment.CenterStart else Alignment.CenterEnd)
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
            .offset { IntOffset((shownX * fwd).roundToInt(), 0) }
            .pointerInput(message.id, thresholdPx, maxPx, touchSlop, fwd) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var totalDx = 0f
                    var totalDy = 0f
                    var dragging = false
                    var triggered = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        // Scroll daftar sudah mengklaim gerakan ini.
                        if (change.isConsumed) break
                        val dx = change.position.x - change.previousPosition.x
                        val dy = change.position.y - change.previousPosition.y
                        if (triggered) {
                            // Balasan sudah dikirim mid-swipe: telan sisa
                            // gerakan supaya daftar tak ikut scroll di bawah jari.
                            change.consume()
                        } else if (!dragging) {
                            totalDx += dx
                            totalDy += dy
                            when (arbitrateSwipe(totalDx, totalDy, touchSlop, fwd)) {
                                // Vertikal/diagonal/lawan arah: milik daftar,
                                // keluar tanpa consume apa pun.
                                SwipeArbitration.ABORT -> break
                                SwipeArbitration.CLAIM -> {
                                    dragging = true
                                    released = false
                                    dragX = (totalDx * fwd).coerceIn(0f, maxPx)
                                    change.consume()
                                }
                                SwipeArbitration.WAIT -> Unit
                            }
                        } else {
                            dragX = (dragX + dx * fwd).coerceIn(0f, maxPx)
                            change.consume()
                            // Reply dikirim SAAT lewati ambang, tanpa menunggu
                            // jari diangkat; bubble memantul kembali.
                            if (dragX >= thresholdPx) {
                                triggered = true
                                released = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnReply(currentMessage)
                                dragX = 0f
                            }
                        }
                    }
                    if (dragging && !triggered) {
                        released = true
                        if (dragX >= thresholdPx) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnReply(currentMessage)
                        }
                        dragX = 0f
                    }
                }
            }
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
                    shadowElevation = if (isHighlighted) 6.dp else 0.dp,
                    border = when {
                        selected -> androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        isHighlighted -> androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                        else -> null
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = { onBubbleClick() },
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
                        onPacketClick = onPacketClick,
                        groupName = if (!mine && isGroup && message.sender != null && !compactTop) {
                            message.sender.familiarName
                        } else null,
                        groupNameColor = memberNameColor(message.sender?.id, baseOther.luminance() < 0.5f),
                        myId = myId,
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
    onPacketClick: (Long) -> Unit = {},
    groupName: String? = null,
    groupNameColor: Color = Color.Unspecified,
    myId: Long = 0L,
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
                // Kotak reply diturunkan dari warna teks bubble — sinkron di
                // semua paket bubble, terang maupun gelap.
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = textColor.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = onQuotedClick != null) { message.replyToId?.let { onQuotedClick?.invoke(it) } },
                ) {
                    Row {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(48.dp)
                                .background(
                                    textColor,
                                    RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                                )
                        )
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).weight(1f)) {
                            Text(
                                text = quoted.sender?.familiarName ?: "Pesan",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = textColor,
                                maxLines = 1,
                            )
                            Text(
                                text = messagePreviewText(quoted, 90),
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.75f),
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
            } else if (message.type == "redpacket") {
                RedPacketBubble(
                    message = message,
                    mine = mine,
                    textColor = textColor,
                    onOpen = { message.packetId?.let(onPacketClick) },
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
            // Lencana bila saya disebut: dari server (REST) atau hitung lokal
            // dari daftar mentions (realtime WS tidak dipersonalisasi per user).
            val mentionsMe = !mine && (message.mentionedMe || (myId != 0L && myId in message.mentions))
            if (mentionsMe) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                ) {
                    Text(
                        text = "Menyebut Anda",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            // Location & redpacket sudah menampilkan judul di kartunya — jangan duplikat.
            if (!message.body.isNullOrBlank() && message.type != "location" && message.type != "redpacket") {
                val fallback = message.body?.let {
                    id.nusachat.app.data.E2eeManager.fallbackTextFor(it)
                }?.takeIf { message.type == "text" }
                if (fallback != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = NusaIcons.Lock),
                            contentDescription = null,
                            tint = textColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = fallback,
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = textColor.copy(alpha = 0.7f),
                        )
                    }
                } else {
                    val body = message.body
                    // Sorot @username/@semua/@all seperti pola server (extractMentions).
                    val mentionColor = if (mine) Color(0xFFB3E5FC) else MaterialTheme.colorScheme.primary
                    val annotated = remember(body) {
                        buildAnnotatedString {
                            var last = 0
                            MentionSpanRegex.findAll(body).forEach { m ->
                                append(body.substring(last, m.range.first))
                                withStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold)) {
                                    append(m.value)
                                }
                                last = m.range.last + 1
                            }
                            append(body.substring(last))
                        }
                    }
                    Text(
                        text = annotated,
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
            if ((message.type == "text" || message.type.isBlank()) && !message.body.isNullOrBlank()) {
                LinkChip(text = message.body, mine = mine, textColor = textColor)
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

/** Chip domain untuk tautan pertama di pesan teks — ketuk untuk buka. */
@Composable
private fun LinkChip(text: String, mine: Boolean, textColor: Color) {
    val url = remember(text) { URL_REGEX.find(text)?.value } ?: return
    val host = remember(url) {
        runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull()
            ?: url.take(24)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    Spacer(Modifier.height(6.dp))
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (mine) textColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable {
            runCatching {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(id = NusaIcons.Link),
                contentDescription = null,
                tint = textColor.copy(alpha = 0.8f),
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = host,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val URL_REGEX = Regex("https?://[^\\s]+")

/** Kartu amplop merah di dalam bubble. */
@Composable
private fun RedPacketBubble(message: Message, mine: Boolean, textColor: Color, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (mine) textColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.widthIn(min = 180.dp, max = 240.dp).clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.error) {
                Icon(
                    painter = painterResource(id = NusaIcons.Gift),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(8.dp).size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.body?.takeIf { it.isNotBlank() } ?: "Amplop merah untukmu",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Ketuk untuk membuka",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** Stiker: emoji raksasa tanpa bubble + jam/centang. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerMessage(
    message: Message,
    mine: Boolean,
    isGroup: Boolean,
    onLongPress: () -> Unit,
    onBubbleClick: () -> Unit,
    selected: Boolean,
    wallpaperDark: Boolean?,
) {
    val onWallpaperDark = wallpaperDark ?: androidx.compose.foundation.isSystemInDarkTheme()
    val metaColor = if (onWallpaperDark) Color.White.copy(alpha = 0.85f) else Color(0xFF5B6B76)
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (isGroup && !mine) {
            id.nusachat.app.ui.components.NusaAvatar(
                name = message.sender?.familiarName ?: "?",
                url = message.sender?.avatarUrl,
                gender = message.sender?.gender,
                seed = message.sender?.id,
                size = 32.dp,
            )
            Spacer(Modifier.width(7.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Box(
                modifier = Modifier
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                        RoundedCornerShape(16.dp),
                    )
                    .combinedClickable(
                        onClick = { onBubbleClick() },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        },
                    )
                    .padding(6.dp),
            ) {
                Text(text = message.body.orEmpty(), fontSize = 64.sp)
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

/** Varian terang untuk bubble lawan yang gelap (palet gelap tak terbaca di sana). */
private val GroupNamePaletteDark = listOf(
    Color(0xFFF06292),
    Color(0xFFBA68C8),
    Color(0xFF7986CB),
    Color(0xFF64B5F6),
    Color(0xFF4DB6AC),
    Color(0xFFAED581),
    Color(0xFFFFB74D),
    Color(0xFFBCAAA4),
)

private fun memberNameColor(seed: Long?, darkBubble: Boolean): Color {
    val palette = if (darkBubble) GroupNamePaletteDark else GroupNamePalette
    val n = palette.size
    if (seed == null) return palette[0]
    val idx = ((seed % n) + n) % n
    return palette[idx.toInt()]
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
