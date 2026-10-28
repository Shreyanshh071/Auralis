package com.auralis.music.ui.components

import com.auralis.music.domain.model.Track
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.str
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Shared UI's [PlatformActions] on desktop: messages go to [showMessage] (the window's snackbar),
 * sharing copies text, and downloads are handled by the desktop file store.
 */
class DesktopPlatformActions(private val message: (String) -> Unit) : PlatformActions {
    override fun showMessage(text: String) = message(text)
    override fun showPill(text: String) = message(text)

    override fun shareText(subject: String?, text: String, chooserTitle: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        message("Copied to clipboard")
    }

    private val downloads get() = com.auralis.music.desktop.library.DesktopDownloads.instance
    override fun isDownloaded(trackId: String) = downloads.localFile(trackId) != null
    override fun isDownloading(trackId: String) = downloads.isDownloading(trackId)
    override fun downloadTrack(track: Track) { downloads.enqueue(listOf(track)); message("Download queued") }
    override fun removeDownload(trackId: String) = downloads.removeTrack(trackId)
    override fun openUrl(url: String): Boolean = runCatching {
        java.awt.Desktop.getDesktop().browse(java.net.URI(url))
    }.isSuccess

    override fun copyText(label: String, text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override fun readClipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    }.getOrNull()

    @androidx.compose.runtime.Composable
    override fun hasActiveDownloads(): Boolean {
        val entries = downloads.entries.collectAsState().value
        return entries.any { it.status in setOf("QUEUED", "DOWNLOADING") }
    }
    override fun clearAllDownloads() = downloads.clear()
    override fun downloadPlaylist(playlistId: String, playlistName: String, tracks: List<Track>, coverUrl: String?) {
        downloads.enqueue(tracks, playlistId, playlistName, coverUrl); message("Playlist download queued")
    }

    /** Into the user's Downloads folder, like the phone. */
    override fun saveTextFile(fileName: String, content: String): String =
        try {
            val dir = java.io.File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
            java.io.File(dir, fileName).writeText(content)
            str(Strings.saved_to_downloads_x, fileName)
        } catch (e: Exception) {
            str(Strings.error_saving_x, e.localizedMessage ?: e.toString())
        }

    /** A Windows file dialog; the chosen image becomes a data: URI so the cover travels with the playlist. */
    @androidx.compose.runtime.Composable
    override fun rememberImagePicker(onPicked: (String) -> Unit): () -> Unit {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val currentOnPicked = androidx.compose.runtime.rememberUpdatedState(onPicked)
        val choosing = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        return {
            if (!choosing.value) {
                val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                    ?.let { com.sun.jna.Native.getWindowPointer(it) }
                choosing.value = true
                scope.launch {
                    try {
                        val file = NativeImagePicker.choose(owner)
                        if (file != null) {
                            val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { imageDataUri(file) }
                            if (uri != null) currentOnPicked.value(uri)
                            else message("This image could not be read. Choose a JPG or PNG photo.")
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        message("Could not open this photo. ${e.localizedMessage ?: "Please try again."}")
                    } finally {
                        choosing.value = false
                    }
                }
            }
        }
    }

    /** Scales the image to at most 400 px and encodes it as a JPEG data: URI (as the phone's picker does). */
    private fun imageDataUri(file: java.io.File): String? {
        val source = javax.imageio.ImageIO.read(file) ?: return null
        val scale = minOf(1.0, 400.0 / maxOf(source.width, source.height))
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(source, 0, 0, w, h, null)
            dispose()
        }
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(out, "jpg", it) }.toByteArray()
        return "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
    }
}
