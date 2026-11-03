package id.nusachat.app.ui.chats.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/**
 * Ekor bubble ala LINE: ujung runcing di ATAS, tepi atasnya segaris dengan
 * tepi atas bubble, lalu melengkung cekung kembali ke sisi bubble.
 * Hanya untuk text message. mine=true = ekor di kanan atas (mirror kiri).
 *
 * Digambar SEBELUM bubble dengan pangkal 2.dp tertutup bubble
 * sehingga sambungannya mulus tanpa nat.
 */
@Composable
fun BubbleTail(mine: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 8.dp, height = 12.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (mine) {
                // Basis di kiri (tertutup bubble), ujung runcing di kanan-atas.
                moveTo(0f, 0f)
                lineTo(w, 0f)
                quadraticTo(w * 0.62f, h * 0.30f, 0f, h)
            } else {
                // Basis di kanan (tertutup bubble), ujung runcing di kiri-atas.
                moveTo(w, 0f)
                lineTo(0f, 0f)
                quadraticTo(w * 0.38f, h * 0.30f, w, h)
            }
            close()
        }
        drawPath(path = path, color = color)
    }
}
