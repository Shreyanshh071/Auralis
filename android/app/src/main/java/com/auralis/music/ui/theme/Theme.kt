package com.auralis.music.ui.theme

import android.app.Activity
import android.os.Build
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
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
// 🎨 CURATED COLOR PALETTES (METROLIST & MATERIAL YOU SPEC)
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

// ============================================================================
// 🚀 AURALIS THEME PROVIDER
// ============================================================================

@Composable
fun AuralisTheme(
    appearanceSettings: com.auralis.music.domain.model.AppearanceSettings = com.auralis.music.domain.model.AppearanceSettings(),
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    artworkDominantColor: Color? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemInDark = isSystemInDarkTheme()

    val isDark = when (appearanceSettings.appTheme) {
        "Light Mode" -> false
        "Pure AMOLED Black", "Midnight Velvet Dark", "Dark Mode" -> true
        "Dynamic Material You", "Follow system" -> systemInDark
        else -> systemInDark
    }

    val isDynamicMonet = appearanceSettings.colorPalette == "Dynamic (Material You)"

    // Base scheme strictly according to light / dark state
    val baseScheme = if (isDark) {
        when (appearanceSettings.appTheme) {
            "Pure AMOLED Black" -> AmoledDarkColorScheme
            "Midnight Velvet Dark" -> MidnightDarkColorScheme
            else -> DarkColorScheme
        }
    } else {
        LightColorScheme
    }

    val colorScheme: ColorScheme = if (isDynamicMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamicSys = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (isDark) {
            when (appearanceSettings.appTheme) {
                "Pure AMOLED Black" -> dynamicSys.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF121214),
                    outline = Color(0xFF2C2C32),
                    outlineVariant = Color(0xFF202026),
                    onBackground = Color(0xFFF3F4F6),
                    onSurface = Color(0xFFF3F4F6),
                    onSurfaceVariant = Color(0xFF9CA3AF)
                )
                "Midnight Velvet Dark" -> dynamicSys.copy(
                    background = Color(0xFF0A0A0C),
                    surface = Color(0xFF121215),
                    surfaceVariant = Color(0xFF1A1A1E),
                    outline = Color(0xFF303038),
                    outlineVariant = Color(0xFF222228),
                    onBackground = Color(0xFFF3F4F6),
                    onSurface = Color(0xFFF3F4F6),
                    onSurfaceVariant = Color(0xFF9CA3AF)
                )
                else -> dynamicSys.copy(
                    background = Color(0xFF0E0E10),
                    surface = Color(0xFF161619),
                    surfaceVariant = Color(0xFF202024),
                    outline = Color(0xFF383840),
                    outlineVariant = Color(0xFF28282E),
                    onBackground = Color(0xFFF3F4F6),
                    onSurface = Color(0xFFF3F4F6),
                    onSurfaceVariant = Color(0xFF9CA3AF)
                )
            }
        } else {
            dynamicSys.copy(
                background = Color(0xFFF9FAFB),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFF3F4F6),
                outlineVariant = Color(0xFFE5E7EB),
                onBackground = Color(0xFF111827),
                onSurface = Color(0xFF111827),
                onSurfaceVariant = Color(0xFF4B5563)
            )
        }
    } else {
        val palette = getPaletteById(appearanceSettings.colorPalette)
        if (isDark) {
            baseScheme.copy(
                primary = palette.primaryDark,
                onPrimary = Color(0xFF1C2000),
                secondary = palette.secondaryDark,
                onSecondary = Color(0xFF2E331B),
                tertiary = palette.tertiaryDark,
                primaryContainer = palette.primaryDark.copy(alpha = 0.28f),
                onPrimaryContainer = palette.primaryDark,
                secondaryContainer = palette.secondaryDark.copy(alpha = 0.24f),
                onSecondaryContainer = palette.secondaryDark
            )
        } else {
            baseScheme.copy(
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
    }

    // Compute dynamic artwork ambient tints
    val dynamicPalette = generateDynamicPalette(artworkDominantColor, isDark = isDark)

    // Honour the system "remove animations" accessibility preference app-wide
    val reducedMotion = rememberReducedMotion()

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
            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.isAppearanceLightStatusBars = !isDark
            windowInsetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(
        LocalAppearanceSettings provides appearanceSettings,
        LocalAuralisDynamicPalette provides dynamicPalette,
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

val MaterialTheme.appearanceSettings: com.auralis.music.domain.model.AppearanceSettings
    @Composable
    @ReadOnlyComposable
    get() = LocalAppearanceSettings.current

