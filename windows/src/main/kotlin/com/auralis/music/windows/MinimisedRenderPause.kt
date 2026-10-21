package com.auralis.music.windows

import com.auralis.music.util.Log
import java.awt.Container

/**
 * Minimise and restore for the OpenGL window, the way native apps paint. While minimised Skia stops
 * drawing (skiko drew ~70 frames a second into the hidden window). Windows discards a minimised
 * OpenGL window's surface, so the restore showed it black until the next frame: on restore one frame
 * is drawn at once, while Windows waits on the restore message, as a GDI app paints in WM_PAINT.
 *
 * Skiko draws only layers whose cached "showing" flag is set (WindowsOpenGLRedrawer); this clears
 * it while minimised and lets skiko recompute it on restore.
 */
internal class MinimisedRenderPause(private val window: java.awt.Window) {
    private val layerClass = runCatching { Class.forName("org.jetbrains.skiko.SkiaLayer") }.getOrNull()
    private val showing = runCatching { layerClass!!.getDeclaredField("isShowingCached").apply { isAccessible = true } }
        .onFailure { Log.w(TAG, "Skia layer flag unavailable: ${it.javaClass.simpleName}") }.getOrNull()
    private val recheck = runCatching { layerClass!!.getDeclaredMethod("checkShowing").apply { isAccessible = true } }.getOrNull()
    private val redrawNow = runCatching { layerClass!!.getDeclaredMethod("tryRedrawImmediately").apply { isAccessible = true } }.getOrNull()
    @Volatile private var paused = false
    // Found once on the UI thread: the window procedure runs on AWT's toolkit thread, where walking
    // the component tree could wait on the UI thread's tree lock.
    private val skiaLayer = layer(window)
        .also { if (it == null && showing != null) Log.w(TAG, "No Skia layer in the window; drawing continues while minimised") }

    private fun layer(container: Container): Any? {
        for (child in container.components) {
            if (layerClass?.isInstance(child) == true) return child
            if (child is Container) layer(child)?.let { return it }
        }
        return null
    }

    /**
     * From the window procedure (WM_SIZE), on AWT's toolkit thread. Minimising pauses before another
     * frame can reach the window. Restoring waits (at most [RESTORE_WAIT_MS], so the UI thread can
     * never deadlock it) for the UI thread to draw a frame at the restored size.
     */
    fun minimised(minimised: Boolean) {
        if (minimised == paused) return
        val field = showing ?: return
        val layer = skiaLayer ?: return
        paused = minimised
        if (minimised) { runCatching { field.setBoolean(layer, false) }; return }
        val started = System.nanoTime()
        val drawn = java.util.concurrent.CountDownLatch(1)
        java.awt.EventQueue.invokeLater {
            try {
                if (!paused) runCatching {
                    recheck?.invoke(layer) ?: field.setBoolean(layer, window.isShowing)
                    redrawNow?.invoke(layer)
                }.onFailure { Log.w(TAG, "Restore frame failed: ${it.javaClass.simpleName}") }
            } finally { drawn.countDown() }
        }
        val inTime = drawn.await(RESTORE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        Log.i(TAG, "Restore frame ${if (inTime) "drawn" else "still pending"} after ${(System.nanoTime() - started) / 1_000_000}ms")
    }

    private companion object {
        const val TAG = "AuralisWindow"
        const val RESTORE_WAIT_MS = 120L
    }
}
