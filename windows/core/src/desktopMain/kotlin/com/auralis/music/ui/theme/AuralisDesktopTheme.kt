package com.auralis.music.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.components.DesktopArtworkRenderer
import com.auralis.music.ui.components.DesktopBrandImages
import com.auralis.music.ui.components.LocalArtworkRenderer
import com.auralis.music.ui.components.LocalBrandImages
import com.auralis.music.ui.components.LocalCoverSheetBackground
import com.auralis.music.ui.components.LocalPlatformActions
import com.auralis.music.ui.components.PlatformActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The phone's AuralisTheme for desktop: the same colour schemes (the fixed Auralis scheme, a
 * curated palette, or one seeded from the playing song's artwork), the same dynamic palette and
 * locals, the phone's type scale with bundled fonts, and desktop's artwork, logos and actions.
 * Light, dark and Follow system as on the phone; Material You (wallpaper colours) doesn't exist on desktop.
 */
@Composable
fun AuralisDesktopTheme(
    appearanceSettings: AppearanceSettings = remember { AppearanceSettings() },
    artworkPalette: ArtworkPalette? = null,
    platformActions: PlatformActions,
    content: @Composable () -> Unit
) {
    // Windows has two modes, Light and Dark (older choices are read as Dark by the settings store).
    val isDark = appearanceSettings.appTheme != "Light Mode" && appearanceSettings.appTheme != "Light"
    val isAmoled = false
    val isWhite = appearanceSettings.colorPalette == "White"
    val isCurated = isWhite || CuratedPalettes.any { it.id == appearanceSettings.colorPalette }
    val hasArtwork = artworkPalette != null && artworkPalette != DefaultArtworkPalette && artworkPalette.seedColor != Color.Unspecified

    val baseScheme = remember(isDark, isAmoled, isCurated, appearanceSettings.colorPalette, appearanceSettings.appTheme) {
        when {
            isCurated && !isWhite -> getCuratedColorScheme(appearanceSettings.colorPalette, isDark, isAmoled, appearanceSettings.appTheme)
            else -> desktopWhiteColorScheme(isDark, isAmoled, appearanceSettings.appTheme)
        }
    }
    val initialScheme = remember(baseScheme, hasArtwork, artworkPalette, appearanceSettings.dynamicTheme) {
        if (hasArtwork && appearanceSettings.dynamicTheme && !isCurated && artworkPalette != null) {
            dynamicColorSchemeFromSeed(
                seedColor = artworkPalette.seedColor,
                isDark = isDark,
                isAmoled = isAmoled,
                appTheme = appearanceSettings.appTheme,
                secondaryColor = artworkPalette.secondary,
                tertiaryColor = artworkPalette.tertiary,
                isMonochrome = artworkPalette.isMonochrome
            )
        } else baseScheme
    }
    // Like the phone: an artwork scheme is computed off the UI thread and swapped in one step.
    var colorScheme by remember(baseScheme) { mutableStateOf(initialScheme) }
    LaunchedEffect(baseScheme, hasArtwork, artworkPalette, appearanceSettings.dynamicTheme) {
        colorScheme = if (hasArtwork && appearanceSettings.dynamicTheme && !isCurated && artworkPalette != null) {
            withContext(Dispatchers.Default) {
                dynamicColorSchemeFromSeed(
                    seedColor = artworkPalette.seedColor,
                    isDark = isDark,
                    isAmoled = isAmoled,
                    appTheme = appearanceSettings.appTheme,
                    secondaryColor = artworkPalette.secondary,
                    tertiaryColor = artworkPalette.tertiary,
                    isMonochrome = artworkPalette.isMonochrome
                )
            }
        } else baseScheme
    }

    // Switching Light and Dark blends every colour over 450ms instead of flipping in one frame.
    // Other changes (a new song's artwork colours) still swap at once, as before.
    val shownScheme = rememberModeCrossfade(colorScheme, isDark)

    val dynamicPalette = remember(artworkPalette, hasArtwork, colorScheme) {
        if (!hasArtwork || artworkPalette == null) {
            // The phone's no-artwork tints: from the scheme's own colours.
            val hsv = FloatArray(3)
            colorToHsv(colorScheme.primary.toArgb(), hsv)
            AuralisDynamicPalette(
                tintA = colorScheme.primary,
                tintB = colorScheme.secondary,
                tintC = colorScheme.tertiary,
                surfaceTint = Color.hsv(hsv[0], 0.08f, 0.11f),
                accentGlow = colorScheme.primary.copy(alpha = 0.5f),
                isDark = isDark
            )
        } else {
            generateDynamicPalette(dominantColor = artworkPalette.seedColor, isDark = isDark, artworkPalette = artworkPalette)
        }
    }

    val reducedMotion = rememberReducedMotion()
    CompositionLocalProvider(
        com.auralis.music.ui.components.LocalWideWindowLayout provides true,
        LocalAppearanceSettings provides appearanceSettings,
        LocalAuralisDynamicPalette provides dynamicPalette,
        LocalDynamicThemePrimary provides shownScheme.primary,
        LocalDynamicThemeSecondary provides shownScheme.secondary,
        LocalDynamicThemeTertiary provides shownScheme.tertiary,
        LocalDynamicThemeBackground provides shownScheme.background,
        LocalDynamicThemeSurface provides shownScheme.surface,
        LocalDynamicThemeOnBackground provides shownScheme.onBackground,
        LocalDynamicThemeOnSurface provides shownScheme.onSurface,
        LocalReducedMotion provides reducedMotion,
        LocalArtworkRenderer provides DesktopArtworkRenderer,
        LocalBrandImages provides DesktopBrandImages.images,
        LocalCoverSheetBackground provides DesktopCoverSheetBackground,
        LocalPlatformActions provides platformActions,
        LocalContentColor provides shownScheme.onBackground
    ) {
        MaterialTheme(
            colorScheme = shownScheme,
            typography = scriptSafeTypography(DesktopTypography, Locale.getDefault()),
            content = content
        )
    }
}

/**
 * [target], blended from the colours on screen when [isDark] flips (Light <-> Dark), so the whole
 * app fades between modes; any other change of [target] shows at once.
 */
@Composable
private fun rememberModeCrossfade(target: androidx.compose.material3.ColorScheme, isDark: Boolean): androidx.compose.material3.ColorScheme {
    val progress = remember { androidx.compose.animation.core.Animatable(1f) }
    var from by remember { mutableStateOf(target) }
    var shown by remember { mutableStateOf(target) }
    var modeDark by remember { mutableStateOf(isDark) }
    LaunchedEffect(isDark) {
        if (isDark == modeDark) return@LaunchedEffect
        modeDark = isDark
        from = shown
        progress.snapTo(0f)
        progress.animateTo(1f, androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val t = progress.value
    val result = if (t >= 1f) target else blendSchemes(from, target, t)
    androidx.compose.runtime.SideEffect { shown = result }
    return result
}

private fun blendSchemes(a: androidx.compose.material3.ColorScheme, b: androidx.compose.material3.ColorScheme, t: Float): androidx.compose.material3.ColorScheme {
    fun m(x: Color, y: Color) = androidx.compose.ui.graphics.lerp(x, y, t)
    return b.copy(
        primary = m(a.primary, b.primary), onPrimary = m(a.onPrimary, b.onPrimary),
        primaryContainer = m(a.primaryContainer, b.primaryContainer), onPrimaryContainer = m(a.onPrimaryContainer, b.onPrimaryContainer),
        inversePrimary = m(a.inversePrimary, b.inversePrimary),
        secondary = m(a.secondary, b.secondary), onSecondary = m(a.onSecondary, b.onSecondary),
        secondaryContainer = m(a.secondaryContainer, b.secondaryContainer), onSecondaryContainer = m(a.onSecondaryContainer, b.onSecondaryContainer),
        tertiary = m(a.tertiary, b.tertiary), onTertiary = m(a.onTertiary, b.onTertiary),
        tertiaryContainer = m(a.tertiaryContainer, b.tertiaryContainer), onTertiaryContainer = m(a.onTertiaryContainer, b.onTertiaryContainer),
        background = m(a.background, b.background), onBackground = m(a.onBackground, b.onBackground),
        surface = m(a.surface, b.surface), onSurface = m(a.onSurface, b.onSurface),
        surfaceVariant = m(a.surfaceVariant, b.surfaceVariant), onSurfaceVariant = m(a.onSurfaceVariant, b.onSurfaceVariant),
        surfaceTint = m(a.surfaceTint, b.surfaceTint), inverseSurface = m(a.inverseSurface, b.inverseSurface),
        inverseOnSurface = m(a.inverseOnSurface, b.inverseOnSurface),
        error = m(a.error, b.error), onError = m(a.onError, b.onError),
        errorContainer = m(a.errorContainer, b.errorContainer), onErrorContainer = m(a.onErrorContainer, b.onErrorContainer),
        outline = m(a.outline, b.outline), outlineVariant = m(a.outlineVariant, b.outlineVariant), scrim = m(a.scrim, b.scrim),
        surfaceBright = m(a.surfaceBright, b.surfaceBright), surfaceDim = m(a.surfaceDim, b.surfaceDim),
        surfaceContainer = m(a.surfaceContainer, b.surfaceContainer), surfaceContainerHigh = m(a.surfaceContainerHigh, b.surfaceContainerHigh),
        surfaceContainerHighest = m(a.surfaceContainerHighest, b.surfaceContainerHighest),
        surfaceContainerLow = m(a.surfaceContainerLow, b.surfaceContainerLow), surfaceContainerLowest = m(a.surfaceContainerLowest, b.surfaceContainerLowest),
    )
}

/** Neutral desktop defaults; the phone keeps its existing colour schemes. */
fun desktopWhiteColorScheme(isDark: Boolean, isAmoled: Boolean, appTheme: String): androidx.compose.material3.ColorScheme {
    val base = when {
        !isDark -> LightColorScheme
        isAmoled -> AmoledDarkColorScheme
        appTheme == "Midnight Velvet Dark" -> MidnightDarkColorScheme
        else -> DarkColorScheme
    }
    val accent = if (isDark) Color.White else Color(0xFF242424)
    val onAccent = if (isDark) Color(0xFF171717) else Color.White
    val container = if (isDark) Color(0xFF383838) else Color(0xFFE4E4E4)
    return base.copy(primary = accent, onPrimary = onAccent, primaryContainer = container, onPrimaryContainer = accent,
        secondary = accent, onSecondary = onAccent, secondaryContainer = container, onSecondaryContainer = accent,
        tertiary = accent, onTertiary = onAccent, tertiaryContainer = container, onTertiaryContainer = accent,
        inversePrimary = if (isDark) Color(0xFF242424) else Color.White, surfaceTint = accent)
}

/** Behind option sheets: the cover, blurred and darkened (the phone draws its player colour field). */
private val DesktopCoverSheetBackground: @Composable (String?) -> Unit = { artworkUrl ->
    DesktopCoverField(artworkUrl)
}
