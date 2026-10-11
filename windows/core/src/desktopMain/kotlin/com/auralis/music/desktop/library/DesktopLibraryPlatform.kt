package com.auralis.music.desktop.library

import com.auralis.music.domain.model.PlaylistDownloadJob
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.viewmodel.LibraryPlatform
import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * [LibraryPlatform] on desktop: no offline downloads yet, the Library's saved flags in [flags],
 * and confirmations shown by [showMessage] (the window's snackbar) instead of the phone's pill.
 */
class DesktopLibraryPlatform(
    private val flags: KeyValueStore,
    private val showMessage: (String) -> Unit
) : LibraryPlatform {
    override val downloadedTracks: Flow<List<Track>> = flowOf(emptyList())
    override val downloadJobs: Flow<List<PlaylistDownloadJob>> = flowOf(emptyList())
    override fun jobTracks(jobId: String): List<Track> = emptyList()
    override fun downloadedJobTracks(jobId: String): List<Track> = emptyList()
    override fun removePlaylistDownloads(jobId: String) {}
    override fun retryPlaylistDownload(jobId: String) {}
    override fun removeDownload(trackId: String) {}

    override fun getInt(key: String, default: Int) = flags.getString(key, default.toString())?.toIntOrNull() ?: default
    override fun putInt(key: String, value: Int) = flags.putStrings(mapOf(key to value.toString()))
    override fun getBoolean(key: String, default: Boolean) = flags.getString(key, default.toString())?.toBooleanStrictOrNull() ?: default
    override fun putBoolean(key: String, value: Boolean) = flags.putStrings(mapOf(key to value.toString()))

    override fun showPill(message: String) = showMessage(message)
}
