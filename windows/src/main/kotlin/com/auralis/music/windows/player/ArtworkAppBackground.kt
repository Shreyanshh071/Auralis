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
import com.auralis.music.ui.theme.ArtworkPalette

/** Windows browsing surfaces follow the current cover without changing Android's theme. */
@Composable
internal fun ArtworkAppBackground(artwork: String?, palette: ArtworkPalette, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val light = base.background.luminance() > .5f
    val foreground = if (light) Color(0xFF211D25) else Color.White
    val contrast = if (light) Color.Black else Color.White
    val tint by animateColorAsState(if (artwork != null) lerp(if (light) Color(0xFFF6F3F8) else Color(0xFF151417), palette.primary, if (light) .18f else .24f) else base.background, tween(600))
    val surfaces = if (artwork == null) base else base.copy(
        background = tint.copy(alpha = .72f), surface = tint.copy(alpha = .82f),
        surfaceContainer = tint.copy(alpha = .85f), surfaceContainerLow = tint.copy(alpha = .75f),
        surfaceContainerHigh = lerp(tint, contrast, .08f), surfaceContainerHighest = lerp(tint, contrast, .12f),
        surfaceVariant = lerp(tint, contrast, .08f), onBackground = foreground,
        onSurface = foreground, onSurfaceVariant = foreground.copy(alpha = .72f))
    Box(Modifier.fillMaxSize().background(tint)) {
        if (artwork != null) BlurredArtworkBackground(artwork)
        MaterialTheme(colorScheme = surfaces) { content() }
    }
}
