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
/** Which caption button (HTMINBUTTON 8, HTMAXBUTTON 9, HTCLOSE 20; 0 = none) the pointer is over. */
data class CaptionHover(val button: Int = 0, val pressed: Boolean = false)

internal class NativeWindowFrame(private val window: Window, handle: Long,
    private val onCaptionHover: (CaptionHover) -> Unit = {}) : AutoCloseable {
    private val hwnd = HWND(Pointer(handle))
    private val user = User32.INSTANCE
    private val nativeUser = NativeLibrary.getInstance("user32")
    private val hooks = mutableListOf<Hook>()
    private val originalStyle = user.GetWindowLong(hwnd, WinUser.GWL_STYLE)
    @Volatile var captionBounds: Rect = Rect.Zero
    @Volatile var brandBounds: Rect = Rect.Zero
    @Volatile var minimizeBounds: Rect = Rect.Zero
    @Volatile var maximizeBounds: Rect = Rect.Zero
    @Volatile var closeBounds: Rect = Rect.Zero
    /** Runs the app's close (tray, confirmation) instead of a bare SC_CLOSE. */
    @Volatile var onClose: (() -> Unit)? = null
    /** The title bar's search field: inside the drag area, but clicks must reach it. */
    @Volatile var searchBounds: Rect = Rect.Zero
    @Volatile var onTaskbarMessage: ((Int, Long) -> Boolean)? = null
    private val renderPause = MinimisedRenderPause(window)
    /** The caption button under the press (Windows' own buttons capture until release), or 0. */
    private var pressedButton = 0
    private var hover = CaptionHover()
    /** Change the real non-client frame without recreating Compose or interrupting playback. */
    var systemDecorated = false
        set(value) {
            if (field == value) return
            field = value
            pressedButton = 0
            hoverCaption(0)
            // Our buttons aren't shown under the system caption; they report fresh bounds when back.
            minimizeBounds = Rect.Zero; maximizeBounds = Rect.Zero; closeBounds = Rect.Zero
            refreshFrame()
            window.validate()
        }

    private fun hoverCaption(button: Int) {
        val next = CaptionHover(button, button != 0 && button == pressedButton)
        if (hover == next) return
        hover = next
        java.awt.EventQueue.invokeLater { onCaptionHover(next) }
    }

    private fun finishCaptionClick(hit: Int): LRESULT {
        val pressed = pressedButton
        pressedButton = 0
        nativeUser.getFunction("ReleaseCapture").invokeInt(emptyArray())
        hoverCaption(if (hit in CAPTION_BUTTONS) hit else 0)
        if (pressed != 0 && hit == pressed) when (pressed) {
            8 -> minimize()
            9 -> toggleMaximize()
            20 -> onClose?.let { java.awt.EventQueue.invokeLater(it) } ?: command(0xF060)
        }
        return LRESULT(0)
    }

    /** Hit test for a client-coordinate mouse message (sent to us while the press is captured). */
    private fun clientHit(l: LPARAM): Int {
        val point = POINT((l.toLong() and 0xffff).toShort().toInt(), ((l.toLong() shr 16) and 0xffff).toShort().toInt())
        point.write()
        nativeUser.getFunction("ClientToScreen").invokeInt(arrayOf(hwnd, point.pointer))
        point.read()
        return hitTest(point.x, point.y)
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
            java.awt.EventQueue.invokeLater { cursorSource = findSkiaLayer(window)?.let { it.canvas ?: it } }
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
                    root && onTaskbarMessage?.invoke(message, w.toLong()) == true -> LRESULT(0)
                    // AWT's undecorated peer suppresses these messages itself. Let Windows draw,
                    // measure and operate the caption when the system title bar is selected.
                    root && systemDecorated && (message == 0x0083 || message == 0x0084 ||
                        message == 0x0085 || message == 0x0086 || message in 0x00A0..0x00AD) ->
                        user.DefWindowProc(current, message, w, l)
                    !root && systemDecorated && message == 0x0084 ->
                        user.CallWindowProc(previous, current, message, w, l)
                    root && message == 0x0005 -> { // WM_SIZE: see MinimisedRenderPause.
                        if (w.toInt() == 1) renderPause.minimised(true)
                        // AWT takes the restored size first; then a frame is drawn before Windows goes on.
                        user.CallWindowProc(previous, current, message, w, l)
                            .also { if (w.toInt() == 0 || w.toInt() == 2) renderPause.minimised(false) }
                    }
                    root && message == 0x0014 -> { // WM_ERASEBKGND: area the app hasn't drawn yet is dark, never white.
                        val area = RECT().also { user.GetClientRect(current, it) }
                        nativeUser.getFunction("FillRect").invokeInt(arrayOf(Pointer(w.toLong()), area.pointer.also { area.write() }, backgroundBrush))
                        LRESULT(1)
                    }
                    root && message == 0x00A0 -> { // Non-client hover bypasses Compose's hoverable.
                        hoverCaption(w.toInt().takeIf { it in CAPTION_BUTTONS } ?: 0)
                        val tracking = Memory(if (Native.POINTER_SIZE == 8) 24L else 16L)
                        tracking.clear()
                        tracking.setInt(0, tracking.size().toInt())
                        tracking.setInt(4, 0x12) // TME_LEAVE | TME_NONCLIENT
                        tracking.setPointer(8, hwnd.pointer)
                        nativeUser.getFunction("TrackMouseEvent").invokeInt(arrayOf(tracking))
                        user.CallWindowProc(previous, current, message, w, l)
                    }
                    root && message == 0x02A2 && pressedButton == 0 -> {
                        hoverCaption(0)
                        user.CallWindowProc(previous, current, message, w, l)
                    }
                    root && (message == 0x00A1 || message == 0x00A3) && w.toInt() in CAPTION_BUTTONS -> {
                        // AWT has no native caption buttons (DefWindowProc would paint classic ones).
                        // Capture until release so dragging off a button cancels the click, like Windows.
                        pressedButton = w.toInt()
                        hoverCaption(pressedButton)
                        nativeUser.getFunction("SetCapture").invokePointer(arrayOf(hwnd))
                        LRESULT(0)
                    }
                    root && message == 0x00A2 && pressedButton != 0 -> {
                        val x = (l.toLong() and 0xffff).toShort().toInt()
                        val y = ((l.toLong() shr 16) and 0xffff).toShort().toInt()
                        finishCaptionClick(hitTest(x, y))
                    }
                    root && message == 0x0202 && pressedButton != 0 -> finishCaptionClick(clientHit(l))
                    root && (message == 0x0215 || message == 0x001F || (message == 0x0006 && (w.toInt() and 0xffff) == 0)) -> {
                        pressedButton = 0
                        hoverCaption(0)
                        user.CallWindowProc(previous, current, message, w, l)
                    }
                    root && message == 0x0200 -> {
                        // While captured, the pressed button stays lit only with the pointer over it.
                        hoverCaption(if (pressedButton != 0 && clientHit(l) == pressedButton) pressedButton else 0)
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
                    message == 0x0020 && (l.toLong() and 0xffff) == 1L -> { // WM_SETCURSOR over HTCLIENT.
                        val result = user.CallWindowProc(previous, current, message, w, l)
                        if (clearStaleSizingCursor()) LRESULT(1) else result
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
        val caption = captionHit(screenX, screenY)
        if (isMaximized()) return caption
        // There is no invisible border (WM_NCCALCSIZE keeps the whole rect as client), so the resize
        // band sits inside the visible window: keep it a thin strip at the very edge. At 8px x scale
        // the top ~12px of the title bar, buttons included, was a resize zone and the rest wasn't.
        val scale = window.graphicsConfiguration.defaultTransform.scaleX
        val side = (5 * scale).toInt().coerceAtLeast(1)
        val topBand = (3 * scale).toInt().coerceAtLeast(1)
        val info = WinUser.MONITORINFO()
        user.GetMonitorInfo(user.MonitorFromWindow(hwnd, 2), info)
        // An edge flush with the screen (or past it) has no resize band, like a native window whose
        // border would lie off-screen: a window filling the screen behaves as maximized, and the corner
        // pixel lights Close (Fitts' law). Bottom compares with the work area, as the taskbar covers it.
        val left = x < side && rect.left > info.rcMonitor.left
        val right = x >= rect.right - rect.left - side && rect.right < info.rcMonitor.right
        val top = y < topBand && rect.top > info.rcMonitor.top
        val bottom = y >= rect.bottom - rect.top - side && rect.bottom < info.rcWork.bottom
        return when {
            top && left -> 13; top && right -> 14; bottom && left -> 16; bottom && right -> 17
            left -> 10; right -> 11; top -> 12; bottom -> 15
            else -> caption
        }
    }

    private fun captionHit(screenX: Int, screenY: Int): Int {
        val point = POINT(screenX, screenY)
        point.write()
        nativeUser.getFunction("ScreenToClient").invokeInt(arrayOf(hwnd, point.pointer))
        point.read()
        val offset = androidx.compose.ui.geometry.Offset(point.x.toFloat(), point.y.toFloat())
        // Caption buttons reach up to the top edge and Close to the right edge, so the corner pixel is
        // still Close even where Compose's bounds stop a pixel short of the client edge.
        fun button(bounds: Rect, toRight: Boolean = false) = !bounds.isEmpty && offset.y < bounds.bottom &&
            offset.x >= bounds.left && (toRight || offset.x < bounds.right)
        return when {
            button(closeBounds, toRight = true) -> 20 // HTCLOSE
            button(maximizeBounds) -> 9 // HTMAXBUTTON: Windows 11 Snap layouts, native toggle.
            button(minimizeBounds) -> 8 // HTMINBUTTON
            searchBounds.contains(offset) -> 1 // HTCLIENT: the search field.
            (captionBounds.contains(offset) || brandBounds.contains(offset)) -> 2 // HTCAPTION: drag-to-top, drag-to-restore, double click.
            else -> 1 // HTCLIENT
        }
    }

    /** The loading frame's colour (0x0F0E13) as a GDI brush (COLORREF is 0x00BBGGRR). */
    private val backgroundBrush by lazy {
        NativeLibrary.getInstance("gdi32").getFunction("CreateSolidBrush").invokePointer(arrayOf(0x130E0F))
    }

    /** Compose sets its pointer icon on the Skia layer's canvas; found on the EDT, read from here. */
    @Volatile private var cursorSource: java.awt.Component? = null
    private fun systemCursor(id: Int): Pointer? =
        nativeUser.getFunction("LoadCursorW").invokePointer(arrayOf(null, Pointer.createConstant(id.toLong())))
    /** IDC_SIZENWSE, IDC_SIZENESW, IDC_SIZEWE, IDC_SIZENS: the cursors DefWindowProc shows on the frame. */
    private val sizingCursors by lazy { listOf(32642, 32643, 32644, 32645).mapNotNull(::systemCursor).toSet() }

    /**
     * Crossing the resize band leaves Windows' sizing cursor set, and AWT doesn't replace it when the
     * pointer moves on into a Compose control (the drag area, being HTCAPTION, does get the arrow back).
     * So the title bar's buttons kept a resize cursor only if the pointer had come in over the edge.
     * Put back the cursor Compose asked for whenever a sizing cursor is still showing over the client.
     */
    private fun clearStaleSizingCursor(): Boolean {
        val now = nativeUser.getFunction("GetCursor").invokePointer(emptyArray()) ?: return false
        if (now !in sizingCursors) return false
        val source = cursorSource
        if (source == null) java.awt.EventQueue.invokeLater {
            cursorSource = findSkiaLayer(window)?.let { it.canvas ?: it }
        }
        val id = when (source?.cursor?.type ?: java.awt.Cursor.DEFAULT_CURSOR) {
            java.awt.Cursor.E_RESIZE_CURSOR, java.awt.Cursor.W_RESIZE_CURSOR -> return false // the sidebar handle's own cursor
            java.awt.Cursor.HAND_CURSOR -> 32649
            java.awt.Cursor.TEXT_CURSOR -> 32513
            java.awt.Cursor.WAIT_CURSOR -> 32514
            java.awt.Cursor.CROSSHAIR_CURSOR -> 32515
            java.awt.Cursor.MOVE_CURSOR -> 32646
            else -> 32512 // IDC_ARROW
        }
        nativeUser.getFunction("SetCursor").invokePointer(arrayOf(systemCursor(id)))
        return true
    }

    private fun findSkiaLayer(root: java.awt.Component): org.jetbrains.skiko.SkiaLayer? =
        root as? org.jetbrains.skiko.SkiaLayer
            ?: (root as? java.awt.Container)?.components?.firstNotNullOfOrNull(::findSkiaLayer)

    private companion object { val CAPTION_BUTTONS = setOf(8, 9, 20) }

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

/**
 * Hides or reveals a window to the compositor while it keeps drawing (DWMWA_CLOAK). Shown again from
 * the tray, an OpenGL window displayed black until Skia's first buffer swap; cloaked, it draws that
 * frame unseen and appears with its content.
 */
internal fun setWindowCloaked(handle: Long, cloaked: Boolean) = runCatching {
    val value = Memory(4).apply { setInt(0, if (cloaked) 1 else 0) }
    NativeLibrary.getInstance("dwmapi").getFunction("DwmSetWindowAttribute")
        .invokeInt(arrayOf(Pointer(handle), 13, value, 4)) == 0
}.getOrDefault(false)
