package id.nusachat.app.ui.chats.components
import id.nusachat.app.ui.components.NusaTextButton
import id.nusachat.app.ui.components.NusaDialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.nusachat.app.data.Message
import id.nusachat.app.ui.components.formatClockTime

/** Emoji reaksi cepat di bar bawah saat satu pesan dipilih. */
val QUICK_REACT_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢")

/** Aksi pesan: ikon app bar, menu overflow ⋮, atau tombol bar bawah. */
enum class MsgAction {
    REACT, REPLY, EDIT, FORWARD, PIN, COPY, STAR, SAVE, SHARE, HIDE, REPORT, REVOKE, RETRY, INFO,
}

/**
 * Aksi yang tersedia untuk satu pesan (sumber tunggal untuk contextual bar).
 * Syarat sama persis dengan menu tahan-bubble lama: [REPLY] dan [INFO] selalu
 * ada; aksi usable butuh pesan utuh terkirim; [COPY]/[SHARE] butuh teks salin;
 * [RETRY] khusus pesan gagal.
 */
fun availableMsgActions(message: Message, mine: Boolean): Set<MsgAction> {
    val usable = !message.revoked && message.id > 0 && message.status != "failed"
    val out = mutableSetOf(MsgAction.REPLY, MsgAction.INFO)
    if (usable) {
        out.add(MsgAction.REACT)
        out.add(MsgAction.FORWARD)
        out.add(MsgAction.PIN)
        out.add(MsgAction.STAR)
        out.add(MsgAction.HIDE)
        if (!message.attachmentUrl.isNullOrBlank()) out.add(MsgAction.SAVE)
    }
    if (mine && usable) {
        out.add(MsgAction.REVOKE)
        if (message.type == "text" && !message.body.isNullOrBlank()) out.add(MsgAction.EDIT)
    }
    if (usable && !mine) out.add(MsgAction.REPORT)
    if (messageCopyText(message) != null) {
        out.add(MsgAction.COPY)
        out.add(MsgAction.SHARE)
    }
    if (message.status == "failed" && message.clientId != null) out.add(MsgAction.RETRY)
    return out
}

/** Teks yang disalin untuk aksi "Salin". Null = sembunyikan aksi. */
internal fun messageCopyText(message: Message): String? = when {
    message.type == "location" && message.lat != null && message.lng != null ->
        "${message.body?.takeIf { it.isNotBlank() } ?: "Lokasi"}: ${message.lat}, ${message.lng}"
    !message.body.isNullOrBlank() -> message.body
    else -> null
}

/** Dialog rincian pesan untuk aksi "Info", termasuk daftar pembaca. */
@Composable
fun MessageInfoDialog(
    message: Message,
    readers: List<id.nusachat.app.data.User> = emptyList(),
    readersLoading: Boolean = false,
    onDismiss: () -> Unit,
) {
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
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (readersLoading) "Dibaca oleh: memuat…"
                    else if (readers.isEmpty()) "Dibaca oleh: belum ada"
                    else "Dibaca oleh (${readers.size}):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                readers.take(10).forEach { user ->
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        id.nusachat.app.ui.components.NusaAvatar(
                            name = user.displayName,
                            url = user.avatarUrl,
                            gender = user.gender,
                            seed = user.id,
                            size = 24.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = user.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                    }
                }
                if (readers.size > 10) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "+${readers.size - 10} lainnya",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            NusaTextButton(onClick = onDismiss) { Text("Tutup") }
        },
    )
}
