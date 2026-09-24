package com.auralis.music.data.download

import android.content.Context
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

class PlaylistDownloadRepository(private val context: Context) {
    private val dao = PlaylistDownloadDatabase.get(context).jobs()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observeAll(): Flow<List<PlaylistDownloadJobEntity>> = dao.observeAll()
    suspend fun getAll() = dao.getAll()
    suspend fun get(jobId: String) = dao.get(jobId)
    suspend fun getForPlaylist(playlistId: String) = dao.getForPlaylist(playlistId)
    fun tracks(job: PlaylistDownloadJobEntity): List<Track> = json.decodeFromString(job.trackSnapshotJson)
    fun results(job: PlaylistDownloadJobEntity): List<TrackDownloadResult> = json.decodeFromString(job.resultsJson)

    suspend fun createOrUpdate(
        playlistId: String,
        playlistName: String,
        tracks: List<Track>,
        backend: String
    ): PlaylistDownloadJobEntity {
        AuralisDownloadManager.awaitInitialized(context)
        val files = PlaylistDownloadFiles(context)
        val existing = dao.getForPlaylist(playlistId)
        val now = System.currentTimeMillis()
        val snapshot = tracks.distinctBy { it.id }
        val folderName = existing?.folderName ?: uniqueFolderName(playlistName, playlistId)
        val existingResultsMap = existing?.let(::results).orEmpty().filter { old ->
            snapshot.any { it.id == old.trackId } &&
                old.outcome in SUCCESS_OUTCOMES &&
                AuralisDownloadManager.isDownloaded(old.trackId) &&
                files.verify(old.contentUri)
        }.associateBy { it.trackId }.toMutableMap()

        // Pre-resolve any tracks that are already downloaded globally on device (e.g. from another playlist)
        for ((index, track) in snapshot.withIndex()) {
            if (existingResultsMap.containsKey(track.id)) continue
            if (AuralisDownloadManager.isDownloaded(track.id)) {
                val source = AuralisDownloadManager.getDownloadedFile(track.id)
                if (source != null && source.isFile && source.length() > 1024) {
                    try {
                        val fileName = PlaylistDownloadPaths.trackFileName(index, track)
                        val published = files.publish(source, folderName, fileName)
                        existingResultsMap[track.id] = TrackDownloadResult(
                            trackId = track.id,
                            outcome = DownloadOutcome.ALREADY_DOWNLOADED,
                            contentUri = published.first,
                            bytes = published.second
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("PlaylistDownload", "Pre-resolve publish failed for ${track.title}: ${e.message}")
                    }
                }
            }
        }

        val resultsList = snapshot.mapNotNull { existingResultsMap[it.id] }
        val completedCount = resultsList.count { it.outcome in SUCCESS_OUTCOMES }
        val isAllCompleted = snapshot.isNotEmpty() && completedCount == snapshot.size
        val status = if (isAllCompleted) {
            PlaylistDownloadStatus.COMPLETE.name
        } else {
            existing?.status?.takeIf { it in ACTIVE_STATES } ?: PlaylistDownloadStatus.DOWNLOADING.name
        }

        val job = PlaylistDownloadJobEntity(
            jobId = existing?.jobId ?: stableJobId(playlistId),
            playlistId = playlistId,
            playlistName = playlistName,
            folderName = folderName,
            backend = backend,
            trackSnapshotJson = json.encodeToString(snapshot),
            resultsJson = json.encodeToString(resultsList),
            status = status,
            completedCount = completedCount,
            failedCount = 0,
            skippedCount = resultsList.count { it.outcome in setOf(DownloadOutcome.ALREADY_DOWNLOADED, DownloadOutcome.SKIPPED) },
            currentTrackId = null,
            cancelRequested = false,
            offlineEnabled = isAllCompleted || existing?.offlineEnabled == true,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        dao.put(job)
        return job
    }

    private suspend fun uniqueFolderName(playlistName: String, playlistId: String): String {
        val preferred = PlaylistDownloadPaths.stableFolder(playlistName)
        val owner = dao.getForFolder(preferred)
        return if (owner == null || owner.playlistId == playlistId) preferred
        else PlaylistDownloadPaths.collisionFolder(playlistName, playlistId)
    }

    suspend fun update(
        job: PlaylistDownloadJobEntity,
        results: List<TrackDownloadResult>,
        status: PlaylistDownloadStatus,
        currentTrackId: String? = null,
        offlineEnabled: Boolean = job.offlineEnabled
    ): PlaylistDownloadJobEntity = job.copy(
        resultsJson = json.encodeToString(results),
        status = status.name,
        completedCount = results.count { it.outcome in SUCCESS_OUTCOMES },
        failedCount = results.count { it.outcome == DownloadOutcome.FAILURE },
        skippedCount = results.count { it.outcome in setOf(DownloadOutcome.ALREADY_DOWNLOADED, DownloadOutcome.SKIPPED) },
        currentTrackId = currentTrackId,
        cancelRequested = status == PlaylistDownloadStatus.CANCELLED,
        offlineEnabled = offlineEnabled,
        updatedAt = System.currentTimeMillis()
    ).also { dao.update(it) }

    suspend fun requestCancel(jobId: String) = dao.requestCancel(jobId, System.currentTimeMillis())
    suspend fun setOfflineEnabled(playlistId: String, enabled: Boolean) =
        dao.setOfflineEnabled(playlistId, enabled, System.currentTimeMillis())
    suspend fun delete(jobId: String) = dao.delete(jobId)

    companion object {
        val SUCCESS_OUTCOMES = setOf(DownloadOutcome.SUCCESS, DownloadOutcome.ALREADY_DOWNLOADED)
        private val ACTIVE_STATES = setOf(PlaylistDownloadStatus.DOWNLOADING.name)
        fun stableJobId(playlistId: String): String = "playlist_" + MessageDigest.getInstance("SHA-256")
            .digest(playlistId.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        fun platformJobId(jobId: String): Int = (jobId.hashCode() and 0x3fffffff) + 10_000
    }
}
