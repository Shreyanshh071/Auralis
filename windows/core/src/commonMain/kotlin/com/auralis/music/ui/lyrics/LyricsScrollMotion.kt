package com.auralis.music.ui.lyrics

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.exp

/** Critically damped motion: ease into a new phrase, then settle without overshoot.
 * Exact integration keeps the same duration at 60, 144 and 240 Hz. */
class LyricsScrollSpring {
    var velocity = 0f
        private set
    private var previousFrame = 0L

    fun reset() { velocity = 0f; previousFrame = 0L }

    fun frameSeconds(frame: Long): Float {
        val gap = frame - previousFrame
        // A busy render frame is not a new scroll. Returning zero on every
        // slow frame can freeze following indefinitely under GPU/CPU load.
        val seconds = if (previousFrame == 0L) 0f else
            (gap / 1_000_000_000f).coerceIn(0f, .1f)
        previousFrame = frame
        return seconds
    }

    fun step(distance: Float, seconds: Float): Float {
        val dt = seconds.coerceIn(0f, .1f)
        val omega = 18f
        val error = -distance
        val c = velocity + omega * error
        val decay = exp(-omega * dt)
        val nextError = (error + c * dt) * decay
        velocity = (velocity - omega * c * dt) * decay
        return distance + nextError
    }
}

/** One animation in the existing viewport, with no LazyColumn teleport/pre-position step. */
suspend fun LazyListState.smoothCenterLyric(index: Int, anchorRatio: Float, heights: Map<Int, Int>,
    spring: LyricsScrollSpring = LyricsScrollSpring()) {
    if (index < 0) return
    scroll {
        while (true) {
            val frame = withFrameNanos { it }
            val seconds = spring.frameSeconds(frame)
            val info = layoutInfo
            val visible = info.visibleItemsInfo
            val first = visible.firstOrNull() ?: break
            val height = info.viewportEndOffset - info.viewportStartOffset
            if (height <= 0) break
            val average = visible.map { it.size }.average().toFloat().coerceAtLeast(1f)
            fun rowHeight(i: Int) = heights[i]?.toFloat() ?: average
            val item = visible.firstOrNull { it.index == index }
            val top = item?.offset?.toFloat() ?: if (index >= first.index) {
                first.offset + (first.index until index).sumOf { rowHeight(it).toDouble() }.toFloat()
            } else first.offset - (index until first.index).sumOf { rowHeight(it).toDouble() }.toFloat()
            val center = top + (item?.size?.toFloat() ?: rowHeight(index)) / 2f
            val target = info.viewportStartOffset + height * anchorRatio
            val distance = center - target
            if (item != null && abs(distance) < .75f) { spring.reset(); break }
            // Retarget continuously as real row measurements replace estimates.
            // Never spend a final frame snapping an estimated, still-offscreen row.
            val step = spring.step(distance, seconds)
            val consumed = scrollBy(step)
            if (abs(consumed) < .1f && abs(step) >= 1f) { spring.reset(); break }
        }
    }
}
