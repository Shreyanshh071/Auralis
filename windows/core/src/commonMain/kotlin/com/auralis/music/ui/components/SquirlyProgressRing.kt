package com.auralis.music.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Organic wavy / squiggly circular progress ring (BetterLyrics / Apple Music inspired).
 * Features harmonic sinusoidal perimeter undulation, fluid wave rotation, and smooth rounded stroke caps.
 *
 * Supports both determinate countdown progress (0f..1f) and indeterminate spinner mode.
 */
@Composable
fun SquirlyProgressRing(
    modifier: Modifier = Modifier,
    progress: Float = 1.0f,
    isIndeterminate: Boolean = false,
    strokeWidth: Dp = 4.dp,
    trackColor: Color = Color.White.copy(alpha = 0.16f),
    progressColor: Color = Color.White.copy(alpha = 0.96f),
    waveCount: Int = 6,
    waveAmplitudeRatio: Float = 0.11f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "squirlyInfinite")

    // Continuous wave phase morphing
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "squirlyPhase"
    )

    // Gentle global perimeter rotation
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isIndeterminate) 1800 else 9000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "squirlyRotation"
    )

    // Indeterminate dynamic arc sweep breathing
    val indeterminateSweep by infiniteTransition.animateFloat(
        initialValue = 60f,
        targetValue = 280f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "squirlySweep"
    )

    Canvas(modifier = modifier) {
        val sizePx = size.minDimension
        val strokeWidthPx = strokeWidth.toPx()
        val baseRadius = (sizePx - strokeWidthPx * 2.8f) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        if (baseRadius <= 0f) return@Canvas

        val amp1 = baseRadius * waveAmplitudeRatio
        val amp2 = baseRadius * (waveAmplitudeRatio * 0.32f)

        fun getRadius(angle: Float): Float {
            val w1 = sin((waveCount * angle + phase).toDouble()).toFloat() * amp1
            val w2 = cos(((waveCount * 2) * angle - phase * 0.75f).toDouble()).toFloat() * amp2
            return baseRadius + w1 + w2
        }

        val rotRad = Math.toRadians(rotationAngle.toDouble()).toFloat()
        val totalSteps = 160

        // 1. Draw Background Track (full 360° squirly loop)
        if (!isIndeterminate || trackColor.alpha > 0f) {
            val trackPath = Path()
            for (i in 0..totalSteps) {
                val fraction = i.toFloat() / totalSteps
                val theta = fraction * (2 * PI).toFloat() + rotRad
                val r = getRadius(theta)
                val x = center.x + r * cos(theta.toDouble()).toFloat()
                val y = center.y + r * sin(theta.toDouble()).toFloat()
                if (i == 0) trackPath.moveTo(x, y) else trackPath.lineTo(x, y)
            }
            trackPath.close()

            drawPath(
                path = trackPath,
                color = trackColor,
                style = Stroke(
                    width = strokeWidthPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        // 2. Draw Active Squirly Filling Arc
        val (startAngleRad, sweepAngleRad) = if (isIndeterminate) {
            val startDeg = rotationAngle
            val sweepDeg = indeterminateSweep
            Pair(
                Math.toRadians(startDeg.toDouble()).toFloat(),
                Math.toRadians(sweepDeg.toDouble()).toFloat()
            )
        } else {
            val clamped = progress.coerceIn(0f, 1f)
            Pair(
                (-PI / 2.0).toFloat() + rotRad,
                (2 * PI * clamped).toFloat()
            )
        }

        if (sweepAngleRad > 0.01f) {
            val progressPath = Path()
            val progressSteps = (totalSteps * (sweepAngleRad / (2 * PI).toFloat())).toInt().coerceIn(4, totalSteps)

            for (i in 0..progressSteps) {
                val fraction = i.toFloat() / progressSteps
                val theta = startAngleRad + fraction * sweepAngleRad
                val r = getRadius(theta)
                val x = center.x + r * cos(theta.toDouble()).toFloat()
                val y = center.y + r * sin(theta.toDouble()).toFloat()
                if (i == 0) progressPath.moveTo(x, y) else progressPath.lineTo(x, y)
            }

            drawPath(
                path = progressPath,
                color = progressColor,
                style = Stroke(
                    width = strokeWidthPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
