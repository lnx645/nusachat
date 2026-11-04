package id.nusachat.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Pilihan latar + bubble ruang chat per pengguna (ala WA/Telegram/LINE).
 * Disimpan lokal; bubble selalu berpasangan dengan latar agar kontras
 * terjaga (tidak acak).
 */
class ChatThemeStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("nusachat.chat_theme", Context.MODE_PRIVATE)

    var wallpaperId: String
        get() = prefs.getString(KEY_WALLPAPER, ChatWallpapers.DEFAULT) ?: ChatWallpapers.DEFAULT
        set(value) = prefs.edit().putString(KEY_WALLPAPER, value).apply()

    var customUri: String
        get() = prefs.getString(KEY_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URI, value).apply()

    var bubbleId: String
        get() = prefs.getString(KEY_BUBBLE, ChatBubbles.AUTO) ?: ChatBubbles.AUTO
        set(value) = prefs.edit().putString(KEY_BUBBLE, value).apply()

    fun reset() {
        prefs.edit().remove(KEY_WALLPAPER).remove(KEY_URI).remove(KEY_BUBBLE).apply()
    }

    private companion object {
        const val KEY_WALLPAPER = "wallpaper_id"
        const val KEY_URI = "custom_uri"
        const val KEY_BUBBLE = "bubble_id"
    }
}

/** Id wallpaper bawaan. */
object ChatWallpapers {
    const val DEFAULT = "nusa_default" // terang: putih semu abu + doodle
    const val DARK = "nusa_dark" // gelap: doodle dark
    const val SOLID_SAND = "solid_sand"
    const val SOLID_SAGE = "solid_sage"
    const val SOLID_SKY = "solid_sky"
    const val SOLID_LILAC = "solid_lilac"
    const val SOLID_PEACH = "solid_peach"
    const val SOLID_SLATE = "solid_slate" // gelap
    const val CUSTOM = "custom" // gambar dari galeri
}

/** Id pasangan bubble. AUTO = ikut terang/gelap wallpaper. */
object ChatBubbles {
    const val AUTO = "auto"
    const val NUSA = "nusa" // hijau Nusa + putih
    const val WA = "wa" // hijau muda WA + putih
    const val BLUE = "blue"
    const val PURPLE = "purple"
    const val DARK = "dark" // untuk latar gelap/gambar ramai
}
