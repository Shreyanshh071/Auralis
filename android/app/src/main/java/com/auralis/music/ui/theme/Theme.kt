package com.auralis.music.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.toHct
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ============================================================================
// 🎨 DYNAMIC ARTWORK PALETTE & TINT ENGINE
// ============================================================================

/**
 * Atmospheric dynamic palette computed from real-time album artwork dominant colors.
 * Used for dynamic bloom, glass tints, and accent glows across Now Playing and MiniPlayer.
 */
@Immutable
data class AuralisDynamicPalette(
    val tintA: Color = DefaultAmbientTintA,
    val tintB: Color = DefaultAmbientTintB,
    val tintC: Color = DefaultAmbientTintC,
    val surfaceTint: Color = AuralisSurface,
    val accentGlow: Color = DefaultAmbientTintA.copy(alpha = 0.45f),
    val isDark: Boolean = true
)

val LocalAuralisDynamicPalette = compositionLocalOf {
    AuralisDynamicPalette()
}

val LocalDynamicThemePrimary = compositionLocalOf { Color.Unspecified }
val LocalDynamicThemeSecondary = compositionLocalOf { Color.Unspecified }
val LocalDynamicThemeTertiary = compositionLocalOf { Color.Unspecified }
val LocalDynamicThemeBackground = compositionLocalOf { Color.Unspecified }
val LocalDynamicThemeSurface = compositionLocalOf { Color.Unspecified }
val LocalDynamicThemeOnBackground = compositionLocalOf { Color.Unspecified }
val LocalDynamicThemeOnSurface = compositionLocalOf { Color.Unspecified }

/**
 * Computes an atmospheric, harmonious color palette from an extracted dominant artwork color.
 */
fun generateDynamicPalette(
    dominantColor: Color?,
    isDark: Boolean = true,
    artworkPalette: ArtworkPalette? = null
): AuralisDynamicPalette {
    if (artworkPalette != null && artworkPalette != ArtworkPaletteCache.defaultPalette && artworkPalette.seedColor != Color.Unspecified) {
        if (artworkPalette.isMonochrome) {
            // Genuinely monochrome/grayscale artwork: pure neutral grayscale hierarchy without any artificial hue
            val silverPrimary = Color(0xFFE0E0E0)
            val midGraySecondary = Color(0xFF9E9E9E)
            val darkGrayTertiary = Color(0xFF616161)
            val neutralSurfaceTint = if (isDark) Color(0xFF141414) else Color(0xFFF0F0F0)
            return AuralisDynamicPalette(
                tintA = silverPrimary,
                tintB = midGraySecondary,
                tintC = darkGrayTertiary,
                surfaceTint = neutralSurfaceTint,
                accentGlow = silverPrimary.copy(alpha = if (isDark) 0.35f else 0.20f),
                isDark = isDark
            )
        }
        val pri = artworkPalette.primary
        val sec = artworkPalette.secondary
        val tert = artworkPalette.tertiary
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(pri.toArgb(), hsv)
        val surfaceTint = if (isDark) Color.hsv(hsv[0], 0.08f, 0.11f) else Color.hsv(hsv[0], 0.04f, 0.94f)
        return AuralisDynamicPalette(
            tintA = pri,
            tintB = sec,
            tintC = tert,
            surfaceTint = surfaceTint,
            accentGlow = pri.copy(alpha = if (isDark) 0.5f else 0.35f),
            isDark = isDark
        )
    }

    if (dominantColor == null || dominantColor == Color.Unspecified) {
        return AuralisDynamicPalette(isDark = isDark)
    }

    // Extract HSL from dominant color for harmonic gradient generation
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(dominantColor.toArgb(), hsv)
    val hue = hsv[0]
    val sat = hsv[1]

    // If dominant color is grayscale / monochrome (sat < 0.08f), render sleek neutral palette
    if (sat < 0.08f) {
        val silverPrimary = Color(0xFFE0E0E0)
        val midGraySecondary = Color(0xFF9E9E9E)
        val darkGrayTertiary = Color(0xFF616161)
        return AuralisDynamicPalette(
            tintA = silverPrimary,
            tintB = midGraySecondary,
            tintC = darkGrayTertiary,
            surfaceTint = if (isDark) Color(0xFF141414) else Color(0xFFF0F0F0),
            accentGlow = silverPrimary.copy(alpha = if (isDark) 0.35f else 0.20f),
            isDark = isDark
        )
    }

    val naturalSat = sat.coerceIn(0.12f, 0.80f)
    val value = if (isDark) hsv[2].coerceIn(0.6f, 0.95f) else hsv[2].coerceIn(0.4f, 0.8f)

    // Primary Tint (Hue shifted slightly + authentic saturation)
    val tintAColor = Color.hsv(hue, naturalSat, value)

    // Secondary Tint (Analogous color + 35 degrees shift)
    val secondaryHue = (hue + 35f) % 360f
    val tintBColor = Color.hsv(secondaryHue, (naturalSat * 0.85f).coerceAtLeast(0.10f), value)

    // Tertiary Tint (Complementary-adjacent + 140 degrees shift)
    val tertiaryHue = (hue + 140f) % 360f
    val tintCColor = Color.hsv(tertiaryHue, (naturalSat * 0.75f).coerceAtLeast(0.08f), value)

    // Subtle dark surface tint
    val surfaceTint = if (isDark) Color.hsv(hue, 0.08f, 0.11f) else Color.hsv(hue, 0.04f, 0.94f)

    return AuralisDynamicPalette(
        tintA = tintAColor,
        tintB = tintBColor,
        tintC = tintCColor,
        surfaceTint = surfaceTint,
        accentGlow = tintAColor.copy(alpha = 0.5f),
        isDark = isDark
    )
}

// ============================================================================
// 🌓 MATERIAL 3 COLOR SCHEMES
// ============================================================================
// 🎨 CURATED COLOR PALETTES (MATERIAL YOU SPEC)
// ============================================================================

@Immutable
data class CuratedPalette(
    val id: String,
    val name: String,
    val primaryDark: Color,
    val primaryLight: Color,
    val secondaryDark: Color,
    val secondaryLight: Color,
    val tertiaryDark: Color,
    val tertiaryLight: Color,
    val previewPrimary: Color = primaryDark,
    val previewSecondary: Color = secondaryDark,
    val previewTertiary: Color = tertiaryDark
) {
    // Backward-compatibility properties
    val primary: Color get() = primaryDark
    val secondary: Color get() = secondaryDark
    val tertiary: Color get() = tertiaryDark
}

val CuratedPalettes = listOf(
    CuratedPalette(
        id = "Auralis Lime",
        name = "Auralis Lime",
        primaryDark = Color(0xFFD4E157),
        primaryLight = Color(0xFF536600),
        secondaryDark = Color(0xFFA2D0C1),
        secondaryLight = Color(0xFF3A665A),
        tertiaryDark = Color(0xFFC4CAAC),
        tertiaryLight = Color(0xFF5C6246),
        previewPrimary = Color(0xFFD4E157),
        previewSecondary = Color(0xFFA2D0C1),
        previewTertiary = Color(0xFFC4CAAC)
    ),
    CuratedPalette(
        id = "Crimson Amber",
        name = "Crimson Amber",
        primaryDark = Color(0xFFE53935),
        primaryLight = Color(0xFFB3261E),
        secondaryDark = Color(0xFFEBB09B),
        secondaryLight = Color(0xFF775652),
        tertiaryDark = Color(0xFFDEB070),
        tertiaryLight = Color(0xFF705C2E),
        previewPrimary = Color(0xFFE53935),
        previewSecondary = Color(0xFFEBB09B),
        previewTertiary = Color(0xFFDEB070)
    ),
    CuratedPalette(
        id = "Rose Gold",
        name = "Rose Gold",
        primaryDark = Color(0xFFF06292),
        primaryLight = Color(0xFF984061),
        secondaryDark = Color(0xFFE2BDC6),
        secondaryLight = Color(0xFF74565F),
        tertiaryDark = Color(0xFFEFBD94),
        tertiaryLight = Color(0xFF7C5635),
        previewPrimary = Color(0xFFF06292),
        previewSecondary = Color(0xFFE2BDC6),
        previewTertiary = Color(0xFFEFBD94)
    ),
    CuratedPalette(
        id = "Purple Lilac",
        name = "Purple Lilac",
        primaryDark = Color(0xFFBA68C8),
        primaryLight = Color(0xFF6750A4),
        secondaryDark = Color(0xFFD7BDE2),
        secondaryLight = Color(0xFF625B71),
        tertiaryDark = Color(0xFFEFB8C8),
        tertiaryLight = Color(0xFF7D5260),
        previewPrimary = Color(0xFFBA68C8),
        previewSecondary = Color(0xFFD7BDE2),
        previewTertiary = Color(0xFFEFB8C8)
    ),
    CuratedPalette(
        id = "Indigo Lavender",
        name = "Indigo Lavender",
        primaryDark = Color(0xFF7986CB),
        primaryLight = Color(0xFF4355B9),
        secondaryDark = Color(0xFFC5CAE9),
        secondaryLight = Color(0xFF5B5D72),
        tertiaryDark = Color(0xFFE3BADB),
        tertiaryLight = Color(0xFF75546F),
        previewPrimary = Color(0xFF7986CB),
        previewSecondary = Color(0xFFC5CAE9),
        previewTertiary = Color(0xFFE3BADB)
    ),
    CuratedPalette(
        id = "Ocean Blue",
        name = "Ocean Blue",
        primaryDark = Color(0xFF42A5F5),
        primaryLight = Color(0xFF0061A4),
        secondaryDark = Color(0xFF90CAF9),
        secondaryLight = Color(0xFF535F70),
        tertiaryDark = Color(0xFFD6BEE4),
        tertiaryLight = Color(0xFF6B5778),
        previewPrimary = Color(0xFF42A5F5),
        previewSecondary = Color(0xFF90CAF9),
        previewTertiary = Color(0xFFD6BEE4)
    ),
    CuratedPalette(
        id = "Teal Cyan",
        name = "Teal Cyan",
        primaryDark = Color(0xFF26C6DA),
        primaryLight = Color(0xFF006A6A),
        secondaryDark = Color(0xFF80DEEA),
        secondaryLight = Color(0xFF4A6363),
        tertiaryDark = Color(0xFFB3C8E8),
        tertiaryLight = Color(0xFF4B607C),
        previewPrimary = Color(0xFF26C6DA),
        previewSecondary = Color(0xFF80DEEA),
        previewTertiary = Color(0xFFB3C8E8)
    ),
    CuratedPalette(
        id = "Emerald Mint",
        name = "Emerald Mint",
        primaryDark = Color(0xFF26A69A),
        primaryLight = Color(0xFF00685F),
        secondaryDark = Color(0xFF80CBC4),
        secondaryLight = Color(0xFF4A635F),
        tertiaryDark = Color(0xFFA7FFEB),
        tertiaryLight = Color(0xFF006874),
        previewPrimary = Color(0xFF26A69A),
        previewSecondary = Color(0xFF80CBC4),
        previewTertiary = Color(0xFFA7FFEB)
    ),
    CuratedPalette(
        id = "Forest Green",
        name = "Forest Green",
        primaryDark = Color(0xFF66BB6A),
        primaryLight = Color(0xFF2E6A3E),
        secondaryDark = Color(0xFFA5D6A7),
        secondaryLight = Color(0xFF516350),
        tertiaryDark = Color(0xFFA0CFD1),
        tertiaryLight = Color(0xFF386567),
        previewPrimary = Color(0xFF66BB6A),
        previewSecondary = Color(0xFFA5D6A7),
        previewTertiary = Color(0xFFA0CFD1)
    ),
    CuratedPalette(
        id = "Golden Yellow",
        name = "Golden Yellow",
        primaryDark = Color(0xFFFFEE58),
        primaryLight = Color(0xFF6A5F00),
        secondaryDark = Color(0xFFFFF59D),
        secondaryLight = Color(0xFF635F41),
        tertiaryDark = Color(0xFFA6D0B7),
        tertiaryLight = Color(0xFF406653),
        previewPrimary = Color(0xFFFFEE58),
        previewSecondary = Color(0xFFFFF59D),
        previewTertiary = Color(0xFFA6D0B7)
    ),
    CuratedPalette(
        id = "Bronze Amber",
        name = "Bronze Amber",
        primaryDark = Color(0xFFFFA726),
        primaryLight = Color(0xFF7E5700),
        secondaryDark = Color(0xFFFFCC80),
        secondaryLight = Color(0xFF6E5D40),
        tertiaryDark = Color(0xFFB6CEA7),
        tertiaryLight = Color(0xFF4F6546),
        previewPrimary = Color(0xFFFFA726),
        previewSecondary = Color(0xFFFFCC80),
        previewTertiary = Color(0xFFB6CEA7)
    ),
    CuratedPalette(
        id = "Sunset Orange",
        name = "Sunset Orange",
        primaryDark = Color(0xFFFF7043),
        primaryLight = Color(0xFF8F4C38),
        secondaryDark = Color(0xFFFFAB91),
        secondaryLight = Color(0xFF77574E),
        tertiaryDark = Color(0xFFD8C58D),
        tertiaryLight = Color(0xFF6C5D2F),
        previewPrimary = Color(0xFFFF7043),
        previewSecondary = Color(0xFFFFAB91),
        previewTertiary = Color(0xFFD8C58D)
    ),
    CuratedPalette(
        id = "Coral Flame",
        name = "Coral Flame",
        primaryDark = Color(0xFFFF5722),
        primaryLight = Color(0xFF9C412C),
        secondaryDark = Color(0xFFFF8A65),
        secondaryLight = Color(0xFF77564E),
        tertiaryDark = Color(0xFFD3C68E),
        tertiaryLight = Color(0xFF685E30),
        previewPrimary = Color(0xFFFF5722),
        previewSecondary = Color(0xFFFF8A65),
        previewTertiary = Color(0xFFD3C68E)
    ),
    CuratedPalette(
        id = "Coffee Brown",
        name = "Coffee Brown",
        primaryDark = Color(0xFFBCAAA4),
        primaryLight = Color(0xFF7A5930),
        secondaryDark = Color(0xFFD7CCC8),
        secondaryLight = Color(0xFF6B5C4E),
        tertiaryDark = Color(0xFFBFC9AC),
        tertiaryLight = Color(0xFF586249),
        previewPrimary = Color(0xFFBCAAA4),
        previewSecondary = Color(0xFFD7CCC8),
        previewTertiary = Color(0xFFBFC9AC)
    ),
    CuratedPalette(
        id = "Monochrome Grey",
        name = "Monochrome Grey",
        primaryDark = Color(0xFFE0E0E0),
        primaryLight = Color(0xFF424242),
        secondaryDark = Color(0xFF9E9E9E),
        secondaryLight = Color(0xFF757575),
        tertiaryDark = Color(0xFF616161),
        tertiaryLight = Color(0xFF9E9E9E),
        previewPrimary = Color(0xFFE0E0E0),
        previewSecondary = Color(0xFF9E9E9E),
        previewTertiary = Color(0xFF616161)
    ),
    CuratedPalette(
        id = "Sky Blue",
        name = "Sky Blue",
        primaryDark = Color(0xFF4FC3F7),
        primaryLight = Color(0xFF00658F),
        secondaryDark = Color(0xFF81D4FA),
        secondaryLight = Color(0xFF455A64),
        tertiaryDark = Color(0xFF80CBC4),
        tertiaryLight = Color(0xFF004D40),
        previewPrimary = Color(0xFF4FC3F7),
        previewSecondary = Color(0xFF81D4FA),
        previewTertiary = Color(0xFF80CBC4)
    ),
    CuratedPalette(
        id = "Electric Violet",
        name = "Electric Violet",
        primaryDark = Color(0xFFB388FF),
        primaryLight = Color(0xFF7B1FA2),
        secondaryDark = Color(0xFFCE93D8),
        secondaryLight = Color(0xFF512DA8),
        tertiaryDark = Color(0xFFFF80AB),
        tertiaryLight = Color(0xFFC2185B),
        previewPrimary = Color(0xFFB388FF),
        previewSecondary = Color(0xFFCE93D8),
        previewTertiary = Color(0xFFFF80AB)
    ),
    CuratedPalette(
        id = "Ruby Red",
        name = "Ruby Red",
        primaryDark = Color(0xFFEF5350),
        primaryLight = Color(0xFFBA1A1A),
        secondaryDark = Color(0xFFEF9A9A),
        secondaryLight = Color(0xFF775656),
        tertiaryDark = Color(0xFFEFBD94),
        tertiaryLight = Color(0xFF7C5635),
        previewPrimary = Color(0xFFEF5350),
        previewSecondary = Color(0xFFEF9A9A),
        previewTertiary = Color(0xFFEFBD94)
    )
)

fun getPaletteById(id: String): CuratedPalette {
    return CuratedPalettes.firstOrNull { it.id == id } ?: CuratedPalettes[0] // Default to Auralis Lime
}

// ============================================================================
// 🌓 MATERIAL 3 COLOR SCHEMES
// ============================================================================

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBDD269),
    onPrimary = Color(0xFF1C2000),
    primaryContainer = Color(0xFF3B4800),
    onPrimaryContainer = Color(0xFFDCE2BD),
    secondary = Color(0xFFC4CAAC),
    onSecondary = Color(0xFF2E331B),
    secondaryContainer = Color(0xFF444A30),
    onSecondaryContainer = Color(0xFFE1E7C5),
    tertiary = Color(0xFFA2D0C1),
    onTertiary = Color(0xFF04372D),
    tertiaryContainer = Color(0xFF204E43),
    onTertiaryContainer = Color(0xFFBCECE0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF161619),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF202024),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF383840),
    outlineVariant = Color(0xFF28282E)
)

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF536600),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE2BD),
    onPrimaryContainer = Color(0xFF171E00),
    secondary = Color(0xFF5C6246),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E7C5),
    onSecondaryContainer = Color(0xFF191E09),
    tertiary = Color(0xFF3A665A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCECE0),
    onTertiaryContainer = Color(0xFF00201A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9FAFB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFF9CA3AF),
    outlineVariant = Color(0xFFE5E7EB)
)

val AmoledDarkColorScheme = DarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF121214),
    onBackground = Color(0xFFF3F4F6),
    onSurface = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF2C2C32),
    outlineVariant = Color(0xFF202026)
)

val MidnightDarkColorScheme = DarkColorScheme.copy(
    background = Color(0xFF0A0A0C),
    surface = Color(0xFF121215),
    surfaceVariant = Color(0xFF1A1A1E),
    onBackground = Color(0xFFF3F4F6),
    onSurface = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF303038),
    outlineVariant = Color(0xFF222228)
)

val LocalAppearanceSettings = staticCompositionLocalOf {
    com.auralis.music.domain.model.AppearanceSettings()
}

fun isLightColor(color: Color): Boolean {
    val r = color.red
    val g = color.green
    val b = color.blue
    val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
    return luminance > 0.5f
}

fun ColorScheme.pureBlack(apply: Boolean): ColorScheme =
    if (apply) copy(
        surface = Color.Black,
        background = Color.Black
    ) else this

private fun paletteStyleFor(chroma: Double): PaletteStyle {
    return when {
        chroma < 4.0 -> PaletteStyle.Monochrome
        chroma < 12.0 -> PaletteStyle.Neutral
        else -> PaletteStyle.TonalSpot
    }
}

/**
 * Synthesizes a balanced, authentic Material 3 ColorScheme from an extracted artwork seed color.
 * Matches standard Material 3 architecture:
 * - Uses MaterialKolor's dynamicColorScheme with HCT tonal palette generation.
 * - Primary accent carries the genuine artwork hue and chroma (Tone 80 in dark mode).
 * - Secondary accent reflects extracted distinct secondary harmonic artwork tones.
 * - Tertiary accent provides harmonic balance without overpowering the UI.
 * - Neutral foundation: background and surface are strictly constrained to Chroma <= 4.0 (near-neutral dark slate/charcoal),
 *   preventing the entire UI from becoming saturated or flooded by bright artwork.
 * - Text (onBackground, onSurface) remains crisp, legible near-white without harsh color skews.
 * - Pure AMOLED black support when AMOLED mode is active.
 * NOTE: This function is CPU-intensive (HCT conversion + MaterialKolor tonal palette). Call it off
 * the composition thread (e.g. via produceState / Dispatchers.Default) to prevent frame jank.
 */
fun dynamicColorSchemeFromSeed(
    seedColor: Color,
    isDark: Boolean = true,
    isAmoled: Boolean = false,
    appTheme: String = "Dark Mode",
    secondaryColor: Color? = null,
    tertiaryColor: Color? = null,
    isMonochrome: Boolean = false
): ColorScheme {
    val effectiveSeed = if (seedColor == Color.Unspecified || seedColor == Color.Transparent) {
        if (isDark) Color(0xFF808080) else Color(0xFF404040)
    } else {
        seedColor
    }
    // Cache the single toHct() call — avoids calling it twice (once here and once in paletteStyleFor)
    val hctChroma = effectiveSeed.toHct().chroma
    val style = if (isMonochrome || hctChroma < 4.0) PaletteStyle.Monochrome else paletteStyleFor(hctChroma)
    val scheme = dynamicColorScheme(
        seedColor = effectiveSeed,
        isDark = isDark,
        isAmoled = isAmoled && isDark,
        secondary = secondaryColor?.takeIf { !isMonochrome && it != Color.Unspecified && it != Color.Transparent },
        tertiary = tertiaryColor?.takeIf { !isMonochrome && it != Color.Unspecified && it != Color.Transparent },
        style = style
    )
    if (!isDark) {
        // Enforce high-contrast light mode guarantees:
        // Text (onBackground, onSurface) must ALWAYS be crisp, legible dark charcoal/black (Tone <= 15, Chroma <= 2.5).
        // Background and surface must ALWAYS be light (Tone >= 95).
        val onBgHct = scheme.onBackground.toHct()
        val cleanOnBg = if (onBgHct.tone > 18.0 || onBgHct.chroma > 2.5) {
            Color(com.materialkolor.hct.Hct.from(onBgHct.hue, 1.5, minOf(onBgHct.tone, 10.0)).toInt())
        } else {
            scheme.onBackground
        }
        val onSurfHct = scheme.onSurface.toHct()
        val cleanOnSurf = if (onSurfHct.tone > 18.0 || onSurfHct.chroma > 2.5) {
            Color(com.materialkolor.hct.Hct.from(onSurfHct.hue, 1.5, minOf(onSurfHct.tone, 10.0)).toInt())
        } else {
            scheme.onSurface
        }
        val bgHct = scheme.background.toHct()
        val cleanBg = if (bgHct.tone < 92.0) {
            Color(com.materialkolor.hct.Hct.from(bgHct.hue, minOf(bgHct.chroma, 3.0), 98.0).toInt())
        } else {
            scheme.background
        }
        val surfHct = scheme.surface.toHct()
        val cleanSurf = if (surfHct.tone < 92.0) {
            Color(com.materialkolor.hct.Hct.from(surfHct.hue, minOf(surfHct.chroma, 3.0), 98.0).toInt())
        } else {
            scheme.surface
        }
        return scheme.copy(
            background = cleanBg,
            surface = cleanSurf,
            onBackground = cleanOnBg,
            onSurface = cleanOnSurf
        )
    }

    return when {
        isAmoled -> scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color(0xFF121214),
            outline = Color(0xFF2C2C32),
            outlineVariant = Color(0xFF202026),
            onBackground = Color(0xFFF3F4F6),
            onSurface = Color(0xFFF3F4F6),
            onSurfaceVariant = Color(0xFF9CA3AF)
        )
        appTheme == "Midnight Velvet Dark" -> scheme.copy(
            background = Color(0xFF0A0A0C),
            surface = Color(0xFF121215),
            surfaceVariant = Color(0xFF1A1A1E),
            outline = Color(0xFF303038),
            outlineVariant = Color(0xFF222228),
            onBackground = Color(0xFFF3F4F6),
            onSurface = Color(0xFFF3F4F6),
            onSurfaceVariant = Color(0xFF9CA3AF)
        )
        else -> {
            // Refined, non-aggressive dark foundation:
            // Ensure background and surface retain an elegant, subtle ambient tint from the artwork (chroma <= 4.0)
            // without becoming an overwhelming, aggressive color wash.
            // Ensure text (onBackground, onSurface) remains crisp, readable near-white without harsh color skews.
            val bgHct = scheme.background.toHct()
            val cappedBg = if (bgHct.chroma > 3.8) {
                Color(com.materialkolor.hct.Hct.from(bgHct.hue, 3.2, bgHct.tone).toInt())
            } else {
                scheme.background
            }
            val surfHct = scheme.surface.toHct()
            val cappedSurf = if (surfHct.chroma > 3.8) {
                Color(com.materialkolor.hct.Hct.from(surfHct.hue, 3.2, surfHct.tone).toInt())
            } else {
                scheme.surface
            }
            val onBgHct = scheme.onBackground.toHct()
            val cleanOnBg = if (onBgHct.chroma > 2.5) {
                Color(com.materialkolor.hct.Hct.from(onBgHct.hue, 1.5, onBgHct.tone).toInt())
            } else {
                scheme.onBackground
            }
            val onSurfHct = scheme.onSurface.toHct()
            val cleanOnSurf = if (onSurfHct.chroma > 2.5) {
                Color(com.materialkolor.hct.Hct.from(onSurfHct.hue, 1.5, onSurfHct.tone).toInt())
            } else {
                scheme.onSurface
            }
            scheme.copy(
                background = cappedBg,
                surface = cappedSurf,
                onBackground = cleanOnBg,
                onSurface = cleanOnSurf
            )
        }
    }
}

/**
 * Resolves the phone's system dynamic Monet ColorScheme synchronously on Android 12+ (API 31+).
 * Runs instantly on the composition thread (reading pre-loaded Android framework resources).
 * Applies Pure AMOLED black or Midnight Velvet dark mode overrides when configured.
 * Returns the fallback static scheme on pre-Android 12 devices.
 */
fun getSystemDynamicColorScheme(
    context: Context,
    isDark: Boolean,
    isAmoled: Boolean,
    appTheme: String
): ColorScheme {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamicSys = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (isDark) {
            when {
                isAmoled -> dynamicSys.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF121214),
                    outline = Color(0xFF2C2C32),
                    outlineVariant = Color(0xFF202026),
                    onBackground = Color(0xFFF3F4F6),
                    onSurface = Color(0xFFF3F4F6),
                    onSurfaceVariant = Color(0xFF9CA3AF)
                )
                appTheme == "Midnight Velvet Dark" -> dynamicSys.copy(
                    background = Color(0xFF0A0A0C),
                    surface = Color(0xFF121215),
                    surfaceVariant = Color(0xFF1A1A1E),
                    outline = Color(0xFF303038),
                    outlineVariant = Color(0xFF222228),
                    onBackground = Color(0xFFF3F4F6),
                    onSurface = Color(0xFFF3F4F6),
                    onSurfaceVariant = Color(0xFF9CA3AF)
                )
                else -> dynamicSys
            }
        } else {
            dynamicSys
        }
    } else {
        if (isDark) {
            when {
                isAmoled -> AmoledDarkColorScheme
                appTheme == "Midnight Velvet Dark" -> MidnightDarkColorScheme
                else -> DarkColorScheme
            }
        } else {
            LightColorScheme
        }
    }
}

private val curatedSchemeCache = ConcurrentHashMap<String, ColorScheme>()

/**
 * Resolves a curated static palette (e.g., Sky Blue, Crimson Amber) with in-memory caching.
 */
fun getCuratedColorScheme(
    paletteId: String,
    isDark: Boolean,
    isAmoled: Boolean,
    appTheme: String
): ColorScheme {
    val cacheKey = "$paletteId-$isDark-$isAmoled-$appTheme"
    curatedSchemeCache[cacheKey]?.let { return it }

    val palette = getPaletteById(paletteId)
    val seed = if (isDark) palette.primaryDark else palette.primaryLight
    val curatedDynamic = dynamicColorSchemeFromSeed(
        seedColor = seed,
        isDark = isDark,
        isAmoled = isAmoled,
        appTheme = appTheme
    )
    val scheme = if (isDark) {
        val onPri = if (isLightColor(palette.primaryDark)) Color(0xFF1C2000) else Color.White
        val onSec = if (isLightColor(palette.secondaryDark)) Color(0xFF1C2000) else Color.White
        curatedDynamic.copy(
            primary = palette.primaryDark,
            onPrimary = onPri,
            secondary = palette.secondaryDark,
            onSecondary = onSec,
            tertiary = palette.tertiaryDark,
            primaryContainer = palette.primaryDark.copy(alpha = 0.28f),
            onPrimaryContainer = palette.primaryDark,
            secondaryContainer = palette.secondaryDark.copy(alpha = 0.24f),
            onSecondaryContainer = palette.secondaryDark
        )
    } else {
        curatedDynamic.copy(
            primary = palette.primaryLight,
            onPrimary = Color.White,
            secondary = palette.secondaryLight,
            onSecondary = Color.White,
            tertiary = palette.tertiaryLight,
            primaryContainer = palette.primaryLight.copy(alpha = 0.16f),
            onPrimaryContainer = palette.primaryLight,
            secondaryContainer = palette.secondaryLight.copy(alpha = 0.14f),
            onSecondaryContainer = palette.secondaryLight
        )
    }
    curatedSchemeCache[cacheKey] = scheme
    return scheme
}

/**
 * Fast color linear interpolation helper for full Material 3 ColorScheme.
 */
fun lerpColorScheme(start: ColorScheme, stop: ColorScheme, fraction: Float): ColorScheme {
    if (fraction <= 0f) return start
    if (fraction >= 1f) return stop
    return ColorScheme(
        primary = lerp(start.primary, stop.primary, fraction),
        onPrimary = lerp(start.onPrimary, stop.onPrimary, fraction),
        primaryContainer = lerp(start.primaryContainer, stop.primaryContainer, fraction),
        onPrimaryContainer = lerp(start.onPrimaryContainer, stop.onPrimaryContainer, fraction),
        inversePrimary = lerp(start.inversePrimary, stop.inversePrimary, fraction),

        secondary = lerp(start.secondary, stop.secondary, fraction),
        onSecondary = lerp(start.onSecondary, stop.onSecondary, fraction),
        secondaryContainer = lerp(start.secondaryContainer, stop.secondaryContainer, fraction),
        onSecondaryContainer = lerp(start.onSecondaryContainer, stop.onSecondaryContainer, fraction),

        tertiary = lerp(start.tertiary, stop.tertiary, fraction),
        onTertiary = lerp(start.onTertiary, stop.onTertiary, fraction),
        tertiaryContainer = lerp(start.tertiaryContainer, stop.tertiaryContainer, fraction),
        onTertiaryContainer = lerp(start.onTertiaryContainer, stop.onTertiaryContainer, fraction),

        background = lerp(start.background, stop.background, fraction),
        onBackground = lerp(start.onBackground, stop.onBackground, fraction),
        surface = lerp(start.surface, stop.surface, fraction),
        onSurface = lerp(start.onSurface, stop.onSurface, fraction),
        surfaceVariant = lerp(start.surfaceVariant, stop.surfaceVariant, fraction),
        onSurfaceVariant = lerp(start.onSurfaceVariant, stop.onSurfaceVariant, fraction),
        surfaceTint = lerp(start.surfaceTint, stop.surfaceTint, fraction),
        inverseSurface = lerp(start.inverseSurface, stop.inverseSurface, fraction),
        inverseOnSurface = lerp(start.inverseOnSurface, stop.inverseOnSurface, fraction),

        error = stop.error,
        onError = stop.onError,
        errorContainer = stop.errorContainer,
        onErrorContainer = stop.onErrorContainer,

        outline = lerp(start.outline, stop.outline, fraction),
        outlineVariant = lerp(start.outlineVariant, stop.outlineVariant, fraction),
        scrim = stop.scrim,

        surfaceBright = lerp(start.surfaceBright, stop.surfaceBright, fraction),
        surfaceDim = lerp(start.surfaceDim, stop.surfaceDim, fraction),
        surfaceContainer = lerp(start.surfaceContainer, stop.surfaceContainer, fraction),
        surfaceContainerLow = lerp(start.surfaceContainerLow, stop.surfaceContainerLow, fraction),
        surfaceContainerLowest = lerp(start.surfaceContainerLowest, stop.surfaceContainerLowest, fraction),
        surfaceContainerHigh = lerp(start.surfaceContainerHigh, stop.surfaceContainerHigh, fraction),
        surfaceContainerHighest = lerp(start.surfaceContainerHighest, stop.surfaceContainerHighest, fraction),
    )
}

/**
 * Fast color linear interpolation helper for AuralisDynamicPalette.
 */
fun lerpDynamicPalette(start: AuralisDynamicPalette, stop: AuralisDynamicPalette, fraction: Float): AuralisDynamicPalette {
    if (fraction <= 0f) return start
    if (fraction >= 1f) return stop
    return AuralisDynamicPalette(
        tintA = lerp(start.tintA, stop.tintA, fraction),
        tintB = lerp(start.tintB, stop.tintB, fraction),
        tintC = lerp(start.tintC, stop.tintC, fraction),
        surfaceTint = lerp(start.surfaceTint, stop.surfaceTint, fraction),
        accentGlow = lerp(start.accentGlow, stop.accentGlow, fraction),
        isDark = stop.isDark
    )
}

/**
 * Smoothly interpolates an entire Material 3 ColorScheme during dynamic track changes using
 * a unified 650ms FastOutSlowIn transition that matches the main player's artwork transition.
 * Interpolates directly from the exact CURRENTLY VISIBLE color state to the new target,
 * guaranteeing seamless continuity without jump or restart during rapid A -> B -> C track changes.
 */
@Composable
fun animateColorScheme(targetColorScheme: ColorScheme): ColorScheme {
    var currentVisibleScheme by remember { mutableStateOf(targetColorScheme) }
    var previousTargetScheme by remember { mutableStateOf(targetColorScheme) }
    val animProgress = remember { Animatable(1f) }

    val isTargetDark = targetColorScheme.background.luminance() < 0.5f
    val isCurrentDark = currentVisibleScheme.background.luminance() < 0.5f

    LaunchedEffect(targetColorScheme) {
        if (targetColorScheme != previousTargetScheme) {
            val targetIsDark = targetColorScheme.background.luminance() < 0.5f
            val previousIsDark = previousTargetScheme.background.luminance() < 0.5f
            if (targetIsDark != previousIsDark) {
                // Instant snap on Light <-> Dark theme mode change!
                // Never interpolate across polar opposite light/dark modes (which causes mid-gray zero-contrast mud / inverted text).
                currentVisibleScheme = targetColorScheme
                previousTargetScheme = targetColorScheme
                animProgress.snapTo(1f)
            } else {
                currentVisibleScheme = lerpColorScheme(currentVisibleScheme, previousTargetScheme, animProgress.value)
                previousTargetScheme = targetColorScheme
                animProgress.snapTo(0f)
                animProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    return if (isTargetDark != isCurrentDark) {
        targetColorScheme
    } else {
        lerpColorScheme(currentVisibleScheme, previousTargetScheme, animProgress.value)
    }
}

/**
 * Smoothly interpolates AuralisDynamicPalette during dynamic track changes synchronously
 * with animateColorScheme over the identical 450ms FastOutSlowIn curve.
 */
@Composable
fun animateDynamicPalette(targetPalette: AuralisDynamicPalette): AuralisDynamicPalette {
    var currentVisiblePalette by remember { mutableStateOf(targetPalette) }
    var previousTargetPalette by remember { mutableStateOf(targetPalette) }
    val animProgress = remember { Animatable(1f) }

    LaunchedEffect(targetPalette) {
        if (targetPalette != previousTargetPalette) {
            if (targetPalette.isDark != previousTargetPalette.isDark) {
                // Instant snap on Light <-> Dark mode switch
                currentVisiblePalette = targetPalette
                previousTargetPalette = targetPalette
                animProgress.snapTo(1f)
            } else {
                currentVisiblePalette = lerpDynamicPalette(currentVisiblePalette, previousTargetPalette, animProgress.value)
                previousTargetPalette = targetPalette
                animProgress.snapTo(0f)
                animProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    return if (targetPalette.isDark != currentVisiblePalette.isDark) {
        targetPalette
    } else {
        lerpDynamicPalette(currentVisiblePalette, previousTargetPalette, animProgress.value)
    }
}

/**
 * Key data class used to invalidate [produceState] in AuralisTheme when the palette-relevant
 * inputs change, without triggering unnecessary recomputation for unrelated setting changes.
 */
private data class DynamicSchemeKey(
    val isDynamic: Boolean,
    val hasSongArtwork: Boolean,
    val seedColor: Color,
    val isMonochrome: Boolean,
    val isDark: Boolean,
    val isAmoled: Boolean,
    val appTheme: String,
    val colorPalette: String
)

// ============================================================================
// 🚀 AURALIS THEME PROVIDER
// ============================================================================

@Composable
fun AuralisTheme(
    appearanceSettings: com.auralis.music.domain.model.AppearanceSettings = com.auralis.music.domain.model.AppearanceSettings(),
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    artworkPalette: ArtworkPalette? = null,
    artworkDominantColor: Color? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemInDark = isSystemInDarkTheme()

    // 1. Resolve Light / Dark mode
    val isDark = when (appearanceSettings.appTheme) {
        "Light Mode", "Light" -> false
        "Pure AMOLED Black", "AMOLED", "Midnight Velvet Dark", "Dark Mode", "Dark" -> true
        "Dynamic Material You", "Follow system", "System" -> systemInDark
        else -> systemInDark
    }

    val isAmoled = appearanceSettings.appTheme == "Pure AMOLED Black" || appearanceSettings.appTheme == "AMOLED"

    // 2. Resolve Dynamic vs Static palette selection
    val isCuratedPalette = remember(appearanceSettings.colorPalette) {
        CuratedPalettes.any { it.id == appearanceSettings.colorPalette }
    }
    val isDynamic = !isCuratedPalette && (
            appearanceSettings.colorPalette == "Dynamic" ||
            appearanceSettings.colorPalette == "Dynamic (Material You)" ||
            appearanceSettings.dynamicTheme
    )

    // Base initial scheme strictly according to current mode and dynamic preference (resolved synchronously on Frame 0)
    val initialScheme = remember(isDark, isAmoled, appearanceSettings.appTheme, isDynamic, appearanceSettings.colorPalette, context) {
        if (isDynamic) {
            getSystemDynamicColorScheme(context, isDark, isAmoled, appearanceSettings.appTheme)
        } else {
            getCuratedColorScheme(appearanceSettings.colorPalette, isDark, isAmoled, appearanceSettings.appTheme)
        }
    }

    // Active artwork palette: preference to explicit parameter, otherwise observe shared cache flow
    val activeArtworkPalette = artworkPalette ?: ArtworkPaletteCache.currentPalette.collectAsState().value
    val hasSongArtwork = activeArtworkPalette != ArtworkPaletteCache.defaultPalette &&
            activeArtworkPalette.seedColor != Color.Unspecified

    // 3. Resolve target color scheme OFF THE COMPOSITION THREAD.
    //    dynamicColorSchemeFromSeed calls toHct() + MaterialKolor tonal palette — both are CPU-heavy
    //    and must NOT run on the main/composition thread to avoid frame drops during track transitions.
    val schemeKey = DynamicSchemeKey(
        isDynamic = isDynamic,
        hasSongArtwork = hasSongArtwork,
        seedColor = activeArtworkPalette.seedColor,
        isMonochrome = activeArtworkPalette.isMonochrome,
        isDark = isDark,
        isAmoled = isAmoled,
        appTheme = appearanceSettings.appTheme,
        colorPalette = appearanceSettings.colorPalette
    )
    var rawTargetScheme by remember(isDark, isAmoled, appearanceSettings.appTheme, isDynamic, appearanceSettings.colorPalette) {
        mutableStateOf(initialScheme)
    }

    LaunchedEffect(schemeKey, context) {
        val computed = if (isDynamic) {
            if (hasSongArtwork) {
                // Song artwork-driven dynamic palette off the composition thread
                withContext(Dispatchers.Default) {
                    dynamicColorSchemeFromSeed(
                        seedColor = activeArtworkPalette.seedColor,
                        isDark = isDark,
                        isAmoled = isAmoled,
                        appTheme = appearanceSettings.appTheme,
                        secondaryColor = activeArtworkPalette.secondary,
                        tertiaryColor = activeArtworkPalette.tertiary,
                        isMonochrome = activeArtworkPalette.isMonochrome
                    )
                }
            } else {
                getSystemDynamicColorScheme(context, isDark, isAmoled, appearanceSettings.appTheme)
            }
        } else {
            getCuratedColorScheme(appearanceSettings.colorPalette, isDark, isAmoled, appearanceSettings.appTheme)
        }
        rawTargetScheme = computed
    }

    // Honour the system "remove animations" accessibility preference app-wide
    val reducedMotion = rememberReducedMotion()

    // 4. Smooth theme transition: animate color changes across song transitions
    val colorScheme = if (reducedMotion) {
        rawTargetScheme
    } else {
        animateColorScheme(rawTargetScheme)
    }

    // Dynamic artwork ambient tints for LocalAuralisDynamicPalette
    val rawDynamicPalette = remember(activeArtworkPalette, isDark, artworkDominantColor, hasSongArtwork, rawTargetScheme) {
        if (!hasSongArtwork) {
            // When no active song artwork, ambient tints cleanly match the phone's system/target color scheme!
            val pri = rawTargetScheme.primary
            val sec = rawTargetScheme.secondary
            val tert = rawTargetScheme.tertiary
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(pri.toArgb(), hsv)
            val surfaceTint = if (isDark) Color.hsv(hsv[0], 0.08f, 0.11f) else Color.hsv(hsv[0], 0.04f, 0.94f)
            AuralisDynamicPalette(
                tintA = pri,
                tintB = sec,
                tintC = tert,
                surfaceTint = surfaceTint,
                accentGlow = pri.copy(alpha = if (isDark) 0.5f else 0.35f),
                isDark = isDark
            )
        } else {
            generateDynamicPalette(
                dominantColor = artworkDominantColor ?: activeArtworkPalette.seedColor,
                isDark = isDark,
                artworkPalette = activeArtworkPalette
            )
        }
    }

    val dynamicPalette = if (reducedMotion) {
        rawDynamicPalette
    } else {
        animateDynamicPalette(rawDynamicPalette)
    }


    // Calculate display density scaling (including responsive landscape scaling)
    val currentDensity = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val baseDensityScale = when {
        appearanceSettings.displayDensity.contains("85") || appearanceSettings.displayDensity.contains("Compact", ignoreCase = true) -> 0.85f
        appearanceSettings.displayDensity.contains("115") || appearanceSettings.displayDensity.contains("Large", ignoreCase = true) -> 1.15f
        else -> 1.0f
    }
    val landscapeScaleFactor = if (isLandscape && appearanceSettings.landscapeScaling) 0.88f else 1.0f
    val densityScale = baseDensityScale * landscapeScaleFactor

    val customDensity = remember(currentDensity.density, currentDensity.fontScale, densityScale) {
        androidx.compose.ui.unit.Density(
            density = currentDensity.density * densityScale,
            fontScale = currentDensity.fontScale * densityScale
        )
    }

    // Set transparent immersive status/navigation bars for edge-to-edge rendering
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.isAppearanceLightStatusBars = !isDark
            windowInsetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(
        LocalAppearanceSettings provides appearanceSettings,
        LocalAuralisDynamicPalette provides dynamicPalette,
        LocalDynamicThemePrimary provides colorScheme.primary,
        LocalDynamicThemeSecondary provides colorScheme.secondary,
        LocalDynamicThemeTertiary provides colorScheme.tertiary,
        LocalDynamicThemeBackground provides colorScheme.background,
        LocalDynamicThemeSurface provides colorScheme.surface,
        LocalDynamicThemeOnBackground provides colorScheme.onBackground,
        LocalDynamicThemeOnSurface provides colorScheme.onSurface,
        LocalReducedMotion provides reducedMotion,
        LocalContentColor provides colorScheme.onBackground,
        androidx.compose.ui.platform.LocalDensity provides customDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/**
 * Access the active dynamic artwork palette anywhere inside an AuralisTheme composable.
 */
val MaterialTheme.dynamicPalette: AuralisDynamicPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalAuralisDynamicPalette.current

val MaterialTheme.dynamicPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() {
        val dyn = LocalDynamicThemePrimary.current
        return if (dyn != Color.Unspecified) dyn else colorScheme.primary
    }

val MaterialTheme.dynamicSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() {
        val dyn = LocalDynamicThemeSecondary.current
        return if (dyn != Color.Unspecified) dyn else colorScheme.secondary
    }

val MaterialTheme.dynamicTertiary: Color
    @Composable
    @ReadOnlyComposable
    get() {
        val dyn = LocalDynamicThemeTertiary.current
        return if (dyn != Color.Unspecified) dyn else colorScheme.tertiary
    }

val MaterialTheme.dynamicBackground: Color
    @Composable
    @ReadOnlyComposable
    get() {
        val dyn = LocalDynamicThemeBackground.current
        return if (dyn != Color.Unspecified) dyn else colorScheme.background
    }

val MaterialTheme.dynamicSurface: Color
    @Composable
    @ReadOnlyComposable
    get() {
        val dyn = LocalDynamicThemeSurface.current
        return if (dyn != Color.Unspecified) dyn else colorScheme.surface
    }

val MaterialTheme.dynamicOnBackground: Color
    @Composable
    @ReadOnlyComposable
    get() {
        val dyn = LocalDynamicThemeOnBackground.current
        return if (dyn != Color.Unspecified) dyn else colorScheme.onBackground
    }

val MaterialTheme.dynamicOnSurface: Color
    @Composable
    @ReadOnlyComposable
    get() {
        val dyn = LocalDynamicThemeOnSurface.current
        return if (dyn != Color.Unspecified) dyn else colorScheme.onSurface
    }

val MaterialTheme.appearanceSettings: com.auralis.music.domain.model.AppearanceSettings
    @Composable
    @ReadOnlyComposable
    get() = LocalAppearanceSettings.current

