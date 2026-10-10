package com.auralis.music.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.auralis.music.domain.model.Track

/**
 * Things shared UI asks the platform to do: short messages, sharing, and offline downloads.
 * The phone answers with its Toast, AppPillManager, share sheet and download manager; each
 * platform installs its own as [LocalPlatformActions].
 */
interface PlatformActions {
    /** A short message (the phone's Toast). */
    fun showMessage(text: String)
    /** The app's pill banner (the phone's AppPillManager). */
    fun showPill(text: String)
    /** Shares [text] with other apps ([chooserTitle] heads the picker where there is one). */
    fun shareText(subject: String?, text: String, chooserTitle: String)
    fun isDownloaded(trackId: String): Boolean
    fun isDownloading(trackId: String): Boolean
    fun downloadTrack(track: Track)
    fun removeDownload(trackId: String)
    /** True while any song is downloading for offline play. */
    @androidx.compose.runtime.Composable
    fun hasActiveDownloads(): Boolean
    fun clearAllDownloads()

    /** Downloads a whole playlist for offline playback. */
    fun downloadPlaylist(playlistId: String, playlistName: String, tracks: List<Track>)

    /** Saves [content] as a file the user can find; returns the message to show (where it went, or the error). */
    fun saveTextFile(fileName: String, content: String): String

    /** Returns a function that asks the user for an image, then gives [onPicked] it as a URL (a data: URI). */
    @androidx.compose.runtime.Composable
    fun rememberImagePicker(onPicked: (String) -> Unit): () -> Unit
}

/** Before a platform installs its own: messages and downloads do nothing. */
object NoPlatformActions : PlatformActions {
    override fun showMessage(text: String) {}
    override fun showPill(text: String) {}
    override fun shareText(subject: String?, text: String, chooserTitle: String) {}
    override fun isDownloaded(trackId: String) = false
    override fun isDownloading(trackId: String) = false
    override fun downloadTrack(track: Track) {}
    override fun removeDownload(trackId: String) {}
    override fun downloadPlaylist(playlistId: String, playlistName: String, tracks: List<Track>) {}
    @androidx.compose.runtime.Composable
    override fun hasActiveDownloads() = false
    override fun clearAllDownloads() {}
    override fun saveTextFile(fileName: String, content: String) = ""
    @androidx.compose.runtime.Composable
    override fun rememberImagePicker(onPicked: (String) -> Unit): () -> Unit = {}
}

val LocalPlatformActions = staticCompositionLocalOf<PlatformActions> { NoPlatformActions }
