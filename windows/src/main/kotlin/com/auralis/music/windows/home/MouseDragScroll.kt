package com.auralis.music.windows.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import kotlin.math.abs

/** Mouse dragging works from a card too; crossing horizontal slop cancels its click. */
internal fun Modifier.mouseDragScroll(state: LazyListState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        var dragging = false
        var cancelled = false
        var previous = down.position
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val distance = change.position - down.position
            if (!dragging && !cancelled) {
                if (abs(distance.y) > viewConfiguration.touchSlop && abs(distance.y) > abs(distance.x)) cancelled = true
                else if (abs(distance.x) > viewConfiguration.touchSlop) {
                    dragging = true
                    state.dispatchRawDelta(-distance.x + kotlin.math.sign(distance.x) * viewConfiguration.touchSlop)
                }
            } else if (dragging) state.dispatchRawDelta(previous.x - change.position.x)
            if (dragging) change.consume()
            previous = change.position
            if (!change.pressed) break
        }
    }
}
