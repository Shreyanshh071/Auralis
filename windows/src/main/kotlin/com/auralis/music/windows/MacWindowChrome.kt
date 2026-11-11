package com.auralis.music.windows

import com.auralis.music.util.Log
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Toolkit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

private const val MAC_CHROME_TAG = "MacWindowChrome"
private const val NS_WINDOW_STYLE_MASK_FULL_SIZE_CONTENT_VIEW = 1L shl 15

internal fun shouldDisableNativeTitleBarDrag(styleMask: Long, titlebarAppearsTransparent: Boolean): Boolean =
    (styleMask and NS_WINDOW_STYLE_MASK_FULL_SIZE_CONTENT_VIEW) != 0L && titlebarAppearsTransparent

/**
 * Prevents macOS AppKit / WindowServer from commandeering mouse drags across AWTView inside
 * full-content transparent-titlebar windows where Compose's WindowDraggableArea manages chrome drag regions.
 */
internal object MacWindowChrome {

    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
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

    private interface BoolImp : Callback {
        fun invoke(self: Pointer, sel: Pointer): Byte
    }

    private val installed = AtomicBoolean(false)

    @Volatile
    private var origMouseDownCanMoveImp: Function? = null

    @Volatile
    private var mouseDownCanMoveCallback: BoolImp? = null

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
            val awtViewClass = objc.objc_getClass("AWTView") ?: run {
                installed.set(false)
                return
            }
            val selMouseDownCanMove = objc.sel_registerName("mouseDownCanMoveWindow")
            val selWindow = objc.sel_registerName("window")
            val selStyleMask = objc.sel_registerName("styleMask")
            val selTitlebarTransparent = objc.sel_registerName("titlebarAppearsTransparent")
            val selRespondsToSelector = objc.sel_registerName("respondsToSelector:")

            val method = objc.class_getInstanceMethod(awtViewClass, selMouseDownCanMove) ?: run {
                installed.set(false)
                return
            }
            val encoding = objc.method_getTypeEncoding(method)?.getString(0)?.takeIf { it.isNotBlank() } ?: "B@:"
            origMouseDownCanMoveImp = objc.method_getImplementation(method)?.let { Function.getFunction(it) }

            val callback = object : BoolImp {
                override fun invoke(self: Pointer, sel: Pointer): Byte {
                    try {
                        val nsWindow = objc.objc_msgSend(self, selWindow)
                        if (nsWindow != null) {
                            val styleMask = msgLong.objc_msgSend(nsWindow, selStyleMask)
                            if ((styleMask and NS_WINDOW_STYLE_MASK_FULL_SIZE_CONTENT_VIEW) != 0L) {
                                val responds = (msgLong.objc_msgSend(nsWindow, selRespondsToSelector, selTitlebarTransparent) and 0xFFL) != 0L
                                val transparent = responds && (msgLong.objc_msgSend(nsWindow, selTitlebarTransparent) and 0xFFL) != 0L
                                if (shouldDisableNativeTitleBarDrag(styleMask, transparent)) {
                                    return 0
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // Fall back to AppKit's default NSView implementation if native inspection fails.
                    }
                    val superImp = origMouseDownCanMoveImp ?: return 1
                    return (superImp.invoke(Byte::class.java, arrayOf(self, sel)) as? Byte) ?: 1
                }
            }
            mouseDownCanMoveCallback = callback

            val added = objc.class_addMethod(awtViewClass, selMouseDownCanMove, callback, encoding)
            if (!added) {
                val previous = objc.method_setImplementation(method, callback)
                if (previous != null) {
                    origMouseDownCanMoveImp = Function.getFunction(previous)
                }
            }
        }.onFailure {
            installed.set(false)
            Log.w(MAC_CHROME_TAG, "Failed to hook AWTView.mouseDownCanMoveWindow: ${it.message}")
        }
    }
}
