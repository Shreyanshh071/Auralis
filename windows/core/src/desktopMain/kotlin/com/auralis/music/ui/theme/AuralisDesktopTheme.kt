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
    // The phone's light/dark rule; "Follow system" follows Windows' app mode.
    val systemInDark = androidx.compose.foundation.isSystemInDarkTheme()
    val isDark = when (appearanceSettings.appTheme) {
        "Light Mode", "Light" -> false
        "Pure AMOLED Black", "AMOLED", "Midnight Velvet Dark", "Dark Mode", "Dark" -> true
        else -> systemInDark
    }
    val isAmoled = appearanceSettings.appTheme == "Pure AMOLED Black" || appearanceSettings.appTheme == "AMOLED"
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
        LocalDynamicThemePrimary provides colorScheme.primary,
        LocalDynamicThemeSecondary provides colorScheme.secondary,
        LocalDynamicThemeTertiary provides colorScheme.tertiary,
        LocalDynamicThemeBackground provides colorScheme.background,
        LocalDynamicThemeSurface provides colorScheme.surface,
        LocalDynamicThemeOnBackground provides colorScheme.onBackground,
        LocalDynamicThemeOnSurface provides colorScheme.onSurface,
        LocalReducedMotion provides reducedMotion,
        LocalArtworkRenderer provides DesktopArtworkRenderer,
        LocalBrandImages provides DesktopBrandImages.images,
        LocalCoverSheetBackground provides DesktopCoverSheetBackground,
        LocalPlatformActions provides platformActions,
        LocalContentColor provides colorScheme.onBackground
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = scriptSafeTypography(DesktopTypography, Locale.getDefault()),
            content = content
        )
    }
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
