package com.auralis.music.data.download

import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
enum class DownloadOutcome { SUCCESS, ALREADY_DOWNLOADED, FAILURE, CANCELLED, SKIPPED }

@Serializable
data class TrackDownloadResult(
    val trackId: String,
    val outcome: DownloadOutcome,
    val stage: String? = null,
    val error: String? = null,
    val contentUri: String? = null,
    val bytes: Long = 0L
)

@Serializable
enum class PlaylistDownloadStatus { DOWNLOADING, COMPLETE, ALREADY_DOWNLOADED, PARTIAL_FAILURE, FAILED, CANCELLED, EMPTY }

data class PlaylistDownloadState(
    val trackIds: List<String>,
    val results: List<TrackDownloadResult> = emptyList(),
    val status: PlaylistDownloadStatus = PlaylistDownloadStatus.DOWNLOADING
) {
    val total: Int get() = trackIds.size
    val finished: Int get() = results.size
    val succeeded: Int get() = results.count {
        it.outcome == DownloadOutcome.SUCCESS || it.outcome == DownloadOutcome.ALREADY_DOWNLOADED
    }
}

/** One awaited item at a time. Existing active transfers can be joined by download(). */
internal suspend fun runPlaylistDownload(
    tracks: List<Track>,
    isDownloaded: (String) -> Boolean,
    download: suspend (Track) -> TrackDownloadResult,
    onState: (PlaylistDownloadState) -> Unit
): PlaylistDownloadState {
    val unique = tracks.distinctBy { it.id }
    var state = PlaylistDownloadState(unique.map { it.id })
    onState(state)
    try {
        for (track in unique) {
            currentCoroutineContext().ensureActive()
            var result = if (isDownloaded(track.id)) {
                TrackDownloadResult(track.id, DownloadOutcome.ALREADY_DOWNLOADED)
            } else {
                try {
                    download(track)
                } catch (e: CancellationException) {
                    // Cancellation of a shared transfer need not cancel its playlist waiter.
                    currentCoroutineContext().ensureActive()
                    TrackDownloadResult(track.id, DownloadOutcome.CANCELLED, "download", e.message)
                } catch (e: Exception) {
                    TrackDownloadResult(track.id, DownloadOutcome.FAILURE, "download", e.message ?: e.javaClass.simpleName)
                }
            }
            if (result.outcome in setOf(DownloadOutcome.SUCCESS, DownloadOutcome.ALREADY_DOWNLOADED) && !isDownloaded(track.id)) {
                result = TrackDownloadResult(track.id, DownloadOutcome.FAILURE, "verification", "Persisted audio file not found")
            }
            state = state.copy(results = state.results + result)
            onState(state)
        }
        // A track might have been removed while a later item was downloading.
        val verifiedResults = state.results.map { result ->
            if (result.outcome in setOf(DownloadOutcome.SUCCESS, DownloadOutcome.ALREADY_DOWNLOADED) && !isDownloaded(result.trackId)) {
                TrackDownloadResult(result.trackId, DownloadOutcome.FAILURE, "verification", "Download removed before playlist completed")
            } else result
        }
        state = state.copy(results = verifiedResults)
        val status = when {
            unique.isEmpty() -> PlaylistDownloadStatus.EMPTY
            state.results.all { it.outcome == DownloadOutcome.ALREADY_DOWNLOADED } -> PlaylistDownloadStatus.ALREADY_DOWNLOADED
            state.succeeded == state.total -> PlaylistDownloadStatus.COMPLETE
            state.succeeded > 0 -> PlaylistDownloadStatus.PARTIAL_FAILURE
            state.results.all { it.outcome == DownloadOutcome.CANCELLED } -> PlaylistDownloadStatus.CANCELLED
            else -> PlaylistDownloadStatus.FAILED
        }
        return state.copy(status = status).also(onState)
    } catch (e: CancellationException) {
        val pending = unique.drop(state.finished).map {
            TrackDownloadResult(it.id, DownloadOutcome.CANCELLED, "playlist", "Playlist download cancelled")
        }
        onState(state.copy(results = state.results + pending, status = PlaylistDownloadStatus.CANCELLED))
        throw e
    }
}
