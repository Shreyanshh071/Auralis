package com.auralis.music.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import com.auralis.music.domain.model.Track
import java.util.concurrent.atomic.AtomicLong
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
 * Uses [PointerEventPass.Initial] so that:
 * 1. Immediate drag occurs when user touches the drag handle area (`isHandleArea` == true)
 *    and moves past touch slop, consuming the event in Initial pass so that LazyColumn
 *    scrolling and child clickables never intercept or cancel the drag gesture.
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
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val onHandle = isHandleArea(down.position)
        var dragStarted = false
        var currentPosition = down.position
        val touchSlop = viewConfiguration.touchSlop

        if (onHandle) {
            try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
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
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
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
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
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

/**
 * Item bounds used for computing target insertion index in reorderable LazyColumns.
 *
 * All coordinates ([offset], [size]) are in the LazyColumn viewport coordinate space,
 * identical to [LazyListItemInfo.offset] and [LazyListItemInfo.size].
 */
data class ReorderItemBounds(
    val index: Int,
    val key: Any?,
    val offset: Int,
    val size: Int
)

/**
 * Calculates the target insertion index for a dragged item in a LazyColumn.
 *
 * Operates purely within the LazyColumn viewport coordinate space:
 * - [currentPointerY]: Current pointer Y in viewport coordinates (from pointerInput)
 * - [grabOffsetY]: Vertical distance from top of dragged item to pointer at drag start
 * - [itemHeight]: Height of the dragged item in pixels
 * - [currentIdx]: Current index of the dragged item in the list
 * - [draggingKey]: Unique key of the item currently being dragged
 * - [visibleItems]: Current visible items from LazyListLayoutInfo
 * - [totalItemCount]: Total number of items in the list
 *
 * Guarantees:
 * 1. Visual locking: Continuously tracks slot boundaries derived from actual measured items.
 * 2. Zero dead-zones: Eliminates the 68px hysteresis trap by referencing stable slot positions.
 * 3. Continuous auto-scroll tracking: Computes correct target index even if original slot is scrolled off-screen.
 * 4. Boundary safety: Clamps strictly within visible range and [0, totalItemCount - 1].
 */
fun calculateReorderTargetIndex(
    currentPointerY: Float,
    grabOffsetY: Float,
    itemHeight: Float,
    currentIdx: Int,
    draggingKey: Any?,
    visibleItems: List<ReorderItemBounds>,
    totalItemCount: Int
): Int {
    if (visibleItems.isEmpty() || totalItemCount <= 0 || currentIdx !in 0 until totalItemCount) {
        return currentIdx
    }

    val effectiveItemH = if (itemHeight > 0f) itemHeight else 60f
    val draggedCenterY = currentPointerY - grabOffsetY + (effectiveItemH / 2f)

    // Dynamic slot geometry calculation from actual measured visible items
    val sampleItem = visibleItems.firstOrNull() ?: return currentIdx
    val actualItemH = if (sampleItem.size > 0) sampleItem.size.toFloat() else effectiveItemH

    // Estimate spacing between items if at least two items are visible
    val spacing = if (visibleItems.size >= 2) {
        val sorted = visibleItems.sortedBy { it.index }
        var sumSpacing = 0f
        var count = 0
        for (i in 0 until sorted.size - 1) {
            if (sorted[i + 1].index == sorted[i].index + 1) {
                val sp = (sorted[i + 1].offset - (sorted[i].offset + sorted[i].size)).toFloat()
                if (sp >= 0f) {
                    sumSpacing += sp
                    count++
                }
            }
        }
        if (count > 0) sumSpacing / count else 0f
    } else 0f

    val slotHeight = actualItemH + spacing
    if (slotHeight <= 0f) return currentIdx

    // Establish a stable anchor from visible items
    val anchor = visibleItems.minByOrNull { it.index } ?: sampleItem
    val anchorIndex = anchor.index
    val anchorOffset = anchor.offset.toFloat()
    val anchorCenter = anchorOffset + (actualItemH / 2f)

    // Calculate candidate target slot in continuous slot space
    val diffFromAnchor = draggedCenterY - anchorCenter
    val slotDelta = kotlin.math.round(diffFromAnchor / slotHeight).toInt()
    val rawTarget = anchorIndex + slotDelta

    // Clamp within visible boundaries and list indices to guarantee validity
    val minVisible = visibleItems.minOf { it.index }
    val maxVisible = visibleItems.maxOf { it.index }
    val candidateTarget = rawTarget.coerceIn(minVisible, maxVisible).coerceIn(0, totalItemCount - 1)

    if (candidateTarget == currentIdx) {
        return currentIdx
    }

    // Proportional hysteresis (8% of slot height, approx 5-6px) to prevent edge oscillation
    val hysteresis = slotHeight * 0.08f
    val currentSlotCenter = anchorCenter + ((currentIdx - anchorIndex) * slotHeight)

    return if (candidateTarget > currentIdx) {
        val threshold = currentSlotCenter + (slotHeight / 2f) + hysteresis
        if (draggedCenterY > threshold) candidateTarget else currentIdx
    } else {
        val threshold = currentSlotCenter - (slotHeight / 2f) - hysteresis
        if (draggedCenterY < threshold) candidateTarget else currentIdx
    }
}

/**
 * Stable model representing an item in the reorderable queue.
 * [instanceId] is an immutable, unique identifier that never changes even when the item moves,
 * ensuring stable identity and composition preservation across reordering operations.
 */
data class QueueTrackItem(
    val instanceId: String,
    val track: Track
)

private val queueItemIdGenerator = AtomicLong(1L)

fun createQueueTrackItem(track: Track): QueueTrackItem {
    return QueueTrackItem(
        instanceId = "${track.id}#${queueItemIdGenerator.getAndIncrement()}",
        track = track
    )
}

/**
 * Reconciles a mutable [localQueue] with a new [snapshot] from player state.
 *
 * When not dragging, reuses existing [QueueTrackItem] instances by matching [Track.id] so that
 * Compose item keys remain completely stable across reorders and queue updates, preventing
 * slot recreation, animation glitches, and state corruption.
 */
fun syncLocalQueueWithSnapshot(
    localQueue: MutableList<QueueTrackItem>,
    snapshot: List<Track>,
    isDragging: Boolean
) {
    if (isDragging) return
    if (localQueue.size == snapshot.size &&
        localQueue.indices.all { localQueue[it].track.id == snapshot[it].id }
    ) {
        return
    }

    val pool = localQueue.toMutableList()
    val newList = ArrayList<QueueTrackItem>(snapshot.size)
    for (track in snapshot) {
        val existingIndex = pool.indexOfFirst { it.track.id == track.id }
        if (existingIndex != -1) {
            val existing = pool.removeAt(existingIndex)
            newList.add(if (existing.track == track) existing else existing.copy(track = track))
        } else {
            newList.add(createQueueTrackItem(track))
        }
    }
    localQueue.clear()
    localQueue.addAll(newList)
}

