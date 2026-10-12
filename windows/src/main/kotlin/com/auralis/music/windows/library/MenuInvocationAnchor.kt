package com.auralis.music.windows.library

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** Capture the initiating press in Compose window pixels, before a click opens its popup. */
internal class MenuInvocationAnchor {
    var point: IntOffset? = null
        private set

    fun record(x: Float, y: Float) {
        point = IntOffset(x.roundToInt(), y.roundToInt())
    }
}

internal val LocalMenuInvocationAnchor = staticCompositionLocalOf<MenuInvocationAnchor?> { null }

internal fun menuInvocationRecorder(anchor: MenuInvocationAnchor): Modifier = Modifier.pointerInput(anchor) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) {
                event.changes.firstOrNull()?.position?.let { anchor.record(it.x, it.y) }
            }
        }
    }
}
