package id.nusachat.app.ui.chats.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

/**
 * Gelembung "sedang mengetik..." — gaya bubble text masuk: berekor, tanpa avatar.
 * Warna bubble + teks dioper dari tema chat (bukan tema aplikasi) supaya
 * sinkron dengan wallpaper & paket bubble pilihan user.
 */
@Composable
fun TypingBubble(name: String, bubble: Color, text: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(modifier = Modifier.padding(start = 6.dp)) {
            BubbleTail(
                mine = false,
                color = bubble,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-6).dp, y = 0.dp),
            )
            Surface(
                shape = RoundedCornerShape(topStart = 2.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp),
                color = bubble,
            ) {
                Text(
                    text = "sedang mengetik...",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = text,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = text,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}
