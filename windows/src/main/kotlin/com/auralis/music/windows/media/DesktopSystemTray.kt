package com.auralis.music.windows.media

import com.auralis.music.domain.model.Track
import com.auralis.music.util.Log
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO

/** Uses Windows' native tray through AWT; never stops audio when hidden. */
internal class DesktopSystemTray(
    private val onOpen: () -> Unit,
    private val onQuit: () -> Unit,
    private val pointerLocation: () -> Point? = { MouseInfo.getPointerInfo()?.location },
    private val onRestart: () -> Unit = {}
) : AutoCloseable {
    private var icon: TrayIcon? = null
    private var menu: DesktopTrayMenu? = null
    val available get() = icon != null
    fun install() {
        check(EventQueue.isDispatchThread())
        if (available) return
        if (!SystemTray.isSupported()) return
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        runCatching {
            if (isMac) {
                System.setProperty("apple.awt.enableTemplateImages", "true")
            }
            val image = (if (isMac) macTrayImage() else trayImage()) ?: return
            if (isMac) {
                // macOS: native Cocoa NSMenu via AWT PopupMenu for genuine macOS glass, typography, and styling
                val popup = PopupMenu().apply {
                    val openItem = MenuItem("Open Auralis").apply {
                        addActionListener { onOpen() }
                    }
                    val quitItem = MenuItem("Quit Auralis").apply {
                        addActionListener { onQuit() }
                    }
                    add(openItem)
                    addSeparator()
                    add(quitItem)
                }
                val tray = TrayIcon(image, "Auralis", popup).apply {
                    isImageAutoSize = false
                }
                SystemTray.getSystemTray().add(tray)
                icon = tray
                Log.i("AuralisTray", "macOS native status menu installed")
            } else {
                val popup = DesktopTrayMenu(onOpen, onQuit, onRestart)
                menu = popup
                fun showMenu(e: MouseEvent) {
                    if (!e.isPopupTrigger) return
                    // Windows tray events use physical pixels. MouseInfo supplies AWT's logical
                    // screen coordinates, matching JWindow even at 125/150% or mixed monitor DPI.
                    popup.showAt(pointerLocation() ?: e.locationOnScreen)
                }
                val tray = TrayIcon(image, "Auralis").apply {
                    // The multi-resolution tile supplies each tray size; AWT's own resize blurs it.
                    isImageAutoSize = false
                    addActionListener { popup.dismiss(); onOpen() }
                    addMouseListener(object : MouseAdapter() {
                        override fun mousePressed(e: MouseEvent) = showMenu(e)
                        override fun mouseReleased(e: MouseEvent) = showMenu(e)
                    })
                }
                SystemTray.getSystemTray().add(tray)
                icon = tray
                Log.i("AuralisTray", "Windows tray installed")
            }
        }.onFailure {
            menu?.close()
            menu = null
            Log.w("AuralisTray", "Tray unavailable: ${it.message}")
        }
    }
    /**
     * macOS menu bar template icons (all-white monochrome glyph on transparent background).
     * Automatically adapts to light/dark themes and selection highlights via apple.awt.enableTemplateImages.
     */
    private fun macTrayImage(): Image? {
        val tiles = listOf(16, 18, 20, 24, 32, 36, 40, 48).mapNotNull { size ->
            javaClass.classLoader.getResourceAsStream("app-icon/tray_mac_$size.png")?.use(ImageIO::read)
        }
        if (tiles.isEmpty()) return trayImage()
        val base = SystemTray.getSystemTray().trayIconSize.width.takeIf { it > 0 } ?: 20
        return java.awt.image.BaseMultiResolutionImage(tiles.indexOfFirst { it.width >= base }.coerceAtLeast(0), *tiles.toTypedArray())
    }

    /**
     * The Auralis tile drawn for each tray size, with the knot smaller than in the taskbar tiles
     * (more brown around it), rather than AWT's blurry shrink of the 256px logo.
     */
    private fun trayImage(): Image? {
        val tiles = listOf(16, 20, 24, 32, 40, 48).mapNotNull { size ->
            javaClass.classLoader.getResourceAsStream("app-icon/tray_$size.png")?.use(ImageIO::read)
        }
        if (tiles.isEmpty()) return null
        val base = SystemTray.getSystemTray().trayIconSize.width.takeIf { it > 0 } ?: 16
        return java.awt.image.BaseMultiResolutionImage(tiles.indexOfFirst { it.width >= base }.coerceAtLeast(0), *tiles.toTypedArray())
    }
    override fun close() {
        menu?.close()
        menu = null
        icon?.let { SystemTray.getSystemTray().remove(it) }
        icon = null
    }
}

/** Start notifications only after playback actually starts, not while a new queue item buffers. */
internal class SongNotificationTracker(initialTrackId: String?) {
    private var startedId = initialTrackId
    fun started(track: Track?, playing: Boolean, enabled: Boolean, background: Boolean): Track? {
        if (!playing || track == null || track.id == startedId) return null
        startedId = track.id
        return track.takeIf { enabled && background }
    }
}

internal fun shouldCloseToTray(enabled: Boolean, trayAvailable: Boolean) = enabled && trayAvailable
