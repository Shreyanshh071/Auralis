package com.auralis.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

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

/**
 * The bars tick at [TickMs] instead of every vsync. They sit under the blurred dock and mini
 * player, so each redraw of the bars makes the whole screen's blur re-render: at 120 Hz that cost
 * ~13 ms a frame and made every list with a playing row lag. A meter reads fine at ~15 fps.
 */
@Composable
private fun AnimatedEqualizerBars(
    modifier: Modifier,
    color: Color
) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            elapsedMs = (System.nanoTime() - start) / 1_000_000
            delay(TickMs)
        }
    }

    // Reading the clock inside the draw lambda keeps this to a draw invalidation per tick —
    // no recomposition, no relayout.
    Canvas(modifier = modifier) {
        val t = elapsedMs
        drawEqualizerBars(
            color,
            pingPong(t, 400, 0.2f, 0.9f),
            pingPong(t, 550, 0.8f, 0.3f),
            pingPong(t, 480, 0.4f, 1.0f)
        )
    }
}

private const val TickMs = 66L

/** Linear back-and-forth between [from] and [to], [halfMs] each way. */
private fun pingPong(t: Long, halfMs: Int, from: Float, to: Float): Float {
    val phase = (t % (2L * halfMs)).toFloat() / halfMs
    val f = if (phase <= 1f) phase else 2f - phase
    return from + (to - from) * f
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
