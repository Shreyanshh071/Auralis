package com.auralis.music.windows.media

import java.awt.*
import java.awt.event.ActionEvent
import kotlin.test.*

class DesktopSystemTrayTest {
    @Test fun nativeTrayInstallsOpenAndQuitActionsAndRemovesItself() {
        if (!SystemTray.isSupported()) return
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
                val menu = added.popupMenu
                menu.getItem(0).dispatchEvent(ActionEvent(menu.getItem(0), ActionEvent.ACTION_PERFORMED, "Open Auralis"))
                menu.getItem(2).dispatchEvent(ActionEvent(menu.getItem(2), ActionEvent.ACTION_PERFORMED, "Quit Auralis"))
                assertEquals(1, opens)
                assertEquals(1, quits)
            } finally { tray.close() }
            assertEquals(before, system.trayIcons.toSet())
        }
    }
}
