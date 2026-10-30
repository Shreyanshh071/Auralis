package com.auralis.music.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/**
 * Colours a page from one image (an artist's photo, an album's cover) instead of the app's theme.
 * Starts on the image's colours when it's in memory; otherwise eases over to them once they're ready.
 */
@Composable
fun ArtworkThemed(artworkUrl: String?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // The card that opened the page already holds this image, so the first frame has its colours.
    var palette by remember { mutableStateOf(ArtworkPaletteCache.getCachedOrExtractNow(context, artworkUrl)) }
    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) return@LaunchedEffect
        val next = ArtworkPaletteCache.getCachedOrExtractNow(context, artworkUrl)
            ?: runCatching { ArtworkPaletteCache.extractPalette(context, artworkUrl, artworkUrl) }.getOrNull()
                ?.takeIf { it != ArtworkPaletteCache.defaultPalette && !it.isPlaceholder }
        if (next != null) palette = next
    }

    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < .5f
    val resolved = palette
    val spec = tween<Color>(450)
    val accent by animateColorAsState(resolved?.let { readableAccent(it.primary, dark) } ?: MaterialTheme.dynamicPrimary, spec, label = "pageAccent")
    val background by animateColorAsState(
        resolved?.let { lerp(if (dark) Color(0xFF0E0E11) else Color(0xFFFAFAFA), it.primary, if (dark) .11f else .07f) } ?: MaterialTheme.dynamicBackground,
        spec, label = "pageBackground"
    )
    val onBackground = if (dark) Color(0xFFF4F4F6) else Color(0xFF18191E)
    val surface = lerp(background, onBackground, .06f)
    val scheme = if (resolved == null) base else base.copy(
        primary = accent,
        onPrimary = if (accent.luminance() > .45f) Color(0xFF111215) else Color.White,
        primaryContainer = lerp(background, accent, .3f),
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onBackground,
        surfaceContainerLow = lerp(background, onBackground, .03f),
        surfaceContainer = surface,
        surfaceContainerHigh = lerp(background, onBackground, .10f)
    )
    // One call site whether or not the palette is ready: the page must not rebuild when it arrives.
    CompositionLocalProvider(
        LocalDynamicThemeEnabled provides (resolved != null || LocalDynamicThemeEnabled.current),
        LocalDynamicThemePrimary provides (if (resolved != null) accent else LocalDynamicThemePrimary.current),
        LocalDynamicThemeSecondary provides (resolved?.let { readableAccent(it.secondary, dark) } ?: LocalDynamicThemeSecondary.current),
        LocalDynamicThemeTertiary provides (resolved?.let { readableAccent(it.tertiary, dark) } ?: LocalDynamicThemeTertiary.current),
        LocalDynamicThemeBackground provides (if (resolved != null) background else LocalDynamicThemeBackground.current),
        LocalDynamicThemeSurface provides (if (resolved != null) surface else LocalDynamicThemeSurface.current),
        LocalDynamicThemeOnBackground provides (if (resolved != null) onBackground else LocalDynamicThemeOnBackground.current),
        LocalDynamicThemeOnSurface provides (if (resolved != null) onBackground else LocalDynamicThemeOnSurface.current)
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The palette's colour, lifted or deepened until it reads as text on the page. */
private fun readableAccent(color: Color, dark: Boolean): Color {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[2] = if (dark) hsl[2].coerceAtLeast(.72f) else hsl[2].coerceAtMost(.38f)
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}
