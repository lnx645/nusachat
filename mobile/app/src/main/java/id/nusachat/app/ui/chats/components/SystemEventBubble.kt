package id.nusachat.app.ui.chats.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusachat.app.data.Message
import id.nusachat.app.ui.components.formatClockTime

/**
 * Bubble sistem ala WhatsApp: pill center yang rapi meski teks panjang
 * (mis. tambah 10 anggota). Teks wrapping, jam menempel di akhir baris.
 */
@Composable
fun SystemEventBubble(message: Message) {
    val time = formatClockTime(message.createdAt)
    val text = message.body?.trim().orEmpty()
    if (text.isBlank()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val inline = if (time.isNotBlank()) mapOf("time" to InlineTextContent(
            Placeholder(width = 36.sp, height = 11.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.Center)
        ) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }) else emptyMap()
        val annotated = buildAnnotatedString {
            append(text)
            if (time.isNotBlank()) {
                append("  ")
                appendInlineContent("time", "[$time]")
            }
        }
        Text(
            text = annotated,
            inlineContent = inline,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
