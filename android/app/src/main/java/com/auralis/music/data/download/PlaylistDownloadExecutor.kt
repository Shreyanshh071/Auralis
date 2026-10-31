package com.auralis.music.data.download

import android.content.Context
import com.auralis.music.data.network.AudioStreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class PlaylistDownloadExecutor(private val context: Context) {
    private val repository = PlaylistDownloadRepository(context)
    private val publicFiles = PlaylistDownloadFiles(context)

    suspend fun execute(jobId: String, onProgress: suspend (PlaylistDownloadJobEntity) -> Unit): PlaylistDownloadJobEntity {
        val mutex = locks.getOrPut(jobId) { Mutex() }
        return mutex.withLock {
            AuralisDownloadManager.awaitInitialized(context)
            var job = requireNotNull(repository.get(jobId)) { "Playlist download job not found: $jobId" }
            val tracks = repository.tracks(job)
            val results = repository.results(job)
                .filter { previous ->
                    previous.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES &&
                        AuralisDownloadManager.isDownloaded(previous.trackId) &&
                        publicFiles.verify(previous.contentUri)
                }
                .associateBy { it.trackId }
                .toMutableMap()

            // Pre-resolve any tracks that are already downloaded globally on device (e.g. from another playlist)
            for ((index, track) in tracks.withIndex()) {
                if (results.containsKey(track.id)) continue
                if (AuralisDownloadManager.isDownloaded(track.id)) {
                    val source = AuralisDownloadManager.getDownloadedFile(track.id)
                    if (source != null && source.isFile && source.length() > 1024) {
                        try {
                            val published = publicFiles.publish(source, job.folderName, PlaylistDownloadPaths.trackFileName(index, track))
                            results[track.id] = TrackDownloadResult(
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

            job = repository.update(job, tracks.mapNotNull { results[it.id] }, PlaylistDownloadStatus.DOWNLOADING, null, false)
            onProgress(job)

            // Only progress and result commits are serialized. Resolution, transfer and public
            // file publishing can overlap, using the manager's existing two global transfer slots.
            val progressLock = Mutex()
            val activeTracks = ConcurrentHashMap.newKeySet<String>()
            val folderName = job.folderName
            try {
                forEachDownload(tracks.withIndex().toList()) { (index, track) ->
                    val shouldDownload = progressLock.withLock {
                        job = requireNotNull(repository.get(jobId))
                        if (job.cancelRequested) throw CancellationException("Playlist download cancelled")
                        val previous = results[track.id]
                        if (previous != null && previous.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES &&
                            AuralisDownloadManager.isDownloaded(track.id) && publicFiles.verify(previous.contentUri)) {
                            false
                        } else {
                            activeTracks.add(track.id)
                            job = repository.update(job, tracks.mapNotNull { results[it.id] }, PlaylistDownloadStatus.DOWNLOADING, track.id, false)
                            onProgress(job)
                            true
                        }
                    }
                    if (!shouldDownload) return@forEachDownload
                    var finalResult: TrackDownloadResult = TrackDownloadResult(track.id, DownloadOutcome.FAILURE, "download", "Initial failure")
                    var attempt = 0
                    val maxAttempts = 3
                    while (attempt < maxAttempts) {
                        attempt++
                        currentCoroutineContext().ensureActive()
                        if (attempt > 1) {
                            AudioStreamResolver.invalidateStream(track.id)
                            delay(1200L * attempt)
                        }
                        val base = AuralisDownloadManager.downloadTrackAwait(track)
                        if (base.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES) {
                            val source = AuralisDownloadManager.getDownloadedFile(track.id)
                            if (source == null) {
                                finalResult = TrackDownloadResult(track.id, DownloadOutcome.FAILURE, "verification", "Private source download missing")
                            } else {
                                try {
                                    val published = publicFiles.publish(source, folderName, PlaylistDownloadPaths.trackFileName(index, track))
                                    finalResult = TrackDownloadResult(track.id, base.outcome, contentUri = published.first, bytes = published.second)
                                    break
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    android.util.Log.w("PlaylistDownload", "Publish attempt $attempt failed for ${track.title}: ${e.message}")
                                    finalResult = TrackDownloadResult(track.id, DownloadOutcome.FAILURE, "public-storage", e.message ?: e.javaClass.simpleName)
                                }
                            }
                        } else {
                            android.util.Log.w("PlaylistDownload", "Download attempt $attempt failed for ${track.title}: ${base.error}")
                            finalResult = base
                            if (AuralisDownloadManager.isAgeRestrictedError(base.error)) break
                        }
                    }
                    progressLock.withLock {
                        results[track.id] = finalResult
                        activeTracks.remove(track.id)
                        job = requireNotNull(repository.get(jobId))
                        if (job.cancelRequested) throw CancellationException("Playlist download cancelled")
                        job = repository.update(job, tracks.mapNotNull { results[it.id] }, PlaylistDownloadStatus.DOWNLOADING, activeTracks.firstOrNull(), false)
                        onProgress(job)
                    }
                }
                val verified = tracks.map { track ->
                    val result = results[track.id]
                    if (result != null && result.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES &&
                        AuralisDownloadManager.isDownloaded(track.id) && publicFiles.verify(result.contentUri)) result
                    else result ?: TrackDownloadResult(track.id, DownloadOutcome.FAILURE, "verification", "Public playlist file missing")
                }
                val successful = verified.count {
                    it.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES &&
                        AuralisDownloadManager.isDownloaded(it.trackId) && publicFiles.verify(it.contentUri)
                }
                val status = when {
                    tracks.isEmpty() -> PlaylistDownloadStatus.EMPTY
                    successful == tracks.size -> PlaylistDownloadStatus.COMPLETE
                    successful > 0 -> PlaylistDownloadStatus.PARTIAL_FAILURE
                    else -> PlaylistDownloadStatus.FAILED
                }
                val shouldEnableOffline = (job.offlineEnabled || status == PlaylistDownloadStatus.COMPLETE) && status == PlaylistDownloadStatus.COMPLETE
                job = repository.update(job, verified, status, null, shouldEnableOffline)
                onProgress(job)
                job
            } catch (e: CancellationException) {
                val latest = repository.get(jobId) ?: job
                if (latest.cancelRequested) {
                    job = repository.update(latest, results.values.toList(), PlaylistDownloadStatus.CANCELLED, null, false)
                    onProgress(job)
                }
                throw e
            } finally {
                // Manager transfers live in its own scope, so cancelling the workers alone
                // does not stop their requests.
                activeTracks.forEach { AuralisDownloadManager.cancelActiveDownload(it) }
            }
        }
    }

    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
