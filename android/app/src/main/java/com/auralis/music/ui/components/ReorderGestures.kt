package com.auralis.music.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
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

/**
 * Detects reorder drag gestures at the container (LazyColumn/Box) level.
 *
 * Provides rock-solid, cancellation-free dragging:
 * 1. Immediate drag when the user touches the drag handle area (`isHandleArea` == true)
 *    and moves past touch slop, or holds.
 * 2. Press-and-hold (long-press timeout) when the user touches elsewhere on a row,
 *    leaving normal taps and list scrolls intact.
 *
 * Operates in container coordinate space to eliminate layout jump feedback loops.
 */
suspend fun PointerInputScope.detectContainerReorderDrag(
    isHandleArea: (Offset) -> Boolean,
    onDragStart: (downOffset: Offset, currentOffset: Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val onHandle = isHandleArea(down.position)
        var dragStarted = false
        var currentPosition = down.position
        val touchSlop = viewConfiguration.touchSlop

        if (onHandle) {
            try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        val dy = change.position.y - down.position.y
                        val dx = change.position.x - down.position.x
                        if (kotlin.math.hypot(dx, dy) > touchSlop) {
                            change.consume()
                            currentPosition = change.position
                            dragStarted = true
                            break
                        }
                    }
                }
            } catch (e: androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException) {
                dragStarted = true
            }
        } else {
            try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        val dy = change.position.y - down.position.y
                        val dx = change.position.x - down.position.x
                        if (kotlin.math.hypot(dx, dy) > touchSlop) {
                            break
                        }
                    }
                }
            } catch (e: androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException) {
                dragStarted = true
            }
        }

        if (dragStarted) {
            onDragStart(down.position, currentPosition)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        onDragEnd()
                        break
                    }
                    change.consume()
                    currentPosition = change.position
                    onDrag(currentPosition)
                }
            } catch (e: CancellationException) {
                onDragCancel()
                throw e
            }
        }
    }
}
