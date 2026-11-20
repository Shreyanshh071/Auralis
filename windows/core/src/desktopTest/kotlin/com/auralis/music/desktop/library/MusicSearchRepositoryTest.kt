package com.auralis.music.desktop.library

import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class MusicSearchRepositoryTest {
    @Test fun allSearchSurfacesAndIncrementalCallbacksExcludeBlockedTracks() = runBlocking {
        val short = Track("short", duration = 49)
        val song = Track("song", duration = 49)
        val results = SearchResults(topResult = SearchTopResult.SongResult(short),
            runnerUp = SearchTopResult.SongResult(short), recommendations = listOf(short, song), songs = listOf(short, song),
            rankedMatches = listOf(SearchTopResult.SongResult(short), SearchTopResult.SongResult(song)))
        val page = ArtistPage(Artist("artist", "Artist"), topSongs = listOf(short, song), videos = listOf(short, song))
        val source = object : SearchRepository {
            override suspend fun search(query: String) = results
            override suspend fun search(query: String, onResults: (SearchResults) -> Unit) = results.also { onResults(it) }
            override fun cachedSearch(query: String) = results
            override suspend fun searchSongs(query: String) = listOf(short, song)
            override suspend fun searchAlbums(query: String) = emptyList<PlaylistResult>()
            override suspend fun searchArtists(query: String) = emptyList<Artist>()
            override suspend fun searchPlaylists(query: String) = emptyList<PlaylistResult>()
            override suspend fun getSuggestions(query: String) = emptyList<String>()
            override suspend fun getArtistPage(artist: Artist) = page
            override suspend fun getAlbumTracks(album: PlaylistResult) = listOf(short, song)
            override fun getRecentSearchQueries() = flowOf(emptyList<String>())
            override suspend fun recordSearchQuery(query: String) {}
            override suspend fun removeSearchQuery(query: String) {}
            override suspend fun clearSearchHistory() {}
        }
        val filter: (List<Track>) -> List<Track> = { tracks -> tracks.filterNot { it.id == short.id } }
        val repository = MusicSearchRepository(source, filter = { filter(it) }, cachedFilter = filter)
        val observed = mutableListOf<SearchResults>()
        observed += repository.search("query") { observed += it }
        observed += assertNotNull(repository.cachedSearch("query"))
        for (result in observed) {
            assertNull(result.topResult); assertNull(result.runnerUp)
            assertEquals(listOf(song), result.songs)
            assertEquals(listOf(song), result.recommendations)
            assertEquals(listOf(SearchTopResult.SongResult(song)), result.rankedMatches)
        }
        repository.searchLiveSongs("query") { assertEquals(listOf(song), it) }
        assertEquals(listOf(song), repository.getAlbumTracks(PlaylistResult("playlist", "Playlist")))
        val artist = assertNotNull(repository.getArtistPage(page.artist) { assertEquals(listOf(song), it.videos) })
        assertEquals(listOf(song), artist.topSongs)
        assertEquals(listOf(song), artist.videos)
    }
}
