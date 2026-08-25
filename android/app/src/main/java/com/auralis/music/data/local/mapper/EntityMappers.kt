package com.auralis.music.data.local.mapper

import com.auralis.music.data.local.dao.HistoryWithTrackTuple
import com.auralis.music.data.local.dao.PlayCountWithTrackTuple
import com.auralis.music.data.local.dao.PlaylistWithTracksTuple
import com.auralis.music.data.local.entity.*
import com.auralis.music.domain.model.*

fun TrackEntity.toDomain(): Track {
    return Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        thumbnail = thumbnail,
        source = source,
        channelTitle = channelTitle,
        views = views,
        dominantColor = dominantColor
    )
}

fun Track.toEntity(isFavorite: Boolean = false, favoriteAddedAt: Long? = null): TrackEntity {
    return TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        thumbnail = thumbnail,
        source = source,
        channelTitle = channelTitle,
        views = views,
        dominantColor = dominantColor,
        isFavorite = isFavorite,
        favoriteAddedAt = favoriteAddedAt
    )
}

fun PlaylistWithTracksTuple.toDomain(): Playlist {
    return Playlist(
        id = playlist.id,
        title = playlist.title,
        description = playlist.description,
        coverUrl = playlist.coverUrl,
        tracks = tracks.map { it.toDomain() },
        createdAt = playlist.createdAt,
        isCustom = playlist.isCustom
    )
}

fun Playlist.toEntity(): PlaylistEntity {
    return PlaylistEntity(
        id = id,
        title = title,
        description = description,
        coverUrl = coverUrl,
        createdAt = createdAt,
        isCustom = isCustom
    )
}

fun SavedArtistEntity.toDomain(): SavedArtist {
    return SavedArtist(
        id = id,
        name = name,
        thumbnail = thumbnail,
        subscribers = subscribers,
        query = query,
        savedAt = savedAt
    )
}

fun SavedArtist.toEntity(): SavedArtistEntity {
    return SavedArtistEntity(
        id = id,
        name = name,
        thumbnail = thumbnail,
        subscribers = subscribers,
        query = query,
        savedAt = savedAt
    )
}

fun SavedAlbumEntity.toDomain(): SavedAlbum {
    return SavedAlbum(
        id = id,
        title = title,
        artist = artist,
        thumbnail = thumbnail,
        trackCount = trackCount,
        savedAt = savedAt
    )
}

fun SavedAlbum.toEntity(): SavedAlbumEntity {
    return SavedAlbumEntity(
        id = id,
        title = title,
        artist = artist,
        thumbnail = thumbnail,
        trackCount = trackCount,
        savedAt = savedAt
    )
}

fun HistoryWithTrackTuple.toDomain(): HistoryEntry {
    return HistoryEntry(
        track = track.toDomain(),
        playedAt = history.playedAt
    )
}

fun PlayCountWithTrackTuple.toDomain(): PlayCountEntry {
    return PlayCountEntry(
        trackId = playCount.trackId,
        count = playCount.count,
        lastPlayed = playCount.lastPlayed,
        track = track.toDomain()
    )
}
