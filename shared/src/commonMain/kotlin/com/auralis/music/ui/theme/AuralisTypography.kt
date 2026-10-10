package com.auralis.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// ============================================================================
// 📐 MATERIAL 3 TYPOGRAPHY SCALE (Auralis Typography Engine)
// ============================================================================

/**
 * The Auralis type scale. [display] is Outfit (headings, titles) and [body] is Plus Jakarta Sans
 * (body text, labels); each platform passes its own copy of those two fonts.
 */
fun auralisTypography(display: FontFamily, body: FontFamily): Typography = Typography(
    // Large prominent titles & Hero sections
    displayLarge = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.025).em
    ),
    displayMedium = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.025).em
    ),
    displaySmall = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.025).em
    ),

    // Section headers & Sheet titles
    headlineLarge = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.025).em
    ),
    headlineMedium = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.025).em
    ),
    headlineSmall = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.02).em
    ),

    // Card titles, modal headers, track titles
    titleLarge = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.015).em
    ),
    titleMedium = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.01).em
    ),
    titleSmall = TextStyle(
        fontFamily = display,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),

    // Body content, descriptions, search queries
    bodyLarge = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp
    ),
    bodySmall = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),

    // Buttons, badges, metadata chips, timestamps
    labelLarge = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = body,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    )
)


/**
 * The type scale uses negative letter spacing and tight line heights tuned for Latin text. Letter
 * spacing splits joined scripts (Devanagari, Bengali, Arabic, Thai…) into separate clusters, so
 * Android measures them too narrow and wraps "होम" as "हो / म"; the tight line height clips their
 * vowel marks. For those languages, drop the letter spacing and let the font decide line height.
 */
fun scriptSafeTypography(base: androidx.compose.material3.Typography, locale: java.util.Locale): androidx.compose.material3.Typography {
    if (locale.language !in JOINED_OR_TALL_SCRIPT_LANGUAGES) return base
    fun TextStyle.safe() = copy(
        letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
        lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified
    )
    return base.copy(
        displayLarge = base.displayLarge.safe(), displayMedium = base.displayMedium.safe(), displaySmall = base.displaySmall.safe(),
        headlineLarge = base.headlineLarge.safe(), headlineMedium = base.headlineMedium.safe(), headlineSmall = base.headlineSmall.safe(),
        titleLarge = base.titleLarge.safe(), titleMedium = base.titleMedium.safe(), titleSmall = base.titleSmall.safe(),
        bodyLarge = base.bodyLarge.safe(), bodyMedium = base.bodyMedium.safe(), bodySmall = base.bodySmall.safe(),
        labelLarge = base.labelLarge.safe(), labelMedium = base.labelMedium.safe(), labelSmall = base.labelSmall.safe()
    )
}

private val JOINED_OR_TALL_SCRIPT_LANGUAGES = setOf(
    "hi", "mr", "ne", "bn", "as", "pa", "gu", "or", "ta", "te", "kn", "ml", "si", // Indic
    "ar", "fa", "ur", "ps", "he", "iw", "yi",                                     // Arabic / Hebrew
    "th", "lo", "km", "my",                                                       // Southeast Asian
    "ja", "ko", "zh", "am", "ka", "hy"
)
