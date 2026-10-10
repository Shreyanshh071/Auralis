package com.auralis.music.ui.viewmodel

import com.auralis.music.domain.model.PlaylistDownloadJob
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * What [LibraryViewModel] needs from the platform: offline downloads (the phone's download
 * manager and playlist jobs), a few saved flags, and the pill that confirms library changes.
 */
interface LibraryPlatform {
    val downloadedTracks: Flow<List<Track>>
    val downloadJobs: Flow<List<PlaylistDownloadJob>>
    /** Every song of a playlist download job. */
    fun jobTracks(jobId: String): List<Track>
    /** The songs of a job that finished and are still on the device. */
    fun downloadedJobTracks(jobId: String): List<Track>
    fun removePlaylistDownloads(jobId: String)
    fun retryPlaylistDownload(jobId: String)
    fun removeDownload(trackId: String)

    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)

    fun showPill(message: String)
}
