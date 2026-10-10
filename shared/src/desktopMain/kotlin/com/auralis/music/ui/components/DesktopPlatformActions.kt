package com.auralis.music.ui.components

import com.auralis.music.domain.model.Track
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
}
