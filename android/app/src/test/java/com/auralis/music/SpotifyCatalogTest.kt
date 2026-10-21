package com.auralis.music

import com.auralis.music.data.datastore.CatalogSource
import com.auralis.music.data.network.SpotifyCatalog
import com.auralis.music.data.network.SpotifyCatalogClient
import com.auralis.music.data.repository.CatalogSearchRepository
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SpotifyCatalogTest {
    private val trackUri = "spotify:track:0123456789012345678901"
    private val albumUri = "spotify:album:1123456789012345678901"
    private fun track() = JSONObject("""{"uri":"$trackUri","name":"Studio song","duration":{"totalMilliseconds":217000},"artists":{"items":[{"profile":{"name":"Artist"}}]},"albumOfTrack":{"uri":"$albumUri","name":"Studio album","coverArt":{"sources":[{"url":"https://i.scdn.co/image/small","width":64},{"url":"https://i.scdn.co/image/large","width":640}]}}} """)

    @Test fun `Spotify metadata and source identity survive until YouTube playback matching`() {
        val song = SpotifyCatalogClient.parseTrack(track())!!
        assertEquals("sp_0123456789012345678901", song.id)
        assertEquals("Studio song", song.title)
        assertEquals("Artist", song.artist)
        assertEquals("Studio album", song.album)
        assertEquals(albumUri, song.albumId)
        assertEquals(217L, song.duration)
        assertEquals("https://i.scdn.co/image/large", song.thumbnail)
    }

    @Test fun `search reads wrapper URIs and skips unavailable or unrelated media`() {
        val song = track().apply { remove("uri") }
        val response = JSONObject("""{"data":{"searchV2":{"tracksV2":{"items":[{"item":{"_uri":"$trackUri","data":$song}},{"item":{"data":{"uri":"spotify:episode:0123456789012345678901","name":"Podcast"}}}]},"albumsV2":{"items":[{"data":{"uri":"$albumUri","name":"Studio album","type":"ALBUM"}}]},"artists":{"items":[{"data":{"uri":"spotify:artist:2123456789012345678901","profile":{"name":"Artist"}}}]}}}}""")
        val results = SpotifyCatalogClient.parseSearch(response)
        assertEquals(1, results.songs.size)
        assertEquals("sp_0123456789012345678901", results.songs.single().id)
        assertEquals(albumUri, results.albums.single().id)
        assertEquals("Artist", results.artists.single().name)
        assertEquals("album", results.albums.single().releaseType)
    }

    @Test fun `malformed search is a failure instead of fake empty results`() {
        try { SpotifyCatalogClient.parseSearch(JSONObject("{}")); fail("Expected failure") } catch (_: IOException) { }
        assertNull(SpotifyCatalogClient.parseTrack(JSONObject("""{"uri":"https://example.com","name":"Invalid"}""")))
    }

    @Test fun `selected connected Spotify catalog supplies results without calling YouTube`() = runBlocking {
        val youtube = FakeYoutube()
        val catalog = FakeSpotify()
        var status: String? = null
        val repo = CatalogSearchRepository(youtube, catalog, { CatalogSource.SPOTIFY }, { true }, { true }, { status = it })
        assertEquals("spotify", repo.search("song").songs.single().id)
        assertEquals(0, youtube.calls)
        assertEquals(1, catalog.calls)
        assertTrue(status!!.contains("Spotify"))
        assertNull(repo.cachedSearch("song"))
    }

    @Test fun `Spotify errors fall back and cancellation never starts fallback`() = runBlocking {
        val youtube = FakeYoutube()
        val catalog = FakeSpotify().apply { failure = IOException("Unavailable") }
        var status: String? = null
        val repo = CatalogSearchRepository(youtube, catalog, { CatalogSource.SPOTIFY }, { true }, { true }, { status = it })
        assertEquals("youtube", repo.search("song").songs.single().id)
        assertTrue(status!!.contains("unavailable"))
        catalog.failure = CancellationException("Changed query")
        try { repo.search("another"); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(1, youtube.calls)
    }

    @Test fun `offline makes no requests and signed-out Spotify uses YouTube`() = runBlocking {
        val youtube = FakeYoutube()
        val catalog = FakeSpotify()
        var online = false
        val repo = CatalogSearchRepository(youtube, catalog, { CatalogSource.SPOTIFY }, { false }, { online }, {})
        assertTrue(repo.search("song").isEmpty())
        assertEquals(0, catalog.calls)
        assertEquals(0, youtube.calls)
        online = true
        assertEquals("youtube", repo.search("song").songs.single().id)
        assertEquals(0, catalog.calls)
    }

    @Test fun `Spotify detail IDs route to catalog even after changing the selected source`() = runBlocking {
        val catalog = FakeSpotify()
        val repo = CatalogSearchRepository(FakeYoutube(), catalog, { CatalogSource.YOUTUBE_MUSIC }, { true }, { true }, {})
        assertEquals("spotify", repo.getAlbumTracks(PlaylistResult(albumUri, "Album")).single().id)
        assertEquals("Artist", repo.getArtistPage(Artist("spotify:artist:2123456789012345678901", "Artist"))!!.artist.name)
    }

    private class FakeSpotify : SpotifyCatalog {
        var calls = 0
        var failure: Exception? = null
        override suspend fun search(query: String): SearchResults {
            calls++
            failure?.let { throw it }
            return SearchResults(songs = listOf(Track("spotify", "Song", "Artist")))
        }
        override suspend fun artist(artist: Artist) = ArtistPage(artist)
        override suspend fun tracks(release: PlaylistResult) = listOf(Track("spotify", "Song", "Artist"))
    }
    private class FakeYoutube : SearchRepository {
        var calls = 0
        override suspend fun search(query: String): SearchResults { calls++; return SearchResults(songs = listOf(Track("youtube"))) }
        override suspend fun searchSongs(query: String) = search(query).songs
        override suspend fun searchAlbums(query: String) = emptyList<PlaylistResult>()
        override suspend fun searchArtists(query: String) = emptyList<Artist>()
        override suspend fun searchPlaylists(query: String) = emptyList<PlaylistResult>()
        override suspend fun getSuggestions(query: String) = emptyList<String>()
        override suspend fun getArtistPage(artist: Artist) = ArtistPage(artist)
        override suspend fun getAlbumTracks(album: PlaylistResult) = emptyList<Track>()
        override fun getRecentSearchQueries() = flowOf(emptyList<String>())
        override suspend fun recordSearchQuery(query: String) = Unit
        override suspend fun removeSearchQuery(query: String) = Unit
        override suspend fun clearSearchHistory() = Unit
    }
}
