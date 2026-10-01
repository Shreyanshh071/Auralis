package com.auralis.music.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.auralis.music.domain.artwork.ArtworkSelection
import com.auralis.music.domain.artwork.ArtworkSelectionBasis
import com.auralis.music.domain.model.TrackSource

@Entity(
    tableName = "artwork_selections",
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class ArtworkSelectionEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val source: TrackSource,
    val thumbnail: String,
    val album: String?,
    val provider: String,
    val providerItemId: String,
    val releaseId: String?,
    val evidence: String,
    val basis: String,
    val revision: Long
) {
    fun toDomain() = ArtworkSelection(
        trackId, thumbnail, album, provider, providerItemId, releaseId, evidence,
        ArtworkSelectionBasis.valueOf(basis), revision
    )
}
