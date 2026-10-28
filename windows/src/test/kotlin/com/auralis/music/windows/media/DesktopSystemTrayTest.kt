package com.auralis.music.windows.media

import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JWindow
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopSystemTrayTest {
    @Test fun nativeTrayInstallsOpenAndQuitActionsAndRemovesItself() {
        if (!SystemTray.isSupported()) return
        EventQueue.invokeAndWait {
            val system = SystemTray.getSystemTray()
            val before = system.trayIcons.toSet()
            var opens = 0
            var quits = 0
            val anchor = Point(200, 200)
            val tray = DesktopSystemTray({ opens++ }, { quits++ }, { anchor })
            try {
                tray.install()
                assertTrue(tray.available)
                tray.install()
                val added = (system.trayIcons.toSet() - before).single()
                assertNull(added.popupMenu)
                fun openMenu(): JWindow {
                    val event = MouseEvent(Canvas(), MouseEvent.MOUSE_RELEASED, 0, 0,
                        300, 300, 300, 300, 1, true, MouseEvent.BUTTON3)
                    added.mouseListeners.forEach { it.mouseReleased(event) }
                    return Window.getWindows().filterIsInstance<JWindow>()
                        .single { it.isVisible && it.contentPane is TrayMenuPanel }
                }
                val openedWindow = openMenu()
                // A 150% Windows tray event reports 300 physical pixels; the popup must
                // use the pointer's 200 logical pixels, rather than scaling it a second time.
                val configuration = openedWindow.graphicsConfiguration
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
                val usable = Rectangle(configuration.bounds).apply {
                    x += insets.left; y += insets.top
                    width -= insets.left + insets.right; height -= insets.top + insets.bottom
                }
                assertEquals(trayMenuLocation(anchor, openedWindow.size, usable), openedWindow.location)
                (openedWindow.contentPane as TrayMenuPanel).openButton.doClick(0)
                assertFalse(openedWindow.isVisible)
                val quitMenu = openMenu()
                assertFalse(openedWindow.isDisplayable)
                (quitMenu.contentPane as TrayMenuPanel).quitButton.doClick(0)
                assertFalse(quitMenu.isVisible)
                assertEquals(1, opens)
                assertEquals(1, quits)
            } finally { tray.close() }
            assertEquals(before, system.trayIcons.toSet())
            assertFalse(Window.getWindows().filterIsInstance<JWindow>()
                .any { it.isDisplayable && it.contentPane is TrayMenuPanel })
        }
    }

    @Test fun menuStaysInsideMonitorWorkingArea() {
        val size = Dimension(234, 136)
        val monitor = Rectangle(-1920, 0, 1920, 1040)
        for (point in listOf(Point(-1919, 1039), Point(-1, 1039), Point(-1900, 1))) {
            val position = trayMenuLocation(point, size, monitor)
            assertTrue(monitor.contains(Rectangle(position, size)))
        }
        assertEquals(9, trayMenuLocation(Point(-1900, 1), size, monitor).y)
    }

    @Test fun escapeAndFocusLossDismissWithoutInvokingActions() {
        if (GraphicsEnvironment.isHeadless()) return
        EventQueue.invokeAndWait {
            var actions = 0
            val menu = DesktopTrayMenu({ actions++ }, { actions++ })
            try {
                menu.showAt(Point(300, 300))
                val window = javax.swing.SwingUtilities.getWindowAncestor(menu.content)
                assertTrue(window.isVisible)
                menu.content.actionMap.get("dismiss").actionPerformed(ActionEvent(menu.content, 0, "dismiss"))
                assertFalse(window.isVisible)
                menu.showAt(Point(300, 300))
                val reopened = javax.swing.SwingUtilities.getWindowAncestor(menu.content)
                reopened.windowFocusListeners.forEach {
                    it.windowLostFocus(WindowEvent(reopened, WindowEvent.WINDOW_LOST_FOCUS))
                }
                assertFalse(reopened.isVisible)
                assertEquals(0, actions)
            } finally { menu.close() }
        }
    }

    @Test fun menuLabelsFitAndRenderPreview() {
        EventQueue.invokeAndWait {
            val panel = TrayMenuPanel({}, {})
            panel.size = panel.preferredSize
            panel.doLayout()
            for (button in listOf(panel.openButton, panel.quitButton)) {
                val labelWidth = button.getFontMetrics(button.font).stringWidth(button.text)
                assertTrue(button.width >= labelWidth + button.insets.left + button.insets.right +
                    button.icon.iconWidth + button.iconTextGap)
            }
            val preview = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = preview.createGraphics()
            try { panel.printAll(graphics) } finally { graphics.dispose() }
            val output = File("build/reports/tray-menu.png")
            output.parentFile.mkdirs()
            ImageIO.write(preview, "png", output)
        }
    }

    @Test fun nativeClickOutsideDismissesEvenWithoutFocusLoss() {
        if (GraphicsEnvironment.isHeadless()) return
        lateinit var menu: DesktopTrayMenu
        lateinit var target: Frame
        lateinit var watcher: TrayOutsideClicks
        var actions = 0
        val cleanup = mutableListOf<() -> Unit>()
        try {
            EventQueue.invokeAndWait {
                target = Frame("Auralis tray dismissal test").apply {
                    setBounds(20, 20, 160, 100)
                    isAlwaysOnTop = true
                    isVisible = true
                }
                cleanup += { target.dispose() }
                menu = DesktopTrayMenu({ actions++ }, { actions++ })
                cleanup += { menu.close() }
                menu.showAt(Point(500, 500))
                // Reproduce the shell retaining focus: dismissal must not depend on this event.
                val popup = javax.swing.SwingUtilities.getWindowAncestor(menu.content)
                popup.windowFocusListeners.forEach { popup.removeWindowFocusListener(it) }
                watcher = checkNotNull(menu.outsideClicks)
            }
            assertTrue(watcher.ready.await(2, TimeUnit.SECONDS))
            assertTrue(watcher.installed)
            Robot().apply {
                mouseMove(70, 80)
                mousePress(InputEvent.BUTTON1_DOWN_MASK)
                mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                waitForIdle()
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var visible = true
            while (visible && System.nanoTime() < deadline) {
                EventQueue.invokeAndWait {
                    visible = javax.swing.SwingUtilities.getWindowAncestor(menu.content).isVisible
                }
                if (visible) Thread.sleep(10)
            }
            assertFalse(visible, "A click in another window must close the menu")
            assertEquals(0, actions)
            assertNull(menu.outsideClicks)
        } finally {
            EventQueue.invokeAndWait {
                cleanup.asReversed().forEach { it() }
            }
        }
    }
}
