package com.auralis.music.ui.components

import androidx.compose.animation.core.*
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
import androidx.compose.ui.unit.dp

@Composable
fun EqualizerBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier.size(16.dp),
    color: Color = MaterialTheme.colorScheme.primary
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

    Canvas(modifier = modifier) {
        val totalWidth = size.width
        val barWidth = totalWidth / 5
        val gap = (totalWidth - 3 * barWidth) / 2
        val maxHeight = size.height

        val h1 = if (isPlaying) maxHeight * height1 else maxHeight * 0.3f
        val h2 = if (isPlaying) maxHeight * height2 else maxHeight * 0.5f
        val h3 = if (isPlaying) maxHeight * height3 else maxHeight * 0.2f

        // Bar 1
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, maxHeight - h1),
            size = Size(barWidth, h1),
            cornerRadius = CornerRadius(2.dp.toPx())
        )
        // Bar 2
        drawRoundRect(
            color = color,
            topLeft = Offset(barWidth + gap, maxHeight - h2),
            size = Size(barWidth, h2),
            cornerRadius = CornerRadius(2.dp.toPx())
        )
        // Bar 3
        drawRoundRect(
            color = color,
            topLeft = Offset(2 * (barWidth + gap), maxHeight - h3),
            size = Size(barWidth, h3),
            cornerRadius = CornerRadius(2.dp.toPx())
        )
    }
}
