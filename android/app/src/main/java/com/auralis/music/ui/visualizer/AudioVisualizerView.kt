package com.auralis.music.ui.visualizer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.theme.dynamicPalette
import kotlin.math.sin

/**
 * Visualizer rendering modes.
 */
enum class VisualizerMode {
    BARS,
    WAVE,
    MIRROR
}

/**
 * Hardware-accelerated 60fps dynamic frequency bar visualizer responding to music playback state
 * with dynamic gradient spectrum coloring.
 */
@Composable
fun AudioVisualizerView(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    mode: VisualizerMode = VisualizerMode.BARS,
    barCount: Int = 28,
    primaryColor: Color = MaterialTheme.dynamicPalette.tintA,
    secondaryColor: Color = MaterialTheme.dynamicPalette.tintB
) {
    val infiniteTransition = rememberInfiniteTransition(label = "VisualizerTransition")
    val animPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isPlaying) (2f * Math.PI.toFloat()) else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "VisualizerPhase"
    )

    Box(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val barSpacing = width / (barCount * 1.5f)
            val barWidth = barSpacing * 0.8f
            val startX = (width - (barCount * (barWidth + barSpacing))) / 2f

            when (mode) {
                VisualizerMode.BARS -> {
                    for (i in 0 until barCount) {
                        // Multi-harmonic sine frequency simulation
                        val harmonic1 = sin(animPhase * 2.2f + (i * 0.45f))
                        val harmonic2 = sin(animPhase * 3.8f + (i * 0.8f))
                        val harmonic3 = sin(animPhase * 1.4f + (i * 0.2f))

                        val rawMagnitude = if (isPlaying) {
                            (0.20f + (harmonic1 * 0.35f) + (harmonic2 * 0.25f) + (harmonic3 * 0.20f)).coerceIn(0.08f, 0.95f)
                        } else {
                            0.06f
                        }

                        val barHeight = height * rawMagnitude
                        val x = startX + i * (barWidth + barSpacing)
                        val y = height - barHeight

                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    primaryColor,
                                    secondaryColor
                                ),
                                startY = y,
                                endY = height
                            ),
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }

                VisualizerMode.MIRROR -> {
                    val centerY = height / 2f
                    for (i in 0 until barCount) {
                        val harmonic = sin(animPhase * 3.0f + (i * 0.5f))
                        val mag = if (isPlaying) (0.15f + (harmonic * 0.40f)).coerceIn(0.05f, 0.90f) else 0.04f
                        val halfHeight = (height * 0.45f) * mag
                        val x = startX + i * (barWidth + barSpacing)

                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(primaryColor, secondaryColor, primaryColor),
                                startY = centerY - halfHeight,
                                endY = centerY + halfHeight
                            ),
                            topLeft = Offset(x, centerY - halfHeight),
                            size = Size(barWidth, halfHeight * 2f),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }

                VisualizerMode.WAVE -> {
                    val centerY = height / 2f
                    for (i in 0 until barCount) {
                        val waveOffset = sin(animPhase * 4.0f + (i * 0.3f)) * (height * 0.35f)
                        val barHeight = if (isPlaying) (height * 0.25f) else (height * 0.05f)
                        val x = startX + i * (barWidth + barSpacing)
                        val y = centerY + (if (isPlaying) waveOffset else 0f) - (barHeight / 2f)

                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(secondaryColor, primaryColor),
                                startY = y,
                                endY = y + barHeight
                            ),
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }
            }
        }
    }
}
