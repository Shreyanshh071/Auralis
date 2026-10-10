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
 * Desktop is dark only for now; Material You (wallpaper colours) doesn't exist on desktop.
 */
@Composable
fun AuralisDesktopTheme(
    appearanceSettings: AppearanceSettings = remember { AppearanceSettings() },
    artworkPalette: ArtworkPalette? = null,
    platformActions: PlatformActions,
    content: @Composable () -> Unit
) {
    val isDark = true
    val isAmoled = appearanceSettings.appTheme == "Pure AMOLED Black" || appearanceSettings.appTheme == "AMOLED"
    val isCurated = CuratedPalettes.any { it.id == appearanceSettings.colorPalette }
    val hasArtwork = artworkPalette != null && artworkPalette != DefaultArtworkPalette && artworkPalette.seedColor != Color.Unspecified

    val baseScheme = remember(isAmoled, isCurated, appearanceSettings.colorPalette, appearanceSettings.appTheme) {
        when {
            isCurated -> getCuratedColorScheme(appearanceSettings.colorPalette, isDark, isAmoled, appearanceSettings.appTheme)
            isAmoled -> AmoledDarkColorScheme
            appearanceSettings.appTheme == "Midnight Velvet Dark" -> MidnightDarkColorScheme
            else -> DarkColorScheme
        }
    }
    // Like the phone: an artwork scheme is computed off the UI thread and swapped in one step.
    var colorScheme by remember(baseScheme) { mutableStateOf(baseScheme) }
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

/** Behind option sheets: the cover, blurred and darkened (the phone draws its player colour field). */
private val DesktopCoverSheetBackground: @Composable (String?) -> Unit = { artworkUrl ->
    DesktopCoverField(artworkUrl)
}
