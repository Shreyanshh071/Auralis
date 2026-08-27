package com.auralis.music.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.theme.LocalReducedMotion

// Resting bar heights, as a fraction of the available height.
private const val IdleBar1 = 0.3f
private const val IdleBar2 = 0.5f
private const val IdleBar3 = 0.2f

/**
 * Three-bar "now playing" equalizer indicator.
 *
 * The animation is only composed while audio is actually playing. Creating the
 * infinite transition unconditionally keeps the draw phase invalidating every
 * frame for as long as the row is on screen — including while paused — which is
 * expensive when several of these are alive in a scrolling list.
 */
@Composable
fun EqualizerBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier.size(16.dp),
    color: Color = MaterialTheme.colorScheme.primary
) {
    if (isPlaying && !LocalReducedMotion.current) {
        AnimatedEqualizerBars(modifier = modifier, color = color)
    } else {
        Canvas(modifier = modifier) {
            drawEqualizerBars(color, IdleBar1, IdleBar2, IdleBar3)
        }
    }
}

@Composable
private fun AnimatedEqualizerBars(
    modifier: Modifier,
    color: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")

    val height1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )

    val height2 by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )

    val height3 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    // Reading the animated values inside the draw lambda keeps this to a draw
    // invalidation per frame — no recomposition, no relayout.
    Canvas(modifier = modifier) {
        drawEqualizerBars(color, height1, height2, height3)
    }
}

private fun DrawScope.drawEqualizerBars(
    color: Color,
    fraction1: Float,
    fraction2: Float,
    fraction3: Float
) {
    val totalWidth = size.width
    val barWidth = totalWidth / 5
    val gap = (totalWidth - 3 * barWidth) / 2
    val maxHeight = size.height
    val cornerRadius = CornerRadius(2.dp.toPx())

    val h1 = maxHeight * fraction1
    val h2 = maxHeight * fraction2
    val h3 = maxHeight * fraction3

    // Bar 1
    drawRoundRect(
        color = color,
        topLeft = Offset(0f, maxHeight - h1),
        size = Size(barWidth, h1),
        cornerRadius = cornerRadius
    )
    // Bar 2
    drawRoundRect(
        color = color,
        topLeft = Offset(barWidth + gap, maxHeight - h2),
        size = Size(barWidth, h2),
        cornerRadius = cornerRadius
    )
    // Bar 3
    drawRoundRect(
        color = color,
        topLeft = Offset(2 * (barWidth + gap), maxHeight - h3),
        size = Size(barWidth, h3),
        cornerRadius = cornerRadius
    )
}
