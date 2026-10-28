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
import androidx.compose.animation.core.CubicBezierEasing

/** CSS dotGroup entry and pre-hidden exit from Spicy's Mixed.css. */
object LyricsDotsTransition {
    private val enter = CubicBezierEasing(.25f, .1f, .25f, 1f)
    private val exitTimes = floatArrayOf(0f, .094f, .18f, .433f, .514f, .559f, .60f, .639f, .676f, .723f, .767f, .85f, .927f, 1f)
    private val exitValues = floatArrayOf(0f, -.006f, -.029f, -.157f, -.185f, -.189f, -.182f, -.163f, -.133f, -.074f, .006f, .238f, .566f, 1f)
    fun scale(elapsedMs: Long, remainingMs: Long): Float {
        if (elapsedMs < 0 || remainingMs <= 100) return 0f
        // Upstream pre-hides 500 ms before the next vocal, then runs a 400 ms exit.
        if (remainingMs >= 500) return enter.transform((elapsedMs / 300f).coerceIn(0f, 1f))
        val t = (500f - remainingMs) / 400f
        val i = (1 until exitTimes.size).first { t <= exitTimes[it] }
        val p = (t - exitTimes[i - 1]) / (exitTimes[i] - exitTimes[i - 1])
        return 1f - (exitValues[i - 1] + (exitValues[i] - exitValues[i - 1]) * p)
    }
    fun alpha(elapsedMs: Long, remainingMs: Long): Float =
        if (remainingMs <= 0) 0f else (elapsedMs / 140f).coerceIn(0f, 1f)
}

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
    color: Color = Color.White,
    compact: Boolean = false
) {
    val motion = remember { DotMotion() }
    // Keep the existing row's breathing room while making the visible dots subtly smaller.
    val diameter = dotSize * .92f
    val gap = diameter * .75f
    Canvas(modifier.size(width = dotSize * 3 + gap * 2 + 8.dp, height = dotSize * if (compact) 1.5f else 2.5f)) {
        // The three source dot intervals finish 550 ms early, ahead of pre-hide.
        val duration = elapsedMs + remainingMs
        val dotProgress = if (duration > 550L) (elapsedMs.toFloat() / (duration - 550L)).coerceIn(0f, 1f) else progress
        motion.update(dotProgress, elapsedMs, remainingMs)
        val d = diameter.toPx()
        val g = gap.toPx()
        val startX = (size.width - (d * 3 + g * 2)) / 2f + d / 2f
        val groupAlpha = LyricsDotsTransition.alpha(elapsedMs, remainingMs)
        scale(LyricsDotsTransition.scale(elapsedMs, remainingMs)) {
            for (i in 0 until 3) {
                // Compact rows remove space below the dots while retaining their lift room above.
                val centre = Offset(startX + i * (d + g), (if (compact) d else size.height / 2f) + motion.lifts[i].value * d * 2.5f)
                drawCircle(color.copy(alpha = color.alpha * groupAlpha * motion.opacities[i].value.coerceIn(0f, 1f)),
                    radius = d / 2f * motion.scales[i].value.coerceAtLeast(0f), center = centre)
            }
        }
    }
}
