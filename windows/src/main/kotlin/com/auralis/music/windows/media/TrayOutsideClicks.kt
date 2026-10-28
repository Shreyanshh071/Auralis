package com.auralis.music.windows.media

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinUser
import java.awt.EventQueue
import java.awt.Window
import java.util.concurrent.CountDownLatch

/** Only watches button presses while the menu is open; never consumes or redirects input. */
internal class TrayOutsideClicks(window: Window, onOutside: () -> Unit) : AutoCloseable {
    private val bounds = RECT().also {
        check(User32.INSTANCE.GetWindowRect(HWND(Native.getComponentPointer(window)), it))
    }
    internal val ready = CountDownLatch(1)
    @Volatile private var active = true
    @Volatile internal var installed = false
        private set
    private val callback = WinUser.LowLevelMouseProc { code, message, event ->
        if (code >= 0 && active && message.toInt() in listOf(0x0201, 0x0204, 0x0207, 0x020B) &&
            (event.pt.x < bounds.left || event.pt.x >= bounds.right ||
                event.pt.y < bounds.top || event.pt.y >= bounds.bottom)) {
            EventQueue.invokeLater { if (active && window.isVisible) onOutside() }
        }
        User32.INSTANCE.CallNextHookEx(null, code, message, LPARAM(Pointer.nativeValue(event.pointer)))
    }
    private val thread = Thread({
        val user = User32.INSTANCE
        val hook = user.SetWindowsHookEx(WinUser.WH_MOUSE_LL, callback, Kernel32.INSTANCE.GetModuleHandle(null), 0)
        installed = hook != null
        ready.countDown()
        if (hook != null) try {
            val message = WinUser.MSG()
            while (active) {
                while (user.PeekMessage(message, null, 0, 0, 1)) {
                    user.TranslateMessage(message)
                    user.DispatchMessage(message)
                }
                Thread.sleep(10)
            }
        } finally { user.UnhookWindowsHookEx(hook) }
    }, "auralis-tray-outside-click").apply { isDaemon = true; start() }

    override fun close() { active = false }
}
