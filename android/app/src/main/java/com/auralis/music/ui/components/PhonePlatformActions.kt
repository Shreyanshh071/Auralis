package com.auralis.music.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.auralis.music.data.download.AuralisDownloadManager
import com.auralis.music.domain.model.Track

/** Shared UI's [PlatformActions] on the phone: the same Toast, pill, share sheet and downloads as before. */
class PhonePlatformActions(private val context: Context) : PlatformActions {
    override fun showMessage(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    override fun showPill(text: String) = AppPillManager.showPill(text)

    override fun shareText(subject: String?, text: String, chooserTitle: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }

    override fun isDownloaded(trackId: String) = AuralisDownloadManager.isDownloaded(trackId)
    override fun isDownloading(trackId: String) = AuralisDownloadManager.isDownloading(trackId)
    override fun downloadTrack(track: Track) = AuralisDownloadManager.downloadTrack(track)
    override fun removeDownload(trackId: String) = AuralisDownloadManager.removeDownload(trackId)
}
