package com.auralis.music.windows.media

import com.auralis.music.domain.model.Track
import com.auralis.music.util.Log
import java.awt.*
import javax.imageio.ImageIO

/** Uses Windows' native tray through AWT; never stops audio when hidden. */
internal class DesktopSystemTray(private val onOpen: () -> Unit, private val onQuit: () -> Unit) : AutoCloseable {
    private var icon: TrayIcon? = null
    val available get() = icon != null
    fun install() {
        check(EventQueue.isDispatchThread())
        if (!SystemTray.isSupported()) return
        runCatching {
            val image = trayImage() ?: return
            val menu = PopupMenu().apply {
                add(MenuItem("Open Auralis").apply { addActionListener { onOpen() } })
                addSeparator()
                add(MenuItem("Quit Auralis").apply { addActionListener { onQuit() } })
            }
            val tray = TrayIcon(image, "Auralis", menu).apply {
                // The multi-resolution tile supplies each tray size; AWT's own resize blurs it.
                isImageAutoSize = false
                addActionListener { onOpen() }
            }
            SystemTray.getSystemTray().add(tray)
            icon = tray
            Log.i("AuralisTray", "Windows tray installed")
        }.onFailure { Log.w("AuralisTray", "Tray unavailable: ${it.message}") }
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
