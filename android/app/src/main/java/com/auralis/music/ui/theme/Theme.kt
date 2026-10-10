package com.auralis.music.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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

    // 4. The theme switches to the new song's colours in one step. Fading it (450ms) changed
    //    MaterialTheme.colorScheme on every frame, which recomposed nearly the whole app (every
    //    queue row, header, control and the dock) for the length of the fade: measured on device
    //    as ~650ms of 25-60ms frames on each song change, which is what made the queue's
    //    slide-up and the song change itself stutter. The player background still blends,
    //    in the draw phase only.
    val colorScheme = rawTargetScheme

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

    // Same as the scheme above: one step, not a per-frame app-wide recomposition.
    val dynamicPalette = rawDynamicPalette


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

    val appearanceStoreContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val appearanceStore = androidx.compose.runtime.remember {
        com.auralis.music.data.datastore.AppearanceSettingsDataStore(appearanceStoreContext)
    }
    val settingsActivityContext = androidx.compose.ui.platform.LocalContext.current
    val settingsStores = androidx.compose.runtime.remember { com.auralis.music.data.datastore.PhoneSettingsStores(appearanceStoreContext) }
    val settingsPlatform = androidx.compose.runtime.remember(settingsActivityContext) {
        com.auralis.music.ui.settings.PhoneSettingsPlatform(settingsActivityContext)
    }
    CompositionLocalProvider(
        com.auralis.music.data.datastore.LocalSettingsStores provides settingsStores,
        com.auralis.music.ui.settings.LocalSettingsPlatform provides settingsPlatform,
        com.auralis.music.ui.screens.LocalWebSignIn provides com.auralis.music.ui.screens.PhoneWebSignIn,
        LocalAppearanceSettingsStore provides appearanceStore,
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
        com.auralis.music.ui.components.LocalArtworkRenderer provides com.auralis.music.ui.components.PhoneArtworkRenderer,
        com.auralis.music.ui.components.LocalBrandImages provides PhoneBrandImages,
        com.auralis.music.ui.components.LocalCoverSheetBackground provides PhoneCoverSheetBackground,
        com.auralis.music.ui.components.LocalPlatformActions provides remember(context) { com.auralis.music.ui.components.PhonePlatformActions(context) },
        LocalContentColor provides colorScheme.onBackground,
        androidx.compose.ui.platform.LocalDensity provides customDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = scriptSafeTypography(Typography, androidx.compose.ui.platform.LocalConfiguration.current.locales[0]),
            content = content
        )
    }
}

/** The logos shared UI draws, from this app's drawables. */
private val PhoneBrandImages = com.auralis.music.ui.components.BrandImages(
    headerLogo = { androidx.compose.ui.res.painterResource(com.auralis.music.R.drawable.ic_auralis_header_logo) },
    appLogo = { androidx.compose.ui.res.painterResource(com.auralis.music.R.drawable.ic_auralis_logo) },
    github = { androidx.compose.ui.res.painterResource(com.auralis.music.R.drawable.ic_github) },
    discord = { androidx.compose.ui.res.painterResource(com.auralis.music.R.drawable.ic_discord) }
)

/** Behind option sheets: the immersive player's colour field from the item's cover. */
private val PhoneCoverSheetBackground: @Composable (String?) -> Unit = { artworkUrl ->
    com.auralis.music.ui.player.ImmersiveColorField(artworkUrl = artworkUrl, seamPx = { 0f })
}
