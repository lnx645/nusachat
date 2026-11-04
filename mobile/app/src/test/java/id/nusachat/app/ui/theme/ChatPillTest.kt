package id.nusachat.app.ui.theme

import androidx.compose.ui.graphics.Color
import id.nusachat.app.data.ChatWallpapers
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sinkronisasi chat: pil & wallpaper mengikuti gelap wallpaper, bukan tema aplikasi. */
class ChatPillTest {
    @Test
    fun pillColorsFollowWallpaper() {
        assertEquals(Color(0xFF182229) to Color(0xFF8696A0), chatPillColors(true))
        assertEquals(Color.White to Color(0xFF54656F), chatPillColors(false))
    }

    @Test
    fun wallpaperDarknessMapping() {
        assertEquals(true, isDarkWallpaper(ChatWallpapers.DARK, false))
        assertEquals(true, isDarkWallpaper(ChatWallpapers.SOLID_SLATE, false))
        assertEquals(false, isDarkWallpaper(ChatWallpapers.DEFAULT, true))
        assertEquals(false, isDarkWallpaper(ChatWallpapers.SOLID_SKY, true))
        assertEquals(true, isDarkWallpaper(ChatWallpapers.CUSTOM, true))
        assertEquals(false, isDarkWallpaper(ChatWallpapers.CUSTOM, false))
    }
}
