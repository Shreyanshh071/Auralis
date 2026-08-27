package com.auralis.music.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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

val LocalAuralisDynamicPalette = staticCompositionLocalOf {
    AuralisDynamicPalette()
}

/**
 * Computes an atmospheric, harmonious color palette from an extracted dominant artwork color.
 */
fun generateDynamicPalette(dominantColor: Color?, isDark: Boolean = true): AuralisDynamicPalette {
    if (dominantColor == null || dominantColor == Color.Unspecified) {
        return AuralisDynamicPalette(isDark = isDark)
    }

    // Extract HSL from dominant color for harmonic gradient generation
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(dominantColor.toArgb(), hsv)
    val hue = hsv[0]
    val sat = hsv[1]

    // If dominant color is grayscale / monochrome (sat < 0.08f), render sleek slate/charcoal palette
    if (sat < 0.08f) {
        val slatePrimary = Color(0xFF2E323A)
        val slateSecondary = Color(0xFF1F2228)
        val slateTertiary = Color(0xFF14161B)
        return AuralisDynamicPalette(
            tintA = slatePrimary,
            tintB = slateSecondary,
            tintC = slateTertiary,
            surfaceTint = Color(0xFF101216),
            accentGlow = slatePrimary.copy(alpha = 0.4f),
            isDark = isDark
        )
    }

    val boostedSat = sat.coerceIn(0.45f, 0.95f)
    val value = if (isDark) hsv[2].coerceIn(0.6f, 0.95f) else hsv[2].coerceIn(0.4f, 0.8f)

    // Primary Tint (Hue shifted slightly + boosted saturation)
    val tintAColor = Color.hsv(hue, boostedSat, value)

    // Secondary Tint (Analogous color + 35 degrees shift)
    val secondaryHue = (hue + 35f) % 360f
    val tintBColor = Color.hsv(secondaryHue, (boostedSat * 0.85f).coerceAtLeast(0.4f), value)

    // Tertiary Tint (Complementary-adjacent + 140 degrees shift)
    val tertiaryHue = (hue + 140f) % 360f
    val tintCColor = Color.hsv(tertiaryHue, (boostedSat * 0.75f).coerceAtLeast(0.35f), value)

    // Subtle dark surface tint
    val surfaceTint = Color.hsv(hue, 0.22f, 0.12f)

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

val DarkColorScheme = darkColorScheme(
    primary = AuralisPrimary,
    onPrimary = AuralisOnPrimary,
    primaryContainer = AuralisPrimaryContainer,
    onPrimaryContainer = AuralisOnPrimaryContainer,
    secondary = AuralisSecondary,
    onSecondary = AuralisOnSecondary,
    secondaryContainer = AuralisSecondaryContainer,
    onSecondaryContainer = AuralisOnSecondaryContainer,
    tertiary = AuralisTertiary,
    onTertiary = AuralisOnTertiary,
    tertiaryContainer = AuralisTertiaryContainer,
    onTertiaryContainer = AuralisOnTertiaryContainer,
    error = AuralisError,
    onError = AuralisOnError,
    errorContainer = AuralisErrorContainer,
    onErrorContainer = AuralisOnErrorContainer,
    background = AuralisBackground,
    onBackground = AuralisOnBackground,
    surface = AuralisSurface,
    onSurface = AuralisOnSurface,
    surfaceVariant = AuralisSurfaceVariant,
    onSurfaceVariant = AuralisOnSurfaceVariant,
    outline = AuralisOutline,
    outlineVariant = AuralisOutlineVariant
)

val LightColorScheme = lightColorScheme(
    primary = AuralisPrimary,
    onPrimary = AuralisOnPrimary,
    primaryContainer = AuralisPrimaryContainer,
    onPrimaryContainer = AuralisOnPrimaryContainer,
    secondary = AuralisSecondary,
    onSecondary = AuralisOnSecondary,
    secondaryContainer = AuralisSecondaryContainer,
    onSecondaryContainer = AuralisOnSecondaryContainer,
    tertiary = AuralisTertiary,
    onTertiary = AuralisOnTertiary,
    background = AuralisBackgroundLight,
    onBackground = AuralisOnBackgroundLight,
    surface = AuralisSurfaceLight,
    onSurface = AuralisOnSurfaceLight,
    surfaceVariant = AuralisSurfaceElevatedLight,
    onSurfaceVariant = AuralisOnSurfaceVariantLight,
    outline = AuralisOutlineLight,
    outlineVariant = AuralisOutlineVariantLight
)

// ============================================================================
// 🚀 AURALIS THEME PROVIDER
// ============================================================================

@Composable
fun AuralisTheme(
    darkTheme: Boolean = true, // Default to rich immersive dark mode
    dynamicColor: Boolean = false, // Set to true to enable Android 12+ wallpaper Monet palette
    artworkDominantColor: Color? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Compute dynamic artwork ambient tints
    val dynamicPalette = generateDynamicPalette(artworkDominantColor, isDark = darkTheme)

    // Honour the system "remove animations" accessibility preference app-wide
    val reducedMotion = rememberReducedMotion()

    // Set transparent immersive status/navigation bars for edge-to-edge rendering
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.isAppearanceLightStatusBars = !darkTheme
            windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalAuralisDynamicPalette provides dynamicPalette,
        LocalReducedMotion provides reducedMotion
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
