package com.auralis.music.windows

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/**
 * Restore the Compose surface after activation and Alt+Tab so hover states
 * and cursor movements respond immediately without requiring a mouse click.
 */
internal fun installWindowInputRestore(window: Window): AutoCloseable {
    var closed = false
    var isActive = window.isActive

    fun restoreFocusAndCursor() {
        if (closed || (!isActive && !window.isActive)) return
        val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val focusedWindow = kfm.focusedWindow
        // If an owned popup/dialog currently has focus, don't steal focus from it.
        if (focusedWindow != null && focusedWindow !== window && window.ownedWindows.contains(focusedWindow)) {
            return
        }

        val surface = composeInputSurface(window)
        val owner = window.focusOwner
        if (surface != null && (owner == null || owner === window || (owner is Container && owner !is org.jetbrains.skiko.SkiaLayer))) {
            surface.requestFocus()
        }

        // Resend current cursor position so hoverable and pointer input components
        // respond immediately to the cursor position without needing a physical click.
        val screen = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return
        val local = Point(screen)
        SwingUtilities.convertPointFromScreen(local, window)
        if (local.x in 0 until window.width && local.y in 0 until window.height) {
            val target = SwingUtilities.getDeepestComponentAt(window, local.x, local.y) ?: surface ?: return
            val point = SwingUtilities.convertPoint(window, local, target)
            val event = MouseEvent(
                target, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                point.x, point.y, screen.x, screen.y, 0, false, MouseEvent.NOBUTTON
            )
            target.dispatchEvent(event)
            if (surface != null && target !== surface) {
                val surfacePoint = SwingUtilities.convertPoint(window, local, surface)
                surface.dispatchEvent(
                    MouseEvent(
                        surface, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                        surfacePoint.x, surfacePoint.y, screen.x, screen.y, 0, false, MouseEvent.NOBUTTON
                    )
                )
            }
        }
    }

    fun restore() {
        SwingUtilities.invokeLater {
            restoreFocusAndCursor()
        }
    }

    val windowListener = object : WindowAdapter() {
        override fun windowActivated(e: WindowEvent) {
            isActive = true
            restore()
        }
        override fun windowDeactivated(e: WindowEvent) {
            isActive = false
        }
        override fun windowGainedFocus(e: WindowEvent) {
            isActive = true
            restore()
        }
    }
    window.addWindowListener(windowListener)
    window.addWindowFocusListener(windowListener)

    // Global AWT listener ensures that as soon as the mouse enters or moves over the window,
    // if the surface isn't focused yet, focus is acquired immediately without waiting for a click.
    val awtListener = AWTEventListener { event ->
        if (closed || (!isActive && !window.isActive)) return@AWTEventListener
        if (event is MouseEvent && (event.id == MouseEvent.MOUSE_MOVED || event.id == MouseEvent.MOUSE_ENTERED)) {
            val src = event.component ?: return@AWTEventListener
            if (SwingUtilities.getWindowAncestor(src) === window) {
                val owner = window.focusOwner
                if (owner == null || owner === window) {
                    composeInputSurface(window)?.requestFocus()
                }
            }
        }
    }

    runCatching {
        Toolkit.getDefaultToolkit().addAWTEventListener(
            awtListener,
            AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK
        )
    }

    return AutoCloseable {
        closed = true
        window.removeWindowListener(windowListener)
        window.removeWindowFocusListener(windowListener)
        runCatching {
            Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener)
        }
    }
}

internal fun composeInputSurface(root: Component): Component? {
    if (root is org.jetbrains.skiko.SkiaLayer) return root.takeIf { it.isFocusable }
    return (root as? Container)?.components?.firstNotNullOfOrNull(::composeInputSurface)
}
