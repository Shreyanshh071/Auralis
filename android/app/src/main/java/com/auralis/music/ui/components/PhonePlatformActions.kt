package com.auralis.music.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.auralis.music.data.download.AuralisDownloadManager
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.str
import kotlinx.coroutines.launch

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

    override fun downloadPlaylist(playlistId: String, playlistName: String, tracks: List<Track>) {
        com.auralis.music.data.download.PlaylistDownloadCoordinator.enqueue(
            context = context,
            playlistId = playlistId,
            playlistName = playlistName,
            tracks = tracks
        )
    }

    /** Public Downloads first; the app's own files folder if that isn't writable. */
    override fun saveTextFile(fileName: String, content: String): String =
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val file = java.io.File(downloadsDir, fileName)
            file.writeText(content)
            str(Strings.saved_to_downloads_x, fileName)
        } catch (e: Exception) {
            try {
                val file = java.io.File(context.getExternalFilesDir(null), fileName)
                file.writeText(content)
                str(Strings.saved_x, file.absolutePath)
            } catch (e2: Exception) {
                str(Strings.error_saving_x, e2.localizedMessage)
            }
        }

    @androidx.compose.runtime.Composable
    override fun rememberImagePicker(onPicked: (String) -> Unit): () -> Unit {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val currentOnPicked = androidx.compose.runtime.rememberUpdatedState(onPicked)
        val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri: android.net.Uri? ->
            if (uri != null) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val encoded = com.auralis.music.util.ArtworkProcessor.encodeImageUriToDataUri(context, uri.toString())
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        currentOnPicked.value(encoded ?: uri.toString())
                    }
                }
            }
        }
        return { launcher.launch("image/*") }
    }
}
