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

/** Windows browsing surfaces follow the current cover without changing Android's theme. */
@Composable
internal fun ArtworkAppBackground(artwork: String?, palette: ArtworkPalette, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val light = base.background.luminance() > .5f
    val foreground = if (light) Color(0xFF211D25) else Color.White
    val contrast = if (light) Color.Black else Color.White
    val tint by animateColorAsState(if (artwork != null) lerp(if (light) Color(0xFFF6F3F8) else Color(0xFF151417), palette.primary, if (light) .18f else .24f) else base.background, tween(600))
    val surfaces = if (artwork == null) base else base.copy(
        background = tint.copy(alpha = if (light) .68f else .16f), surface = tint.copy(alpha = if (light) .74f else .24f),
        surfaceContainer = tint.copy(alpha = if (light) .80f else .36f), surfaceContainerLow = tint.copy(alpha = if (light) .72f else .28f),
        surfaceContainerHigh = lerp(tint, contrast, .08f).copy(alpha = .55f), surfaceContainerHighest = lerp(tint, contrast, .12f).copy(alpha = .65f),
        surfaceVariant = lerp(tint, contrast, .08f).copy(alpha = .55f), onBackground = foreground,
        onSurface = foreground, onSurfaceVariant = foreground.copy(alpha = .72f))
    Box(modifier.fillMaxSize().background(tint)) {
        if (artwork != null) BlurredArtworkBackground(artwork, blurRadius = 48.dp)
        MaterialTheme(colorScheme = surfaces) { content() }
    }
}
