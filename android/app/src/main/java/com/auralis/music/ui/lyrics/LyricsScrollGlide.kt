package com.auralis.music.ui.lyrics

import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.min

/**
 * The curve the lyrics follow: a third-order critically damped response, an S-curve that starts
 * from rest, peaks mid-way and eases out. A spring leaves at full acceleration, which still reads
 * as a lurch on a 2-3 line move. Speed and acceleration both carry over a new target, so a line
 * arriving mid-glide bends the motion instead of restarting it.
 */
internal class LyricsGlideMotion(private val omega: Float = DEFAULT_OMEGA) {
    var velocity = 0f
        private set
    private var acceleration = 0f

    /** Advances by [seconds] with [remaining] px left to travel; returns the px to move now. */
    fun advance(remaining: Float, seconds: Float): Float {
        var moved = 0f
        var left = seconds
        while (left > 0f) {
            val dt = min(left, MAX_STEP_SECONDS)
            val jerk = omega * omega * omega * (remaining - moved) -
                3f * omega * omega * velocity - 3f * omega * acceleration
            acceleration += jerk * dt
            velocity += acceleration * dt
            moved += velocity * dt
            left -= dt
        }
        return moved
    }

    fun isSettled(remaining: Float) = abs(remaining) < SETTLED_PX && abs(velocity) < SETTLED_SPEED

    fun reset() {
        velocity = 0f
        acceleration = 0f
    }

    private companion object {
        const val DEFAULT_OMEGA = 9f // About 0.7s to cover 95% of any distance.
        const val MAX_STEP_SECONDS = .004f
        const val SETTLED_PX = .5f
        const val SETTLED_SPEED = 12f
    }
}

/** Moves the list along [LyricsGlideMotion], keeping the motion across cancelled glides. */
internal class LyricsScrollGlide {
    private val motion = LyricsGlideMotion()
    private var lastFrameAt = 0L

    suspend fun glide(list: LazyListState, distance: Float) {
        // Only a glide cut short a moment ago is still moving; a user drag ends that one.
        if (SystemClock.uptimeMillis() - lastFrameAt >= RETARGET_WINDOW_MS) motion.reset()
        var remaining = distance
        list.scroll {
            var previous = withFrameNanos { it }
            while (!motion.isSettled(remaining)) {
                val now = withFrameNanos { it }
                val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(MAX_FRAME_SECONDS)
                previous = now
                val step = motion.advance(remaining, seconds)
                val consumed = scrollBy(step)
                remaining -= step
                lastFrameAt = SystemClock.uptimeMillis()
                if (step != 0f && consumed == 0f) break // The list's end.
            }
            scrollBy(remaining)
        }
        motion.reset()
    }

    private companion object {
        const val RETARGET_WINDOW_MS = 100L
        const val MAX_FRAME_SECONDS = .05f
    }
}

/**
 * The elastic "settle back" when the list returns to the sung line after the user scrolled away.
 *
 * The list itself rides a soft, slightly underdamped spring. On top of that every visible line has
 * its own spring that the scroll drags along: a line lags behind by however far the list just moved,
 * then catches up and overshoots a little. Lines further from the sung line have softer springs, so
 * they trail more and the block ripples into place instead of moving as one rigid sheet.
 */
internal class LyricsSettleWobble {
    /**
     * How springy one move is. [SETTLE] is the playful return after a manual scroll; [FOLLOW] is the
     * calmer cascade used line-to-line while the song plays, so it never distracts from the words.
     */
    data class Profile(
        val listStiffness: Float,
        val listDamping: Float,
        val lineStiffness: Float,
        val lineSoftening: Float,
        val lineDamping: Float
    )

    companion object {
        // The list itself is critically damped (1.0): it lands on the sung line without sailing
        // past it. The wobble comes only from the lines trailing on their own springs.
        val SETTLE = Profile(listStiffness = 80f, listDamping = 1f, lineStiffness = 260f, lineSoftening = .45f, lineDamping = .55f)
        val FOLLOW = Profile(listStiffness = 120f, listDamping = 1f, lineStiffness = 300f, lineSoftening = .38f, lineDamping = .72f)
        private const val STEP_SECONDS = .004f
        private const val MAX_FRAME_SECONDS = .05f
        private const val SETTLED_PX = .4f
        private const val SETTLED_SPEED = 8f
    }

    private var profile = SETTLE

    /** Visual vertical offset (px) per list item index. Read it in the draw phase (graphicsLayer). */
    val offsets = androidx.compose.runtime.mutableStateMapOf<Int, Float>()
    private val velocities = HashMap<Int, Float>()
    private var focusIndex = 0
    private var maxOffsetPx = 0f

    /**
     * Springs the list onto the sung line. [remaining] is asked every frame for the px still to go:
     * exact once the line is on screen, an estimate while it isn't. Re-aiming each frame means a
     * wrong first guess (tall or translated lines) bends the motion instead of overshooting and
     * coming back. Visible lines are re-read every frame, so lines that scroll in mid-move join
     * the ripple. [targetIndex] is the sung line, the stiffest point of the ripple.
     */
    suspend fun settle(
        list: LazyListState,
        targetIndex: Int,
        maxOffsetPx: Float,
        profile: Profile = SETTLE,
        remaining: () -> Float
    ) {
        this.profile = profile
        focusIndex = targetIndex
        this.maxOffsetPx = maxOffsetPx
        val stiffness = profile.listStiffness
        val damping = 2f * profile.listDamping * kotlin.math.sqrt(stiffness)
        list.scroll {
            var velocity = 0f
            var previous = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                var left = ((now - previous) / 1_000_000_000f).coerceAtMost(MAX_FRAME_SECONDS)
                previous = now
                var step = 0f
                val target = remaining()
                var toGo = target
                while (left > 0f) {
                    val dt = min(left, STEP_SECONDS)
                    velocity += (stiffness * toGo - damping * velocity) * dt
                    // Never faster than the spring could still brake from: when the real target
                    // turns out nearer than the estimate, the list eases in instead of flying past.
                    val brake = kotlin.math.sqrt(stiffness) * abs(toGo) + 40f
                    velocity = velocity.coerceIn(-brake, brake)
                    val move = velocity * dt
                    step += move
                    toGo -= move
                    left -= dt
                }
                val consumed = scrollBy(step)
                if (consumed != 0f) drag(consumed, list)
                if (abs(target - consumed) < SETTLED_PX && abs(velocity) < SETTLED_SPEED * 4) {
                    scrollBy(remaining())
                    break
                }
                if (step != 0f && consumed == 0f) break // Hit the list's end.
            }
        }
    }

    private fun drag(delta: Float, list: LazyListState) {
        // The list moved content up by delta; holding each line back by the same amount makes it lag.
        for (item in list.layoutInfo.visibleItemsInfo) {
            offsets[item.index] = ((offsets[item.index] ?: 0f) + delta).coerceIn(-maxOffsetPx, maxOffsetPx)
        }
    }

    /** Runs the per-line springs for as long as any line is still off its rest position. */
    suspend fun animateLines() {
        while (true) {
            androidx.compose.runtime.snapshotFlow { offsets.isNotEmpty() }.first { it }
            var previous = withFrameNanos { it }
            while (offsets.isNotEmpty()) {
                val now = withFrameNanos { it }
                var left = ((now - previous) / 1_000_000_000f).coerceAtMost(MAX_FRAME_SECONDS)
                previous = now
                while (left > 0f) {
                    val dt = min(left, STEP_SECONDS)
                    step(dt)
                    left -= dt
                }
            }
        }
    }

    private fun step(dt: Float) {
        val settled = ArrayList<Int>()
        for ((index, offset) in offsets.entries.toList()) {
            val distance = abs(index - focusIndex).toFloat()
            val stiffness = profile.lineStiffness / (1f + profile.lineSoftening * distance)
            val damping = 2f * profile.lineDamping * kotlin.math.sqrt(stiffness)
            var velocity = velocities[index] ?: 0f
            velocity += (-stiffness * offset - damping * velocity) * dt
            val next = offset + velocity * dt
            if (abs(next) < SETTLED_PX && abs(velocity) < SETTLED_SPEED) {
                settled += index
            } else {
                offsets[index] = next
                velocities[index] = velocity
            }
        }
        for (index in settled) {
            offsets.remove(index)
            velocities.remove(index)
        }
    }

    fun clear() {
        offsets.clear()
        velocities.clear()
    }

}

/**
 * ViVi-style staggered lyric motion (vivi-music MetroLyrics, GPL-3.0, reimplemented).
 *
 * The list glides to the sung line over [durationMs] with FastOutSlowIn easing, but each line
 * starts its own glide a little later the further it sits from the sung line (20 ms per line,
 * at most 200 ms). Lines therefore ripple into place one after another instead of moving as one
 * rigid sheet. Every line's lag is a pure function of time, so nothing accumulates or drifts.
 */
internal class LyricsStaggerScroll {
    /**
     * Visual vertical offset (px) per list item index. Read it in the draw phase (graphicsLayer).
     * Replaced whole once per frame: one state write instead of one per line, each of which
     * re-notified every line reading it.
     */
    private val state = androidx.compose.runtime.mutableStateOf<Map<Int, Float>>(emptyMap())
    val offsets: Map<Int, Float> get() = state.value

    /**
     * Scrolls the list onto [targetIndex]. [remaining] gives the px still to go each frame: exact
     * once the line is on screen, an estimate before, so a wrong guess bends the path instead of
     * overshooting.
     */
    suspend fun scroll(
        list: LazyListState,
        targetIndex: Int,
        durationMs: Int = DURATION_MS,
        maxRipplePx: Float = Float.MAX_VALUE,
        remaining: () -> Float
    ) {
        val easing = androidx.compose.animation.core.FastOutSlowInEasing
        // A glide cut short leaves lines mid-lag; the new glide eases those out instead of jumping.
        val carried = offsets.toMap()
        list.scroll {
                var scrolled = 0f
                val start = withFrameNanos { it }
                val totalMs = durationMs + MAX_STAGGER_MS
                while (true) {
                    val now = withFrameNanos { it }
                    val t = (now - start) / 1_000_000f
                    val total = scrolled + remaining()
                    val p = easing.transform((t / durationMs).coerceIn(0f, 1f))
                    val consumed = scrollBy(total * p - scrolled)
                    scrolled += consumed
                    // The ripple is sized like a one-line step however far the list travels, so a
                    // long return doesn't tear lines hundreds of px apart.
                    val ripple = total.coerceIn(-maxRipplePx, maxRipplePx)
                    val frame = HashMap<Int, Float>()
                    for (item in list.layoutInfo.visibleItemsInfo) {
                        // Only lines trailing the motion lag (below the sung line when the lyrics
                        // move up, above it when they move down). Lines ahead move with it, so gaps
                        // can only widen and close again: lines never overlap or squash together.
                        val trailing = if (total >= 0f) item.index > targetIndex else item.index < targetIndex
                        val delay = if (!trailing) 0
                            else (kotlin.math.abs(item.index - targetIndex) * STAGGER_PER_LINE_MS).coerceAtMost(MAX_STAGGER_MS)
                        val pLine = easing.transform(((t - delay) / durationMs).coerceIn(0f, 1f))
                        // The list has moved total*p; this line should only have moved ripple*pLine of it.
                        val lag = ripple * (p - pLine) + (carried[item.index] ?: 0f) * (1f - pLine)
                        if (kotlin.math.abs(lag) >= .5f) frame[item.index] = lag
                    }
                    state.value = frame
                    if (t >= totalMs) break
                }
                val left = remaining()
                if (kotlin.math.abs(left) > .5f) scrollBy(left)
            }
        state.value = emptyMap()
    }

    /** Drops any lag at once (a user drag takes over the list). */
    fun clear() {
        state.value = emptyMap()
    }

    private companion object {
        const val DURATION_MS = 750
        const val STAGGER_PER_LINE_MS = 20
        const val MAX_STAGGER_MS = 200
    }
}
