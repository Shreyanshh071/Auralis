package com.auralis.music.ui.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI

/** Natural cubic spline through three animation keys. */
private class DotCurve(private val middle: Float, private val start: Float, private val peak: Float, private val end: Float) {
    private val bend = 3f * ((end - peak) / (1f - middle) - (peak - start) / middle)
    fun at(t: Float): Float {
        if (t <= 0f) return start
        if (t >= 1f) return end
        val first = t <= middle
        val width = if (first) middle else 1f - middle
        val a = if (first) (middle - t) / width else (1f - t) / width
        val b = 1f - a
        val leftBend = if (first) 0f else bend
        val rightBend = if (first) bend else 0f
        return a * (if (first) start else peak) + b * (if (first) peak else end) +
            ((a * a * a - a) * leftBend + (b * b * b - b) * rightBend) * width * width / 6f
    }
}

private val dotOpacity = DotCurve(.6f, .35f, 1f, 1f)
private val dotScale = DotCurve(.7f, .75f, 1.05f, 1f)
private val dotLift = DotCurve(.9f, 0f, -.12f, 0f)

private class DotSpring(frequency: Float, private val damping: Float) {
    private val omega = 2f * PI.toFloat() * frequency
    var value = 0f
        private set
    private var velocity = 0f
    fun step(target: Float, seconds: Float, reset: Boolean) {
        if (reset) { value = target; velocity = 0f; return }
        // Small integration steps keep the springs stable at low frame rates too.
        val steps = kotlin.math.ceil(seconds * 240f).toInt().coerceAtLeast(1)
        val dt = seconds / steps
        repeat(steps) {
            velocity += (omega * omega * (target - value) - 2f * damping * omega * velocity) * dt
            value += velocity * dt
        }
    }
}

/** Playback-clock springs: pause freezes motion; seeking resets instead of replaying it. */
private class DotMotion {
    val scales = Array(3) { DotSpring(.7f, .6f) }
    val lifts = Array(3) { DotSpring(1.25f, .4f) }
    val opacities = Array(3) { DotSpring(1f, .5f) }
    val group = DotSpring(5f, .7f)
    private var previous: Long? = null
    fun update(progress: Float, elapsedMs: Long, remainingMs: Long) {
        val delta = previous?.let { elapsedMs - it }
        val reset = delta == null || delta < 0L || delta > 250L
        val seconds = (delta ?: 0L).coerceIn(0L, 250L) / 1000f
        previous = elapsedMs
        for (i in 0 until 3) {
            val q = (progress * 3f - i).coerceIn(0f, 1f)
            scales[i].step(dotScale.at(q), seconds, reset)
            lifts[i].step(dotLift.at(q), seconds, reset)
            opacities[i].step(dotOpacity.at(q).coerceIn(.35f, 1f), seconds, reset)
        }
        val groupTarget = when {
            remainingMs <= 0L -> 0f
            remainingMs < 75L -> 1.15f * remainingMs / 75f
            remainingMs < 200L -> 1f + .15f * (200L - remainingMs) / 125f
            elapsedMs < 200L -> 1.05f * elapsedMs.coerceAtLeast(0L) / 200f
            else -> 1f
        }
        group.step(groupTarget, seconds, reset)
    }
}

/** Three clean, solid interlude dots with spline curves and independent spring motion. */
@Composable
fun LyricsDots(
    progress: Float,
    elapsedMs: Long,
    remainingMs: Long,
    modifier: Modifier = Modifier,
    dotSize: Dp = 18.dp,
    color: Color = Color.White
) {
    val motion = remember { DotMotion() }
    val gap = dotSize * .75f
    Canvas(modifier.size(width = dotSize * 3 + gap * 2 + 8.dp, height = dotSize * 2.5f)) {
        motion.update(progress, elapsedMs, remainingMs)
        val d = dotSize.toPx()
        val g = gap.toPx()
        val startX = (size.width - (d * 3 + g * 2)) / 2f + d / 2f
        val groupAlpha = (elapsedMs / 500f).coerceIn(0f, 1f) * (remainingMs / 75f).coerceIn(0f, 1f)
        scale(motion.group.value.coerceAtLeast(0f)) {
            for (i in 0 until 3) {
                val centre = Offset(startX + i * (d + g), size.height / 2f + motion.lifts[i].value * d * 2.5f)
                drawCircle(color.copy(alpha = color.alpha * groupAlpha * motion.opacities[i].value.coerceIn(0f, 1f)),
                    radius = d / 2f * motion.scales[i].value.coerceAtLeast(0f), center = centre)
            }
        }
    }
}
