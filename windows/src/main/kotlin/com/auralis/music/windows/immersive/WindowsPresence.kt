package com.auralis.music.windows.immersive

import com.auralis.music.util.Log
import com.sun.jna.NativeLibrary
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference

/** What Windows says about the person at the PC. Reads system counters only; no input hooks. */
internal object WindowsPresence {
    private const val TAG = "Immersive"
    private const val QUNS_ACCEPTS_NOTIFICATIONS = 5
    private const val ES_SYSTEM_REQUIRED = 0x1
    private const val ES_DISPLAY_REQUIRED = 0x2

    /** Time since the last keyboard or mouse input anywhere on the system. */
    fun idleMillis(): Long? = runCatching {
        val info = WinUser.LASTINPUTINFO()
        check(User32.INSTANCE.GetLastInputInfo(info))
        elapsedTicks(Kernel32.INSTANCE.GetTickCount(), info.dwTime)
    }.onFailure { Log.w(TAG, "Idle time unavailable: ${it.javaClass.simpleName}") }.getOrNull()

    /**
     * False while a fullscreen game or app, a presentation or the lock screen is up. An idle start
     * waits for this, so it can never open over a game.
     */
    fun acceptsInterruptions(): Boolean = runCatching {
        val state = IntByReference()
        val hr = NativeLibrary.getInstance("shell32").getFunction("SHQueryUserNotificationState").invokeInt(arrayOf(state))
        hr == 0 && state.value == QUNS_ACCEPTS_NOTIFICATIONS
    }.onFailure { Log.w(TAG, "Notification state unavailable: ${it.javaClass.simpleName}") }.getOrDefault(false)

    /** Resets the display's idle timer once; repeat it for as long as the screen should stay on. */
    fun keepDisplayOn() {
        runCatching {
            NativeLibrary.getInstance("kernel32").getFunction("SetThreadExecutionState")
                .invokeInt(arrayOf(ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED))
        }.onFailure { Log.w(TAG, "Could not keep the display on: ${it.javaClass.simpleName}") }
    }
}

/** GetTickCount wraps every 49.7 days; the unsigned difference stays right across the wrap. */
internal fun elapsedTicks(now: Int, then: Int): Long = (now - then).toLong() and 0xffffffffL
