package com.auralis.music.windows.media

import com.auralis.music.util.Log
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Manages macOS power assertions via [NSProcessInfo beginActivityWithOptions:reason:]
 * to prevent App Nap, thread throttling, and system idle sleep while audio is actively playing.
 * Automatically releases the activity when playback pauses or stops to conserve battery.
 */
internal object MacPlaybackActivity {
    private const val TAG = "MacPlaybackActivity"
    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    // NSActivityUserInitiated (0x00FFFFFF | 1<<20) | NSActivityLatencyCritical (0xFF << 32)
    private const val ACTIVITY_OPTIONS = 0xFF001FFFFFL

    @Volatile private var activityToken: Pointer? = null
    private var processInfo: Pointer? = null
    private var beginActivitySel: Pointer? = null
    private var endActivitySel: Pointer? = null
    private var cfStringFunc: com.sun.jna.Function? = null
    private var initialized = false

    private fun initNative() {
        if (!isMac || initialized) return
        initialized = true
        runCatching {
            val objc = NativeLibrary.getInstance("objc")
            val cf = NativeLibrary.getInstance("CoreFoundation")
            val getClass = objc.getFunction("objc_getClass")
            val selRegister = objc.getFunction("sel_registerName")
            val msgSend = objc.getFunction("objc_msgSend")

            val processInfoClass = getClass.invokePointer(arrayOf("NSProcessInfo")) ?: return
            val processInfoSel = selRegister.invokePointer(arrayOf("processInfo")) ?: return
            processInfo = msgSend.invokePointer(arrayOf(processInfoClass, processInfoSel))

            beginActivitySel = selRegister.invokePointer(arrayOf("beginActivityWithOptions:reason:"))
            endActivitySel = selRegister.invokePointer(arrayOf("endActivity:"))
            cfStringFunc = cf.getFunction("CFStringCreateWithCString")
        }.onFailure {
            Log.w(TAG, "Failed to initialize NSProcessInfo activity tracking: ${it.message}")
        }
    }

    @Synchronized
    fun startPlaybackActivity() {
        if (!isMac) return
        initNative()
        if (activityToken != null || processInfo == null || beginActivitySel == null || cfStringFunc == null) return

        runCatching {
            val objc = NativeLibrary.getInstance("objc")
            val cf = NativeLibrary.getInstance("CoreFoundation")
            val msgSend = objc.getFunction("objc_msgSend")
            val cfRelease = cf.getFunction("CFRelease")

            val reasonStr = cfStringFunc!!.invokePointer(arrayOf(Pointer.NULL, "Auralis Audio Playback", 0x08000100))
            try {
                activityToken = msgSend.invokePointer(arrayOf(processInfo, beginActivitySel, ACTIVITY_OPTIONS, reasonStr))
                Log.d(TAG, "macOS background playback activity assertion acquired")
            } finally {
                if (reasonStr != null) cfRelease.invoke(arrayOf(reasonStr))
            }
        }.onFailure {
            Log.w(TAG, "Failed to acquire macOS playback activity: ${it.message}")
        }
    }

    @Synchronized
    fun stopPlaybackActivity() {
        if (!isMac) return
        val token = activityToken ?: return
        activityToken = null

        runCatching {
            if (processInfo != null && endActivitySel != null) {
                val objc = NativeLibrary.getInstance("objc")
                val msgSend = objc.getFunction("objc_msgSend")
                msgSend.invoke(Void.TYPE, arrayOf(processInfo, endActivitySel, token))
                Log.d(TAG, "macOS background playback activity assertion released")
            }
        }.onFailure {
            Log.w(TAG, "Failed to release macOS playback activity: ${it.message}")
        }
    }

    val isAcquired: Boolean get() = activityToken != null
}
