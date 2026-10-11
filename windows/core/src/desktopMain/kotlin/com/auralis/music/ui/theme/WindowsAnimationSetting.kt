package com.auralis.music.ui.theme

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/** Settings → Accessibility → Visual effects → Animation effects (off = reduced motion). */
internal object WindowsAnimationSetting {
    private const val SPI_GETCLIENTAREAANIMATION = 0x1042

    private interface User32 : StdCallLibrary {
        fun SystemParametersInfoW(action: Int, param: Int, value: IntByReference, winIni: Int): Boolean
    }

    val animationsOff: Boolean by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return@lazy false
        runCatching {
            val user32 = Native.load("user32", User32::class.java)
            val enabled = IntByReference(1)
            user32.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, enabled, 0) && enabled.value == 0
        }.getOrDefault(false)
    }
}
