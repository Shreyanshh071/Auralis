package com.auralis.music.ui.components

import com.auralis.music.domain.model.Track
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.str
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Shared UI's [PlatformActions] on desktop: messages go to [showMessage] (the window's snackbar),
 * sharing copies the text to the clipboard, and offline downloads aren't available yet.
 */
class DesktopPlatformActions(private val message: (String) -> Unit) : PlatformActions {
    override fun showMessage(text: String) = message(text)
    override fun showPill(text: String) = message(text)

    override fun shareText(subject: String?, text: String, chooserTitle: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
        message("Copied to clipboard")
    }

    override fun isDownloaded(trackId: String) = false
    override fun isDownloading(trackId: String) = false
    override fun downloadTrack(track: Track) = message("Downloads aren't available on Windows yet")
    override fun removeDownload(trackId: String) {}
    @androidx.compose.runtime.Composable
    override fun hasActiveDownloads() = false
    override fun clearAllDownloads() {}
    override fun downloadPlaylist(playlistId: String, playlistName: String, tracks: List<Track>) =
        message("Downloads aren't available on Windows yet")

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
        return {
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose an image", java.awt.FileDialog.LOAD).apply {
                file = "*.jpg;*.jpeg;*.png;*.webp;*.gif;*.bmp"
                isVisible = true
            }
            val name = dialog.file
            val dir = dialog.directory
            if (name != null && dir != null) {
                val file = java.io.File(dir, name)
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val uri = runCatching { imageDataUri(file) }.getOrNull()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        currentOnPicked.value(uri ?: file.toURI().toString())
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
