package com.auralis.music.windows

import com.auralis.music.util.Log
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * Ensures only a single instance of Auralis runs at a time.
 * If another instance is launched (e.g. by double-clicking the shortcut again),
 * the secondary process restores and focuses the existing window, then exits immediately.
 */
object SingleInstanceApp {
    private const val TAG = "SingleInstance"
    private const val LOCK_FILE_NAME = "app.lock"
    private const val HWND_FILE_NAME = "app.hwnd"

    class Lock internal constructor(
        private val lockFile: File,
        private val hwndFile: File,
        private val randomAccessFile: RandomAccessFile,
        private val channel: FileChannel,
        private val fileLock: FileLock,
        private val startTime: Long
    ) : AutoCloseable {
        @Volatile
        private var closed = false

        fun recordWindowHandle(hwnd: Long) {
            if (closed) return
            runCatching {
                hwndFile.writeText("HWND=$hwnd\nPID=${ProcessHandle.current().pid()}\nSTART_TIME=$startTime\n")
            }.onFailure { Log.w(TAG, "Failed to record HWND in lock file: ${it.message}") }
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { fileLock.release() }
            runCatching { channel.close() }
            runCatching { randomAccessFile.close() }
            runCatching { hwndFile.delete() }
            runCatching { lockFile.delete() }
        }
    }

    /**
     * Attempts to acquire the single-instance lock for the specified [dataDir].
     * Returns a [Lock] if this is the first/only instance, or `null` if another instance is already running.
     */
    fun tryAcquire(dataDir: File): Lock? {
        dataDir.mkdirs()
        val lockFile = File(dataDir, LOCK_FILE_NAME)
        val hwndFile = File(dataDir, HWND_FILE_NAME)
        var raf: RandomAccessFile? = null
        var chan: FileChannel? = null
        var lock: FileLock? = null
        return try {
            val randomAccessFile = RandomAccessFile(lockFile, "rw")
            raf = randomAccessFile
            val channel = randomAccessFile.channel
            chan = channel
            val fileLock = channel.tryLock() ?: run {
                runCatching { channel.close() }
                runCatching { randomAccessFile.close() }
                return null
            }
            lock = fileLock
            val startTime = System.currentTimeMillis()
            // Record PID and start time immediately so it can be identified even before HWND is bound
            runCatching {
                hwndFile.writeText("PID=${ProcessHandle.current().pid()}\nSTART_TIME=$startTime\n")
            }
            val appLock = Lock(lockFile, hwndFile, randomAccessFile, channel, fileLock, startTime)
            Runtime.getRuntime().addShutdownHook(Thread { appLock.close() })
            appLock
        } catch (e: Exception) {
            runCatching { lock?.release() }
            runCatching { chan?.close() }
            runCatching { raf?.close() }
            Log.w(TAG, "Could not acquire lock (another instance likely running): ${e.message}")
            null
        }
    }

    /**
     * Attempts to acquire the lock, retrying periodically until [maxWaitMs] expires.
     * Useful when a prior instance has just terminated and Windows is releasing file handles.
     */
    fun acquireWithRetry(dataDir: File, maxWaitMs: Long = 1500L): Lock? {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() <= deadline) {
            val lock = tryAcquire(dataDir)
            if (lock != null) return lock
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
        }
        return null
    }

    /**
     * Detects and cleans up after terminated or dead background Auralis processes holding [dataDir]'s lock.
     * Never terminates processes that are still alive, as they may be running in the system tray or booting.
     */
    fun recoverZombieInstance(dataDir: File): Boolean {
        val hwndFile = File(dataDir, HWND_FILE_NAME)
        val saved = readSavedInstance(hwndFile)
        val currentPid = ProcessHandle.current().pid()
        val parentPid = ProcessHandle.current().parent().map { it.pid() }.orElse(-1L)

        if (saved?.pid != null && saved.pid != currentPid && saved.pid != parentPid) {
            val handle = runCatching { ProcessHandle.of(saved.pid).orElse(null) }.getOrNull()
            if (handle != null && handle.isAlive) {
                val nativeUser = runCatching { NativeLibrary.getInstance("user32") }.getOrNull()
                val isHung = if (saved.hwnd != 0L && nativeUser != null) {
                    runCatching {
                        nativeUser.getFunction("IsHungAppWindow").invokeInt(arrayOf(Pointer(saved.hwnd))) != 0
                    }.getOrDefault(false)
                } else false

                if (isHung) {
                    Log.w(TAG, "Process PID ${saved.pid} window is hung/unresponsive. Terminating hung instance.")
                    runCatching { handle.destroyForcibly() }
                    runCatching { hwndFile.delete() }
                    return true
                }

                val startTime = saved.startTime ?: 0L
                val ageMs = if (startTime > 0L) System.currentTimeMillis() - startTime else -1L
                Log.i(TAG, "Process PID ${saved.pid} is alive (running for ${if (ageMs >= 0) "${ageMs}ms" else "unknown duration"}). Retaining running instance.")
                return false
            }
        }

        runCatching { hwndFile.delete() }
        return true
    }

    /**
     * Finds and brings the existing application window to the foreground,
     * restoring it from minimized or system tray state if needed.
     */
    fun focusExistingWindow(dataDir: File, windowTitle: String, maxWaitMs: Long = 4000L): Boolean {
        val user32 = runCatching { com.sun.jna.platform.win32.User32.INSTANCE }.getOrNull()
        val hwndFile = File(dataDir, HWND_FILE_NAME)
        val restoreFile = File(dataDir, "window-restore.request")

        val initialSaved = readSavedInstance(hwndFile)
        val isAlive = if (initialSaved?.pid != null && initialSaved.pid > 0) {
            runCatching { ProcessHandle.of(initialSaved.pid).map { it.isAlive }.orElse(false) }.getOrDefault(false)
        } else false

        if (!isAlive && initialSaved?.pid != null) {
            // Process holding the lock has already terminated
            return false
        }

        // Immediately signal the running primary instance to restore and focus its window
        if (isAlive) {
            runCatching { restoreFile.writeText(System.currentTimeMillis().toString()) }
        }

        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() <= deadline) {
            val saved = readSavedInstance(hwndFile)
            var targetHwnd: HWND? = null

            if (user32 != null && saved != null && saved.hwnd != 0L) {
                val candidate = HWND(Pointer(saved.hwnd))
                if (user32.IsWindow(candidate)) {
                    targetHwnd = candidate
                }
            }

            if (user32 != null && targetHwnd == null) {
                val found = user32.FindWindow(null, windowTitle)
                if (found != null && user32.IsWindow(found)) {
                    targetHwnd = found
                }
            }

            if (targetHwnd != null && user32 != null) {
                runCatching { restoreFile.writeText(System.currentTimeMillis().toString()) }
                bringToForeground(user32, targetHwnd, saved?.pid)
                return true
            }

            // If the restore request was consumed and deleted by the primary instance, it is active and restored
            if (isAlive && !restoreFile.exists()) {
                if (targetHwnd != null && user32 != null) {
                    bringToForeground(user32, targetHwnd, saved?.pid)
                }
                return true
            }

            // If the process holding the lock died during wait, stop waiting
            if (saved?.pid != null && saved.pid > 0) {
                val stillAlive = runCatching { ProcessHandle.of(saved.pid).map { it.isAlive }.orElse(false) }.getOrDefault(true)
                if (!stillAlive) return false
            }

            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
        }

        // Only consider successful if the restore request was actually consumed by the running instance
        if (isAlive && !restoreFile.exists()) {
            return true
        }

        return false
    }

    private data class SavedInstance(val hwnd: Long, val pid: Long?, val startTime: Long?)

    private fun readSavedInstance(hwndFile: File): SavedInstance? {
        if (!hwndFile.isFile) return null
        return runCatching {
            var hwnd: Long? = null
            var pid: Long? = null
            var startTime: Long? = null
            hwndFile.readLines().forEach { line ->
                if (line.startsWith("HWND=")) hwnd = line.removePrefix("HWND=").trim().toLongOrNull()
                else if (line.startsWith("PID=")) pid = line.removePrefix("PID=").trim().toLongOrNull()
                else if (line.startsWith("START_TIME=")) startTime = line.removePrefix("START_TIME=").trim().toLongOrNull()
            }
            if (hwnd != null || pid != null || startTime != null) {
                SavedInstance(hwnd ?: 0L, pid, startTime)
            } else null
        }.getOrNull()
    }

    private fun bringToForeground(user32: com.sun.jna.platform.win32.User32, hwnd: HWND, targetPid: Long?) {
        val nativeUser = runCatching { NativeLibrary.getInstance("user32") }.getOrNull()
        val nativeKernel = runCatching { NativeLibrary.getInstance("kernel32") }.getOrNull()

        if (targetPid != null && targetPid > 0 && nativeUser != null) {
            runCatching {
                nativeUser.getFunction("AllowSetForegroundWindow").invokeInt(arrayOf(targetPid.toInt()))
            }
        }
        val isIconic = nativeUser != null && nativeUser.getFunction("IsIconic").invokeInt(arrayOf(hwnd.pointer)) != 0
        if (isIconic) {
            user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
        } else {
            user32.ShowWindow(hwnd, WinUser.SW_SHOW)
        }

        val foregroundHwnd = user32.GetForegroundWindow()
        val currentThread = if (nativeKernel != null) {
            nativeKernel.getFunction("GetCurrentThreadId").invokeInt(emptyArray())
        } else 0
        val foregroundThread = if (foregroundHwnd != null) {
            user32.GetWindowThreadProcessId(foregroundHwnd, null)
        } else 0

        val attached = if (nativeUser != null && foregroundThread != 0 && foregroundThread != currentThread) {
            nativeUser.getFunction("AttachThreadInput").invokeInt(arrayOf(currentThread, foregroundThread, 1)) != 0
        } else false

        try {
            // Unlock foreground rights in Windows 10/11 using simulated Alt keypress
            nativeUser?.getFunction("keybd_event")?.invokeVoid(arrayOf(0x12, 0, 0, 0))
            nativeUser?.getFunction("keybd_event")?.invokeVoid(arrayOf(0x12, 0, 2, 0))
            user32.BringWindowToTop(hwnd)
            user32.SetForegroundWindow(hwnd)
        } finally {
            if (attached && nativeUser != null) {
                nativeUser.getFunction("AttachThreadInput").invokeInt(arrayOf(currentThread, foregroundThread, 0))
            }
        }
    }
}
