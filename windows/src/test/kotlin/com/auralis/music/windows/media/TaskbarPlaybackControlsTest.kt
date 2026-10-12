package com.auralis.music.windows.media

import com.auralis.music.windows.NativeWindowFrame
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.*

class TaskbarPlaybackControlsTest {
    @Test fun nativeToolbarRegistersUpdatesAndDispatchesOnlyItsCommands() {
        lateinit var window: JFrame
        lateinit var frame: NativeWindowFrame
        lateinit var controls: TaskbarPlaybackControls
        lateinit var hwnd: HWND
        var previous = 0; var toggle = 0; var next = 0
        fun ui(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
        ui {
            window = JFrame("Auralis taskbar controls check").apply { isUndecorated = true; setSize(440,240); isVisible = true }
            val handle = Pointer.nativeValue(Native.getComponentPointer(window))
            hwnd = HWND(Pointer(handle))
            frame = NativeWindowFrame(window, handle)
            controls = TaskbarPlaybackControls(frame, handle, { previous++ }, { toggle++ }, { next++ })
        }
        try {
            ui { assertTrue(controls.isRegistered, "Explorer must accept the actual thumbnail toolbar") }
            val created = User32.INSTANCE.RegisterWindowMessage("TaskbarButtonCreated")
            User32.INSTANCE.SendMessage(hwnd, created, WPARAM(0), LPARAM(0)); ui {}
            ui { assertTrue(controls.isRegistered, "Delayed taskbar-ready notification must retain the toolbar") }
            fun click(id: Int) {
                User32.INSTANCE.SendMessage(hwnd, 0x111, WPARAM((0x1800L shl 16) or id.toLong()), LPARAM(0))
                ui {} // Drain playback actions dispatched from the native window thread.
            }
            click(4102); assertEquals(0,toggle,"No-track controls must stay disabled")
            ui { controls.update(true,false,true) }
            click(4101); click(4102); click(4103)
            assertEquals(listOf(1,1,1),listOf(previous,toggle,next))
            ui { controls.update(true,true,false) }
            click(4102); click(4103)
            assertEquals(2,toggle); assertEquals(1,next,"Next unavailable at queue end")
            ui { assertFalse(frame.onTaskbarMessage!!.invoke(0x111, 4102)) }
            assertEquals(2,toggle)
            ui { controls.close(); assertNull(frame.onTaskbarMessage) }
        } finally { ui { controls.close(); frame.close(); window.dispose() } }
    }
}
