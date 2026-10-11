package com.auralis.music.windows

import androidx.compose.ui.geometry.Rect
import com.auralis.music.util.Log
import com.sun.jna.CallbackReference
import com.sun.jna.NativeLibrary
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary.StdCallCallback
import java.awt.Window

/** Keep Windows' resizable frame and system commands underneath our Compose title bar.
 * Moving a borderless AWT window with setLocation bypasses Windows Snap and DWM animations.
 * Native caption hit testing lets Windows own the move/resize/maximize loop instead.
 */
internal class NativeWindowFrame(private val window: Window, handle: Long,
    private val onMaximizeHover: (Boolean) -> Unit = {}) : AutoCloseable {
    private val hwnd = HWND(Pointer(handle))
    private val user = User32.INSTANCE
    private val nativeUser = NativeLibrary.getInstance("user32")
    private val hooks = mutableListOf<Hook>()
    private val originalStyle = user.GetWindowLong(hwnd, WinUser.GWL_STYLE)
    @Volatile var captionBounds: Rect = Rect.Zero
    @Volatile var maximizeBounds: Rect = Rect.Zero
    private var maximizePressed = false
    private var maximizeHovered = false

    private fun hoverMaximize(hovered: Boolean) {
        if (maximizeHovered == hovered) return
        maximizeHovered = hovered
        java.awt.EventQueue.invokeLater { onMaximizeHover(hovered) }
    }

    private fun finishMaximizeClick(overButton: Boolean): LRESULT {
        val pressed = maximizePressed
        maximizePressed = false
        nativeUser.getFunction("ReleaseCapture").invokeInt(emptyArray())
        if (pressed && overButton) toggleMaximize()
        return LRESULT(0)
    }

    private fun interface Procedure : StdCallCallback {
        fun callback(hwnd: HWND, message: Int, w: WPARAM, l: LPARAM): LRESULT
    }
    private data class Hook(val hwnd: HWND, val previous: Pointer, val procedure: Procedure)

    init {
        try {
            hook(hwnd, root = true)
            // AWT's Skia canvas has a child HWND. Forward only frame hits through it; all normal
            // client input (search, buttons, scrolling) continues to the original AWT procedure.
            user.EnumChildWindows(hwnd, { child, _ -> hook(child, root = false); true }, null)
            user.SetWindowLong(hwnd, WinUser.GWL_STYLE,
                originalStyle or WinUser.WS_CAPTION or WinUser.WS_THICKFRAME or
                    WinUser.WS_SYSMENU or WinUser.WS_MINIMIZEBOX or WinUser.WS_MAXIMIZEBOX)
            refreshFrame()
            Log.i("AuralisWindow", "Native frame installed (${hooks.size} window handles)")
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun hook(target: HWND, root: Boolean) {
        val previous = checkNotNull(nativeUser.getFunction("GetWindowLongPtrW").invokePointer(arrayOf(target, -4))) {
            "Window procedure unavailable"
        }
        val procedure = Procedure { current, message, w, l ->
            try {
                when {
                    root && message == 0x00A0 -> { // Non-client hover bypasses Compose's hoverable.
                        hoverMaximize(w.toInt() == 9)
                        val tracking = Memory(if (Native.POINTER_SIZE == 8) 24L else 16L)
                        tracking.clear()
                        tracking.setInt(0, tracking.size().toInt())
                        tracking.setInt(4, 0x12) // TME_LEAVE | TME_NONCLIENT
                        tracking.setPointer(8, hwnd.pointer)
                        nativeUser.getFunction("TrackMouseEvent").invokeInt(arrayOf(tracking))
                        user.CallWindowProc(previous, current, message, w, l)
                    }
                    root && message == 0x02A2 -> {
                        hoverMaximize(false)
                        user.CallWindowProc(previous, current, message, w, l)
                    }
                    root && (message == 0x00A1 || message == 0x00A3) && w.toInt() == 9 -> {
                        // AWT has no native caption button here. Capture until release so dragging
                        // off the button cancels the click, just like a normal Windows control.
                        maximizePressed = true
                        nativeUser.getFunction("SetCapture").invokePointer(arrayOf(hwnd))
                        LRESULT(0)
                    }
                    root && message == 0x00A2 && maximizePressed -> {
                        val x = (l.toLong() and 0xffff).toShort().toInt()
                        val y = ((l.toLong() shr 16) and 0xffff).toShort().toInt()
                        finishMaximizeClick(hitTest(x, y) == 9)
                    }
                    root && message == 0x0202 && maximizePressed -> {
                        val point = POINT((l.toLong() and 0xffff).toShort().toInt(),
                            ((l.toLong() shr 16) and 0xffff).toShort().toInt())
                        point.write()
                        nativeUser.getFunction("ClientToScreen").invokeInt(arrayOf(hwnd, point.pointer))
                        point.read()
                        finishMaximizeClick(hitTest(point.x, point.y) == 9)
                    }
                    root && (message == 0x0215 || message == 0x001F) -> {
                        maximizePressed = false
                        hoverMaximize(false)
                        user.CallWindowProc(previous, current, message, w, l)
                    }
                    root && message == 0x0200 -> {
                        hoverMaximize(false)
                        user.CallWindowProc(previous, current, message, w, l)
                    }
                    root && message == 0x0083 -> { // WM_NCCALCSIZE: draw our bar in the client area.
                        if (isMaximized()) {
                            val info = WinUser.MONITORINFO()
                            user.GetMonitorInfo(user.MonitorFromWindow(hwnd, 2), info)
                            val rect = Pointer(l.toLong()) // first RECT in NCCALCSIZE_PARAMS
                            rect.setInt(0, maxOf(rect.getInt(0), info.rcWork.left))
                            rect.setInt(4, maxOf(rect.getInt(4), info.rcWork.top))
                            rect.setInt(8, minOf(rect.getInt(8), info.rcWork.right))
                            rect.setInt(12, minOf(rect.getInt(12), info.rcWork.bottom))
                        }
                        LRESULT(0)
                    }
                    message == 0x0084 -> { // WM_NCHITTEST (signed screen coordinates).
                        val x = (l.toLong() and 0xffff).toShort().toInt()
                        val y = ((l.toLong() shr 16) and 0xffff).toShort().toInt()
                        val hit = hitTest(x, y)
                        if (hit != 1) LRESULT(if (root) hit.toLong() else -1L) // HTTRANSPARENT
                        else user.CallWindowProc(previous, current, message, w, l)
                    }
                    root && message == 0x0024 -> { // WM_GETMINMAXINFO, including secondary monitors.
                        val result = user.CallWindowProc(previous, current, message, w, l)
                        val info = WinUser.MONITORINFO()
                        user.GetMonitorInfo(user.MonitorFromWindow(hwnd, 2), info)
                        val p = Pointer(l.toLong())
                        p.setInt(8, info.rcWork.right - info.rcWork.left)
                        p.setInt(12, info.rcWork.bottom - info.rcWork.top)
                        p.setInt(16, info.rcWork.left - info.rcMonitor.left)
                        p.setInt(20, info.rcWork.top - info.rcMonitor.top)
                        val scale = window.graphicsConfiguration.defaultTransform
                        p.setInt(24, (window.minimumSize.width * scale.scaleX).toInt())
                        p.setInt(28, (window.minimumSize.height * scale.scaleY).toInt())
                        result
                    }
                    else -> user.CallWindowProc(previous, current, message, w, l)
                }
            } catch (error: Exception) {
                Log.w("AuralisWindow", "Native frame message failed: ${error.javaClass.simpleName}")
                user.CallWindowProc(previous, current, message, w, l)
            }
        }
        nativeUser.getFunction("SetWindowLongPtrW").invokePointer(
            arrayOf(target, -4, CallbackReference.getFunctionPointer(procedure)))
        // Keep callbacks alive until the original native procedures have been restored.
        hooks += Hook(target, previous, procedure)
    }

    private fun hitTest(screenX: Int, screenY: Int): Int {
        val rect = RECT()
        user.GetWindowRect(hwnd, rect)
        val x = screenX - rect.left
        val y = screenY - rect.top
        if (!isMaximized()) {
            val border = (8 * window.graphicsConfiguration.defaultTransform.scaleX).toInt()
            val left = x < border
            val right = x >= rect.right - rect.left - border
            val top = y < border
            val bottom = y >= rect.bottom - rect.top - border
            return when {
                top && left -> 13; top && right -> 14; bottom && left -> 16; bottom && right -> 17
                left -> 10; right -> 11; top -> 12; bottom -> 15
                else -> captionHit(screenX, screenY)
            }
        }
        return captionHit(screenX, screenY)
    }

    private fun captionHit(screenX: Int, screenY: Int): Int {
        val point = POINT(screenX, screenY)
        point.write()
        nativeUser.getFunction("ScreenToClient").invokeInt(arrayOf(hwnd, point.pointer))
        point.read()
        val offset = androidx.compose.ui.geometry.Offset(point.x.toFloat(), point.y.toFloat())
        return when {
            maximizeBounds.contains(offset) -> 9 // HTMAXBUTTON: Windows 11 Snap layouts, native toggle.
            captionBounds.contains(offset) -> 2 // HTCAPTION: drag-to-top, drag-to-restore, double click.
            else -> 1 // HTCLIENT
        }
    }

    fun minimize() = command(0xF020)
    private fun isMaximized() = nativeUser.getFunction("IsZoomed").invokeInt(arrayOf(hwnd)) != 0
    fun toggleMaximize() = command(if (isMaximized()) 0xF120 else 0xF030)
    private fun command(command: Int) { user.PostMessage(hwnd, 0x0112, WPARAM(command.toLong()), LPARAM(0)) }

    private fun refreshFrame() {
        user.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x0020 or 0x0001 or 0x0002 or 0x0004 or 0x0010)
    }

    override fun close() {
        hooks.asReversed().forEach { hook ->
            if (user.IsWindow(hook.hwnd)) nativeUser.getFunction("SetWindowLongPtrW")
                .invokePointer(arrayOf(hook.hwnd, -4, hook.previous))
        }
        if (user.IsWindow(hwnd)) {
            user.SetWindowLong(hwnd, WinUser.GWL_STYLE, originalStyle)
            refreshFrame()
        }
        hooks.clear()
    }
}
