package com.auralis.music.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.auralis.music.domain.model.TrackSource

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val duration: Long,
    val thumbnail: String,
    val source: TrackSource = TrackSource.YOUTUBE,
    val channelTitle: String? = null,
    val views: String? = null,
    val dominantColor: Int? = null,
    val isFavorite: Boolean = false,
    val favoriteAddedAt: Long? = null
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val isCustom: Boolean = true
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["trackId"])
    ]
)
data class PlaylistTrackCrossRef(
    val playlistId: String,
    val trackId: String,
    val position: Int
)

@Entity(tableName = "saved_artists")
data class SavedArtistEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val thumbnail: String?,
    val subscribers: String?,
    val query: String?,
    val savedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_albums")
data class SavedAlbumEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val artist: String?,
    val thumbnail: String?,
    val trackCount: Int?,
    val savedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "history",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["trackId"])]
)
data class HistoryEntity(
    @PrimaryKey
    val trackId: String,
    val playedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "play_counts",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["trackId"])]
)
data class PlayCountEntity(
    @PrimaryKey
    val trackId: String,
    val count: Int,
    val lastPlayed: Long = System.currentTimeMillis()
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey
    val query: String,
    val timestamp: Long = System.currentTimeMillis()
)
