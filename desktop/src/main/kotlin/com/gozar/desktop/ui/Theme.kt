package com.gozar.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp
import com.gozar.app.data.ThemeMode

// The same palette as the phone app: violet carries identity, mint means a
// healthy tunnel, amber and rose mean degraded and failed.
val Violet = Color(0xFF7C6CFF)
val VioletDeep = Color(0xFF5B4BE0)
val Mint = Color(0xFF00E6B0)
val MintDeep = Color(0xFF00B98D)
val Amber = Color(0xFFFFB020)
val Rose = Color(0xFFFF5C7A)

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2352),
    onPrimaryContainer = Color(0xFFD9D3FF),
    secondary = Mint,
    onSecondary = Color(0xFF00251B),
    tertiary = Rose,
    background = Color(0xFF0B0B14),
    onBackground = Color(0xFFE8E7F0),
    surface = Color(0xFF14141F),
    onSurface = Color(0xFFE8E7F0),
    surfaceVariant = Color(0xFF1E1E2D),
    onSurfaceVariant = Color(0xFFA9A7BC),
    outline = Color(0xFF34334A),
    outlineVariant = Color(0xFF26263A),
    error = Rose,
)

private val LightColors = lightColorScheme(
    primary = VioletDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E1FF),
    onPrimaryContainer = Color(0xFF1B1150),
    secondary = MintDeep,
    onSecondary = Color.White,
    tertiary = Color(0xFFD93F63),
    background = Color(0xFFF7F6FC),
    onBackground = Color(0xFF171626),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171626),
    surfaceVariant = Color(0xFFEFEDF7),
    onSurfaceVariant = Color(0xFF5A586E),
    outline = Color(0xFFD5D2E4),
    outlineVariant = Color(0xFFE7E4F1),
    error = Color(0xFFD93F63),
)

/**
 * Vazirmatn, loaded from the shared resources so the Windows app renders
 * Persian with the same metrics as the phone. Falls back to the platform
 * default if the resource is missing rather than failing to start.
 */
private val vazirmatn: FontFamily = runCatching {
    FontFamily(
        Font("fonts/vazirmatn_regular.ttf", FontWeight.Normal),
        Font("fonts/vazirmatn_medium.ttf", FontWeight.Medium),
        Font("fonts/vazirmatn_semibold.ttf", FontWeight.SemiBold),
        Font("fonts/vazirmatn_bold.ttf", FontWeight.Bold),
    )
}.getOrDefault(FontFamily.Default)

private val GozarTypography = Typography().run {
    Typography(
        displaySmall = displaySmall.copy(fontFamily = vazirmatn, fontWeight = FontWeight.Bold),
        headlineLarge = headlineLarge.copy(fontFamily = vazirmatn, fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontFamily = vazirmatn, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = vazirmatn, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontFamily = vazirmatn, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = vazirmatn, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = vazirmatn, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = vazirmatn, lineHeight = 26.sp),
        bodyMedium = bodyMedium.copy(fontFamily = vazirmatn, lineHeight = 23.sp),
        bodySmall = bodySmall.copy(fontFamily = vazirmatn, lineHeight = 19.sp),
        labelLarge = labelLarge.copy(fontFamily = vazirmatn, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontFamily = vazirmatn, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontFamily = vazirmatn, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun GozarTheme(theme: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        // Desktop has no reliable cross-version system signal, so "follow
        // system" resolves to dark, which is what this app is designed around.
        ThemeMode.SYSTEM -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = GozarTypography,
        content = content,
    )
}
