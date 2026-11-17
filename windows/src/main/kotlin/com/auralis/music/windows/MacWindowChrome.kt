package com.auralis.music.windows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.auralis.music.util.Log
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

private const val MAC_CHROME_TAG = "MacWindowChrome"
private const val NS_WINDOW_STYLE_MASK_FULL_SCREEN = 1L shl 14
private const val NS_WINDOW_STYLE_MASK_FULL_SIZE_CONTENT_VIEW = 1L shl 15

internal fun shouldDisableNativeTitleBarDrag(styleMask: Long, titlebarAppearsTransparent: Boolean): Boolean =
    (styleMask and NS_WINDOW_STYLE_MASK_FULL_SIZE_CONTENT_VIEW) != 0L && titlebarAppearsTransparent

internal fun isMacWindowFullscreen(styleMask: Long): Boolean =
    (styleMask and NS_WINDOW_STYLE_MASK_FULL_SCREEN) != 0L

internal fun rectToLogicalPoints(rect: Rect, density: Float): Rect {
    if (rect.isEmpty) return Rect.Zero
    val scale = if (density.isFinite() && density > 0f) density else 1f
    return if (scale == 1f) rect else Rect(
        left = rect.left / scale,
        top = rect.top / scale,
        right = rect.right / scale,
        bottom = rect.bottom / scale
    )
}

internal fun isMacTitleBarDragPoint(
    x: Float,
    yTopDown: Float,
    brandBounds: Rect,
    captionBounds: Rect,
    trailingBounds: Rect,
    searchBounds: Rect,
    titleBarEnabled: Boolean,
    modalOverlayActive: Boolean
): Boolean {
    if (!titleBarEnabled || modalOverlayActive) return false
    if (!x.isFinite() || !yTopDown.isFinite()) return false
    val point = Offset(x, yTopDown)
    if (searchBounds.contains(point)) return false
    return brandBounds.contains(point) ||
        captionBounds.contains(point) ||
        trailingBounds.contains(point)
}

internal enum class MacTitleBarDoubleClickAction { MAXIMIZE, MINIMIZE, NONE }

internal fun parseMacDoubleClickAction(raw: String?): MacTitleBarDoubleClickAction =
    when (raw?.trim()?.lowercase()) {
        "none" -> MacTitleBarDoubleClickAction.NONE
        "minimize" -> MacTitleBarDoubleClickAction.MINIMIZE
        else -> MacTitleBarDoubleClickAction.MAXIMIZE
    }

/**
 * Coordinates macOS AppKit / WindowServer native dragging and double-click zoom for full-content
 * transparent-titlebar Compose windows.
 *
 * `AWTView.mouseDownCanMoveWindow` always returns `NO` for full-content transparent-titlebar
 * windows so AppKit never intercepts or caches view-level titlebar drags over interactive regions
 * such as the titlebar search field. Instead, `AWTView.mouseDown:` inspects each click's exact
 * `locationInWindow`: non-draggable regions (the search field, buttons, body, and modal overlays)
 * fall straight through to `-[AWTView mouseDown:]` so Compose handles clicks and text selection
 * normally, while empty titlebar chrome (`brandBounds`, empty `captionBounds`, `trailingBounds`)
 * delegates to `NSWindow.performWindowDragWithEvent:` / native double-click zoom so macOS Window
 * Tiling (auto-resize on screen-edge drag) and Spaces dragging work natively.
 */
internal object MacWindowChrome {

    @Structure.FieldOrder("x", "y")
    internal class NSPoint : Structure(), Structure.ByValue {
        @JvmField var x: Double = 0.0
        @JvmField var y: Double = 0.0
    }

    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, arg: Pointer?): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, arg: String): Pointer?
        fun class_getInstanceMethod(cls: Pointer, sel: Pointer): Pointer?
        fun method_getImplementation(method: Pointer): Pointer?
        fun method_getTypeEncoding(method: Pointer): Pointer?
        fun class_addMethod(cls: Pointer, name: Pointer, imp: Callback, types: String): Boolean
        fun method_setImplementation(method: Pointer, imp: Callback): Pointer?

        companion object {
            val INSTANCE: ObjC = Native.load("objc", ObjC::class.java)
        }
    }

    private interface MsgLong : Library {
        fun objc_msgSend(receiver: Pointer, sel: Pointer): Long
        fun objc_msgSend(receiver: Pointer, sel: Pointer, arg: Pointer): Long

        companion object {
            val INSTANCE: MsgLong = Native.load("objc", MsgLong::class.java)
        }
    }

    private interface MsgVoid : Library {
        fun objc_msgSend(receiver: Pointer, sel: Pointer, arg: Pointer?)

        companion object {
            val INSTANCE: MsgVoid = Native.load("objc", MsgVoid::class.java)
        }
    }

    private interface MsgPoint : Library {
        fun objc_msgSend(receiver: Pointer, sel: Pointer): NSPoint

        companion object {
            val INSTANCE: MsgPoint = Native.load("objc", MsgPoint::class.java)
        }
    }

    private interface BoolImp : Callback {
        fun invoke(self: Pointer, sel: Pointer): Byte
    }

    private interface MouseEventImp : Callback {
        fun invoke(self: Pointer, sel: Pointer, event: Pointer)
    }

    internal class WindowHandle internal constructor(
        private val awtWindow: Window
    ) : AutoCloseable {
        @Volatile var titleBarEnabled: Boolean = true
        @Volatile var brandBounds: Rect = Rect.Zero
        @Volatile var captionBounds: Rect = Rect.Zero
        @Volatile var trailingBounds: Rect = Rect.Zero
        @Volatile var searchBounds: Rect = Rect.Zero
        @Volatile internal var nsWindowPtr: Long = 0L

        fun setBrandBounds(bounds: Rect, density: Float) {
            brandBounds = rectToLogicalPoints(bounds, density)
        }

        fun setCaptionBounds(bounds: Rect, density: Float) {
            captionBounds = rectToLogicalPoints(bounds, density)
        }

        fun setTrailingBounds(bounds: Rect, density: Float) {
            trailingBounds = rectToLogicalPoints(bounds, density)
        }

        fun setSearchBounds(bounds: Rect, density: Float) {
            searchBounds = rectToLogicalPoints(bounds, density)
        }

        fun clearBounds() {
            brandBounds = Rect.Zero
            captionBounds = Rect.Zero
            trailingBounds = Rect.Zero
            searchBounds = Rect.Zero
        }

        internal fun windowHeight(): Float = awtWindow.height.toFloat()

        internal fun matchesAwtWindow(nsTitle: String?): Boolean {
            if (!awtWindow.isDisplayable) return false
            val awtTitle = (awtWindow as? Frame)?.title
            return awtTitle.isNullOrEmpty() || awtTitle == nsTitle
        }

        internal fun isDraggablePoint(x: Float, yTopDown: Float): Boolean =
            isMacTitleBarDragPoint(
                x = x,
                yTopDown = yTopDown,
                brandBounds = brandBounds,
                captionBounds = captionBounds,
                trailingBounds = trailingBounds,
                searchBounds = searchBounds,
                titleBarEnabled = titleBarEnabled,
                modalOverlayActive = modalOverlayCount.get() > 0
            )

        override fun close() {
            handles.remove(this)
            val ptr = nsWindowPtr
            if (ptr != 0L) {
                handlesByNsWindow.remove(ptr, this)
                nsWindowPtr = 0L
            }
        }
    }

    private val installed = AtomicBoolean(false)
    private val modalOverlayCount = AtomicInteger(0)
    private val handles = CopyOnWriteArrayList<WindowHandle>()
    private val handlesByNsWindow = ConcurrentHashMap<Long, WindowHandle>()

    @Volatile private var origMouseDownCanMoveImp: Function? = null
    @Volatile private var origMouseDownImp: Function? = null
    @Volatile private var mouseDownCanMoveCallback: BoolImp? = null
    @Volatile private var mouseDownCallback: MouseEventImp? = null

    private val selWindow by lazy { ObjC.INSTANCE.sel_registerName("window") }
    private val selStyleMask by lazy { ObjC.INSTANCE.sel_registerName("styleMask") }
    private val selTitlebarTransparent by lazy { ObjC.INSTANCE.sel_registerName("titlebarAppearsTransparent") }
    private val selRespondsToSelector by lazy { ObjC.INSTANCE.sel_registerName("respondsToSelector:") }
    private val selTitle by lazy { ObjC.INSTANCE.sel_registerName("title") }
    private val selUtf8String by lazy { ObjC.INSTANCE.sel_registerName("UTF8String") }
    private val selLocationInWindow by lazy { ObjC.INSTANCE.sel_registerName("locationInWindow") }
    private val selClickCount by lazy { ObjC.INSTANCE.sel_registerName("clickCount") }
    private val selPerformWindowDrag by lazy { ObjC.INSTANCE.sel_registerName("performWindowDragWithEvent:") }
    private val selPerformZoom by lazy { ObjC.INSTANCE.sel_registerName("performZoom:") }
    private val selPerformMiniaturize by lazy { ObjC.INSTANCE.sel_registerName("performMiniaturize:") }
    private val selStandardUserDefaults by lazy { ObjC.INSTANCE.sel_registerName("standardUserDefaults") }
    private val selStringWithUtf8 by lazy { ObjC.INSTANCE.sel_registerName("stringWithUTF8String:") }
    private val selStringForKey by lazy { ObjC.INSTANCE.sel_registerName("stringForKey:") }

    fun attachWindow(window: Window): WindowHandle {
        val handle = WindowHandle(window)
        handles.add(handle)
        return handle
    }

    fun acquireModalBlock(): AutoCloseable {
        modalOverlayCount.incrementAndGet()
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) {
                modalOverlayCount.decrementAndGet()
            }
        }
    }

    @Composable
    fun ModalOverlayEffect() {
        DisposableEffect(Unit) {
            val token = acquireModalBlock()
            onDispose { token.close() }
        }
    }

    fun installIfMac(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("mac")) return false
        if (installed.get()) return true

        Toolkit.getDefaultToolkit()
        val apply = Runnable { installOnEdt() }
        if (SwingUtilities.isEventDispatchThread()) {
            apply.run()
        } else {
            runCatching { SwingUtilities.invokeAndWait(apply) }
                .onFailure { Log.w(MAC_CHROME_TAG, "Failed to install macOS titlebar drag hook: ${it.message}") }
        }
        return installed.get()
    }

    private fun installOnEdt() {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val objc = ObjC.INSTANCE
            val msgLong = MsgLong.INSTANCE
            val msgPoint = MsgPoint.INSTANCE
            val msgVoid = MsgVoid.INSTANCE
            val awtViewClass = objc.objc_getClass("AWTView") ?: run {
                installed.set(false)
                return
            }

            val selMouseDownCanMove = objc.sel_registerName("mouseDownCanMoveWindow")
            val canMoveMethod = objc.class_getInstanceMethod(awtViewClass, selMouseDownCanMove) ?: run {
                installed.set(false)
                return
            }
            val canMoveEncoding = objc.method_getTypeEncoding(canMoveMethod)?.getString(0)?.takeIf { it.isNotBlank() } ?: "B@:"
            origMouseDownCanMoveImp = objc.method_getImplementation(canMoveMethod)?.let { Function.getFunction(it) }

            val canMoveCallback = object : BoolImp {
                override fun invoke(self: Pointer, sel: Pointer): Byte {
                    try {
                        val nsWindow = objc.objc_msgSend(self, selWindow)
                        if (nsWindow != null) {
                            val styleMask = msgLong.objc_msgSend(nsWindow, selStyleMask)
                            if (isTransparentFullContentWindow(styleMask, nsWindow)) {
                                // Always return NO from mouseDownCanMoveWindow for full-content
                                // transparent-titlebar windows so AppKit never intercepts or caches
                                // view-level drags over the search field; empty titlebar regions are
                                // initiated explicitly from mouseDown: via performWindowDragWithEvent:.
                                return 0
                            }
                        }
                    } catch (_: Throwable) {
                        return 0
                    }
                    val superImp = origMouseDownCanMoveImp ?: return 1
                    return (superImp.invoke(Byte::class.java, arrayOf(self, sel)) as? Byte) ?: 1
                }
            }
            mouseDownCanMoveCallback = canMoveCallback

            val addedCanMove = objc.class_addMethod(awtViewClass, selMouseDownCanMove, canMoveCallback, canMoveEncoding)
            if (!addedCanMove) {
                val previous = objc.method_setImplementation(canMoveMethod, canMoveCallback)
                if (previous != null) {
                    origMouseDownCanMoveImp = Function.getFunction(previous)
                }
            }

            val selMouseDown = objc.sel_registerName("mouseDown:")
            val mouseDownMethod = objc.class_getInstanceMethod(awtViewClass, selMouseDown)
            if (mouseDownMethod != null) {
                val mouseDownEncoding = objc.method_getTypeEncoding(mouseDownMethod)?.getString(0)?.takeIf { it.isNotBlank() } ?: "v@:@"
                origMouseDownImp = objc.method_getImplementation(mouseDownMethod)?.let { Function.getFunction(it) }

                val downCallback = object : MouseEventImp {
                    override fun invoke(self: Pointer, sel: Pointer, event: Pointer) {
                        var handled = false
                        try {
                            val nsWindow = objc.objc_msgSend(self, selWindow)
                            if (nsWindow != null) {
                                val styleMask = msgLong.objc_msgSend(nsWindow, selStyleMask)
                                if (isTransparentFullContentWindow(styleMask, nsWindow) && !isMacWindowFullscreen(styleMask)) {
                                    val handle = resolveHandle(nsWindow)
                                    if (handle != null) {
                                        val loc = msgPoint.objc_msgSend(event, selLocationInWindow)
                                        val yTopDown = handle.windowHeight() - loc.y.toFloat()
                                        if (handle.isDraggablePoint(loc.x.toFloat(), yTopDown)) {
                                            handled = true
                                            val clicks = msgLong.objc_msgSend(event, selClickCount)
                                            if (clicks == 2L) {
                                                performTitleBarDoubleClick(nsWindow, msgVoid)
                                            } else {
                                                msgVoid.objc_msgSend(nsWindow, selPerformWindowDrag, event)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                            handled = false
                        }
                        if (!handled) {
                            origMouseDownImp?.invokeVoid(arrayOf(self, sel, event))
                        }
                    }
                }
                mouseDownCallback = downCallback

                val addedDown = objc.class_addMethod(awtViewClass, selMouseDown, downCallback, mouseDownEncoding)
                if (!addedDown) {
                    val previous = objc.method_setImplementation(mouseDownMethod, downCallback)
                    if (previous != null) {
                        origMouseDownImp = Function.getFunction(previous)
                    }
                }
            }
        }.onFailure {
            installed.set(false)
            Log.w(MAC_CHROME_TAG, "Failed to hook AWTView titlebar drag handlers: ${it.message}")
        }
    }

    private fun isTransparentFullContentWindow(styleMask: Long, nsWindow: Pointer): Boolean {
        if ((styleMask and NS_WINDOW_STYLE_MASK_FULL_SIZE_CONTENT_VIEW) == 0L) return false
        val msgLong = MsgLong.INSTANCE
        val responds = (msgLong.objc_msgSend(nsWindow, selRespondsToSelector, selTitlebarTransparent) and 0xFFL) != 0L
        val transparent = responds && (msgLong.objc_msgSend(nsWindow, selTitlebarTransparent) and 0xFFL) != 0L
        return shouldDisableNativeTitleBarDrag(styleMask, transparent)
    }

    private fun resolveHandle(nsWindow: Pointer): WindowHandle? {
        val ptr = Pointer.nativeValue(nsWindow)
        handlesByNsWindow[ptr]?.let { cached ->
            if (cached in handles) return cached
            handlesByNsWindow.remove(ptr, cached)
        }
        val titlePtr = ObjC.INSTANCE.objc_msgSend(nsWindow, selTitle)
        val nsTitle = titlePtr?.let { ObjC.INSTANCE.objc_msgSend(it, selUtf8String)?.getString(0) }
        val matched = handles.firstOrNull { it.matchesAwtWindow(nsTitle) } ?: return null
        matched.nsWindowPtr = ptr
        handlesByNsWindow[ptr] = matched
        return matched
    }


    private fun performTitleBarDoubleClick(nsWindow: Pointer, msgVoid: MsgVoid) {
        when (parseMacDoubleClickAction(readAppleDoubleClickAction())) {
            MacTitleBarDoubleClickAction.NONE -> Unit
            MacTitleBarDoubleClickAction.MINIMIZE -> msgVoid.objc_msgSend(nsWindow, selPerformMiniaturize, null)
            MacTitleBarDoubleClickAction.MAXIMIZE -> msgVoid.objc_msgSend(nsWindow, selPerformZoom, null)
        }
    }

    private fun readAppleDoubleClickAction(): String? {
        val objc = ObjC.INSTANCE
        val defaultsClass = objc.objc_getClass("NSUserDefaults") ?: return null
        val defaults = objc.objc_msgSend(defaultsClass, selStandardUserDefaults) ?: return null
        val stringClass = objc.objc_getClass("NSString") ?: return null
        val key = objc.objc_msgSend(stringClass, selStringWithUtf8, "AppleActionOnDoubleClick") ?: return null
        val value = objc.objc_msgSend(defaults, selStringForKey, key) ?: return null
        return objc.objc_msgSend(value, selUtf8String)?.getString(0)
    }
}
