package com.auralis.music.windows.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

// Screen-pixel ceiling matching the requested reference size, even on larger monitors.
internal val MiniPopupMaximum = IntSize(1280, 720)

internal enum class MiniPopupEdge(val left: Boolean = false, val top: Boolean = false,
    val right: Boolean = false, val bottom: Boolean = false) {
    LEFT(left = true), TOP(top = true), RIGHT(right = true), BOTTOM(bottom = true),
    TOP_LEFT(left = true, top = true), TOP_RIGHT(top = true, right = true),
    BOTTOM_LEFT(left = true, bottom = true), BOTTOM_RIGHT(right = true, bottom = true)
}

/** Keep the opposite edges fixed. Every event is measured from the initial rectangle,
 * never from the popup's moving local origin or an accumulated rounded delta. */
internal fun resizeMiniPopup(start: IntRect, edge: MiniPopupEdge, delta: Offset,
    limits: IntRect, minimum: Int): IntRect {
    val maxWidth = minOf(MiniPopupMaximum.width, limits.width).coerceAtLeast(1)
    val maxHeight = minOf(MiniPopupMaximum.height, limits.height).coerceAtLeast(1)
    val minWidth = minimum.coerceIn(1, maxWidth)
    val minHeight = minimum.coerceIn(1, maxHeight)
    val dx = delta.x.roundToInt(); val dy = delta.y.roundToInt()
    return IntRect(
        if (edge.left) (start.left + dx).coerceIn(maxOf(limits.left, start.right - maxWidth), start.right - minWidth) else start.left,
        if (edge.top) (start.top + dy).coerceIn(maxOf(limits.top, start.bottom - maxHeight), start.bottom - minHeight) else start.top,
        if (edge.right) (start.right + dx).coerceIn(start.left + minWidth, minOf(limits.right, start.left + maxWidth)) else start.right,
        if (edge.bottom) (start.bottom + dy).coerceIn(start.top + minHeight, minOf(limits.bottom, start.top + maxHeight)) else start.bottom
    )
}

internal fun fitMiniPopup(bounds: IntRect, limits: IntRect, minimum: Int): IntRect {
    val maxWidth = minOf(MiniPopupMaximum.width, limits.width)
    val maxHeight = minOf(MiniPopupMaximum.height, limits.height)
    val width = bounds.width.coerceIn(minimum.coerceIn(1, maxWidth), maxWidth)
    val height = bounds.height.coerceIn(minimum.coerceIn(1, maxHeight), maxHeight)
    val left = bounds.left.coerceIn(limits.left, limits.right - width)
    val top = bounds.top.coerceIn(limits.top, limits.bottom - height)
    return IntRect(left, top, left + width, top + height)
}
