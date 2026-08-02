package com.gozar.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gozar.app.R

/**
 * Vazirmatn, bundled rather than relying on the system font.
 *
 * Android's default families render Persian acceptably but with Latin metrics,
 * so mixed lines — a server name next to a latency in milliseconds — sit on
 * inconsistent baselines and the numerals change shape between screens.
 * Vazirmatn covers Persian and Latin in one family, so everything lines up.
 *
 * Four static weights are shipped instead of the variable font: variable-font
 * weight interpolation is not dependable across the Android versions this app
 * supports, and 480 KB is a fair price for text that always looks right.
 */
private val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

/**
 * Material 3's scale with the family swapped in and the weights nudged for a
 * Persian face — Vazirmatn's Regular reads a touch lighter than Roboto's, so
 * titles carry a little more weight to keep the hierarchy legible.
 */
val GozarTypography: Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.copy(fontFamily = Vazirmatn, fontWeight = FontWeight.Bold),
        displayMedium = displayMedium.copy(fontFamily = Vazirmatn, fontWeight = FontWeight.Bold),
        displaySmall = displaySmall.copy(fontFamily = Vazirmatn, fontWeight = FontWeight.Bold),

        headlineLarge = headlineLarge.copy(fontFamily = Vazirmatn, fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(
            fontFamily = Vazirmatn,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
        ),
        headlineSmall = headlineSmall.copy(fontFamily = Vazirmatn, fontWeight = FontWeight.SemiBold),

        titleLarge = titleLarge.copy(fontFamily = Vazirmatn, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = Vazirmatn, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = Vazirmatn, fontWeight = FontWeight.Medium),

        // Persian needs a little more line height than the Latin default.
        bodyLarge = bodyLarge.copy(fontFamily = Vazirmatn, lineHeight = 26.sp),
        bodyMedium = bodyMedium.copy(fontFamily = Vazirmatn, lineHeight = 23.sp),
        bodySmall = bodySmall.copy(fontFamily = Vazirmatn, lineHeight = 19.sp),

        labelLarge = labelLarge.copy(fontFamily = Vazirmatn, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontFamily = Vazirmatn, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontFamily = Vazirmatn, fontWeight = FontWeight.Medium),
    )
}
