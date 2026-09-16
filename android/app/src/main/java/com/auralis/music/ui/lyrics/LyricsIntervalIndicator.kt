package com.auralis.music.ui.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.draw.alpha
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Expressive fluid/wavy progress indicator matching Metrolist & Material 3 Expressive.
 *
 * Renders an organic, fluid wavy progress arc sweeping clockwise from top (12 o'clock),
 * accompanied by a dim circular track indicating remaining time, both with smooth rounded stroke caps.
 * The active stroke undulates seamlessly while cosine tapering at both endpoints anchors the moving
 * tip concentric to the circular track without bobbing.
 */
@Composable
fun WavyProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.18f),
    strokeWidth: Dp = 3.0.dp,
    gapSize: Dp = 3.0.dp,
    lobes: Int = 7,
    amplitudeRatio: Float = 0.085f
) {
    val clampedProgress = progress.coerceIn(0f, 1f)

    // Wave animation clock matching Material 3 Expressive: 1 cycle per second
    val infiniteTransition = rememberInfiniteTransition(label = "wavyProgressTransition")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavyProgressPhase"
    )

    // Reusable path across frames to avoid allocations during draw-phase
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val strokeWidthPx = strokeWidth.toPx()
        val gapPx = gapSize.toPx()
        val sizePx = min(size.width, size.height)
        val center = Offset(size.width / 2f, size.height / 2f)

        // Circle radius for track lining and wave baseline (34dp container with 3.0dp stroke)
        val baseRadius = (sizePx - strokeWidthPx) / 2f
        if (baseRadius <= 0f) return@Canvas
        val circumference = 2f * PI.toFloat() * baseRadius
        val maxAmplitude = baseRadius * amplitudeRatio

        // 1. Calculate track gap spacing (dot-sized gap with rounded caps)
        val pStopPx = clampedProgress * circumference
        val currentStrokeCapWidth = strokeWidthPx / 2f
        val trackGapSize = min(pStopPx, gapPx)
        val horizontalInsets = min(pStopPx, currentStrokeCapWidth)
        val trackSpacing = horizontalInsets * 2f + trackGapSize
        val gapDegrees = if (circumference > 0f) (trackSpacing / circumference) * 360f else 0f

        val activeSweep = clampedProgress * 360f

        // Draw remaining circular track lining (or full circle when idle)
        if (clampedProgress <= 0.001f) {
            drawCircle(
                color = trackColor,
                radius = baseRadius,
                center = center,
                style = Stroke(width = strokeWidthPx)
            )
        } else {
            val trackStartAngle = -90f + activeSweep + gapDegrees
            val trackSweepAngle = 360f - activeSweep - 2f * gapDegrees
            if (trackSweepAngle > 0.5f) {
                drawArc(
                    color = trackColor,
                    startAngle = trackStartAngle,
                    sweepAngle = trackSweepAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
                    size = Size(baseRadius * 2f, baseRadius * 2f),
                    style = Stroke(
                        width = strokeWidthPx,
                        cap = StrokeCap.Round
                    )
                )
            }
        }

        // 2. Draw active fluid wavy progress arc
        if (activeSweep > 0.8f) {
            path.rewind()

            // Rapid smooth entry so organic waves undulate immediately from countdown start
            val ampScale = when {
                clampedProgress < 0.03f -> (clampedProgress / 0.03f)
                clampedProgress > 0.97f -> ((1f - clampedProgress) / 0.03f)
                else -> 1f
            }
            val effAmp = maxAmplitude * ampScale

            val steps = (activeSweep * 2.5f).toInt().coerceIn(36, 180)
            val phaseRad = (wavePhase * 2.0 * PI).toFloat()
            // Taper angle at both ends (anchor at 12 o'clock and moving tip at activeSweep)
            // ensures the moving tip never bobs or breaks concentricity with the circular track.
            val taperAngle = 20f.coerceAtMost(activeSweep / 3f)

            for (i in 0..steps) {
                val deg = (i.toFloat() / steps.toFloat()) * activeSweep
                val rad = Math.toRadians((deg - 90.0)).toFloat()
                val lobeAngle = Math.toRadians((deg * lobes).toDouble()).toFloat() - phaseRad

                val startDist = deg
                val endDist = activeSweep - deg
                val startT = if (taperAngle > 0f) (startDist / taperAngle).coerceIn(0f, 1f) else 1f
                val endT = if (taperAngle > 0f) (endDist / taperAngle).coerceIn(0f, 1f) else 1f
                // Cosine easing for smooth C1 continuous derivatives at endpoints
                val startWeight = (1f - cos(startT * PI.toFloat())) / 2f
                val endWeight = (1f - cos(endT * PI.toFloat())) / 2f
                val taper = startWeight * endWeight

                val r = baseRadius + effAmp * taper * sin(lobeAngle)
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
 * as the instrumental gap between lyrics progresses, matching Metrolist style.
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
                    trackColor = color.copy(alpha = 0.18f),
                    strokeWidth = 3.0.dp,
                    gapSize = 3.0.dp,
                    lobes = 7,
                    amplitudeRatio = 0.085f
                )
            }
        }
    }
}
