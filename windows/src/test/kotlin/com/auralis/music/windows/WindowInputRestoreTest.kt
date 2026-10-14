package com.auralis.music.windows

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.jetbrains.skiko.SkiaLayer
import kotlin.test.*

class WindowInputRestoreTest {
    @Test fun findsComposeSurfacePastUnrelatedFirstChild() = SwingUtilities.invokeAndWait {
        val root = JPanel()
        root.add(JPanel().apply { add(JPanel()) })
        val surface = SkiaLayer()
        surface.isFocusable = true
        root.add(JPanel().apply { add(surface) })
        try {
            assertSame(surface, composeInputSurface(root), "Activation must target the Skia surface, not the first focusable container")
            assertNull(composeInputSurface(JPanel()), "Do not give focus to an unrelated container")
        } finally { surface.dispose() }
    }

    @Test fun restoresFocusOnActivationEvenWhenNotInitiallyFocused() {
        var requestedFocus = false
        var frame: JFrame? = null
        var customSurface: SkiaLayer? = null
        var restore: AutoCloseable? = null

        SwingUtilities.invokeAndWait {
            val f = JFrame("Test Window").apply {
                size = Dimension(400, 300)
            }
            frame = f
            val s = object : SkiaLayer() {
                init { isFocusable = true }
                override fun requestFocus() {
                    requestedFocus = true
                    super.requestFocus()
                }
            }
            customSurface = s
            f.contentPane.add(s)
            f.isVisible = true

            restore = installWindowInputRestore(f)
            // Simulate window activation from Alt+Tab
            f.dispatchEvent(WindowEvent(f, WindowEvent.WINDOW_ACTIVATED))
        }

        // Wait for EDT to process the invokeLater in restore()
        SwingUtilities.invokeAndWait {
            // By the time this invokeAndWait runs on EDT, previous invokeLater has finished
        }

        try {
            assertTrue(requestedFocus, "installWindowInputRestore must request focus on activation even before isFocused is true")
        } finally {
            SwingUtilities.invokeAndWait {
                restore?.close()
                frame?.dispose()
                customSurface?.dispose()
            }
        }
    }

    @Test fun mouseMovementAcrossWindowRestoresFocusWithoutClick() {
        var requestedFocus = false
        var frame: JFrame? = null
        var customSurface: SkiaLayer? = null
        var restore: AutoCloseable? = null

        SwingUtilities.invokeAndWait {
            val f = JFrame("Test Window 2").apply {
                size = Dimension(500, 400)
                setLocation(200, 200)
            }
            frame = f
            val s = object : SkiaLayer() {
                init { isFocusable = true }
                override fun requestFocus() {
                    requestedFocus = true
                    super.requestFocus()
                }
            }
            customSurface = s
            f.contentPane.add(s)
            f.isVisible = true

            restore = installWindowInputRestore(f)
            // Simulate mouse entering/moving over window component while active
            f.dispatchEvent(WindowEvent(f, WindowEvent.WINDOW_ACTIVATED))
            s.dispatchEvent(
                java.awt.event.MouseEvent(
                    s, java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                    50, 50, 250, 250, 0, false, java.awt.event.MouseEvent.NOBUTTON
                )
            )
        }

        SwingUtilities.invokeAndWait { }

        try {
            assertTrue(requestedFocus, "Mouse motion across active window must immediately restore focus without waiting for a click")
        } finally {
            SwingUtilities.invokeAndWait {
                restore?.close()
                frame?.dispose()
                customSurface?.dispose()
            }
        }
    }
}
