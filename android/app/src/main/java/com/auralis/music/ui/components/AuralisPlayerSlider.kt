package com.auralis.music.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Authentic AOSP/Metrolist-Grade Multi-Mode Player Slider Engine:
 * 1. "Default": Thick solid pill bar with vertical divider playhead, symmetric gap, and endpoint dot.
 * 2. "Wavy": Native AOSP Cubic Bézier sinusoidal wave with circular thumb, seamless clipping, and pause flattening.
 * 3. "Slim": Continuous ultra-sleek minimalist rounded bar without protruding thumb.
 * 4. "Squiggly": Native AOSP Material You squiggly waveform with capsule divider playhead.
 */
@Composable
fun AuralisPlayerSlider(
    value: Float, // 0f..1f
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isPlaying: Boolean,
    currentPosMs: Long,
    totalDurationMs: Long,
    modifier: Modifier = Modifier,
    sliderStyle: String = "Wavy",
    activeTrackColor: Color = Color.White,
    inactiveTrackColor: Color = Color.White.copy(alpha = 0.28f),
    thumbColor: Color = Color.White,
    textColor: Color = Color.White.copy(alpha = 0.65f)
) {
    val view = LocalView.current
    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(value) }

    val effectiveProgress = if (isDragging) dragProgress else value.coerceIn(0f, 1f)

    val normalizedStyle = remember(sliderStyle) {
        when (sliderStyle) {
            "Squiggly Waveform", "Squiggly" -> "Squiggly"
            "Thin Line", "Slim" -> "Slim"
            "Wavy", "Neon Glow" -> "Wavy"
            else -> "Default"
        }
    }

    val isWaveStyle = normalizedStyle == "Wavy" || normalizedStyle == "Squiggly"

    // Infinite phase progress (0f..1f) for the Bézier wave cycle
    val infiniteTransition = rememberInfiniteTransition(label = "sliderWaveTransition")
    val wavePhaseFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sliderWavePhase"
    )

    // Wave amplitude animation: flattens to 0.dp when paused, balanced 2.8.dp when playing
    val targetAmplitude = if (!isWaveStyle || !isPlaying) {
        0.dp
    } else {
        2.8.dp
    }
    val animatedAmplitude by animateDpAsState(
        targetValue = targetAmplitude,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "amplitudeAnim"
    )

    // Animated thumb dimensions
    val animatedThumbRadius by animateDpAsState(
        targetValue = if (isDragging) 10.5.dp else 8.5.dp,
        animationSpec = tween(durationMillis = 150),
        label = "thumbRadiusAnim"
    )

    val cachedWavePath = remember { Path() }
    val cachedActiveDefaultPath = remember { Path() }
    val cachedInactiveDefaultPath = remember { Path() }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // ── INTERACTIVE CANVAS SLIDER TRACK ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = size.width.toFloat()
                        if (width > 0f) {
                            isDragging = true
                            val initialProgress = (down.position.x / width).coerceIn(0f, 1f)
                            dragProgress = initialProgress
                            onValueChange(initialProgress)
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

                            val pointerId = down.id
                            down.consume()

                            while (true) {
                                val event = awaitPointerEvent()
                                val pointerChange = event.changes.firstOrNull { it.id == pointerId } ?: break

                                if (pointerChange.pressed) {
                                    val newProgress = (pointerChange.position.x / width).coerceIn(0f, 1f)
                                    if (newProgress != dragProgress) {
                                        dragProgress = newProgress
                                        onValueChange(newProgress)
                                    }
                                    pointerChange.consume()
                                } else {
                                    pointerChange.consume()
                                    break
                                }
                            }

                            isDragging = false
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onValueChangeFinished()
                        }
                    }
                }
        ) {
            Canvas(
                modifier = Modifier.matchParentSize()
            ) {
                val width = size.width
                val height = size.height
                val centerY = height / 2f

                when (normalizedStyle) {
                    "Default" -> {
                        // ── 1. DEFAULT STYLE (Thick bars + vertical divider line + gap + endpoint dot) ──
                        val trackHeightPx = with(density) { 14.dp.toPx() }
                        val r = trackHeightPx / 2f
                        val gapPx = with(density) { 6.dp.toPx() }
                        val startX = r
                        val endX = width - r
                        val trackWidth = (endX - startX).coerceAtLeast(1f)
                        val thumbX = (startX + trackWidth * effectiveProgress).coerceIn(startX, endX)

                        // Inactive Track (Right) with straight cut on left and rounded right cap
                        val inactiveLeft = (thumbX + gapPx).coerceAtMost(endX + r)
                        if (inactiveLeft < endX) {
                            cachedInactiveDefaultPath.reset()
                            cachedInactiveDefaultPath.moveTo(inactiveLeft, centerY - r)
                            cachedInactiveDefaultPath.lineTo(endX, centerY - r)
                            cachedInactiveDefaultPath.arcTo(
                                rect = Rect(endX - r, centerY - r, endX + r, centerY + r),
                                startAngleDegrees = -90f,
                                sweepAngleDegrees = 180f,
                                forceMoveTo = false
                            )
                            cachedInactiveDefaultPath.lineTo(inactiveLeft, centerY + r)
                            cachedInactiveDefaultPath.close()
                            drawPath(cachedInactiveDefaultPath, inactiveTrackColor)
                        }

                        // Endpoint Accent Dot at far right inside the inactive track
                        drawCircle(
                            color = activeTrackColor,
                            radius = with(density) { 2.5.dp.toPx() },
                            center = Offset(endX, centerY)
                        )

                        // Active Track (Left) with rounded left cap and straight cut on right
                        val activeRight = (thumbX - gapPx).coerceAtLeast(startX - r)
                        if (activeRight > startX) {
                            cachedActiveDefaultPath.reset()
                            cachedActiveDefaultPath.moveTo(activeRight, centerY - r)
                            cachedActiveDefaultPath.lineTo(startX, centerY - r)
                            cachedActiveDefaultPath.arcTo(
                                rect = Rect(startX - r, centerY - r, startX + r, centerY + r),
                                startAngleDegrees = -90f,
                                sweepAngleDegrees = -180f,
                                forceMoveTo = false
                            )
                            cachedActiveDefaultPath.lineTo(activeRight, centerY + r)
                            cachedActiveDefaultPath.close()
                            drawPath(cachedActiveDefaultPath, activeTrackColor)
                        } else if (activeRight >= startX - r) {
                            drawCircle(
                                color = activeTrackColor,
                                radius = r,
                                center = Offset(startX, centerY)
                            )
                        }

                        // Vertical Playhead Thumb Line
                        val pillWidthPx = with(density) { (if (isDragging) 4.dp else 3.dp).toPx() }
                        val pillHeightPx = with(density) { (if (isDragging) 36.dp else 32.dp).toPx() }
                        val pillRadiusPx = pillWidthPx / 2f
                        drawRoundRect(
                            color = thumbColor,
                            topLeft = Offset(thumbX - pillRadiusPx, centerY - pillHeightPx / 2f),
                            size = Size(pillWidthPx, pillHeightPx),
                            cornerRadius = CornerRadius(pillRadiusPx, pillRadiusPx)
                        )
                    }

                    "Wavy" -> {
                        // ── 2. WAVY STYLE (Metrolist-Identical Silky Wave + 8.5dp Circle Thumb + 3dp Accent Dot) ──
                        val strokePx = with(density) { 5.dp.toPx() }
                        val r = strokePx / 2f
                        val startX = r
                        val endX = width - r
                        val trackWidth = (endX - startX).coerceAtLeast(1f)
                        val thumbX = (startX + trackWidth * effectiveProgress).coerceIn(startX, endX)
                        val ampPx = with(density) { animatedAmplitude.toPx() }
                        val waveLengthPx = with(density) { 40.dp.toPx() }
                        val radiusPx = with(density) { animatedThumbRadius.toPx() }
                        val gapPx = with(density) { 8.dp.toPx() }

                        // Inactive Track (Thick Straight Line starting after a clean gap from the thumb dot)
                        val inactiveStart = (thumbX + radiusPx + gapPx).coerceAtMost(endX)
                        if (inactiveStart < endX) {
                            drawLine(
                                color = inactiveTrackColor,
                                start = Offset(inactiveStart, centerY),
                                end = Offset(endX, centerY),
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round
                            )
                        }

                        // Endpoint Accent Dot at Far-Right
                        drawCircle(
                            color = activeTrackColor,
                            radius = with(density) { 3.dp.toPx() },
                            center = Offset(endX, centerY)
                        )

                        // Active Track (Smooth Continuous Wave with organic envelope curve into the thumb)
                        if (thumbX > startX) {
                            if (ampPx > 0.2f) {
                                val totalSpan = (thumbX - startX).coerceAtLeast(1f)
                                val endTransitionLength = (waveLengthPx * 0.9f).coerceAtMost(totalSpan * 0.6f).coerceAtLeast(1f)
                                val startAngle = -wavePhaseFraction * (2 * PI).toFloat()
                                val startY = centerY + sin(startAngle) * ampPx

                                cachedWavePath.reset()
                                cachedWavePath.moveTo(startX, startY)

                                var currentX = startX
                                val step = 1.0f
                                while (currentX <= thumbX) {
                                    val distFromStart = currentX - startX
                                    val distFromEnd = thumbX - currentX

                                    val endEnvelope = if (distFromEnd < endTransitionLength) {
                                        val v = (distFromEnd / endTransitionLength).coerceIn(0f, 1f)
                                        0.5f * (1f - kotlin.math.cos(v * PI.toFloat()))
                                    } else 1.0f

                                    val angle = (distFromStart / waveLengthPx) * (2 * PI).toFloat() + startAngle
                                    val y = centerY + sin(angle) * ampPx * endEnvelope
                                    cachedWavePath.lineTo(currentX, y)
                                    currentX += step
                                }
                                cachedWavePath.lineTo(thumbX, centerY)

                                drawPath(
                                    path = cachedWavePath,
                                    color = activeTrackColor,
                                    style = Stroke(
                                        width = strokePx,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            } else {
                                drawLine(
                                    color = activeTrackColor,
                                    start = Offset(startX, centerY),
                                    end = Offset(thumbX, centerY),
                                    strokeWidth = strokePx,
                                    cap = StrokeCap.Round
                                )
                            }
                        }

                        // 8.5dp Circle Thumb with subtle shadow
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.25f),
                            radius = radiusPx + with(density) { 1.5.dp.toPx() },
                            center = Offset(thumbX, centerY + with(density) { 0.8.dp.toPx() })
                        )
                        drawCircle(
                            color = thumbColor,
                            radius = radiusPx,
                            center = Offset(thumbX, centerY)
                        )
                    }

                    "Slim" -> {
                        // ── 3. SLIM STYLE (Continuous thin rounded bar without protruding thumb) ──
                        val strokePx = with(density) { 5.5.dp.toPx() }
                        val r = strokePx / 2f
                        val startX = r
                        val endX = width - r
                        val trackWidth = (endX - startX).coerceAtLeast(1f)
                        val thumbX = (startX + trackWidth * effectiveProgress).coerceIn(startX, endX)

                        // Inactive Track (Right)
                        if (thumbX < endX) {
                            drawLine(
                                color = inactiveTrackColor,
                                start = Offset(thumbX, centerY),
                                end = Offset(endX, centerY),
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round
                            )
                        }

                        // Active Track (Left)
                        if (thumbX > startX) {
                            drawLine(
                                color = activeTrackColor,
                                start = Offset(startX, centerY),
                                end = Offset(thumbX, centerY),
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    "Squiggly" -> {
                        // ── 4. SQUIGGLY STYLE (Wavy-Identical Wave Physics + Thickened Vertical Capsule Pill Thumb) ──
                        val strokePx = with(density) { 5.dp.toPx() }
                        val r = strokePx / 2f
                        val startX = r
                        val endX = width - r
                        val trackWidth = (endX - startX).coerceAtLeast(1f)
                        val thumbX = (startX + trackWidth * effectiveProgress).coerceIn(startX, endX)
                        val ampPx = with(density) { animatedAmplitude.toPx() }
                        val waveLengthPx = with(density) { 40.dp.toPx() }

                        // Thickened vertical capsule pill thumb bar
                        val pillWidthPx = with(density) { (if (isDragging) 6.dp else 4.5.dp).toPx() }
                        val pillHeightPx = with(density) { (if (isDragging) 26.dp else 20.dp).toPx() }
                        val pillRadiusPx = pillWidthPx / 2f

                        // Clamped boundary to ensure wave round cap NEVER seeps past the vertical bar
                        val waveEndX = (thumbX - r).coerceAtLeast(startX)
                        val inactiveStartX = (thumbX + r).coerceAtMost(endX)

                        // Inactive Track (Clean straight line starting right at the bar)
                        if (inactiveStartX < endX) {
                            drawLine(
                                color = inactiveTrackColor,
                                start = Offset(inactiveStartX, centerY),
                                end = Offset(endX, centerY),
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round
                            )
                        }

                        // Active Track (Silky continuous wave stopping cleanly inside the vertical bar)
                        if (waveEndX > startX) {
                            if (ampPx > 0.2f) {
                                val totalSpan = (waveEndX - startX).coerceAtLeast(1f)
                                val endTransitionLength = (waveLengthPx * 0.9f).coerceAtMost(totalSpan * 0.6f).coerceAtLeast(1f)
                                val startAngle = -wavePhaseFraction * (2 * PI).toFloat()
                                val startY = centerY + sin(startAngle) * ampPx

                                cachedWavePath.reset()
                                cachedWavePath.moveTo(startX, startY)

                                var currentX = startX
                                val step = 1.0f
                                while (currentX <= waveEndX) {
                                    val distFromStart = currentX - startX
                                    val distFromEnd = waveEndX - currentX

                                    val endEnvelope = if (distFromEnd < endTransitionLength) {
                                        val v = (distFromEnd / endTransitionLength).coerceIn(0f, 1f)
                                        0.5f * (1f - kotlin.math.cos(v * PI.toFloat()))
                                    } else 1.0f

                                    val angle = (distFromStart / waveLengthPx) * (2 * PI).toFloat() + startAngle
                                    val y = centerY + sin(angle) * ampPx * endEnvelope
                                    cachedWavePath.lineTo(currentX, y)
                                    currentX += step
                                }
                                cachedWavePath.lineTo(waveEndX, centerY)

                                drawPath(
                                    path = cachedWavePath,
                                    color = activeTrackColor,
                                    style = Stroke(
                                        width = strokePx,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            } else {
                                drawLine(
                                    color = activeTrackColor,
                                    start = Offset(startX, centerY),
                                    end = Offset(waveEndX, centerY),
                                    strokeWidth = strokePx,
                                    cap = StrokeCap.Round
                                )
                            }
                        }

                        // Vertical Rounded Capsule Divider Thumb Bar (Rendered on top)
                        drawRoundRect(
                            color = thumbColor,
                            topLeft = Offset(thumbX - pillRadiusPx, centerY - pillHeightPx / 2f),
                            size = Size(pillWidthPx, pillHeightPx),
                            cornerRadius = CornerRadius(pillRadiusPx, pillRadiusPx)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // ── TIMESTAMPS ROW ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration(currentPosMs),
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = formatDuration(totalDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
