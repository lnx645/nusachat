package id.nusachat.app.ui.chats.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import id.nusachat.app.data.Message
import id.nusachat.app.ui.theme.NusaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bubble gambar ala LINE: foto full-bleed rounded, ketuk untuk fullscreen.
 */
@Composable
fun ImageBubble(url: String, onClick: () -> Unit) {
    AsyncImage(
        model = url,
        contentDescription = "Foto",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .widthIn(max = 230.dp)
            .heightIn(min = 90.dp, max = 280.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    )
}

/**
 * Bubble video ala LINE: thumbnail frame + tombol play + badge, ketuk untuk putar.
 */
@Composable
fun VideoBubble(url: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var frame by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            frame = runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(url, HashMap())
                val raw = retriever.getFrameAtTime(0)
                retriever.release()
                raw?.let {
                    val targetW = 460
                    val targetH = (targetW.toFloat() / it.width * it.height).toInt().coerceIn(1, 800)
                    Bitmap.createScaledBitmap(it, targetW, targetH, true)
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .width(230.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { openMedia(ctx, url, "video/*") },
    ) {
        if (frame != null) {
            Image(
                bitmap = frame!!.asImageBitmap(),
                contentDescription = "Video",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.VideoFile),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.15f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(50.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.93f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = NusaIcons.Play),
                contentDescription = "Putar video",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        ) {
            Text(
                text = "VIDEO",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * Bubble pesan suara ala LINE: tombol play + waveform + durasi. Ketuk untuk putar/jeda.
 * Hanya satu yang bunyi dalam satu chat (dikoordinasi via [isPlaying]).
 */
@Composable
fun VoiceBubble(
    url: String,
    key: String,
    mine: Boolean,
    isPlaying: Boolean,
    onToggle: () -> Unit,
) {
    var player by remember(url) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(url) { mutableStateOf(false) }
    var durationMs by remember(url) { mutableStateOf(0) }
    var positionMs by remember(url) { mutableStateOf(0) }

    DisposableEffect(url) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }
    LaunchedEffect(isPlaying, url) {
        if (isPlaying) {
            val mp = player ?: MediaPlayer().apply {
                try {
                    setDataSource(url)
                    setOnPreparedListener { p ->
                        prepared = true
                        durationMs = p.duration
                        p.start()
                    }
                    setOnErrorListener { _, _, _ -> onToggle(); true }
                    setOnCompletionListener { onToggle() }
                    prepareAsync()
                } catch (_: Exception) {
                    onToggle()
                }
            }.also { player = it }
            if (prepared) {
                runCatching { if (!mp.isPlaying) mp.start() }
            }
        } else {
            runCatching { if (player?.isPlaying == true) player?.pause() }
        }
    }
    LaunchedEffect(isPlaying, player) {
        while (isPlaying) {
            val mp = player
            if (mp != null) {
                runCatching {
                    positionMs = mp.currentPosition
                    durationMs = mp.duration
                }
            }
            kotlinx.coroutines.delay(250)
        }
    }

    val barFull = if (mine) Color.White else MaterialTheme.colorScheme.primary
    val barDim = barFull.copy(alpha = 0.4f)
    val bars = remember(key) {
        List(26) { i ->
            val h = key.hashCode() + i * 31
            5 + (if (h < 0) -h else h) % 15
        }
    }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val timeColor = if (mine) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .widthIn(min = 190.dp, max = 230.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (mine) Color.White else MaterialTheme.colorScheme.primary)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = if (isPlaying) NusaIcons.Pause else NusaIcons.Play),
                contentDescription = if (isPlaying) "Jeda" else "Putar pesan suara",
                tint = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                bars.forEachIndexed { i, bh ->
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(bh.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (fraction > 0f && i / 26f <= fraction) barFull else barDim),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (durationMs > 0) {
                    "${formatAudioTime(positionMs)} / ${formatAudioTime(durationMs)}"
                } else {
                    "Pesan suara"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = timeColor,
            )
        }
    }
}

/**
 * Bubble file ala LINE: ikon tipe berwarna + nama + tombol buka.
 */
@Composable
fun FileBubble(url: String, mine: Boolean, textColor: Color) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val name = url.substringAfterLast("/").substringBefore("?").ifBlank { "lampiran" }
    val ext = name.substringAfterLast(".", "").uppercase().takeIf { it.length in 1..5 } ?: "FILE"
    val dimColor = if (mine) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val (fileBg, fileFg) = fileColorsForExt(ext)
    Row(
        modifier = Modifier
            .widthIn(min = 190.dp, max = 240.dp)
            .clickable { openMedia(ctx, url, mimeForExt(ext)) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(fileBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = NusaIcons.FileText),
                contentDescription = null,
                tint = fileFg,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$ext • File",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = dimColor,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            painter = painterResource(id = NusaIcons.Download),
            contentDescription = "Buka file",
            tint = dimColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Bubble lokasi ala LINE: peta mini + nama/koordinat + "Buka di Maps".
 */
@Composable
fun LocationBubble(message: Message, mine: Boolean, textColor: Color) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lat = message.lat ?: 0.0
    val lng = message.lng ?: 0.0
    val title = message.body?.takeIf { it.isNotBlank() && it != "Lokasi saya" } ?: "Lokasi dibagikan"
    val dimColor = if (mine) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = if (mine) Color.White else MaterialTheme.colorScheme.primary
    Column {
        MapPreview(lat = lat, lng = lng)
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${String.format("%.5f", lat)}, ${String.format("%.5f", lng)}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = dimColor,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    val uri = android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng")
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(intent) }
                },
            ) {
                Icon(
                    painter = painterResource(id = NusaIcons.Navigation),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Buka di Maps",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                )
            }
        }
    }
}

/**
 * Peta mini gaya LINE: blok taman + air + jalan + pin merah di tengah.
 */
@Composable
fun MapPreview(lat: Double, lng: Double) {
    // Semua warna peta dari color scheme aplikasi (ikut light/dark).
    val mapBg = MaterialTheme.colorScheme.surfaceVariant
    val park = MaterialTheme.colorScheme.primaryContainer
    val water = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f)
    val road = MaterialTheme.colorScheme.surface
    val roadCase = MaterialTheme.colorScheme.outlineVariant
    val pin = MaterialTheme.colorScheme.error
    val pinCenter = MaterialTheme.colorScheme.surface
    val pinShadow = MaterialTheme.colorScheme.scrim.copy(alpha = 0.25f)
    key(lat, lng) {
        Canvas(
            modifier = Modifier
                .width(230.dp)
                .height(140.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        ) {
            val w = size.width
            val h = size.height
            drawRect(mapBg)
            // Blok taman & air
            drawRoundRect(
                park,
                topLeft = Offset(w * 0.62f, h * 0.52f),
                size = Size(w * 0.42f, h * 0.52f),
                cornerRadius = CornerRadius(10f, 10f),
            )
            drawRoundRect(
                water,
                topLeft = Offset(-w * 0.12f, -h * 0.18f),
                size = Size(w * 0.36f, h * 0.38f),
                cornerRadius = CornerRadius(14f, 14f),
            )
            // Jalan kecil (grid)
            val minor = road
            for (i in 0..4) {
                val y = h * (0.10f + i * 0.20f)
                drawLine(minor, Offset(0f, y), Offset(w, y), strokeWidth = h * 0.022f)
            }
            for (i in 0..5) {
                val x = w * (0.04f + i * 0.19f)
                drawLine(minor, Offset(x, 0f), Offset(x, h), strokeWidth = h * 0.022f)
            }
            // Jalan utama (casing outline + isi surface)
            drawLine(roadCase, Offset(0f, h * 0.86f), Offset(w, h * 0.24f), strokeWidth = h * 0.10f)
            drawLine(road, Offset(0f, h * 0.86f), Offset(w, h * 0.24f), strokeWidth = h * 0.065f)
            drawLine(roadCase, Offset(w * 0.22f, 0f), Offset(w * 0.56f, h), strokeWidth = h * 0.08f)
            drawLine(road, Offset(w * 0.22f, 0f), Offset(w * 0.56f, h), strokeWidth = h * 0.05f)
            // Pin di tengah (error scheme)
            val cx = w / 2f
            val cy = h / 2f - 8f
            val r = 25f
            drawOval(pinShadow, topLeft = Offset(cx - 13f, cy + r * 1.18f), size = Size(26f, 8f))
            drawCircle(pin, radius = r, center = Offset(cx, cy))
            drawPath(
                Path().apply {
                    moveTo(cx - r * 0.55f, cy + r * 0.35f)
                    lineTo(cx + r * 0.55f, cy + r * 0.35f)
                    lineTo(cx, cy + r * 1.25f)
                    close()
                },
                pin,
            )
            drawCircle(pinCenter, radius = r * 0.42f, center = Offset(cx, cy))
        }
    }
}

private fun openMedia(ctx: android.content.Context, url: String, mime: String) {
    runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(url), mime)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}

private fun mimeForExt(ext: String): String = when (ext.uppercase()) {
    "PDF" -> "application/pdf"
    "DOC", "DOCX" -> "application/msword"
    "XLS", "XLSX" -> "application/vnd.ms-excel"
    "PPT", "PPTX" -> "application/vnd.ms-powerpoint"
    "TXT" -> "text/plain"
    "ZIP", "RAR" -> "application/zip"
    "MP3" -> "audio/mpeg"
    "WAV", "OGG", "M4A" -> "audio/*"
    "MP4" -> "video/mp4"
    "JPG", "JPEG", "PNG", "WEBP", "GIF" -> "image/*"
    else -> "*/*"
}

/** Pasangan (background, ikon) file — semua dari color scheme aplikasi. */
@Composable
private fun fileColorsForExt(ext: String): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (ext.uppercase()) {
        "PDF" -> scheme.error.copy(alpha = 0.12f) to scheme.error
        "DOC", "DOCX" -> scheme.tertiary.copy(alpha = 0.12f) to scheme.tertiary
        "XLS", "XLSX" -> scheme.primary.copy(alpha = 0.12f) to scheme.primary
        "PPT", "PPTX" -> scheme.secondary.copy(alpha = 0.16f) to scheme.secondary
        "MP3", "WAV", "OGG", "M4A" -> scheme.primary.copy(alpha = 0.12f) to scheme.primary
        "MP4", "MOV" -> scheme.tertiary.copy(alpha = 0.12f) to scheme.tertiary
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
}

private fun formatAudioTime(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "${totalSec / 60}:${String.format("%02d", totalSec % 60)}"
}
