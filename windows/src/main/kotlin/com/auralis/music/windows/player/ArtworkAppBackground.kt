package com.auralis.music.windows.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
    val light = base.background.luminance() > .5f
    val foreground = if (light) Color(0xFF211D25) else Color.White
    val contrast = if (light) Color.Black else Color.White
    // Keep the soft song-synced artwork field. Neutral glass overlays must not
    // recolour it with a palette accent such as a tiny red signature.
    val tint by animateColorAsState(if (artwork != null) if (light) Color(0xFFF6F6F6) else Color(0xFF101010) else base.background, tween(600))
    val surfaces = if (artwork == null) base else base.copy(
        background = tint.copy(alpha = if (light) .68f else .16f), surface = tint.copy(alpha = if (light) .74f else .24f),
        surfaceContainer = tint.copy(alpha = if (light) .80f else .36f), surfaceContainerLow = tint.copy(alpha = if (light) .72f else .28f),
        surfaceContainerHigh = lerp(tint, contrast, .08f).copy(alpha = .55f), surfaceContainerHighest = lerp(tint, contrast, .12f).copy(alpha = .65f),
        surfaceVariant = lerp(tint, contrast, .08f).copy(alpha = .55f), onBackground = foreground,
        onSurface = foreground, onSurfaceVariant = foreground.copy(alpha = .72f))
    Box(modifier.fillMaxSize().background(tint)) {
        if (artwork != null) BlurredArtworkBackground(artwork, blurRadius = 48.dp, brightness = .68f, artworkScale = 1.10f)
        // Shared settings/profile pages read the dynamic locals, while desktop browsing
        // pages read MaterialTheme. Both must see the same translucent artwork surfaces.
        CompositionLocalProvider(
            LocalDynamicThemeBackground provides surfaces.background,
            LocalDynamicThemeSurface provides surfaces.surface,
            LocalDynamicThemeOnBackground provides surfaces.onBackground,
            LocalDynamicThemeOnSurface provides surfaces.onSurface,
            androidx.compose.material3.LocalContentColor provides surfaces.onSurface
        ) {
            MaterialTheme(colorScheme = surfaces) { content() }
        }
    }
}
