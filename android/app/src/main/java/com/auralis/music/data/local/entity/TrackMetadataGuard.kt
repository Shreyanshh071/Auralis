package com.auralis.music.data.local.entity

/**
 * A playlist, queue, history or cloud Track is a snapshot, not an authority over an
 * existing library row. A verified repair uses the narrow update methods in TrackDao.
 */
object TrackMetadataGuard {
    fun merge(existing: TrackEntity, incoming: TrackEntity): TrackEntity {
        require(existing.id == incoming.id)
        return existing.copy(
            title = existing.title.ifBlank { incoming.title },
            artist = existing.artist.ifBlank { incoming.artist },
            // A later queue or playlist snapshot cannot establish a missing release.
            // Only the narrow verified-release update may fill or clear this field.
            album = existing.album,
            duration = existing.duration.takeIf { it > 0 } ?: incoming.duration,
            thumbnail = existing.thumbnail.ifBlank { incoming.thumbnail },
            // A fresh per-artist list from YouTube is better than none; never drop a known one.
            artistsJson = incoming.artistsJson ?: existing.artistsJson,
            isFavorite = existing.isFavorite,
            favoriteAddedAt = existing.favoriteAddedAt
        )
    }
}
