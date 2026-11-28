package com.auralis.music.windows.media

import com.auralis.music.util.Log
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Hands memory back to Windows while Auralis only plays in the background (Windows only).
 *
 * Measured 2026-11-27: the visible app held ~1 GB, of which the Java heap was ~100 MB and decoded
 * covers ~144 MB; the rest is Direct3D/driver and Skia memory that a hidden window doesn't touch.
 * Hidden in the tray or minimized for a while, the app collects garbage once (free heap is returned,
 * see the heap flags in build.gradle.kts) and empties its working set, as Chromium-based apps do when
 * minimized. Playback keeps running: the pages it uses fault straight back in from memory, and the
 * rest come back when the window is shown again.
 */
internal object WorkingSetTrim {
    private const val TAG = "WorkingSetTrim"
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** [collect]: a full GC first (only when nothing is on screen; it pauses drawing briefly). */
    fun trim(collect: Boolean = true) {
        if (!windows) return
        runCatching {
            if (collect) System.gc()
            // K32EmptyWorkingSet on GetCurrentProcess()'s pseudo-handle (-1).
            val ok = NativeLibrary.getInstance("kernel32").getFunction("K32EmptyWorkingSet")
                .invokeInt(arrayOf(Pointer.createConstant(-1L))) != 0
            Log.i(TAG, if (ok) "Working set emptied (collect=$collect)" else "Working set not emptied")
        }.onFailure { Log.w(TAG, "Working set trim failed: ${it.message}") }
    }
}
