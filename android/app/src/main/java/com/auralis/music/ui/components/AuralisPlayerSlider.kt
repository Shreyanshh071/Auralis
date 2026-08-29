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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Multi-Mode Player Slider Engine supporting Metrolist-identical styles:
 * 1. "Default": Thick solid pill bar with vertical divider playhead, gap separation, and endpoint dot.
 * 2. "Wavy": Smooth sinusoidal wave animating when playing, flattens to straight line when paused, with circular thumb.
 * 3. "Slim": Continuous ultra-sleek minimalist thin rounded bar without protruding thumb.
 * 4. "Squiggly": Expressive squiggly waveform animating when playing, flattens when paused, with capsule divider playhead.
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
    sliderStyle: String = "Default",
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

    // Infinite phase animation while playing (responsive, lively wave motion)
    val infiniteTransition = rememberInfiniteTransition(label = "sliderWaveTransition")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (normalizedStyle == "Squiggly") 1300 else 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sliderWavePhase"
    )

    // Wave amplitude animation: flattens to 0.dp when paused, gentle 2.6.dp when playing (smooth and subtle)
    val targetAmplitude = if (!isWaveStyle || !isPlaying) {
        0.dp
    } else if (normalizedStyle == "Squiggly") {
        2.8.dp
    } else {
        2.6.dp
    }
    val animatedAmplitude by animateDpAsState(
        targetValue = targetAmplitude,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "amplitudeAnim"
    )

    // Animated thumb dimensions
    val animatedThumbRadius by animateDpAsState(
        targetValue = if (isDragging) 8.5.dp else 6.5.dp,
        animationSpec = tween(durationMillis = 150),
        label = "thumbRadiusAnim"
    )

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // ── INTERACTIVE CANVAS SLIDER TRACK ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            val width = size.width.toFloat()
                            val newProgress = (offset.x / width).coerceIn(0f, 1f)
                            dragProgress = newProgress
                            onValueChange(newProgress)
                            tryAwaitRelease()
                            onValueChangeFinished()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            val width = size.width.toFloat()
                            val newProgress = (offset.x / width).coerceIn(0f, 1f)
                            dragProgress = newProgress
                            onValueChange(newProgress)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val width = size.width.toFloat()
                            val newProgress = (change.position.x / width).coerceIn(0f, 1f)
                            dragProgress = newProgress
                            onValueChange(newProgress)
                        },
                        onDragEnd = {
                            isDragging = false
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onValueChangeFinished()
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    )
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
                            val inactivePath = Path().apply {
                                moveTo(inactiveLeft, centerY - r)
                                lineTo(endX, centerY - r)
                                arcTo(
                                    rect = Rect(endX - r, centerY - r, endX + r, centerY + r),
                                    startAngleDegrees = -90f,
                                    sweepAngleDegrees = 180f,
                                    forceMoveTo = false
                                )
                                lineTo(inactiveLeft, centerY + r)
                                close()
                            }
                            drawPath(inactivePath, inactiveTrackColor)
                        }

                        // Endpoint Accent Dot at far right inside the inactive track
                        drawCircle(
                            color = activeTrackColor,
                            radius = with(density) { 2.2.dp.toPx() },
                            center = Offset(endX, centerY)
                        )

                        // Active Track (Left) with rounded left cap and straight cut on right
                        val activeRight = (thumbX - gapPx).coerceAtLeast(startX - r)
                        if (activeRight > startX) {
                            val activePath = Path().apply {
                                moveTo(activeRight, centerY - r)
                                lineTo(startX, centerY - r)
                                arcTo(
                                    rect = Rect(startX - r, centerY - r, startX + r, centerY + r),
                                    startAngleDegrees = -90f,
                                    sweepAngleDegrees = -180f,
                                    forceMoveTo = false
                                )
                                lineTo(activeRight, centerY + r)
                                close()
                            }
                            drawPath(activePath, activeTrackColor)
                        } else if (activeRight >= startX - r) {
                            // Very small progress: draw partial circle
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
                        // ── 2. WAVY STYLE (Silky smooth sinusoidal wave when playing / flat when paused + circle thumb + endpoint dot) ──
                        val startX = with(density) { 8.dp.toPx() }
                        val endX = width - with(density) { 8.dp.toPx() }
                        val trackWidth = (endX - startX).coerceAtLeast(1f)
                        val thumbX = (startX + trackWidth * effectiveProgress).coerceIn(startX, endX)
                        val strokePx = with(density) { 3.2.dp.toPx() }
                        val ampPx = with(density) { animatedAmplitude.toPx() }
                        val wavelengthPx = with(density) { 46.dp.toPx() }

                        // Inactive Track (Straight Line)
                        if (thumbX < endX) {
                            drawLine(
                                color = inactiveTrackColor,
                                start = Offset(thumbX, centerY),
                                end = Offset(endX, centerY),
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round
                            )
                        }

                        // Endpoint Accent Dot
                        drawCircle(
                            color = activeTrackColor,
                            radius = with(density) { 2.dp.toPx() },
                            center = Offset(endX, centerY)
                        )

                        // Active Track (Sine Wave or Flat Line)
                        if (thumbX > startX) {
                            if (ampPx > 0.4f) {
                                val startAngle = -wavePhase
                                val startY = centerY + sin(startAngle) * ampPx
                                val wavePath = Path().apply {
                                    moveTo(startX, startY)
                                    var x = startX + 1f
                                    val step = 1f
                                    while (x <= thumbX) {
                                        val distFromEnd = thumbX - x
                                        val taper = (distFromEnd / (wavelengthPx * 0.35f)).coerceIn(0f, 1f)
                                        val angle = ((x - startX) / wavelengthPx) * (2 * PI).toFloat() - wavePhase
                                        val y = centerY + sin(angle) * ampPx * taper
                                        lineTo(x, y)
                                        x += step
                                    }
                                    lineTo(thumbX, centerY)
                                }
                                drawPath(
                                    path = wavePath,
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

                        // Circle Thumb
                        val radiusPx = with(density) { animatedThumbRadius.toPx() }
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.20f),
                            radius = radiusPx + with(density) { 1.5.dp.toPx() },
                            center = Offset(thumbX, centerY + with(density) { 0.5.dp.toPx() })
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
                        // ── 4. SQUIGGLY STYLE (Material You squiggly waveform when playing / flat when paused + vertical capsule divider + endpoint dot) ──
                        val startX = with(density) { 8.dp.toPx() }
                        val endX = width - with(density) { 8.dp.toPx() }
                        val trackWidth = (endX - startX).coerceAtLeast(1f)
                        val thumbX = (startX + trackWidth * effectiveProgress).coerceIn(startX, endX)
                        val strokePx = with(density) { 3.2.dp.toPx() }
                        val ampPx = with(density) { animatedAmplitude.toPx() }
                        val wavelengthPx = with(density) { 24.dp.toPx() }

                        // Inactive Track (Straight Line)
                        if (thumbX < endX) {
                            drawLine(
                                color = inactiveTrackColor,
                                start = Offset(thumbX, centerY),
                                end = Offset(endX, centerY),
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round
                            )
                        }

                        // Endpoint Accent Dot
                        drawCircle(
                            color = activeTrackColor,
                            radius = with(density) { 2.dp.toPx() },
                            center = Offset(endX, centerY)
                        )

                        // Active Track (Squiggly Wave or Flat Line)
                        if (thumbX > startX) {
                            if (ampPx > 0.4f) {
                                val startAngle = -wavePhase
                                val startY = centerY + sin(startAngle) * ampPx
                                val wavePath = Path().apply {
                                    moveTo(startX, startY)
                                    var x = startX + 1f
                                    val step = 1f
                                    while (x <= thumbX) {
                                        val distFromEnd = thumbX - x
                                        val taper = (distFromEnd / (wavelengthPx * 0.35f)).coerceIn(0f, 1f)
                                        val angle = ((x - startX) / wavelengthPx) * (2 * PI).toFloat() - wavePhase
                                        val y = centerY + sin(angle) * ampPx * taper
                                        lineTo(x, y)
                                        x += step
                                    }
                                    lineTo(thumbX, centerY)
                                }
                                drawPath(
                                    path = wavePath,
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

                        // Vertical Rounded Capsule Divider Thumb
                        val pillWidthPx = with(density) { (if (isDragging) 3.5.dp else 2.8.dp).toPx() }
                        val pillHeightPx = with(density) { (if (isDragging) 28.dp else 24.dp).toPx() }
                        val pillRadiusPx = pillWidthPx / 2f
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.20f),
                            topLeft = Offset(thumbX - pillRadiusPx - 0.5f, centerY - pillHeightPx / 2f + 0.5f),
                            size = Size(pillWidthPx + 1f, pillHeightPx + 1f),
                            cornerRadius = CornerRadius(pillRadiusPx + 0.5f, pillRadiusPx + 0.5f)
                        )
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
