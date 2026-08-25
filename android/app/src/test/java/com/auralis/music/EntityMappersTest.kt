package com.auralis.music

import com.auralis.music.data.local.dao.HistoryWithTrackTuple
import com.auralis.music.data.local.dao.PlayCountWithTrackTuple
import com.auralis.music.data.local.dao.PlaylistWithTracksTuple
import com.auralis.music.data.local.entity.*
import com.auralis.music.data.local.mapper.toDomain
import com.auralis.music.data.local.mapper.toEntity
import com.auralis.music.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EntityMappersTest {

    @Test
    fun `Track and TrackEntity round-trip conversion preserves all fields`() {
        val domainTrack = Track(
            id = "test_vid_123",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            duration = 200,
            thumbnail = "https://i.ytimg.com/vi/test_vid_123/hqdefault.jpg",
            source = TrackSource.YOUTUBE,
            channelTitle = "TheWeekndVEVO",
            views = "1.5B",
            dominantColor = 0xFF123456.toInt()
        )

        val entity = domainTrack.toEntity(isFavorite = true, favoriteAddedAt = 123456789L)
        assertEquals("test_vid_123", entity.id)
        assertEquals("Blinding Lights", entity.title)
        assertEquals(true, entity.isFavorite)
        assertEquals(123456789L, entity.favoriteAddedAt)

        val mappedBack = entity.toDomain()
        assertEquals(domainTrack.id, mappedBack.id)
        assertEquals(domainTrack.title, mappedBack.title)
        assertEquals(domainTrack.artist, mappedBack.artist)
        assertEquals(domainTrack.album, mappedBack.album)
        assertEquals(domainTrack.duration, mappedBack.duration)
        assertEquals(domainTrack.thumbnail, mappedBack.thumbnail)
        assertEquals(domainTrack.source, mappedBack.source)
        assertEquals(domainTrack.dominantColor, mappedBack.dominantColor)
    }

    @Test
    fun `PlaylistWithTracksTuple maps cleanly to domain Playlist`() {
        val playlistEntity = PlaylistEntity(
            id = "pl_1",
            title = "Night Drive",
            description = "Synthwave and midnight vibes",
            coverUrl = "https://example.com/cover.jpg",
            createdAt = 1000L,
            isCustom = true
        )
        val trackEntities = listOf(
            TrackEntity(
                id = "t1",
                title = "Starboy",
                artist = "The Weeknd",
                album = "Starboy",
                duration = 230,
                thumbnail = "https://example.com/t1.jpg"
            )
        )
        val tuple = PlaylistWithTracksTuple(playlist = playlistEntity, tracks = trackEntities)
        val domain = tuple.toDomain()

        assertEquals("pl_1", domain.id)
        assertEquals("Night Drive", domain.title)
        assertEquals(1, domain.tracks.size)
        assertEquals("Starboy", domain.tracks[0].title)
    }

    @Test
    fun `SavedArtist and SavedAlbum conversions are lossless`() {
        val artist = SavedArtist(
            id = "UC_artist",
            name = "Daft Punk",
            thumbnail = "https://example.com/daft.jpg",
            subscribers = "5M",
            query = "daft punk songs",
            savedAt = 5000L
        )
        val artistEntity = artist.toEntity()
        val artistBack = artistEntity.toDomain()
        assertEquals(artist, artistBack)

        val album = SavedAlbum(
            id = "album_1",
            title = "Discovery",
            artist = "Daft Punk",
            thumbnail = "https://example.com/discovery.jpg",
            trackCount = 14,
            savedAt = 6000L
        )
        val albumEntity = album.toEntity()
        val albumBack = albumEntity.toDomain()
        assertEquals(album, albumBack)
    }

    @Test
    fun `History and PlayCount tuples map correctly with nested track`() {
        val trackEntity = TrackEntity(
            id = "t_history",
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            duration = 240,
            thumbnail = "https://example.com/m83.jpg"
        )
        val historyTuple = HistoryWithTrackTuple(
            history = HistoryEntity(trackId = "t_history", playedAt = 9999L),
            track = trackEntity
        )
        val historyDomain = historyTuple.toDomain()
        assertEquals("t_history", historyDomain.track.id)
        assertEquals(9999L, historyDomain.playedAt)

        val playCountTuple = PlayCountWithTrackTuple(
            playCount = PlayCountEntity(trackId = "t_history", count = 42, lastPlayed = 8888L),
            track = trackEntity
        )
        val playCountDomain = playCountTuple.toDomain()
        assertEquals(42, playCountDomain.count)
        assertEquals(8888L, playCountDomain.lastPlayed)
        assertEquals("Midnight City", playCountDomain.track.title)
    }
}
