package com.auralis.music.windows.dev

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.auralis.music.windows.NativeWindowFrame
import com.auralis.music.windows.TitleBarActions
import com.auralis.music.windows.WindowTitleBar
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** An isolated native-frame integration check. No music, network, or user data. */
fun main() = application {
    val state = rememberWindowState(size = DpSize(1000.dp, 650.dp))
    Window(onCloseRequest = ::exitApplication, title = "Auralis window-frame check", undecorated = true, state = state) {
        var frame by remember { mutableStateOf<NativeWindowFrame?>(null) }
        DisposableEffect(window) {
            window.minimumSize = java.awt.Dimension(900, 600)
            val native = NativeWindowFrame(window, window.windowHandle)
            frame = native
            onDispose { native.close() }
        }
        Column {
            WindowTitleBar(
                TitleBarActions(false, false, {}, {}, {}, "Sign in", {}, {}, {}, {}),
                "Auralis window-frame check", null, state.placement == WindowPlacement.Maximized,
                { frame?.minimize() }, { frame?.toggleMaximize() }, ::exitApplication,
                captionBounds = { frame?.captionBounds = it }, maximizeBounds = { frame?.maximizeBounds = it }
            )
            Text("Checking native window controls. This window will close automatically.")
        }
        LaunchedEffect(Unit) {
            val native = snapshotFlow { frame }.filterNotNull().first()
            val user = User32.INSTANCE
            val hwnd = HWND(Pointer(window.windowHandle))
            val api = NativeLibrary.getInstance("user32")
            fun rect() = RECT().also { check(user.GetWindowRect(hwnd, it)) }
            fun zoomed() = api.getFunction("IsZoomed").invokeInt(arrayOf(hwnd)) != 0
            fun hit(x: Int, y: Int) = user.SendMessage(hwnd, 0x0084, WPARAM(0),
                LPARAM((x.toLong() and 0xffff) or ((y.toLong() and 0xffff) shl 16))).toInt()
            try {
                delay(800)
                val original = rect()
                val caption = native.captionBounds
                check(caption.width > 0) { "Missing caption geometry" }
                check(hit(original.left + caption.center.x.toInt(), original.top + caption.center.y.toInt()) == 2)
                val maximize = native.maximizeBounds
                check(hit(original.left + maximize.center.x.toInt(), original.top + maximize.center.y.toInt()) == 9)
                check(hit(original.left + 1, original.top + 1) == 13)
                user.EnumChildWindows(hwnd, { child, _ ->
                    val packed = (original.left + caption.center.x.toInt()).toLong() and 0xffff or
                        (((original.top + caption.center.y.toInt()).toLong() and 0xffff) shl 16)
                    check(user.SendMessage(child, 0x0084, WPARAM(0), LPARAM(packed)).toInt() == -1) {
                        "Canvas swallowed native caption hit"
                    }
                    true
                }, null)
                println("PASS: native caption, maximize/Snap and resize hit testing")
                native.toggleMaximize()
                delay(1200)
                check(zoomed()) { "Native maximize did not maximize" }
                val monitor = WinUser.MONITORINFO()
                user.GetMonitorInfo(user.MonitorFromWindow(hwnd, 2), monitor)
                // Windows may keep the invisible resize border outside the monitor when maximized.
                // What must fit the work area exactly is the visible client area, including our bar.
                val client = RECT().also { user.GetClientRect(hwnd, it) }
                val origin = POINT().also { it.write() }
                api.getFunction("ClientToScreen").invokeInt(arrayOf(hwnd, origin.pointer))
                origin.read()
                check(origin.x == monitor.rcWork.left && origin.y == monitor.rcWork.top &&
                    client.right == monitor.rcWork.right - monitor.rcWork.left &&
                    client.bottom == monitor.rcWork.bottom - monitor.rcWork.top) {
                    "Client $client at ${origin.x},${origin.y} does not match work area ${monitor.rcWork}"
                }
                check(state.placement == WindowPlacement.Maximized) { "Compose did not track native maximize" }
                user.EnumChildWindows(hwnd, { child, _ ->
                    val childRect = RECT().also { user.GetWindowRect(child, it) }
                    check(childRect.left == origin.x && childRect.top == origin.y &&
                        childRect.right == origin.x + client.right && childRect.bottom == origin.y + client.bottom) {
                        "Canvas clipped when maximized: $childRect, client $client at ${origin.x},${origin.y}"
                    }
                    true
                }, null)
                println("PASS: maximize fills monitor work area; Compose state follows Windows")
                native.toggleMaximize()
                delay(800)
                check(!zoomed())
                val restored = rect()
                check(restored.left == original.left && restored.top == original.top &&
                    restored.right == original.right && restored.bottom == original.bottom) { "Restore changed floating bounds" }
                println("PASS: restore returns to original bounds")
                user.PostMessage(hwnd, 0x00A3, WPARAM(2), LPARAM(0)) // caption double click
                delay(800)
                check(zoomed()) { "Caption double click did not maximize" }
                println("PASS: caption double click")
                native.minimize()
                delay(800)
                check(api.getFunction("IsIconic").invokeInt(arrayOf(hwnd)) != 0) { "Native minimize did not minimize" }
                println("PASS: native minimize")
                user.PostMessage(hwnd, 0x0112, WPARAM(0xF120), LPARAM(0))
                delay(500)
                println("PASS: window frame integration")
                exitApplication()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                error.printStackTrace()
                kotlin.system.exitProcess(1)
            }
        }
    }
}
