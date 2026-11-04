package com.auralis.music.windows

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Rectangle

/** Window geometry saved before entering borderless fullscreen. */
internal data class WindowGeometry(
    val placement: WindowPlacement,
    val position: WindowPosition,
    val size: DpSize
) {
    fun restore(state: WindowState) {
        state.placement = WindowPlacement.Floating
        state.position = position
        state.size = size
        state.placement = placement
    }
}

internal fun WindowState.enterBorderlessFullscreen(bounds: Rectangle): WindowGeometry {
    val previous = WindowGeometry(placement, position, size)
    // AWT exclusive fullscreen can iconify on activation loss to capture overlays.
    placement = WindowPlacement.Floating
    position = WindowPosition.Absolute(bounds.x.dp, bounds.y.dp)
    size = DpSize(bounds.width.dp, bounds.height.dp)
    return previous
}
