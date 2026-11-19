package com.auralis.music.windows

import com.auralis.music.util.Log
import java.awt.Container
import org.jetbrains.skiko.SkiaLayer

/**
 * Stop drawing into a minimised window and request a frame on restore. The native window
 * procedure must return before Skia presents, since presentation may itself need AWT.
 */
internal class MinimisedRenderPause(private val window: java.awt.Window) {
    private val showing = runCatching { SkiaLayer::class.java.getDeclaredField("isShowingCached").apply { isAccessible = true } }
        .onFailure { Log.w(TAG, "Skia layer flag unavailable: ${it.javaClass.simpleName}") }.getOrNull()
    private val recheck = runCatching { SkiaLayer::class.java.getDeclaredMethod("checkShowing").apply { isAccessible = true } }.getOrNull()
    @Volatile private var paused = false
    // Found once on the UI thread: the window procedure runs on AWT's toolkit thread, where walking
    // the component tree could wait on the UI thread's tree lock.
    private val skiaLayer = layer(window)
        .also { if (it == null && showing != null) Log.w(TAG, "No Skia layer in the window; drawing continues while minimised") }

    private fun layer(container: Container): SkiaLayer? {
        for (child in container.components) {
            if (child is SkiaLayer) return child
            if (child is Container) layer(child)?.let { return it }
        }
        return null
    }

    /**
     * Called by the window procedure on AWT's toolkit thread. The render request runs on
     * the event dispatch thread after AWT receives the restored size.
     */
    fun minimised(minimised: Boolean) {
        if (minimised == paused) return
        val field = showing ?: return
        val layer = skiaLayer ?: return
        paused = minimised
        if (minimised) { runCatching { field.setBoolean(layer, false) }; return }
        java.awt.EventQueue.invokeLater {
            if (!paused && window.isDisplayable) runCatching {
                recheck?.invoke(layer) ?: field.setBoolean(layer, window.isShowing)
                layer.needRedraw()
            }.onFailure { Log.w(TAG, "Restore frame request failed: ${it.javaClass.simpleName}") }
        }
    }

    private companion object {
        const val TAG = "AuralisWindow"
    }
}
