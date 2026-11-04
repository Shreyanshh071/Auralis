package com.auralis.music.data.download

internal data class DownloadedFolderMembership(
    val downloadedIds: Set<String>,
    val accountedIds: Set<String>,
    val visible: Boolean
)

internal fun canRemovePlaylistDownload(
    trackId: String,
    otherPlaylistTrackIds: Set<String>,
    individualIds: Set<String>
): Boolean = trackId !in otherPlaylistTrackIds && trackId !in individualIds

/** A playlist snapshot or an old completion count is not proof that its songs are offline. */
internal fun downloadedFolderMembership(
    trackIds: List<String>,
    results: List<TrackDownloadResult>,
    downloadedIds: Set<String>,
    individualIds: Set<String>,
    active: Boolean
): DownloadedFolderMembership {
    val expected = trackIds.toSet()
    val saved = results.asSequence()
        .filter { it.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES }
        .map { it.trackId }
        .filter { it in expected && it in downloadedIds }
        .toSet()
    val accounted = (if (active) expected.intersect(downloadedIds) else saved) - individualIds
    return DownloadedFolderMembership(saved, accounted, active || saved.isNotEmpty())
}
