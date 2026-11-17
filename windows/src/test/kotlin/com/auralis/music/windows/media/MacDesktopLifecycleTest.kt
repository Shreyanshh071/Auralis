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

                // Verify macOS template image property is enabled
                assertEquals("true", System.getProperty("apple.awt.enableTemplateImages"))

                // Verify the tray icon image is multi-resolution with transparent background and monochrome white glyph
                val img = added.image
                assertTrue(img is java.awt.image.MultiResolutionImage, "macOS TrayIcon image must be MultiResolutionImage")
                val variant = img.getResolutionVariant(40.0, 40.0)
                val buf = java.awt.image.BufferedImage(variant.getWidth(null), variant.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val g = buf.createGraphics()
                g.drawImage(variant, 0, 0, null)
                g.dispose()

                // Corner must be transparent (no brown background box)
                assertEquals(0, (buf.getRGB(0, 0) ushr 24) and 0xFF, "Corner pixel must be transparent")

                // All non-transparent pixels must be monochrome white (R == G == B == 255)
                var visibleCount = 0
                for (y in 0 until buf.height) {
                    for (x in 0 until buf.width) {
                        val argb = buf.getRGB(x, y)
                        val a = (argb ushr 24) and 0xFF
                        val r = (argb ushr 16) and 0xFF
                        val green = (argb ushr 8) and 0xFF
                        val b = argb and 0xFF
                        if (a > 0) {
                            visibleCount++
                            assertEquals(255, r, "Knot pixel must be pure white")
                            assertEquals(255, green, "Knot pixel must be pure white")
                            assertEquals(255, b, "Knot pixel must be pure white")
                        }
                    }
                }
                assertTrue(visibleCount > 50, "Knot glyph must contain visible pixels")

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

    @Test
    fun testMacPlaybackActivityAcquiresAndReleases() {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!isMac) return

        assertFalse(MacPlaybackActivity.isAcquired)
        MacPlaybackActivity.startPlaybackActivity()
        assertTrue(MacPlaybackActivity.isAcquired, "Playback activity assertion should be held during playback")

        MacPlaybackActivity.stopPlaybackActivity()
        assertFalse(MacPlaybackActivity.isAcquired, "Playback activity assertion should be released when stopped")
    }

    @Test
    fun testMacSongNotificationsSend() {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!isMac) return

        // Verify notification sends cleanly via osascript without exception
        MacSongNotifications.send("Test Track", "Test Artist")
    }
}
