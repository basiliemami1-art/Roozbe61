package com.gozar.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.gozar.app.data.ThemeMode

// Brand palette. Violet carries identity, mint signals a healthy tunnel, and
// amber/rose carry degraded and failed states across both schemes.
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
    secondaryContainer = Color(0xFF0C3B31),
    onSecondaryContainer = Color(0xFF9FF5DD),
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
    secondaryContainer = Color(0xFFC5F5E7),
    onSecondaryContainer = Color(0xFF002016),
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

private val GozarTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
    )
}

/** Tabular-ish style for counters that update every second. */
val MonoNumber = TextStyle(
    fontWeight = FontWeight.Bold,
    fontSize = 22.sp,
    letterSpacing = 0.sp,
)

@Composable
fun GozarTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    val context = LocalContext.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = GozarTypography,
        content = content,
    )
}
