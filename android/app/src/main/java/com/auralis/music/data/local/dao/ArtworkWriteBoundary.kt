package com.auralis.music.data.local.dao

import com.auralis.music.data.local.entity.ArtworkSelectionEntity
import com.auralis.music.data.local.entity.TrackEntity

/** Only an explicit selection protects artwork. Legacy nonblank URLs gain no authority. */
internal object ArtworkWriteBoundary {
    fun sameRecording(a: TrackEntity, b: TrackEntity) =
        a.id == b.id && a.title == b.title && a.artist == b.artist &&
            a.duration == b.duration && a.source == b.source

    fun sameSnapshot(a: TrackEntity, b: TrackEntity) =
        sameRecording(a, b) && a.album == b.album && a.thumbnail == b.thumbnail

    fun merge(incoming: TrackEntity, existing: TrackEntity?, selected: ArtworkSelectionEntity?): TrackEntity {
        if (selected == null || existing == null) return incoming
        // A reused ID must not apply selected artwork to a different recording. Preserve the
        // bound row instead; favorites can still change. Rebinding requires an explicit decision.
        val bound = existing.id == selected.trackId && existing.title == selected.title &&
            existing.artist == selected.artist && existing.duration == selected.duration &&
            existing.source == selected.source
        if (!bound || !sameRecording(incoming, existing)) {
            return existing.copy(isFavorite = incoming.isFavorite, favoriteAddedAt = incoming.favoriteAddedAt)
        }
        return incoming.copy(album = selected.album, thumbnail = selected.thumbnail,
            dominantColor = if (incoming.thumbnail == selected.thumbnail) incoming.dominantColor else existing.dominantColor)
    }
}
