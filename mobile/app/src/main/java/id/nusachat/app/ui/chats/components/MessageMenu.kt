package id.nusachat.app.ui.chats.components
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaDialog

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusachat.app.data.Message
import id.nusachat.app.ui.components.formatClockTime
import id.nusachat.app.ui.theme.NusaIcons

/**
 * Isi menu tahan-bubble ala Google Messages: Balas / Salin / Tarik pesan /
 * Kirim ulang / Info. Custom penuh (bukan item bawaan) — dirender di dalam
 * kartu menu yang ditempel langsung ke bubble, jadi selalu nempel.
 */
@Composable
fun MessageMenuContent(
    message: Message,
    mine: Boolean,
    pinned: Boolean = false,
    onReply: (Message) -> Unit,
    onEdit: (Message) -> Unit = {},
    onForward: (Message) -> Unit = {},
    onReact: (Message, String) -> Unit = { _, _ -> },
    onPin: (Message) -> Unit = {},
    onRevoke: (Message) -> Unit,
    onRetry: () -> Unit,
    onShowInfo: (Message) -> Unit,
    onDismiss: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.85f,
        animationSpec = tween(durationMillis = 160),
        label = "menu-pop",
    )
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "menu-fade",
    )
    val copyText = messageCopyText(message)
    val usable = !message.revoked && message.id > 0 && message.status != "failed"
    val canRevoke = mine && usable
    val canEdit = mine && usable && message.type == "text" && !message.body.isNullOrBlank()
    val canRetry = message.status == "failed" && message.clientId != null
    val clipboard = LocalClipboardManager.current
    val quickEmojis = listOf("👍", "❤️", "😂", "😮", "😢")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                transformOrigin = TransformOrigin(
                    pivotFractionX = if (mine) 1f else 0f,
                    pivotFractionY = 0f,
                )
            },
    ) {
        if (usable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                quickEmojis.forEach { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 24.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onReact(message, emoji) }
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
        MessageMenuRow(
            label = "Balas",
            icon = NusaIcons.Reply,
            onClick = { onReply(message) },
        )
        if (canEdit) {
            MessageMenuRow(
                label = "Edit",
                icon = NusaIcons.Edit,
                onClick = { onEdit(message) },
            )
        }
        if (usable) {
            MessageMenuRow(
                label = "Teruskan",
                icon = NusaIcons.Forward,
                onClick = { onForward(message) },
            )
            MessageMenuRow(
                label = if (pinned) "Lepas sematan" else "Sematkan",
                icon = NusaIcons.Pin,
                onClick = { onPin(message) },
            )
        }
        if (copyText != null) {
            MessageMenuRow(
                label = "Salin",
                icon = NusaIcons.Copy,
                onClick = {
                    clipboard.setText(AnnotatedString(copyText))
                    onDismiss()
                },
            )
        }
        if (canRevoke) {
            MessageMenuRow(
                label = "Tarik pesan",
                icon = NusaIcons.Delete,
                danger = true,
                onClick = { onRevoke(message) },
            )
        }
        if (canRetry) {
            MessageMenuRow(
                label = "Kirim ulang",
                icon = NusaIcons.Refresh,
                onClick = {
                    onDismiss()
                    onRetry()
                },
            )
        }
        MessageMenuRow(
            label = "Info",
            icon = NusaIcons.Info,
            onClick = { onShowInfo(message) },
        )
    }
}

/** Satu baris aksi di kartu menu: ikon 22dp + label 15sp, ripple penuh. */
@Composable
private fun MessageMenuRow(
    label: String,
    icon: Int,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = if (danger) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = contentColor,
        )
    }
}

/** Dialog rincian pesan untuk aksi "Info". */
@Composable
fun MessageInfoDialog(message: Message, onDismiss: () -> Unit) {
    NusaDialog(
        onDismissRequest = onDismiss,
        title = { Text("Info pesan") },
        text = {
            Column {
                Text("Dari: ${message.sender?.displayName ?: "Sistem"}")
                Spacer(Modifier.height(4.dp))
                Text("Waktu: ${formatClockTime(message.createdAt)}")
                Spacer(Modifier.height(4.dp))
                Text("Tipe: ${message.type}")
                Spacer(Modifier.height(4.dp))
                Text("Status: ${message.status}")
            }
        },
        confirmButton = {
            NusaTextButton(onClick = onDismiss) { Text("Tutup") }
        },
    )
}

/** Teks yang disalin untuk aksi "Salin". Null = sembunyikan aksi. */
private fun messageCopyText(message: Message): String? = when {
    message.type == "location" && message.lat != null && message.lng != null ->
        "${message.body?.takeIf { it.isNotBlank() } ?: "Lokasi"}: ${message.lat}, ${message.lng}"
    !message.body.isNullOrBlank() -> message.body
    else -> null
}
