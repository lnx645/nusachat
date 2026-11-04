package id.nusachat.app.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Gaya latar story teks: kunci style (disimpan di server) + gradasi.
 */
object StoryStyles {
    val STYLES = listOf("sunset", "ocean", "forest", "grape", "mono", "candy")

    fun brushFor(style: String?): Brush = when (style) {
        "sunset" -> Brush.verticalGradient(listOf(Color(0xFFFF6A3D), Color(0xFFC81D5E)))
        "ocean" -> Brush.verticalGradient(listOf(Color(0xFF11998E), Color(0xFF1D4ED8)))
        "forest" -> Brush.verticalGradient(listOf(Color(0xFF134E4A), Color(0xFF65A30D)))
        "grape" -> Brush.verticalGradient(listOf(Color(0xFF6D28D9), Color(0xFFDB2777)))
        "mono" -> Brush.verticalGradient(listOf(Color(0xFF27272A), Color(0xFF09090B)))
        "candy" -> Brush.verticalGradient(listOf(Color(0xFFF472B6), Color(0xFFFACC15)))
        else -> Brush.verticalGradient(listOf(Color(0xFF18181B), Color(0xFF09090B)))
    }

    @Composable
    fun Swatch(style: String, selected: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(brushFor(style))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else Modifier
                )
                .clickable(onClick = onClick),
        )
    }
}
