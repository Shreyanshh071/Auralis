package com.auralis.music.windows.player

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.awt.event.MouseWheelEvent
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Wheel and touchpad travel in display pixels, proportional to the input. Every delta adds its
 * full distance (no per-event or per-burst clamp) and the list eases toward the unspent total, so
 * speed follows how much input arrived and how fast. Precision-touchpad deltas are fractional and
 * already carry the OS's own motion and inertia, so they ease over a much shorter window and are
 * never accelerated. Notches from a fast-spun mouse wheel travel progressively farther each.
 */
@Composable
internal fun lyricsWheelScroll(state: LazyListState, onUserScroll: () -> Unit): Modifier {
    val density = LocalDensity.current
    val unit = with(density) { 48.dp.toPx() }
    val callback by rememberUpdatedState(onUserScroll)
    val motion = remember(state) { LyricsWheelMotion() }
    val requests = remember(state) { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(state) {
        for (request in requests) {
            try {
                state.scroll(MutatePriority.UserInput) {
                    var lastFrame = 0L
                    while (abs(motion.pending) > .25f) {
                        withFrameNanos { frame ->
                            val seconds = if (lastFrame == 0L) 1f / 60f else
                                ((frame - lastFrame) / 1_000_000_000f).coerceIn(1f / 480f, 1f / 20f)
                            lastFrame = frame
                            val ease = if (motion.precise) PRECISE_EASE_SECONDS else NOTCH_EASE_SECONDS
                            var step = motion.pending * (1f - exp(-seconds / ease))
                            // Land the tail instead of creeping through sub-pixel frames.
                            if (abs(motion.pending - step) < .5f) step = motion.pending
                            val consumed = scrollBy(step)
                            motion.pending -= consumed
                            // Discard travel at a boundary so reversing the wheel responds immediately.
                            if (abs(consumed - step) > .5f ||
                                (step > 0f && !state.canScrollForward) ||
                                (step < 0f && !state.canScrollBackward)) motion.pending = 0f
                        }
                    }
                    motion.pending = 0f
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                // A drag/seek can interrupt a wheel gesture. Keep the input collector alive
                // so the next wheel event still works, without resuming the old momentum.
                motion.pending = 0f
            }
        }
    }
    return Modifier.pointerInput(state, unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll || event.changes.any { it.isConsumed }) continue
                val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                if (!delta.isFinite() || delta == 0f) continue
                callback()
                val time = event.changes.first().uptimeMillis
                val sinceLast = time - motion.lastInput
                val sameGesture = motion.lastInput != 0L && sinceLast in 0..GESTURE_GAP_MS
                val reversed = motion.pending != 0f && sign(delta) != sign(motion.pending) ||
                    sameGesture && sign(delta) != motion.direction
                motion.lastInput = time
                motion.direction = sign(delta)
                // A touchpad's fractional deltas mark the whole gesture as precise, including
                // any whole-number deltas inside it.
                val fractional = abs(delta - delta.roundToInt()) > .001f
                motion.precise = fractional || (sameGesture && !reversed && motion.precise)
                if (reversed) motion.pending = 0f
                motion.boost = if (motion.precise || !sameGesture || reversed) 1f else {
                    val rate = ((ACCELERATION_SLOW_MS - sinceLast).toFloat() /
                        (ACCELERATION_SLOW_MS - ACCELERATION_FAST_MS)).coerceIn(0f, 1f)
                    (motion.boost + (1f + rate * (MAX_NOTCH_BOOST - 1f))) / 2f
                }
                val wheel = event.nativeEvent as? MouseWheelEvent
                val travel = if (wheel?.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL) {
                    delta * state.layoutInfo.viewportSize.height * .85f
                } else {
                    // Honour Windows' "lines per notch"; 48dp is the default three lines.
                    val lines = wheel?.scrollAmount?.takeIf { it > 0 } ?: 3
                    delta * unit * (lines / 3f) * motion.boost
                }
                motion.pending += travel
                event.changes.forEach { it.consume() }
                if (abs(motion.pending) > .25f) requests.trySend(Unit)
            }
        }
    }
}

/** Notches ease over ~85ms; touchpad input already moves smoothly, so it follows within ~30ms. */
private const val NOTCH_EASE_SECONDS = .085f
private const val PRECISE_EASE_SECONDS = .03f
private const val GESTURE_GAP_MS = 250L
/** Notches slower than this apart travel 1x; at [ACCELERATION_FAST_MS] apart they reach the full boost. */
private const val ACCELERATION_SLOW_MS = 110L
private const val ACCELERATION_FAST_MS = 20L
private const val MAX_NOTCH_BOOST = 2.5f

private class LyricsWheelMotion(
    var pending: Float = 0f,
    var precise: Boolean = false,
    var boost: Float = 1f,
    var direction: Float = 0f,
    var lastInput: Long = 0L,
)
