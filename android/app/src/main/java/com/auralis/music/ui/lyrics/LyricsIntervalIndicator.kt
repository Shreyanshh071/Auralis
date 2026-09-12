package com.auralis.music.ui.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Modern fluid/wavy countdown & interval progress indicator matching BetterLyrics style.
 *
 * Renders an organic 8-lobed wavy progress arc sweeping clockwise from top (12 o'clock),
 * accompanied by a dim circular track indicating remaining time, both with smooth rounded stroke caps.
 */
@Composable
fun WavyProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.15f),
    strokeWidth: Dp = 2.8.dp,
    lobes: Int = 8,
    amplitudeRatio: Float = 0.078f
) {
    val clampedProgress = progress.coerceIn(0f, 1f)

    Canvas(modifier = modifier) {
        val strokeWidthPx = strokeWidth.toPx()
        val sizePx = min(size.width, size.height)
        val maxRadius = (sizePx - strokeWidthPx) / 2f
        val baseRadius = maxRadius / (1f + amplitudeRatio)
        val amplitude = baseRadius * amplitudeRatio
        val center = Offset(size.width / 2f, size.height / 2f)

        // 1. Draw remaining circular track (or full circle when idle)
        val remainingSweep = (1f - clampedProgress) * 360f
        if (remainingSweep >= 359.5f) {
            drawCircle(
                color = trackColor,
                radius = baseRadius,
                center = center,
                style = Stroke(width = strokeWidthPx)
            )
        } else if (remainingSweep > 2f) {
            val startAngle = -90f + clampedProgress * 360f
            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = remainingSweep,
                useCenter = false,
                topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
                size = Size(baseRadius * 2f, baseRadius * 2f),
                style = Stroke(
                    width = strokeWidthPx,
                    cap = StrokeCap.Round
                )
            )
        }

        // 2. Draw active wavy progress arc
        val activeSweep = clampedProgress * 360f
        if (activeSweep > 1f) {
            val path = Path()
            val steps = (activeSweep * 1.5f).toInt().coerceAtLeast(16)
            for (i in 0..steps) {
                val deg = i.toFloat() / steps.toFloat() * activeSweep
                val rad = Math.toRadians((deg - 90.0)).toFloat()
                val lobeAngle = Math.toRadians((deg * lobes).toDouble()).toFloat()
                val r = baseRadius + amplitude * sin(lobeAngle)
                val x = center.x + r * cos(rad)
                val y = center.y + r * sin(rad)
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = strokeWidthPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

/**
 * Modern lyrics instrumental music filler indicator.
 *
 * Displays a clean, elegant wavy progress ring that fills smoothly clockwise
 * as the instrumental gap between lyrics progresses, matching the BetterLyrics style.
 * Includes smooth entry/exit animations and tap-to-skip support.
 */
@Composable
fun LyricsIntervalIndicator(
    gapStartMs: Long,
    gapEndMs: Long,
    currentPositionMs: Long,
    visible: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    onSkip: (() -> Unit)? = null
) {
    val alphaAnim = remember { Animatable(if (visible) 1f else 0f) }
    val heightAnim = remember { Animatable(if (visible) 1f else 0f) }

    LaunchedEffect(visible) {
        if (visible) {
            heightAnim.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
            alphaAnim.animateTo(1f, tween(200, easing = LinearEasing))
        } else {
            alphaAnim.animateTo(0f, tween(200, easing = LinearEasing))
            heightAnim.animateTo(0f, tween(250, easing = FastOutSlowInEasing))
        }
    }

    val progress = if (gapEndMs > gapStartMs) {
        ((currentPositionMs - gapStartMs).toFloat() / (gapEndMs - gapStartMs).toFloat()).coerceIn(0f, 1f)
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "intervalProgress"
    )

    val targetHeight = 48.dp * heightAnim.value

    if (heightAnim.value > 0.01f) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(targetHeight)
                .padding(vertical = (3 * heightAnim.value).dp)
                .graphicsLayer {
                    alpha = alphaAnim.value
                    clip = true
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .then(
                        if (onSkip != null) {
                            Modifier.clickable(onClick = onSkip)
                        } else Modifier
                    )
            ) {
                WavyProgressIndicator(
                    progress = animatedProgress,
                    modifier = Modifier.size(34.dp),
                    color = color.copy(alpha = 0.95f),
                    trackColor = color.copy(alpha = 0.15f),
                    strokeWidth = 2.8.dp,
                    lobes = 8,
                    amplitudeRatio = 0.078f
                )
            }
        }
    }
}
