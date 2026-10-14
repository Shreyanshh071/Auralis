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
        private val fileLock: FileLock
    ) : AutoCloseable {
        @Volatile
        private var closed = false

        fun recordWindowHandle(hwnd: Long) {
            if (closed) return
            runCatching {
                hwndFile.writeText("HWND=$hwnd\nPID=${ProcessHandle.current().pid()}\n")
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
            // Record PID immediately so it can be identified even before HWND is bound
            runCatching {
                hwndFile.writeText("PID=${ProcessHandle.current().pid()}\n")
            }
            val appLock = Lock(lockFile, hwndFile, randomAccessFile, channel, fileLock)
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
     * Detects and terminates any orphaned or headless background Auralis process holding [dataDir]'s lock.
     * Called only when [focusExistingWindow] confirms no UI window is visible on screen.
     */
    fun recoverZombieInstance(dataDir: File): Boolean {
        val hwndFile = File(dataDir, HWND_FILE_NAME)
        val saved = readSavedInstance(hwndFile)
        val currentPid = ProcessHandle.current().pid()
        val parentPid = ProcessHandle.current().parent().map { it.pid() }.orElse(-1L)
        var killedAny = false

        if (saved?.pid != null && saved.pid != currentPid && saved.pid != parentPid) {
            runCatching {
                val handle = ProcessHandle.of(saved.pid).orElse(null)
                if (handle != null && handle.isAlive) {
                    Log.w(TAG, "Found zombie Auralis process PID ${saved.pid} holding lock with no window. Forcibly terminating...")
                    handle.destroyForcibly()
                    runCatching { handle.onExit().get(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    killedAny = true
                }
            }.onFailure { Log.w(TAG, "Could not destroy zombie PID ${saved.pid}: ${it.message}") }
        }

        if (killedAny) {
            try { Thread.sleep(250) } catch (_: InterruptedException) {}
        }
        runCatching { hwndFile.delete() }
        return true
    }

    /**
     * Finds and brings the existing application window to the foreground,
     * restoring it from minimized state if needed.
     */
    fun focusExistingWindow(dataDir: File, windowTitle: String, maxWaitMs: Long = 1500L): Boolean {
        val user32 = runCatching { com.sun.jna.platform.win32.User32.INSTANCE }.getOrNull() ?: return false
        val hwndFile = File(dataDir, HWND_FILE_NAME)

        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() <= deadline) {
            val saved = readSavedInstance(hwndFile)
            var targetHwnd: HWND? = null
            if (saved != null && saved.hwnd != 0L) {
                val candidate = HWND(Pointer(saved.hwnd))
                if (user32.IsWindow(candidate)) {
                    targetHwnd = candidate
                }
            }

            if (targetHwnd == null) {
                val found = user32.FindWindow(null, windowTitle)
                if (found != null && user32.IsWindow(found)) {
                    targetHwnd = found
                }
            }

            if (targetHwnd != null) {
                bringToForeground(user32, targetHwnd, saved?.pid)
                return true
            }

            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
        }
        return false
    }

    private data class SavedInstance(val hwnd: Long, val pid: Long?)

    private fun readSavedInstance(hwndFile: File): SavedInstance? {
        if (!hwndFile.isFile) return null
        return runCatching {
            var hwnd: Long? = null
            var pid: Long? = null
            hwndFile.readLines().forEach { line ->
                if (line.startsWith("HWND=")) hwnd = line.removePrefix("HWND=").trim().toLongOrNull()
                else if (line.startsWith("PID=")) pid = line.removePrefix("PID=").trim().toLongOrNull()
            }
            if (hwnd != null || pid != null) {
                SavedInstance(hwnd ?: 0L, pid)
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
            user32.BringWindowToTop(hwnd)
            user32.SetForegroundWindow(hwnd)
        } finally {
            if (attached && nativeUser != null) {
                nativeUser.getFunction("AttachThreadInput").invokeInt(arrayOf(currentThread, foregroundThread, 0))
            }
        }
    }
}
