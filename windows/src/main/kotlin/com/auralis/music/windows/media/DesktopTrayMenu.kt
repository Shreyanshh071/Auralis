package com.auralis.music.windows.media

import java.awt.*
import java.awt.event.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.EmptyBorder

/** AWT's heavyweight PopupMenu cannot be themed. Keep this lightweight window on the AWT EDT. */
internal class DesktopTrayMenu(onOpen: () -> Unit, onQuit: () -> Unit, onRestart: () -> Unit = {}) : AutoCloseable {
    private var window: JWindow? = null
    internal var outsideClicks: TrayOutsideClicks? = null
        private set
    internal val content = TrayMenuPanel({ dismiss(); onOpen() }, { dismiss(); onQuit() }, { dismiss(); onRestart() })

    init {
        content.actionMap.put("dismiss", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = dismiss()
        })
    }

    fun showAt(anchor: Point) {
        check(EventQueue.isDispatchThread())
        val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .flatMap { it.configurations.toList() }.firstOrNull { it.bounds.contains(anchor) }
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        // Recreate on the target monitor so Swing uses that monitor's DPI.
        outsideClicks?.close()
        window?.dispose()
        window = JWindow(configuration).apply {
            type = Window.Type.POPUP
            isAlwaysOnTop = true
            focusableWindowState = true
            contentPane = content
            addWindowFocusListener(object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) {
                    if (window === e.window) dismiss()
                }
            })
            pack()
            if (configuration.device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) {
                shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 16.0, 16.0)
            }
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
            val usable = Rectangle(configuration.bounds).apply {
                x += insets.left; y += insets.top
                width -= insets.left + insets.right; height -= insets.top + insets.bottom
            }
            location = trayMenuLocation(anchor, size, usable)
            isVisible = true
            requestFocus()
            content.openButton.requestFocusInWindow()
        }
        outsideClicks = TrayOutsideClicks(checkNotNull(window), ::dismiss)
    }

    fun dismiss() {
        outsideClicks?.close()
        outsideClicks = null
        window?.isVisible = false
    }
    override fun close() { dismiss(); window?.dispose(); window = null }
}

/** Prefer above the tray pointer, clamped to this monitor's working area (including negative origins). */
internal fun trayMenuLocation(anchor: Point, size: Dimension, usable: Rectangle): Point = Point(
    (anchor.x - size.width / 2).coerceIn(usable.x, maxOf(usable.x, usable.x + usable.width - size.width)),
    (if (anchor.y - size.height - 8 >= usable.y) anchor.y - size.height - 8 else anchor.y + 8)
        .coerceIn(usable.y, maxOf(usable.y, usable.y + usable.height - size.height))
)

internal class TrayMenuPanel(onOpen: () -> Unit, onQuit: () -> Unit, onRestart: () -> Unit = {}) : JPanel() {
    internal val openButton = menuButton("Open Auralis", false, onOpen)
    internal val quitButton = menuButton("Quit Auralis", true, onQuit)
    internal val restartButton = menuButton("Restart Auralis", false, onRestart).apply {
        icon = TrayActionIcon(quit = false, restart = true)
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Color(0x1B181F)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(0x39333F)), EmptyBorder(8, 8, 8, 8))
        val header = JLabel("AURALIS").apply {
            foreground = Color(0xAFA5BA)
            font = Font("Segoe UI", Font.BOLD, 10)
            border = EmptyBorder(3, 12, 9, 0)
            alignmentX = LEFT_ALIGNMENT
        }
        add(header)
        add(openButton)
        add(restartButton)
        add(Box.createVerticalStrut(5))
        add(JSeparator().apply {
            alignmentX = LEFT_ALIGNMENT
            foreground = Color(0x35303B)
            background = Color(0x1B181F)
            maximumSize = Dimension(Int.MAX_VALUE, 1)
        })
        add(Box.createVerticalStrut(5))
        add(quitButton)
        val buttons = listOf(openButton, restartButton, quitButton)
        buttons.forEachIndexed { index, button ->
            for (key in listOf("UP", "DOWN", "HOME", "END")) {
                button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key)
                button.actionMap.put(key, object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent) {
                        (when (key) {
                            "HOME" -> openButton
                            "END" -> quitButton
                            "UP" -> buttons[(index + buttons.size - 1) % buttons.size]
                            else -> buttons[(index + 1) % buttons.size]
                        }).requestFocusInWindow()
                    }
                })
            }
            button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "activate")
            button.actionMap.put("activate", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = button.doClick()
            })
        }
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "dismiss")
        actionMap.put("dismiss", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) { SwingUtilities.getWindowAncestor(this@TrayMenuPanel)?.isVisible = false }
        })
    }

    private fun menuButton(label: String, quit: Boolean, action: () -> Unit) = object : JButton(label) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (model.isRollover || isFocusOwner || model.isPressed) {
                    g2.color = when {
                        model.isPressed -> Color(0x44374E)
                        quit -> Color(0x3C292F)
                        else -> Color(0x312A3B)
                    }
                    g2.fillRoundRect(0, 0, width, height, 10, 10)
                }
                if (isFocusOwner) {
                    g2.color = Color(0x9F87B8)
                    g2.drawRoundRect(1, 1, width - 3, height - 3, 9, 9)
                }
            } finally { g2.dispose() }
            super.paintComponent(g)
        }
    }.apply {
        icon = TrayActionIcon(quit)
        iconTextGap = 12
        horizontalAlignment = SwingConstants.LEFT
        alignmentX = LEFT_ALIGNMENT
        preferredSize = Dimension(216, 38)
        maximumSize = Dimension(Int.MAX_VALUE, 38)
        font = Font("Segoe UI", Font.PLAIN, 13)
        foreground = if (quit) Color(0xF0B9BD) else Color(0xEEE8F3)
        border = EmptyBorder(0, 12, 0, 12)
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isRolloverEnabled = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action() }
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
    }
}

private class TrayActionIcon(private val quit: Boolean, private val restart: Boolean = false) : Icon {
    override fun getIconWidth() = 16
    override fun getIconHeight() = 16
    override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.translate(x, y)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = c.foreground
            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            if (restart) {
                g2.drawArc(2, 2, 12, 12, 45, 290)
                g2.drawLine(13, 1, 13, 5)
                g2.drawLine(9, 5, 13, 5)
            } else if (quit) {
                g2.drawArc(2, 2, 12, 12, 45, -270)
                g2.drawLine(8, 1, 8, 7)
            } else {
                g2.drawRoundRect(1, 3, 14, 11, 3, 3)
                g2.drawLine(2, 6, 14, 6)
                g2.fillOval(3, 4, 1, 1)
                g2.fillOval(5, 4, 1, 1)
            }
        } finally { g2.dispose() }
    }
}
