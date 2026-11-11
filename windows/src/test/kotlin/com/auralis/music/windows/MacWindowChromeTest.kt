package com.auralis.music.windows

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacWindowChromeTest {

    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, arg: Long): Pointer?

        companion object {
            val INSTANCE: ObjC = Native.load("objc", ObjC::class.java)
        }
    }

    private interface MsgLong : Library {
        fun objc_msgSend(receiver: Pointer, sel: Pointer): Long

        companion object {
            val INSTANCE: MsgLong = Native.load("objc", MsgLong::class.java)
        }
    }

    @Test
    fun predicateOnlyDisablesNativeDragForFullContentTransparentTitlebarWindows() {
        val fullSizeBit = 1L shl 15
        assertTrue(shouldDisableNativeTitleBarDrag(0x000FL or fullSizeBit, titlebarAppearsTransparent = true))
        assertTrue(shouldDisableNativeTitleBarDrag(0x400FL or fullSizeBit, titlebarAppearsTransparent = true))
        assertFalse(shouldDisableNativeTitleBarDrag(0x000FL or fullSizeBit, titlebarAppearsTransparent = false))
        assertFalse(shouldDisableNativeTitleBarDrag(0x000FL, titlebarAppearsTransparent = true))
        assertFalse(shouldDisableNativeTitleBarDrag(0x000FL, titlebarAppearsTransparent = false))
    }

    @Test
    fun awtViewDisablesNativeDragOnlyForFullContentTransparentWindowOnMac() {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!isMac) return

        assertTrue(MacWindowChrome.installIfMac(), "First installIfMac() should succeed on macOS")
        assertTrue(MacWindowChrome.installIfMac(), "Repeated installIfMac() should remain idempotent")

        val customTitle = "MacWindowChromeCustom-${System.nanoTime()}"
        val standardTitle = "MacWindowChromeStd-${System.nanoTime()}"
        var customFrame: JFrame? = null
        var standardFrame: JFrame? = null
        var customCanMove: Int? = null
        var standardCanMove: Int? = null

        try {
            SwingUtilities.invokeAndWait {
                customFrame = JFrame(customTitle).apply {
                    rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    setSize(320, 180)
                    setLocation(120, 120)
                    isVisible = true
                }
                standardFrame = JFrame(standardTitle).apply {
                    setSize(320, 180)
                    setLocation(480, 120)
                    isVisible = true
                }
            }
            Thread.sleep(250)
            SwingUtilities.invokeAndWait {
                customCanMove = readContentViewMouseDownCanMoveWindow(customTitle)
                standardCanMove = readContentViewMouseDownCanMoveWindow(standardTitle)
            }
            assertEquals(0, customCanMove, "Full-content transparent-titlebar AWTView must not allow native window drag")
            assertEquals(1, standardCanMove, "Standard AWTView must preserve default AppKit mouseDownCanMoveWindow")
        } finally {
            SwingUtilities.invokeAndWait {
                disposeQuietly(customFrame)
                disposeQuietly(standardFrame)
            }
        }
    }

    private fun disposeQuietly(window: Window?) {
        window?.isVisible = false
        window?.dispose()
    }

    private fun readContentViewMouseDownCanMoveWindow(windowTitle: String): Int? {
        val objc = ObjC.INSTANCE
        val msgLong = MsgLong.INSTANCE
        val selSharedApp = objc.sel_registerName("sharedApplication")
        val selWindows = objc.sel_registerName("windows")
        val selCount = objc.sel_registerName("count")
        val selObjectAtIndex = objc.sel_registerName("objectAtIndex:")
        val selTitle = objc.sel_registerName("title")
        val selUtf8 = objc.sel_registerName("UTF8String")
        val selContentView = objc.sel_registerName("contentView")
        val selMouseDownCanMove = objc.sel_registerName("mouseDownCanMoveWindow")

        val nsAppClass = objc.objc_getClass("NSApplication") ?: return null
        val app = objc.objc_msgSend(nsAppClass, selSharedApp) ?: return null
        val windows = objc.objc_msgSend(app, selWindows) ?: return null
        val count = msgLong.objc_msgSend(windows, selCount)
        for (i in 0 until count) {
            val nsWin = objc.objc_msgSend(windows, selObjectAtIndex, i) ?: continue
            val titlePtr = objc.objc_msgSend(nsWin, selTitle)
            val title = titlePtr?.let { objc.objc_msgSend(it, selUtf8)?.getString(0) }
            if (title == windowTitle) {
                val contentView = objc.objc_msgSend(nsWin, selContentView) ?: return null
                return (msgLong.objc_msgSend(contentView, selMouseDownCanMove) and 0xFFL).toInt()
            }
        }
        return null
    }
}
