package com.auralis.music.windows.media

import java.awt.*
import java.awt.event.ActionEvent
import kotlin.test.*

class MacDesktopLifecycleTest {
    @Test
    fun trayIconInstallsNativeCocoaMenuOnMac() {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!isMac || !SystemTray.isSupported()) return
        EventQueue.invokeAndWait {
            val system = SystemTray.getSystemTray()
            val before = system.trayIcons.toSet()
            var opens = 0
            var quits = 0
            val tray = DesktopSystemTray({ opens++ }, { quits++ })
            try {
                tray.install()
                assertTrue(tray.available)
                val added = (system.trayIcons.toSet() - before).single()

                // On macOS, native TrayIcon uses Cocoa NSMenu via java.awt.PopupMenu
                assertNotNull(added.popupMenu, "macOS TrayIcon should have a native PopupMenu")
                assertEquals(0, added.actionListeners.size, "macOS TrayIcon should not register ActionListeners")

                val popup = added.popupMenu
                assertEquals(3, popup.itemCount, "Menu should have Open, separator, and Quit")

                val openItem = popup.getItem(0)
                assertEquals("Open Auralis", openItem.label)
                assertEquals(0, opens)

                // Trigger Open action
                openItem.actionListeners.forEach { it.actionPerformed(ActionEvent(openItem, ActionEvent.ACTION_PERFORMED, "Open")) }
                assertEquals(1, opens, "Open Auralis click must trigger onOpen")

                val separatorItem = popup.getItem(1)
                assertEquals("-", separatorItem.label)

                val quitItem = popup.getItem(2)
                assertEquals("Quit Auralis", quitItem.label)
                assertEquals(0, quits)

                // Trigger Quit action
                quitItem.actionListeners.forEach { it.actionPerformed(ActionEvent(quitItem, ActionEvent.ACTION_PERFORMED, "Quit")) }
                assertEquals(1, quits, "Quit Auralis click must trigger onQuit")
            } finally {
                tray.close()
            }
            assertEquals(before, system.trayIcons.toSet(), "Closing tray must remove the TrayIcon")
        }
    }
}
