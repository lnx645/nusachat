package id.nusachat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palet terinspirasi LINE — reverse dari jp.naver.line.android.apk
// Primary LINE 06C755 ditemukan di res/color/selector_timeline_linegreen & bubble green.9.png
val NusaGreen = Color(0xFF06C755)
val NusaGreenDark = Color(0xFF00B900)
val NusaMint = Color(0xFF4FD080)

// Background aplikasi ala LINE (satu-satunya sumber warna latar):
// daftar/umum mengikuti background scheme, ruang chat biru khas LINE.
val NusaChatBackgroundLight = Color(0xFF8DB4D6)
val NusaChatBackgroundDark = Color(0xFF1B2837)

private val LightColors = lightColorScheme(
    primary = NusaGreen, // LINE green
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F7ED),
    onPrimaryContainer = Color(0xFF0A3D1C),
    secondary = Color(0xFF5A5A5A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF222222),
    tertiary = Color(0xFF2C5DB0),
    onTertiary = Color.White,
    background = Color(0xFFF5F7F8), // LINE app bg
    onBackground = Color(0xFF111111),
    surface = Color.White, // LINE card bg
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF0F0F0), // LINE separator #E9E9E9 ~ F0
    onSurfaceVariant = Color(0xFF8A8A8A),
    outline = Color(0xFFD0D0D0),
    outlineVariant = Color(0xFFE9E9E9),
    error = Color(0xFFE60012), // LINE red
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = NusaGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0A4D24),
    onPrimaryContainer = Color(0xFFB8F0C8),
    secondary = Color(0xFFB0B0B0),
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFF2C2C2C),
    onSecondaryContainer = Color(0xFFE0E0E0),
    tertiary = Color(0xFF8AB4F8),
    onTertiary = Color(0xFF0B1A33),
    background = Color(0xFF121212), // LINE dark app bg
    onBackground = Color(0xFFEFEFEF),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFEFEFEF),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFF9E9E9E),
    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF2E2E2E),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0000),
)

// LINE — ukuran diekstrak dari layout chat_ui_row_* dan values/dimens
// Header Obrolan 24sp extraBold, title 19sp, body pesan 15sp, timestamp 11sp, label chip 13sp
// Semua slot Material diisi supaya komponen bawaan tidak lari ke ukuran default.
private val LineTypography = Typography(
    displayLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    displaySmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal),
)

private val LineShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun NusaChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LineTypography,
        shapes = LineShapes,
        content = content,
    )
}
