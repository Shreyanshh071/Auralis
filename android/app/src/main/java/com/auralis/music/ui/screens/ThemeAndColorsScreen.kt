package com.auralis.music.ui.screens

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.components.bottomChromePadding
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.ArtworkPaletteCache
import com.auralis.music.ui.theme.CuratedPalette
import com.auralis.music.ui.theme.CuratedPalettes
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicColorSchemeFromSeed
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import com.auralis.music.ui.theme.getPaletteById
import com.auralis.music.ui.theme.isLightColor

/** The preview's colours plus whether they describe a dark theme. */
internal data class ThemePreview(val scheme: ColorScheme, val isDark: Boolean, val isAmoled: Boolean, val isDynamic: Boolean)

/** The colour scheme [settings] produce, for previews (the app theme itself applies them globally). */
@Composable
internal fun rememberThemePreview(settings: AppearanceSettings): ThemePreview {
    val context = LocalContext.current
    val systemInDark = isSystemInDarkTheme()
    val currentPalette = remember(settings.colorPalette) {
        getPaletteById(settings.colorPalette)
    }

    val isDark = when (settings.appTheme) {
        "Light Mode", "Light" -> false
        "Pure AMOLED Black", "AMOLED", "Midnight Velvet Dark", "Dark Mode", "Dark" -> true
        else -> systemInDark
    }
    val isAmoled = settings.appTheme == "Pure AMOLED Black" || settings.appTheme == "AMOLED"

    val isDynamic = settings.colorPalette == "Dynamic" ||
            settings.colorPalette == "Dynamic (Material You)" ||
            (settings.dynamicTheme && CuratedPalettes.none { it.id == settings.colorPalette })

    // Observe shared extracted artwork palette
    val sharedArtworkPalette by ArtworkPaletteCache.currentPalette.collectAsState()
    val hasSongArtwork = sharedArtworkPalette != ArtworkPaletteCache.defaultPalette

    val dynamicSysScheme = remember(context, isDark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null
    }

    // 1. Determine active seed color from the selected palette or dynamic source
    // The top color of the swatch circle is the primary seed color that dictates the background & UI scheme!
    val activeSeedColor = if (isDynamic) {
        if (hasSongArtwork) {
            sharedArtworkPalette.seedColor.takeIf { it != Color.Unspecified } ?: sharedArtworkPalette.primary
        } else {
            dynamicSysScheme?.primary ?: Color(0xFF6750A4)
        }
    } else {
        if (isDark) currentPalette.primaryDark else currentPalette.primaryLight
    }

    val activeSecondaryColor = if (isDynamic) {
        if (hasSongArtwork) sharedArtworkPalette.secondary else dynamicSysScheme?.secondary
    } else {
        if (isDark) currentPalette.secondaryDark else currentPalette.secondaryLight
    }

    val activeTertiaryColor = if (isDynamic) {
        if (hasSongArtwork) sharedArtworkPalette.tertiary else dynamicSysScheme?.tertiary
    } else {
        if (isDark) currentPalette.tertiaryDark else currentPalette.tertiaryLight
    }

    // Coherent Material 3 scheme dynamically generated from the active palette's top color
    val activePreviewScheme = remember(
        activeSeedColor, activeSecondaryColor, activeTertiaryColor,
        isDark, isAmoled, settings.appTheme, isDynamic, hasSongArtwork, sharedArtworkPalette, currentPalette
    ) {
        if (isDynamic && !hasSongArtwork && dynamicSysScheme != null) {
            if (isAmoled) {
                dynamicSysScheme.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF121214)
                )
            } else if (settings.appTheme == "Midnight Velvet Dark") {
                dynamicSysScheme.copy(
                    background = Color(0xFF0A0A0C),
                    surface = Color(0xFF121215)
                )
            } else {
                dynamicSysScheme
            }
        } else {
            val scheme = dynamicColorSchemeFromSeed(
                seedColor = activeSeedColor,
                isDark = isDark,
                isAmoled = isAmoled,
                appTheme = settings.appTheme,
                secondaryColor = activeSecondaryColor,
                tertiaryColor = activeTertiaryColor,
                isMonochrome = if (isDynamic && hasSongArtwork) sharedArtworkPalette.isMonochrome else false
            )

            if (!isDynamic) {
                if (isDark) {
                    val onPri = if (isLightColor(currentPalette.primaryDark)) Color(0xFF1C2000) else Color.White
                    val onSec = if (isLightColor(currentPalette.secondaryDark)) Color(0xFF1C2000) else Color.White
                    scheme.copy(
                        primary = currentPalette.primaryDark,
                        onPrimary = onPri,
                        secondary = currentPalette.secondaryDark,
                        onSecondary = onSec,
                        tertiary = currentPalette.tertiaryDark,
                        primaryContainer = currentPalette.primaryDark.copy(alpha = 0.28f),
                        onPrimaryContainer = currentPalette.primaryDark,
                        secondaryContainer = currentPalette.secondaryDark.copy(alpha = 0.24f),
                        onSecondaryContainer = currentPalette.secondaryDark
                    )
                } else {
                    scheme.copy(
                        primary = currentPalette.primaryLight,
                        onPrimary = Color.White,
                        secondary = currentPalette.secondaryLight,
                        onSecondary = Color.White,
                        tertiary = currentPalette.tertiaryLight,
                        primaryContainer = currentPalette.primaryLight.copy(alpha = 0.16f),
                        onPrimaryContainer = currentPalette.primaryLight,
                        secondaryContainer = currentPalette.secondaryLight.copy(alpha = 0.14f),
                        onSecondaryContainer = currentPalette.secondaryLight
                    )
                }
            } else {
                scheme
            }
        }
    }

    return ThemePreview(activePreviewScheme, isDark, isAmoled, isDynamic)
}

/**
 * Theme & colours, laid out after Vivi Music's theme page: a live preview card on top, then one
 * grouped list of Theme mode, Colour palette, Pure black, Dynamic theme and Liquid glass.
 */
@Composable
fun ThemeAndColorsScreen(
    settings: AppearanceSettings,
    onUpdateSettings: (AppearanceSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    BackHandler(enabled = true) {
        onBack()
    }

    val preview = rememberThemePreview(settings)
    val isDark = preview.isDark
    val isAmoled = preview.isAmoled
    val isDynamic = preview.isDynamic
    val accent = preview.scheme.primary

    val sharedArtworkPalette by ArtworkPaletteCache.currentPalette.collectAsState()
    val hasSongArtwork = sharedArtworkPalette != ArtworkPaletteCache.defaultPalette
    val dynamicSysScheme = remember(context, isDark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null
    }

    val backgroundColor = MaterialTheme.dynamicBackground
    val onBackground = MaterialTheme.dynamicOnBackground

    val rootView = androidx.compose.ui.platform.LocalView.current
    // The change rebuilds the page, so the switch slides first and the crossfade starts after it.
    // App style: 0 Solid, 1 Glass, 2 Frost. The toggle moves first and the crossfade follows.
    val savedStyle = if (!settings.liquidGlass) 0 else if (settings.frostGlass) 2 else 1
    var shownStyle by androidx.compose.runtime.remember(savedStyle) {
        androidx.compose.runtime.mutableStateOf(savedStyle)
    }
    val glassScope = androidx.compose.runtime.rememberCoroutineScope()
    val pendingGlassChange = androidx.compose.runtime.remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val setAppStyle = { style: Int ->
        shownStyle = style
        // Changing the style rebuilds the whole app, so rapid taps wait for the last one.
        pendingGlassChange[0]?.cancel()
        pendingGlassChange[0] = glassScope.launch {
            kotlinx.coroutines.delay(GlassSwitchSettleMillis)
            if (style != savedStyle) {
                com.auralis.music.ui.theme.ThemeCrossfade.run(rootView) {
                    onUpdateSettings(settings.copy(liquidGlass = style != 0, frostGlass = style == 2))
                }
            }
        }
        Unit
    }

    val darkModeSelected = settings.appTheme in setOf("Dark Mode", "Midnight Velvet Dark", "Pure AMOLED Black", "AMOLED", "Dark")
    val lightModeSelected = settings.appTheme in setOf("Light Mode", "Light")
    val themeModes = listOf(
        Triple(str(R.string.system), !darkModeSelected && !lightModeSelected, "Follow system"),
        Triple(str(R.string.light), lightModeSelected, "Light Mode"),
        Triple(str(R.string.dark), darkModeSelected, "Dark Mode"),
    )

    com.auralis.music.ui.glass.OptionalGlassPage(
        background = backgroundColor,
        accent = MaterialTheme.dynamicPrimary,
        modifier = modifier.fillMaxSize().background(backgroundColor),
        glowWithoutGlass = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            com.auralis.music.ui.glass.PageHeading(
                title = str(R.string.theme_colors),
                onBack = onBack,
                subtitle = str(R.string.customize_player_interface_palette),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            ThemePreviewCard(preview, Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(160.dp))

            Spacer(modifier = Modifier.height(32.dp))

            // Glass rows as separate cards bend each other's edges across the 3dp seams, so with
            // glass on the whole group is one card with hairline dividers instead.
            val groupedGlass = com.auralis.music.ui.glass.pageGlassEnabled()
            ThemeGroup(groupedGlass, onBackground, Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                // Theme mode
                ThemeGroupItem(0, 5, Icons.Rounded.BrightnessMedium, str(R.string.theme_mode), onBackground) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        themeModes.forEachIndexed { index, (label, selected, key) ->
                            ConnectedToggle(label, selected, index, themeModes.lastIndex, accent, onBackground, Modifier.weight(1f)) {
                                if (!selected) com.auralis.music.ui.theme.ThemeCrossfade.run(rootView) {
                                    onUpdateSettings(settings.copy(appTheme = key))
                                }
                            }
                        }
                    }
                }

                // Colour palette
                ThemeGroupItem(1, 5, Icons.Rounded.Palette, str(R.string.color_palette), onBackground) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val dynamicTop = if (hasSongArtwork) sharedArtworkPalette.primary else (dynamicSysScheme?.primary ?: Color(0xFF6750A4))
                        val dynamicLeft = if (hasSongArtwork) sharedArtworkPalette.secondary else (dynamicSysScheme?.secondary ?: Color(0xFFD0BCFF))
                        val dynamicRight = if (hasSongArtwork) sharedArtworkPalette.tertiary else (dynamicSysScheme?.tertiary ?: Color(0xFFCCC2DC))
                        PaletteTile(str(R.string.dynamic), isDynamic, accent, onBackground, onClick = {
                            onUpdateSettings(settings.copy(colorPalette = "Dynamic", dynamicTheme = true))
                        }) { TripleTonePaletteCircle(dynamicTop, dynamicLeft, dynamicRight, Modifier.size(44.dp)) }
                        CuratedPalettes.forEach { palette ->
                            PaletteTile(com.auralis.music.ui.i18n.UiLabels.of(palette.name), !isDynamic && settings.colorPalette == palette.id,
                                if (isDark) palette.primaryDark else palette.primaryLight, onBackground, onClick = {
                                    onUpdateSettings(settings.copy(colorPalette = palette.id, dynamicTheme = false))
                                }) {
                                TripleTonePaletteCircle(
                                    topColor = if (isDark) palette.primaryDark else palette.primaryLight,
                                    bottomLeftColor = if (isDark) palette.secondaryDark else palette.secondaryLight,
                                    bottomRightColor = if (isDark) palette.tertiaryDark else palette.tertiaryLight,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }
                    }
                }

                // Pure black is a dark-theme variant, so turning it on also picks dark.
                ThemeSwitchItem(2, 5, Icons.Rounded.Contrast, str(R.string.pure_black_mode), str(R.string.pure_black_mode_summary),
                    checked = isAmoled, enabled = true, accent = accent, onBackground = onBackground) { on ->
                    com.auralis.music.ui.theme.ThemeCrossfade.run(rootView) {
                        onUpdateSettings(settings.copy(appTheme = if (on) "Pure AMOLED Black" else "Dark Mode"))
                    }
                }

                // Dynamic theme
                ThemeSwitchItem(3, 5, Icons.Rounded.AutoAwesome, str(R.string.enable_dynamic_theme), str(R.string.enable_dynamic_theme_summary),
                    checked = isDynamic, enabled = true, accent = accent, onBackground = onBackground) { on ->
                    onUpdateSettings(if (on) settings.copy(colorPalette = "Dynamic", dynamicTheme = true)
                        else settings.copy(colorPalette = CuratedPalettes.first().id, dynamicTheme = false))
                }

                // App style: Solid, Glass or Frost, on light and dark themes alike
                ThemeGroupItem(4, 5, Icons.Rounded.BlurOn, str(R.string.app_style), onBackground) {
                    val styles = listOf(str(R.string.solid), str(R.string.app_style_glass), str(R.string.app_style_frost))
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        styles.forEachIndexed { index, label ->
                            ConnectedToggle(label, shownStyle == index, index, styles.lastIndex, accent, onBackground, Modifier.weight(1f)) {
                                if (shownStyle != index) setAppStyle(index)
                            }
                        }
                    }
                    Text(
                        when {
                            shownStyle == 0 -> str(R.string.app_style_solid_summary)
                            !com.auralis.music.ui.glass.isLiquidGlassSupported() -> str(R.string.glass_dock_and_mini_player_live_blur_nee)
                            shownStyle == 2 -> str(R.string.app_style_frost_summary)
                            else -> str(R.string.glass_dock_and_mini_player_scrolling_dow)
                        },
                        style = MaterialTheme.typography.bodySmall, color = onBackground.copy(alpha = .6f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.padding(bottomChromePadding(gap = 24.dp)))
        }
    }
}

private const val GlassSwitchSettleMillis = 320L

/**
 * A small app mockup in the chosen theme: a title pill and action dot over primary, secondary,
 * tertiary and container swatches. Light and dark swap with a short colour animation.
 */
@Composable
internal fun ThemePreviewCard(preview: ThemePreview, modifier: Modifier = Modifier) {
    val scheme = preview.scheme
    val spec = androidx.compose.animation.core.tween<Color>(320)
    val surface by animateColorAsState(if (preview.isAmoled) Color.Black else scheme.surface, spec, label = "preview surface")
    val bar by animateColorAsState(if (preview.isDark) Color.White.copy(alpha = .08f) else Color.Black.copy(alpha = .06f), spec, label = "preview bar")
    val outline by animateColorAsState(if (preview.isDark) Color.White.copy(alpha = .16f) else Color.Black.copy(alpha = .14f), spec, label = "preview outline")
    val primary by animateColorAsState(scheme.primary, spec, label = "preview primary")
    val secondary by animateColorAsState(scheme.secondary, spec, label = "preview secondary")
    val tertiary by animateColorAsState(scheme.tertiary, spec, label = "preview tertiary")
    val container by animateColorAsState(scheme.primaryContainer.compositeOver(surface), spec, label = "preview container")
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier.clip(shape).background(surface).border(1.dp, outline, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(bar))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(28.dp).background(primary, CircleShape))
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(2f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(primary))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(secondary))
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(tertiary))
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(container))
        }
    }
}

/** Separate rounded rows normally; one glass card with dividers when page glass is on. */
@Composable
private fun ThemeGroup(glass: Boolean, onBackground: Color, modifier: Modifier, content: @Composable () -> Unit) {
    if (!glass) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) { content() }
        return
    }
    com.auralis.music.ui.glass.OptionalGlassCard(
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        border = null,
        modifier = modifier
    ) {
        CompositionLocalProvider(LocalThemeGroupDivider provides onBackground.copy(alpha = .08f)) {
            Column { content() }
        }
    }
}

/** Set inside a single glass group: rows draw no card of their own, only a divider above. */
private val LocalThemeGroupDivider = staticCompositionLocalOf<Color?> { null }

@Composable
private fun ThemeRowSurface(index: Int, count: Int, onBackground: Color, content: @Composable () -> Unit) {
    val divider = LocalThemeGroupDivider.current
    if (divider != null) {
        Column(Modifier.fillMaxWidth()) {
            if (index > 0) Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(1.dp).background(divider))
            content()
        }
        return
    }
    com.auralis.music.ui.glass.OptionalGlassCard(
        shape = groupShape(index, count),
        color = onBackground.copy(alpha = .07f),
        border = null,
        modifier = Modifier.fillMaxWidth(),
        content = content
    )
}

private fun groupShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) 24.dp else 6.dp
    val bottom = if (index == count - 1) 24.dp else 6.dp
    return RoundedCornerShape(top, top, bottom, bottom)
}

/** One row of the grouped list: leading icon, title, then [content] underneath. */
@Composable
private fun ThemeGroupItem(index: Int, count: Int, icon: ImageVector, title: String, onBackground: Color,
    content: @Composable () -> Unit) {
    ThemeRowSurface(index, count, onBackground) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Icon(icon, null, tint = onBackground, modifier = Modifier.padding(top = 2.dp).size(24.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = onBackground, fontSize = 17.sp)
                content()
            }
        }
    }
}

@Composable
private fun ThemeSwitchItem(index: Int, count: Int, icon: ImageVector, title: String, summary: String,
    checked: Boolean, enabled: Boolean, accent: Color, onBackground: Color, onChange: (Boolean) -> Unit) {
    ThemeRowSurface(index, count, onBackground) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }
                .graphicsLayer { alpha = if (enabled) 1f else .45f }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = onBackground, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = onBackground, fontSize = 17.sp)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = onBackground.copy(alpha = .6f))
            }
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onChange,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = accent,
                    checkedThumbColor = if (isLightColor(accent)) Color(0xFF2A211C) else Color.White,
                    checkedIconColor = accent,
                ),
                thumbContent = {
                    Icon(if (checked) Icons.Rounded.Check else Icons.Rounded.Close, null,
                        modifier = Modifier.size(androidx.compose.material3.SwitchDefaults.IconSize))
                }
            )
        }
    }
}

/** Connected segmented button: the chosen one becomes a full pill, the rest keep tight joins. */
@Composable
private fun ConnectedToggle(label: String, selected: Boolean, index: Int, last: Int, accent: Color, onBackground: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val outer by animateDpAsState(if (selected) 24.dp else 20.dp, label = "toggle outer")
    val inner by animateDpAsState(if (selected) 24.dp else 6.dp, label = "toggle inner")
    val start = if (index == 0) outer else inner
    val end = if (index == last) outer else inner
    val shape = RoundedCornerShape(start, end, end, start)
    val fill by animateColorAsState(if (selected) accent else onBackground.copy(alpha = .08f), label = "toggle fill")
    Box(
        modifier.height(48.dp).clip(shape).background(fill).tactileBounce()
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) (if (isLightColor(accent)) Color(0xFF2A211C) else Color.White) else onBackground)
    }
}

/** Rounded-square palette tile with the three-tone circle and a check over the chosen one. */
@Composable
private fun PaletteTile(title: String, selected: Boolean, activeColor: Color, onBackground: Color,
    onClick: () -> Unit, content: @Composable () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "paletteScale"
    )
    Box(
        modifier = Modifier
            .size(68.dp)
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(onBackground.copy(alpha = if (selected) .14f else .05f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
        androidx.compose.animation.AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + scaleIn(initialScale = .4f),
            exit = fadeOut() + scaleOut(targetScale = .4f)
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(activeColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Check, str(R.string.x_selected, title),
                    tint = if (isLightColor(activeColor)) Color(0xFF1B1D22) else Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * Triple-Tone Palette Circle:
 * - Top half (180° sweep from 180° to 360°): Primary shade
 * - Bottom-left quadrant (90° sweep from 90° to 180°): Secondary shade
 * - Bottom-right quadrant (90° sweep from 0° to 90°): Tertiary shade
 */
@Composable
private fun TripleTonePaletteCircle(
    topColor: Color,
    bottomLeftColor: Color,
    bottomRightColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.clip(CircleShape)) {
        // Top half
        drawArc(
            color = topColor,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true
        )

        // Bottom-left quadrant
        drawArc(
            color = bottomLeftColor,
            startAngle = 90f,
            sweepAngle = 90f,
            useCenter = true
        )

        // Bottom-right quadrant
        drawArc(
            color = bottomRightColor,
            startAngle = 0f,
            sweepAngle = 90f,
            useCenter = true
        )
    }
}
