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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.nusachat.app.R

// Palet brand: teal yang kalem di mata.
val NusaTeal = Color(0xFF009688)
val NusaTealDark = Color(0xFF00796B)
val NusaTealLight = Color(0xFF4DB6AC)

// Background aplikasi ala LINE (satu-satunya sumber warna latar):
// daftar/umum mengikuti background scheme, ruang chat biru khas LINE.
val NusaChatBackgroundLight = Color(0xFF8DB4D6)
val NusaChatBackgroundDark = Color(0xFF1B2837)

private val LightColors = lightColorScheme(
    primary = NusaTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDE8E1),
    onPrimaryContainer = Color(0xFF07332D),
    secondary = Color(0xFF5A5A5A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF222222),
    tertiary = Color(0xFF2C5DB0),
    onTertiary = Color.White,
    background = Color.White, // LINE app bg 100% putih
    onBackground = Color(0xFF111111),
    surface = Color.White, // LINE card bg
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF0F0F0), // LINE separator #E9E9E9 ~ F0
    onSurfaceVariant = Color(0xFF8A8A8A),
    outline = Color(0xFFD0D0D0),
    outlineVariant = Color(0xFFE9E9E9),
    error = Color(0xFFE60012), // LINE red
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    // Ramp surface netral berbasis putih (tanpa tint bawaan Material):
    // dipakai menu ⋮, kotak saran, sheet sistem, dan snackbar.
    surfaceDim = Color(0xFFD9D9D9),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F4F4),
    surfaceContainer = Color(0xFFEDEDED),
    surfaceContainerHigh = Color(0xFFE7E7E7),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    inverseSurface = Color(0xFF2F2F2F),
    inverseOnSurface = Color(0xFFF2F2F2),
    inversePrimary = Color(0xFF4DB6AC),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = NusaTealLight,
    onPrimary = Color(0xFF06302B),
    primaryContainer = Color(0xFF0A4A44),
    onPrimaryContainer = Color(0xFFA5E9DC),
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
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    // Ramp surface netral berbasis #1A1A1A (tanpa tint bawaan Material).
    surfaceDim = Color(0xFF0E0E0E),
    surfaceBright = Color(0xFF383838),
    surfaceContainerLowest = Color(0xFF0B0B0B),
    surfaceContainerLow = Color(0xFF1F1F1F),
    surfaceContainer = Color(0xFF232323),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF333333),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1A1A1A),
    inversePrimary = Color(0xFF009688),
    scrim = Color(0xFF000000),
)

// Satu-satunya font aplikasi: Manrope (400/500/700). Bobot lain
// (SemiBold/ExtraBold) dipetakan Compose ke file terdekat.
private val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

// LINE — ukuran diekstrak dari layout chat_ui_row_* dan values/dimens
// Header Obrolan 24sp extraBold, title 19sp, body pesan 15sp, timestamp 11sp, label chip 13sp
// Semua slot Material diisi supaya komponen bawaan tidak lari ke ukuran default.
private val LineTypography = Typography(
    displayLarge = TextStyle(fontFamily = Manrope, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = Manrope, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    displaySmall = TextStyle(fontFamily = Manrope, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = Manrope, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = Manrope, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontFamily = Manrope, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = Manrope, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = Manrope, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = Manrope, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Manrope, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = Manrope, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = Manrope, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = Manrope, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = Manrope, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = Manrope, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal),
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
