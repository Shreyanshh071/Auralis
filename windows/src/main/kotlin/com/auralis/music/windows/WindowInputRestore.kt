package com.auralis.music.windows

import java.awt.Component
import java.awt.Container
import java.awt.MouseInfo
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/** Restore the Compose surface after activation without stealing focus from an owned popup. */
internal fun installWindowInputRestore(window: Window): AutoCloseable {
    var closed = false
    fun restore() {
        SwingUtilities.invokeLater {
            if (closed || !window.isActive || !window.isFocused) return@invokeLater
            val owner = window.focusOwner
            val surface = composeInputSurface(window)
            if (surface != null && (owner == null || owner === window || owner is Container && owner !is org.jetbrains.skiko.SkiaLayer)) {
                surface.requestFocusInWindow()
            }
            // A pointer already over the window need not physically move after Alt+Tab.
            // Re-send its current position to the actual component; no click or cursor movement.
            val screen = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return@invokeLater
            val local = java.awt.Point(screen)
            SwingUtilities.convertPointFromScreen(local, window)
            val target = SwingUtilities.getDeepestComponentAt(window, local.x, local.y) ?: return@invokeLater
            val point = SwingUtilities.convertPoint(window, local, target)
            target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                point.x, point.y, screen.x, screen.y, 0, false, MouseEvent.NOBUTTON))
        }
    }
    val listener = object : WindowAdapter() {
        override fun windowActivated(e: WindowEvent) = restore()
        override fun windowGainedFocus(e: WindowEvent) = restore()
    }
    window.addWindowListener(listener)
    window.addWindowFocusListener(listener)
    return AutoCloseable {
        closed = true
        window.removeWindowListener(listener)
        window.removeWindowFocusListener(listener)
    }
}

internal fun composeInputSurface(root: Component): Component? {
    if (root is org.jetbrains.skiko.SkiaLayer) return root.takeIf { it.isFocusable }
    return (root as? Container)?.components?.firstNotNullOfOrNull(::composeInputSurface)
}
