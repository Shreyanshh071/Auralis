package com.auralis.music.data.repository

import com.auralis.music.data.local.dao.TrackDao
import com.auralis.music.data.local.mapper.toEntity
import com.auralis.music.domain.artwork.ArtworkSelection
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.map

/** No network lookups, repair jobs, or automatic selections. Decisions enter explicitly. */
class ArtworkSelectionRepository(private val trackDao: TrackDao) {
    suspend fun get(trackId: String) = trackDao.getArtworkSelection(trackId)?.toDomain()
    fun observe(trackId: String) = trackDao.observeArtworkSelection(trackId).map { it?.toDomain() }

    /** False means stale snapshot/revision, wrong identity, missing Track, or invalid evidence.
     * expectedRevision=0 means no previous selection. A successful decision increments revision.
     */
    suspend fun select(expectedTrack: Track, decision: ArtworkSelection, expectedRevision: Long = 0): Boolean =
        trackDao.selectArtwork(expectedTrack.toEntity(), decision, expectedRevision)
}
