package com.auralis.music.windows

import com.auralis.music.util.Log
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.AWTEvent
import java.awt.Container
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowEvent

/**
 * Draws a window's last frame once more after it goes idle, so screenshots show it.
 *
 * Compose draws only when something changes. With OpenGL, a screenshot (PrtSc, Snipping Tool)
 * taken while the window is idle got the frame before the newest one: a Settings window or popup
 * that had just opened was missing from the first screenshot and only showed in a second one.
 * Presenting the finished frame a second time, [ECHO_DELAY_MS] after the last one, makes the
 * newest frame the one Windows captures. During animation frames keep coming and no echo is drawn.
 */
internal object CaptureFrameEcho {
    private const val ECHO_DELAY_MS = 60

    /** Every Compose window and dialog as it opens. */
    fun installForAllWindows() {
        Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
            if (event.id == WindowEvent.WINDOW_OPENED) (event.source as? Window)?.let { window ->
                java.awt.EventQueue.invokeLater { install(window) }
            }
        }, AWTEvent.WINDOW_EVENT_MASK)
    }

    fun install(window: Window) {
        val layer = skiaLayer(window) ?: return
        val original = layer.renderDelegate ?: return
        if (original is Echoing) return
        layer.renderDelegate = Echoing(layer, original)
    }

    private class Echoing(private val layer: SkiaLayer, private val original: SkikoRenderDelegate) : SkikoRenderDelegate {
        private var echoing = false
        private val timer = javax.swing.Timer(ECHO_DELAY_MS) {
            echoing = true
            runCatching { layer.needRedraw() }.onFailure { echoing = false; Log.w("AuralisWindow", "Echo frame failed: ${it.javaClass.simpleName}") }
        }.apply { isRepeats = false }

        override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
            // A translucent lyric picture must never blend over a previous swap
            // buffer's ink. Clear the actual presentation target, not just a row.
            canvas.clear(org.jetbrains.skia.Color.TRANSPARENT)
            original.onRender(canvas, width, height, nanoTime)
            // The echo itself mustn't schedule another; a real frame restarts the wait.
            // The extra present is an OpenGL capture workaround. On Direct3D,
            // let the compositor present the real animation frames only.
            if (echoing) echoing = false
            else if (System.getProperty("skiko.renderApi", "DIRECT3D").equals("OPENGL", true)) timer.restart()
        }
    }

    private fun skiaLayer(container: Container): SkiaLayer? {
        for (child in container.components) {
            if (child is SkiaLayer) return child
            if (child is Container) skiaLayer(child)?.let { return it }
        }
        return null
    }
}
