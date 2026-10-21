package com.auralis.music.windows.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.theme.ArtworkPalette
import com.auralis.music.ui.theme.LocalDynamicThemeBackground
import com.auralis.music.ui.theme.LocalDynamicThemeSurface
import com.auralis.music.ui.theme.LocalDynamicThemeOnBackground
import com.auralis.music.ui.theme.LocalDynamicThemeOnSurface

/** Windows browsing surfaces follow the current cover without changing Android's theme. */
@Composable
internal fun ArtworkAppBackground(artwork: String?, palette: ArtworkPalette, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val preferences = com.auralis.music.windows.settings.DesktopPreferences.store
    val revision by preferences.changes.collectAsState()
    val light = base.background.luminance() > .5f
    // Keep the soft song-synced artwork field. Neutral glass overlays must not
    // recolour it with a palette accent such as a tiny red signature.
    val tint by animateColorAsState(if (artwork != null) if (light) Color(0xFFF6F6F6) else Color(0xFF101010) else base.background, tween(600))
    val surfaces = if (artwork == null) base else base.withArtworkSurfaces(tint)
    Box(modifier.fillMaxSize().background(tint)) {
        if (artwork != null) Box(Modifier.fillMaxSize().then(com.auralis.music.windows.dev.perfSkip("background"))) {
            BlurredArtworkBackground(artwork, blurRadius = 48.dp, brightness = .68f, artworkScale = 1.10f)
        }
        Box(Modifier.fillMaxSize().background(base.primary.copy(alpha=preferences.number("backgroundTint",0.0).toFloat().coerceIn(0f,.4f))))
        // Shared settings/profile pages read the dynamic locals, while desktop browsing
        // pages read MaterialTheme. Both must see the same translucent artwork surfaces.
        CompositionLocalProvider(
            LocalDynamicThemeBackground provides surfaces.background,
            LocalDynamicThemeSurface provides surfaces.surface,
            LocalDynamicThemeOnBackground provides surfaces.onBackground,
            LocalDynamicThemeOnSurface provides surfaces.onSurface,
            androidx.compose.material3.LocalContentColor provides surfaces.onSurface,
            LocalArtworkBackdrop provides artwork?.let { ArtworkBackdrop(it, palette) }
        ) {
            MaterialTheme(colorScheme = surfaces) { content() }
        }
    }
}

/** The song-synced artwork field behind the app, so popups that cover it (settings) can show the same one. */
internal data class ArtworkBackdrop(val artwork: String, val palette: ArtworkPalette)
internal val LocalArtworkBackdrop = compositionLocalOf<ArtworkBackdrop?> { null }

/** Translucent surfaces over the blurred artwork field; [tint] is the neutral wash under it. */
internal fun ColorScheme.withArtworkSurfaces(tint: Color): ColorScheme {
    val light = background.luminance() > .5f
    val foreground = if (light) Color(0xFF211D25) else Color.White
    val contrast = if (light) Color.Black else Color.White
    return copy(
        background = tint.copy(alpha = if (light) .68f else .16f), surface = tint.copy(alpha = if (light) .74f else .24f),
        surfaceContainer = tint.copy(alpha = if (light) .80f else .36f), surfaceContainerLow = tint.copy(alpha = if (light) .72f else .28f),
        surfaceContainerHigh = lerp(tint, contrast, .08f).copy(alpha = .55f), surfaceContainerHighest = lerp(tint, contrast, .12f).copy(alpha = .65f),
        surfaceVariant = lerp(tint, contrast, .08f).copy(alpha = .55f), onBackground = foreground,
        onSurface = foreground, onSurfaceVariant = foreground.copy(alpha = .72f))
}
