package com.auralis.music

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import com.auralis.music.ui.library.getDistinctArtworkTracks
import com.auralis.music.ui.library.isAlbumPlaylist
import com.auralis.music.ui.library.resolveAlbumArtworkUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryAlbumArtworkTest {

    private fun createTrack(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        albumId: String? = null,
        thumbnail: String = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    ): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            albumId = albumId,
            thumbnail = thumbnail,
            duration = 200L,
            source = TrackSource.YOUTUBE
        )
    }

    @Test
    fun testIsAlbumPlaylist_byIdPrefix() {
        val albumFromId = Playlist(
            id = "album-bad-newz-123",
            title = "Bad Newz",
            tracks = listOf(createTrack("t1", "Tauba Tauba", "Karan Aujla"))
        )
        assertTrue(isAlbumPlaylist(albumFromId))

        val ytAlbum = Playlist(
            id = "OLAK5uy_k12345",
            title = "Aashiqui 2",
            tracks = listOf(createTrack("t2", "Tum Hi Ho", "Arijit Singh"))
        )
        assertTrue(isAlbumPlaylist(ytAlbum))

        val regularPlaylist = Playlist(
            id = "user-playlist-456",
            title = "Gym Workout",
            tracks = listOf(
                createTrack("t1", "Song A", "Artist 1", album = "Album 1"),
                createTrack("t2", "Song B", "Artist 2", album = "Album 2")
            )
        )
        assertFalse(isAlbumPlaylist(regularPlaylist))
    }

    @Test
    fun testIsAlbumPlaylist_byDescription() {
        val albumWithDesc = Playlist(
            id = "custom-id-789",
            title = "Future Nostalgia",
            description = "Album by Dua Lipa",
            tracks = listOf(createTrack("t1", "Levitating", "Dua Lipa"))
        )
        assertTrue(isAlbumPlaylist(albumWithDesc))
    }

    @Test
    fun testIsAlbumPlaylist_bySavedAlbumsMatch() {
        val savedAlbums = listOf(
            SavedAlbum(
                id = "saved-album-1",
                title = "Starboy",
                artist = "The Weeknd",
                thumbnail = "https://artwork.com/starboy.jpg"
            )
        )
        val album = Playlist(
            id = "random-playlist-id",
            title = "Starboy",
            tracks = listOf(createTrack("t1", "Starboy", "The Weeknd"))
        )
        assertTrue(isAlbumPlaylist(album, savedAlbums))
    }

    @Test
    fun testIsAlbumPlaylist_byTracksSharingSameAlbumEvenWithDifferentArtists() {
        // Bollywood album or soundtrack where different songs have different artists
        val soundtrackAlbum = Playlist(
            id = "custom-soundtrack-id",
            title = "Bad Newz",
            tracks = listOf(
                createTrack("t1", "Tauba Tauba", "Karan Aujla", album = "Bad Newz"),
                createTrack("t2", "Jaanam", "Vishal Mishra", album = "Bad Newz"),
                createTrack("t3", "Mere Mehboob Mere Sanam", "Alka Yagnik, Udit Narayan", album = "Bad Newz"),
                createTrack("t4", "Haule Haule", "Jubin Nautiyal", album = "Bad Newz")
            )
        )
        assertTrue(isAlbumPlaylist(soundtrackAlbum))
    }

    @Test
    fun testIsAlbumPlaylist_falseForMixedPlaylists() {
        val mixedPlaylist = Playlist(
            id = "playlist-uuid-999",
            title = "My Daily Mix",
            tracks = listOf(
                createTrack("t1", "Song 1", "Artist 1", album = "Album 1"),
                createTrack("t2", "Song 2", "Artist 2", album = "Album 2"),
                createTrack("t3", "Song 3", "Artist 3", album = "Album 3"),
                createTrack("t4", "Song 4", "Artist 4", album = "Album 4")
            )
        )
        assertFalse(isAlbumPlaylist(mixedPlaylist))
    }

    @Test
    fun testResolveAlbumArtworkUrl_prioritizesDirectCover() {
        val album = Playlist(
            id = "album-1",
            title = "Album One",
            coverUrl = "https://canonical.com/cover.jpg",
            tracks = listOf(createTrack("t1", "Track 1", "Artist 1", thumbnail = "https://yt.com/thumb.jpg"))
        )
        assertEquals("https://canonical.com/cover.jpg", resolveAlbumArtworkUrl(album))
    }

    @Test
    fun testResolveAlbumArtworkUrl_fallsBackToSavedAlbumThumbnail() {
        val savedAlbums = listOf(
            SavedAlbum(
                id = "album-2",
                title = "Album Two",
                artist = "Artist Two",
                thumbnail = "https://saved.com/album2.jpg"
            )
        )
        val album = Playlist(
            id = "album-2",
            title = "Album Two",
            coverUrl = null,
            tracks = listOf(createTrack("t1", "Track 1", "Artist 2", thumbnail = "https://yt.com/fallback.jpg"))
        )
        assertEquals("https://saved.com/album2.jpg", resolveAlbumArtworkUrl(album, savedAlbums))
    }

    @Test
    fun testResolveAlbumArtworkUrl_fallsBackToFirstTrackThumbnail() {
        val album = Playlist(
            id = "album-3",
            title = "Album Three",
            coverUrl = null,
            tracks = listOf(
                createTrack("t1", "Track 1", "Artist 3", thumbnail = "https://yt.com/track1.jpg"),
                createTrack("t2", "Track 2", "Artist 3", thumbnail = "https://yt.com/track2.jpg")
            )
        )
        assertEquals("https://yt.com/track1.jpg", resolveAlbumArtworkUrl(album))
    }

    @Test
    fun testResolveAlbumArtworkUrl_neverHijackedByCachedTrackMashup() {
        val track = createTrack(
            id = "t_tame",
            title = "The Less I Know The Better",
            artist = "Tame Impala",
            album = "Currents",
            thumbnail = "https://canonical.com/currents_purple.jpg"
        )
        val album = Playlist(
            id = "album_currents",
            title = "Currents",
            coverUrl = null,
            tracks = listOf(track)
        )

        // Inject contaminated cache entry in AlbumMetadataResolver for this track
        val cacheKey = "tame impala::the less i know the better"
        com.auralis.music.data.network.AlbumMetadataResolver.memoryCache.put(
            cacheKey,
            com.auralis.music.data.network.ResolvedAlbum(
                albumTitle = "The Less I Know The Better x Houdini",
                albumId = "MPREb_fake",
                albumArt = "https://fake.com/lace_mask.jpg",
                artistName = "Mashup Artist",
                isSingle = false
            )
        )

        val resolvedArt = resolveAlbumArtworkUrl(album)
        // Must resolve to the authentic track thumbnail, NOT the cached lace mask!
        assertEquals("https://canonical.com/currents_purple.jpg", resolvedArt)
        assertFalse("Must never resolve to lace mask mashup", resolvedArt == "https://fake.com/lace_mask.jpg")
    }

    @Test
    fun testGetDistinctArtworkTracks_deduplicatesTracksWithSameAlbumAcrossDifferentArtists() {
        val tracks = listOf(
            createTrack("t1", "Tauba Tauba", "Karan Aujla", album = "Bad Newz"),
            createTrack("t2", "Jaanam", "Vishal Mishra", album = "Bad Newz"),
            createTrack("t3", "Mere Mehboob Mere Sanam", "Alka Yagnik", album = "Bad Newz"),
            createTrack("t4", "Haule Haule", "Jubin Nautiyal", album = "Bad Newz")
        )
        val distinct = getDistinctArtworkTracks(tracks)
        assertEquals(1, distinct.size)
    }

    @Test
    fun testGetDistinctArtworkTracks_preservesDistinctForMixedPlaylist() {
        val tracks = listOf(
            createTrack("t1", "Song 1", "Artist 1", album = "Album 1"),
            createTrack("t2", "Song 2", "Artist 2", album = "Album 2"),
            createTrack("t3", "Song 3", "Artist 3", album = "Album 3"),
            createTrack("t4", "Song 4", "Artist 4", album = "Album 4")
        )
        val distinct = getDistinctArtworkTracks(tracks)
        assertEquals(4, distinct.size)
    }
}
