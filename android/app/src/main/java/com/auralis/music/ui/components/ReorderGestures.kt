package com.auralis.music.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/**
 * Detects reorder drag gestures on a handle.
 *
 * Supports BOTH:
 * 1. Immediate drag when the user swipes/moves the handle past touch slop.
 * 2. Press-and-hold (long-press timeout) when the user rests their thumb on the handle.
 *
 * Consumes taps/touches so that tapping the drag handle does not inadvertently
 * trigger outer item click / play listeners.
 */
suspend fun PointerInputScope.detectReorderDrag(
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var dragStarted = false
        val touchSlop = viewConfiguration.touchSlop

        try {
            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    if (!change.pressed) {
                        // Finger lifted before timeout or movement -> consume so parent row doesn't click
                        change.consume()
                        break
                    }

                    val dy = change.position.y - down.position.y
                    val dx = change.position.x - down.position.x
                    if (kotlin.math.hypot(dx, dy) > touchSlop) {
                        change.consume()
                        dragStarted = true
                        break
                    }
                }
            }
        } catch (e: androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException) {
            // User held their finger on the drag handle!
            dragStarted = true
        }

        if (dragStarted) {
            try {
                onDragStart()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        onDragEnd()
                        break
                    }
                    val deltaY = change.positionChange().y
                    change.consume()
                    if (deltaY != 0f) {
                        onDrag(deltaY)
                    }
                }
            } catch (e: CancellationException) {
                onDragCancel()
                throw e
            }
        }
    }
}
