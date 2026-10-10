package com.auralis.music.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import com.auralis.music.data.download.AuralisDownloadManager
import com.auralis.music.data.download.PlaylistDownloadCoordinator
import com.auralis.music.data.download.PlaylistDownloadRepository
import com.auralis.music.domain.model.PlaylistDownloadJob
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.AppPillManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [LibraryPlatform] on the phone: the download manager, playlist download jobs, the import-matching prefs and the pill. */
class PhoneLibraryPlatform(
    private val context: Context,
    private val prefs: SharedPreferences
) : LibraryPlatform {
    override val downloadedTracks: Flow<List<Track>> get() = AuralisDownloadManager.downloadedTracks

    override val downloadJobs: Flow<List<PlaylistDownloadJob>>
        get() = PlaylistDownloadCoordinator.jobs.map { jobs ->
            jobs.values.map { job ->
                PlaylistDownloadJob(
                    jobId = job.jobId,
                    playlistId = job.playlistId,
                    playlistName = job.playlistName,
                    status = job.status,
                    completedCount = job.completedCount,
                    failedCount = job.failedCount,
                    skippedCount = job.skippedCount
                )
            }
        }

    private fun job(jobId: String) = PlaylistDownloadCoordinator.jobs.value.values.firstOrNull { it.jobId == jobId }

    override fun jobTracks(jobId: String): List<Track> =
        job(jobId)?.let { PlaylistDownloadCoordinator.tracks(it) }.orEmpty()

    override fun downloadedJobTracks(jobId: String): List<Track> {
        val job = job(jobId) ?: return emptyList()
        val successfulTrackIds = PlaylistDownloadCoordinator.results(job)
            .filter { it.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES }
            .map { it.trackId }
            .toSet()
        return PlaylistDownloadCoordinator.tracks(job)
            .filter { it.id in successfulTrackIds && AuralisDownloadManager.isDownloaded(it.id) }
    }

    override fun removePlaylistDownloads(jobId: String) = PlaylistDownloadCoordinator.removePlaylistDownloads(context, jobId)
    override fun retryPlaylistDownload(jobId: String) = PlaylistDownloadCoordinator.retry(context, jobId)
    override fun removeDownload(trackId: String) { AuralisDownloadManager.removeDownload(trackId) }

    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    override fun showPill(message: String) = AppPillManager.showPill(message)
}
