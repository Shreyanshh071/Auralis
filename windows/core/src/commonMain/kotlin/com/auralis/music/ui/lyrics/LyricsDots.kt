package com.auralis.music.ui.lyrics

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Piecewise-linear curve through (time, value) points, as Spicy Lyrics' splines. */
private fun curve(t: Float, vararg points: Pair<Float, Float>): Float {
    if (t <= points.first().first) return points.first().second
    for (i in 1 until points.size) {
        val (t1, v1) = points[i]
        if (t <= t1) {
            val (t0, v0) = points[i - 1]
            return v0 + (v1 - v0) * ((t - t0) / (t1 - t0))
        }
    }
    return points.last().second
}

/**
 * Spicy Lyrics' interlude dots: three dots that each light up over a third of the gap
 * (opacity 0.35 to 1, scale 0.75 to 1.05 then 1, a small lift and glow). The group pops in over
 * 0.2s, swells to 1.15 just before the words return, then collapses.
 */
@Composable
fun LyricsDots(
    progress: Float,
    elapsedMs: Long,
    remainingMs: Long,
    modifier: Modifier = Modifier,
    dotSize: Dp = 11.dp,
    color: Color = Color.White
) {
    val groupTarget = when {
        remainingMs <= 0L -> 0f
        remainingMs <= 75L -> 1.15f
        elapsedMs < 200L -> 1.05f * (elapsedMs / 200f)
        else -> 1f + 0.05f * (1f - ((elapsedMs - 200L).coerceAtMost(400L) / 400f))
    }
    val groupScale by animateFloatAsState(groupTarget, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "dotGroupScale")
    val groupAlpha = when {
        remainingMs <= 0L -> 0f
        else -> (elapsedMs / 500f).coerceIn(0f, 1f)
    }
    val gap = dotSize * 0.75f
    Canvas(modifier.size(width = dotSize * 3 + gap * 2 + 8.dp, height = dotSize * 2.2f).graphicsLayer {
        scaleX = groupScale; scaleY = groupScale; alpha = groupAlpha
    }) {
        val d = dotSize.toPx(); val g = gap.toPx()
        val startX = (size.width - (d * 3 + g * 2)) / 2f + d / 2f
        for (i in 0 until 3) {
            val q = (progress * 3f - i).coerceIn(0f, 1f)
            val opacity = curve(q, 0f to .35f, .6f to 1f, 1f to 1f)
            val scale = curve(q, 0f to .75f, .7f to 1.05f, 1f to 1f)
            val lift = curve(q, 0f to 0f, .9f to -.12f, 1f to 0f) * d * 1.6f
            val glow = curve(q, 0f to 0f, .6f to 1f, 1f to 1f)
            val centre = Offset(startX + i * (d + g), size.height / 2f + lift)
            if (glow > 0f) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .35f * glow), Color.Transparent), centre, d * 1.4f),
                radius = d * 1.4f, center = centre)
            drawCircle(color.copy(alpha = opacity), radius = d / 2f * scale, center = centre)
        }
    }
}
