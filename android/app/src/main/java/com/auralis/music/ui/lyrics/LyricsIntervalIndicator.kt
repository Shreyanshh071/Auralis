package com.auralis.music.ui.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.auralis.music.ui.lyrics.wavy.CircularWavyProgressIndicator
import com.auralis.music.ui.lyrics.wavy.WavyProgressIndicator
import com.auralis.music.ui.lyrics.wavy.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The instrumental break after [line], as the (start, end) the interval circle should span, or
 * null when the gap before [nextStart] isn't a genuine break.
 *
 * The circle appears as soon as the singing stops and fills across the whole break, rather than
 * only counting down the last few seconds.
 * - Word-synced lines know when the singing ends ([LyricLine.effectiveEndTime]); a break needs
 *   >= 5s of silence after it.
 * - Line-synced lines have no end time, so a break needs >= 10s between line starts, and the end
 *   of the singing is estimated from the line's word count (display only, never lyric timing).
 */
internal fun instrumentalBreakWindow(line: com.auralis.music.domain.model.LyricLine, nextStart: Long): Pair<Long, Long>? {
    val knownEnd = line.effectiveEndTime
    val isGenuineBreak = if (knownEnd != null) {
        nextStart - knownEnd >= 5_000L
    } else {
        nextStart - line.time >= 10_000L
    }
    if (!isGenuineBreak) return null
    val singingEnd = knownEnd ?: (line.time + estimatedSungLengthMs(line.text))
    // Always leave at least a second of visible countdown before the next line.
    val start = singingEnd.coerceIn(line.time, nextStart - 1_000L)
    return if (nextStart > start) start to nextStart else null
}

/** Rough sung length of a line with no end time: ~0.45s a word, 2.5–7s. */
internal fun estimatedSungLengthMs(text: String): Long {
    val words = text.split(Regex("""\s+""")).count { it.isNotBlank() }
    return (words * 450L + 800L).coerceIn(2_500L, 7_000L)
}

/** Alias providing clear semantic naming while maintaining backwards compatibility */
@Composable
fun CircularIntervalIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.18f),
    strokeWidth: Dp = WavyProgressIndicatorDefaults.StandardStrokeWidth,
    indicatorSize: Dp = WavyProgressIndicatorDefaults.StandardIndicatorSize
) {
    val strokeWidthPx = with(LocalDensity.current) { strokeWidth.coerceAtLeast(1.dp).toPx() }
    val indicatorStroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
    CircularWavyProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.size(indicatorSize),
        color = color,
        trackColor = trackColor,
        stroke = indicatorStroke,
        trackStroke = indicatorStroke,
        gapSize = strokeWidth
    )
}

/** Backwards-compatible WavyProgressIndicator function */
@Composable
fun WavyProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = WavyProgressIndicatorDefaults.indicatorColor,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor,
    strokeWidth: Dp = WavyProgressIndicatorDefaults.StandardStrokeWidth,
    gapSize: Dp = WavyProgressIndicatorDefaults.StandardTrackGapSize,
    @Suppress("UNUSED_PARAMETER") lobes: Int = 7,
    @Suppress("UNUSED_PARAMETER") amplitudeRatio: Float = 0.055f,
    amplitude: (progress: Float) -> Float = WavyProgressIndicatorDefaults.indicatorAmplitude,
    wavelength: Dp = WavyProgressIndicatorDefaults.CircularWavelength,
    waveSpeed: Dp = wavelength,
) {
    com.auralis.music.ui.lyrics.wavy.WavyProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeWidth = strokeWidth,
        gapSize = gapSize,
        lobes = lobes,
        amplitudeRatio = amplitudeRatio,
        amplitude = amplitude,
        wavelength = wavelength,
        waveSpeed = waveSpeed,
    )
}

/**
 * Modern lyrics instrumental music filler indicator.
 *
 * Displays a clean, elegant fluid progress ring that fills smoothly clockwise
 * as the instrumental gap between lyrics progresses.
 *
 * [isMetroLyrics] controls whether the indicator uses the thick MetroLyrics styling
 * (40dp size, 5.0dp stroke width) or the classic standard lyrics animation thinness
 * (34dp size, 3.0dp stroke width) used for Auralis Default, Apple Music, Fade, Glow, etc.
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
    isMetroLyrics: Boolean = false,
    indicatorSize: Dp = if (isMetroLyrics) WavyProgressIndicatorDefaults.MetroIndicatorSize else WavyProgressIndicatorDefaults.StandardIndicatorSize,
    strokeWidth: Dp = if (isMetroLyrics) WavyProgressIndicatorDefaults.MetroStrokeWidth else WavyProgressIndicatorDefaults.StandardStrokeWidth,
    gapSize: Dp = if (isMetroLyrics) WavyProgressIndicatorDefaults.MetroTrackGapSize else WavyProgressIndicatorDefaults.StandardTrackGapSize,
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

    val targetHeight = (if (isMetroLyrics || indicatorSize.value >= 40f) 48.dp else 44.dp) * heightAnim.value
    val clickableSize = if (isMetroLyrics || indicatorSize.value >= 40f) 44.dp else 40.dp

    if (heightAnim.value > 0.01f) {
        val density = LocalDensity.current
        val strokeWidthPx = with(density) { strokeWidth.coerceAtLeast(1.dp).toPx() }
        val stroke = remember(strokeWidthPx) { Stroke(width = strokeWidthPx, cap = StrokeCap.Round) }

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
                    .size(clickableSize)
                    .clip(CircleShape)
                    .then(
                        if (onSkip != null) {
                            Modifier.clickable(onClick = onSkip)
                        } else Modifier
                    )
            ) {
                CircularWavyProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.size(indicatorSize),
                    color = color.copy(alpha = 0.95f),
                    trackColor = color.copy(alpha = 0.18f),
                    stroke = stroke,
                    trackStroke = stroke,
                    gapSize = gapSize,
                    amplitude = WavyProgressIndicatorDefaults.indicatorAmplitude,
                    wavelength = WavyProgressIndicatorDefaults.CircularWavelength,
                    waveSpeed = WavyProgressIndicatorDefaults.CircularWavelength,
                )
            }
        }
    }
}
