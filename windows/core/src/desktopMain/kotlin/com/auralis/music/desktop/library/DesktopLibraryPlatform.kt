package com.auralis.music.desktop.library

import com.auralis.music.domain.model.PlaylistDownloadJob
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.viewmodel.LibraryPlatform
import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * [LibraryPlatform] on desktop: persistent offline downloads, the Library's saved flags in [flags],
 * and confirmations shown by [showMessage] (the window's snackbar) instead of the phone's pill.
 */
class DesktopLibraryPlatform(
    private val flags: KeyValueStore,
    private val showMessage: (String) -> Unit
) : LibraryPlatform {
    private val downloads get() = DesktopDownloads.instance
    override val downloadedTracks: Flow<List<Track>> get() = downloads.tracks
    override val downloadJobs: Flow<List<PlaylistDownloadJob>> get() = downloads.jobs
    override fun jobTracks(jobId: String): List<Track> = downloads.groupTracks(jobId)
    override fun downloadedJobTracks(jobId: String): List<Track> = downloads.groupTracks(jobId, true)
    override fun removePlaylistDownloads(jobId: String) = downloads.removeGroup(jobId)
    override fun retryPlaylistDownload(jobId: String) = downloads.retry(jobId)
    override fun removeDownload(trackId: String) = downloads.removeTrack(trackId)

    override fun getInt(key: String, default: Int) = flags.getString(key, default.toString())?.toIntOrNull() ?: default
    override fun putInt(key: String, value: Int) = flags.putStrings(mapOf(key to value.toString()))
    override fun getBoolean(key: String, default: Boolean) = flags.getString(key, default.toString())?.toBooleanStrictOrNull() ?: default
    override fun putBoolean(key: String, value: Boolean) = flags.putStrings(mapOf(key to value.toString()))

    override fun showPill(message: String) = showMessage(message)
}
