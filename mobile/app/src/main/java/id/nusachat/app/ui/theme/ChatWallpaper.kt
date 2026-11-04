package id.nusachat.app.ui.theme

import androidx.compose.ui.graphics.Color
import id.nusachat.app.data.ChatBubbles
import id.nusachat.app.data.ChatWallpapers

/**
 * Resolusi wallpaper + pasangan bubble ruang chat.
 *
 * Aturan produksi:
 * - Default terang = putih semu abu (#ECE5DD ala WA) + doodle Nusa.
 * - Setiap wallpaper menentukan apakah ia "gelap"; bubble AUTO mengikuti.
 * - Bubble custom selalu satu paket (mine + other + warna teks) supaya
 *   kontras dengan latar terjaga, tidak acak.
 */
data class ResolvedBubbles(
    val mine: Color,
    val mineText: Color,
    val other: Color,
    val otherText: Color,
)

data class ResolvedChatTheme(
    /** Warna dasar di bawah pola/gambar (juga warna Scaffold). */
    val base: Color,
    val isDark: Boolean,
    val customUri: String? = null,
    val bubbles: ResolvedBubbles,
    /** Lapisan gelap tipis di atas gambar custom agar bubble terbaca. */
    val customScrim: Float = 0f,
)

private val LightMine = NusaGreen
private val LightMineText = Color.White
private val LightOther = Color.White
private val LightOtherText = Color(0xFF111111)

private val DarkMine = Color(0xFF005C4B)
private val DarkMineText = Color(0xFFE7FFDB)
private val DarkOther = Color(0xFF1F2C34)
private val DarkOtherText = Color(0xFFE9EDEF)

fun bubblePair(bubbleId: String, dark: Boolean): ResolvedBubbles {
    if (bubbleId == ChatBubbles.AUTO) {
        return if (dark) {
            ResolvedBubbles(DarkMine, DarkMineText, DarkOther, DarkOtherText)
        } else {
            ResolvedBubbles(LightMine, LightMineText, LightOther, LightOtherText)
        }
    }
    return when (bubbleId) {
        ChatBubbles.WA -> ResolvedBubbles(
            mine = Color(0xFFDCF8C6), mineText = Color(0xFF111111),
            other = Color.White, otherText = Color(0xFF111111),
        )
        ChatBubbles.BLUE -> ResolvedBubbles(
            mine = Color(0xFFD2E3FC), mineText = Color(0xFF0B2A4A),
            other = Color.White, otherText = Color(0xFF111111),
        )
        ChatBubbles.PURPLE -> ResolvedBubbles(
            mine = Color(0xFFE4D7FB), mineText = Color(0xFF2E1A5E),
            other = Color.White, otherText = Color(0xFF111111),
        )
        ChatBubbles.DARK -> ResolvedBubbles(DarkMine, DarkMineText, DarkOther, DarkOtherText)
        else -> ResolvedBubbles(LightMine, LightMineText, LightOther, LightOtherText) // NUSA
    }
}

/** Wallpaper bawaan: (id, label, warna dasar). */
fun builtinWallpapers(): List<Triple<String, String, Long>> = listOf(
    Triple(ChatWallpapers.DEFAULT, "Nusa Terang", 0xFFECE5DD),
    Triple(ChatWallpapers.DARK, "Nusa Gelap", 0xFF0B141A),
    Triple(ChatWallpapers.SOLID_SAND, "Pasir", 0xFFE7DCC8),
    Triple(ChatWallpapers.SOLID_SAGE, "Sage", 0xFFDCE5D8),
    Triple(ChatWallpapers.SOLID_SKY, "Langit", 0xFFD8E6F3),
    Triple(ChatWallpapers.SOLID_LILAC, "Lilac", 0xFFE2DCF1),
    Triple(ChatWallpapers.SOLID_PEACH, "Persik", 0xFFF3DDD3),
    Triple(ChatWallpapers.SOLID_SLATE, "Batu Tulis", 0xFF272E36),
)

/** Id wallpaper yang dianggap gelap (untuk bubble AUTO). */
fun isDarkWallpaper(wallpaperId: String, systemDark: Boolean): Boolean = when (wallpaperId) {
    ChatWallpapers.DARK -> true
    ChatWallpapers.SOLID_SLATE -> true
    ChatWallpapers.CUSTOM -> systemDark
    ChatWallpapers.DEFAULT -> false
    else -> if (wallpaperId.startsWith("solid_")) false else systemDark
}

fun resolveChatTheme(
    wallpaperId: String,
    customUri: String,
    bubbleId: String,
    systemDark: Boolean,
): ResolvedChatTheme {
    val dark = isDarkWallpaper(wallpaperId, systemDark)
    val base = builtinWallpapers().firstOrNull { it.first == wallpaperId }?.let { Color(it.third) }
        ?: if (dark) Color(0xFF0B141A) else Color(0xFFECE5DD)
    val uri = if (wallpaperId == ChatWallpapers.CUSTOM) customUri.takeIf { it.isNotBlank() } else null
    return ResolvedChatTheme(
        base = base,
        isDark = dark,
        customUri = uri,
        bubbles = bubblePair(bubbleId, dark),
        customScrim = if (uri != null) 0.28f else 0f,
    )
}

/** Nama drawable doodle untuk wallpaper, null bila bukan doodle. */
fun doodleDrawableName(wallpaperId: String): String? = when (wallpaperId) {
    ChatWallpapers.DEFAULT -> "chat_doodle_light"
    ChatWallpapers.DARK -> "chat_doodle_dark"
    else -> null
}
