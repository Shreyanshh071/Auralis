package com.auralis.music.ui.components

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos

/** Scroll to the first item without the pauses caused by long lazy-list item jumps. */
private suspend fun ScrollableState.scrollUpContinuously(
    atTop: () -> Boolean,
    viewportHeight: () -> Int,
    remainingDistance: () -> Float
) {
    if (atTop()) return
    val viewport = viewportHeight().coerceAtLeast(1).toFloat()
    var velocity = (remainingDistance() / 0.65f).coerceIn(viewport * 2.2f, viewport * 8f)
    scroll(MutatePriority.Default) {
        var lastFrame = withFrameNanos { it }
        var stalledFrames = 0
        while (!atTop()) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceIn(1f / 120f, 0.05f)
            lastFrame = frame
            val targetVelocity = (remainingDistance() * 4f).coerceIn(viewport * 2.2f, viewport * 8f)
            velocity += (targetVelocity - velocity) * 0.18f
            if (scrollBy(-velocity * seconds) >= -0.5f) {
                if (++stalledFrames >= 3) break
            } else {
                stalledFrames = 0
            }
        }
    }
}

suspend fun LazyListState.smoothScrollToTop() {
    scrollUpContinuously(
        atTop = { firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0 },
        viewportHeight = { layoutInfo.viewportSize.height },
        remainingDistance = {
            val averageHeight = layoutInfo.visibleItemsInfo.map { it.size }.average()
                .takeIf { !it.isNaN() }?.toFloat() ?: layoutInfo.viewportSize.height.toFloat()
            firstVisibleItemIndex * averageHeight + firstVisibleItemScrollOffset
        }
    )
}

suspend fun LazyGridState.smoothScrollToTop() {
    scrollUpContinuously(
        atTop = { firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0 },
        viewportHeight = { layoutInfo.viewportSize.height },
        remainingDistance = {
            val visible = layoutInfo.visibleItemsInfo
            val averageHeight = visible.map { it.size.height }.average()
                .takeIf { !it.isNaN() }?.toFloat() ?: layoutInfo.viewportSize.height.toFloat()
            val firstRow = visible.minOfOrNull { it.row }?.coerceAtLeast(0) ?: 0
            firstRow * averageHeight + firstVisibleItemScrollOffset
        }
    )
}
