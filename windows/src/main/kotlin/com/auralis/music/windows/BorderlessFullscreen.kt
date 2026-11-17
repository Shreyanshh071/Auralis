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

/**
 * Handles macOS window placement transitions for player and immersive fullscreen.
 *
 * On macOS, decorated windows (`undecorated = false`) with traffic lights must not be
 * forced into [WindowPlacement.Floating] when entering player fullscreen, because setting
 * [WindowPlacement.Floating] on an already maximized window unmaximizes it and causes it
 * to shrink down to its restored floating bounds.
 *
 * - When entering player fullscreen:
 *   - If currently [WindowPlacement.Maximized] or [WindowPlacement.Fullscreen], retain it and record it.
 *   - If currently [WindowPlacement.Floating], record [WindowPlacement.Floating] and expand to [WindowPlacement.Maximized].
 * - When exiting player fullscreen:
 *   - Restore to [WindowPlacement.Floating] if the window was previously floating.
 *   - Keep [WindowPlacement.Maximized] or [WindowPlacement.Fullscreen] if the window was already in that state.
 */
internal fun applyMacPlayerFullscreen(
    enabled: Boolean,
    currentPlacement: WindowPlacement,
    savedPlacement: WindowPlacement?,
    onSetPlacement: (WindowPlacement) -> Unit
): WindowPlacement? {
    return if (enabled) {
        val prev = currentPlacement
        if (currentPlacement != WindowPlacement.Maximized && currentPlacement != WindowPlacement.Fullscreen) {
            onSetPlacement(WindowPlacement.Maximized)
        }
        prev
    } else {
        savedPlacement?.let { prev ->
            if (prev != WindowPlacement.Maximized && prev != WindowPlacement.Fullscreen) {
                onSetPlacement(prev)
            }
        }
        null
    }
}
