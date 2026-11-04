package com.auralis.music.ui.lyrics

import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
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
