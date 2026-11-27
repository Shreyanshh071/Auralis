package com.auralis.music.windows

import com.auralis.music.util.Log

/**
 * Makes sure a closed Auralis really leaves memory (Windows).
 *
 * On 2026-11-27 a closed copy stayed alive for 7 hours with no window, holding ~1.4 GB: System.exit had
 * started (a thread sat in Shutdown.halt0) but never finished, while the AWT thread was stuck in a native
 * window hide during dispose. The single-instance lock was already released, so the next launch opened
 * normally beside it, and Task Manager added both up under "Auralis".
 *
 * Once the JVM is exiting, no Java thread can run again, so an in-process watchdog can't help. The
 * backstop is a separate hidden process started at the beginning of the close: it waits a few seconds
 * (an orderly close takes ~1.5s) and force-ends this PID if it is still there. After a normal exit the
 * PID is gone and taskkill just fails quietly.
 */
internal object ExitGuard {
    private const val TAG = "ExitGuard"
    private const val GRACE_SECONDS = 6

    @Volatile private var armed = false

    fun arm() {
        if (armed || !System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
        armed = true
        runCatching {
            val pid = ProcessHandle.current().pid()
            // ping is the console-free way to wait in cmd (timeout needs a console). Java starts the
            // child with CREATE_NO_WINDOW, so nothing flashes on screen.
            ProcessBuilder("cmd.exe", "/d", "/c", "ping -n ${GRACE_SECONDS + 1} 127.0.0.1 >nul & taskkill /F /PID $pid >nul 2>&1")
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("NUL")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.onFailure { Log.w(TAG, "Exit backstop not started: ${it.message}") }
    }
}
