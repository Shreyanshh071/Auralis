package com.auralis.music.data.download

data class PlaylistDownloadReconciliation(
    val results: List<TrackDownloadResult>,
    val status: PlaylistDownloadStatus,
    val offlineEnabled: Boolean
)

internal fun reconcilePlaylistDownloadState(
    trackIds: List<String>,
    results: List<TrackDownloadResult>,
    offlineEnabled: Boolean,
    isInDownloadStore: (String) -> Boolean,
    isPublicFileValid: (String?) -> Boolean
): PlaylistDownloadReconciliation {
    val expected = trackIds.distinct()
    val byId = results.associateBy { it.trackId }
    val reconciled = expected.map { trackId ->
        val existing = byId[trackId]
        when {
            existing == null -> TrackDownloadResult(
                trackId, DownloadOutcome.FAILURE, "verification", "Downloaded file is missing"
            )
            existing.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES &&
                (!isInDownloadStore(trackId) || !isPublicFileValid(existing.contentUri)) -> existing.copy(
                    outcome = DownloadOutcome.FAILURE,
                    stage = "verification",
                    error = "Downloaded file is missing or invalid"
                )
            else -> existing
        }
    }
    val successful = reconciled.count { it.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES }
    val status = when {
        expected.isEmpty() -> PlaylistDownloadStatus.EMPTY
        successful == expected.size -> PlaylistDownloadStatus.COMPLETE
        successful > 0 -> PlaylistDownloadStatus.PARTIAL_FAILURE
        else -> PlaylistDownloadStatus.FAILED
    }
    return PlaylistDownloadReconciliation(reconciled, status, offlineEnabled && status == PlaylistDownloadStatus.COMPLETE)
}

internal fun verifiedDownloadBytes(results: List<TrackDownloadResult>): Long =
    results.filter { it.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES }.sumOf { it.bytes.coerceAtLeast(0L) }

internal fun indexPlaylistJobs(jobs: List<PlaylistDownloadJobEntity>): Map<String, PlaylistDownloadJobEntity> =
    jobs.associateBy { it.playlistId }

internal fun removePlaylistJobFromState(
    jobs: Map<String, PlaylistDownloadJobEntity>,
    jobId: String
): Map<String, PlaylistDownloadJobEntity> = jobs.filterValues { it.jobId != jobId }
