package com.auralis.music

import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchSelectionRegressionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val songA = Track(
        id = "id_song_a",
        title = "Song A Title",
        artist = "Artist A",
        thumbnail = "https://example.com/a.jpg",
        duration = 200
    )

    private val songB = Track(
        id = "id_song_b",
        title = "Song B Title",
        artist = "Artist B",
        thumbnail = "https://example.com/b.jpg",
        duration = 210
    )

    private val songB2 = Track(
        id = "id_song_b2",
        title = "Song B2 - Second Result",
        artist = "Artist B2",
        thumbnail = "https://example.com/b2.jpg",
        duration = 195
    )

    private val songC = Track(
        id = "id_song_c",
        title = "Song C Title",
        artist = "Artist C",
        thumbnail = "https://example.com/c.jpg",
        duration = 180
    )

    private val albumB = PlaylistResult(
        id = "album_b_id",
        title = "Song B Album",
        author = "Artist B",
        thumbnail = "https://example.com/album_b.jpg"
    )

    private val artistB = Artist(
        id = "artist_b_id",
        name = "Artist B",
        thumbnail = "https://example.com/artist_b.jpg"
    )

    private val resultsA = SearchResults(
        topResult = SearchTopResult.SongResult(songA),
        songs = listOf(songA)
    )

    private val resultsB = SearchResults(
        topResult = SearchTopResult.SongResult(songB),
        songs = listOf(songB, songB2),
        albums = listOf(albumB),
        artists = listOf(artistB)
    )

    private val resultsC = SearchResults(
        topResult = SearchTopResult.SongResult(songC),
        songs = listOf(songC)
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testPreviousSearchMustNotOverwriteNewerSearchWhenFinishingOutOfOrder() = testScope.runTest {
        val searchADeferred = CompletableDeferred<SearchResults>()
        val searchBDeferred = CompletableDeferred<SearchResults>()

        val fakeRepo = object : SearchRepository {
            override suspend fun search(query: String): SearchResults {
                return when (query) {
                    "Song A" -> searchADeferred.await()
                    "Song B" -> searchBDeferred.await()
                    else -> SearchResults()
                }
            }
            override suspend fun searchSongs(query: String): List<Track> = emptyList()
            override suspend fun searchAlbums(query: String): List<PlaylistResult> = emptyList()
            override suspend fun searchArtists(query: String): List<Artist> = emptyList()
            override suspend fun searchPlaylists(query: String): List<PlaylistResult> = emptyList()
            override suspend fun getSuggestions(query: String): List<String> = emptyList()
            override fun getRecentSearchQueries(): Flow<List<String>> = flowOf(emptyList())
            override suspend fun recordSearchQuery(query: String) {}
            override suspend fun removeSearchQuery(query: String) {}
            override suspend fun clearSearchHistory() {}
            override suspend fun getArtistPage(artist: Artist): ArtistPage? = null
            override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = emptyList()
        }

        val viewModel = SearchViewModel(fakeRepo)
        advanceUntilIdle()

        // 1. Trigger Search A
        viewModel.performSearch("Song A")
        advanceTimeBy(10)

        // 2. Quickly trigger Search B
        viewModel.performSearch("Song B")
        advanceTimeBy(10)

        // 3. Search B finishes FIRST
        searchBDeferred.complete(resultsB)
        advanceUntilIdle()

        assertEquals("Song B", viewModel.uiState.value.query)
        assertEquals("Song B results should be visible after B finishes", "id_song_b", (viewModel.uiState.value.searchResults.topResult as? SearchTopResult.SongResult)?.track?.id)

        // 4. Search A finishes LATER (simulating delayed network arrival)
        searchADeferred.complete(resultsA)
        advanceUntilIdle()

        // CRITICAL CHECK: Search A must NOT stomp over Search B's results!
        assertEquals("Search query must remain Song B", "Song B", viewModel.uiState.value.query)
        val currentTop = (viewModel.uiState.value.searchResults.topResult as? SearchTopResult.SongResult)?.track?.id
        assertEquals("Search A must NEVER overwrite Search B!", "id_song_b", currentTop)
    }

    @Test
    fun testStaleSearchResultsMustBeClearedImmediatelyWhenNewSearchInitiated() = testScope.runTest {
        val searchBDeferred = CompletableDeferred<SearchResults>()

        val fakeRepo = object : SearchRepository {
            override suspend fun search(query: String): SearchResults {
                return when (query) {
                    "Song A" -> resultsA
                    "Song B" -> searchBDeferred.await()
                    else -> SearchResults()
                }
            }
            override suspend fun searchSongs(query: String): List<Track> = emptyList()
            override suspend fun searchAlbums(query: String): List<PlaylistResult> = emptyList()
            override suspend fun searchArtists(query: String): List<Artist> = emptyList()
            override suspend fun searchPlaylists(query: String): List<PlaylistResult> = emptyList()
            override suspend fun getSuggestions(query: String): List<String> = emptyList()
            override fun getRecentSearchQueries(): Flow<List<String>> = flowOf(emptyList())
            override suspend fun recordSearchQuery(query: String) {}
            override suspend fun removeSearchQuery(query: String) {}
            override suspend fun clearSearchHistory() {}
            override suspend fun getArtistPage(artist: Artist): ArtistPage? = null
            override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = emptyList()
        }

        val viewModel = SearchViewModel(fakeRepo)
        advanceUntilIdle()

        // Search A completes and populates resultsA
        viewModel.performSearch("Song A")
        advanceUntilIdle()
        assertEquals("id_song_a", (viewModel.uiState.value.searchResults.topResult as? SearchTopResult.SongResult)?.track?.id)

        // Search B starts
        viewModel.performSearch("Song B")
        advanceTimeBy(1)

        // While Search B is in-flight, searchResults must NOT contain Song A!
        assertTrue("SearchResults must be cleared while searching for new song to prevent clicking stale results!", viewModel.uiState.value.searchResults.isEmpty())
        assertTrue("isSearching must be true", viewModel.uiState.value.isSearching)

        // Search B finishes
        searchBDeferred.complete(resultsB)
        advanceUntilIdle()
        assertEquals("id_song_b", (viewModel.uiState.value.searchResults.topResult as? SearchTopResult.SongResult)?.track?.id)
    }

    @Test
    fun testLiveRecommendationsMustNotRetainPreviousSearchSongsOnQueryChange() = testScope.runTest {
        val fakeRepo = object : SearchRepository {
            override suspend fun search(query: String): SearchResults = SearchResults()
            override suspend fun searchSongs(query: String): List<Track> {
                return when (query) {
                    "Song A" -> listOf(songA)
                    "Song B" -> listOf(songB)
                    else -> emptyList()
                }
            }
            override suspend fun searchAlbums(query: String): List<PlaylistResult> = emptyList()
            override suspend fun searchArtists(query: String): List<Artist> = emptyList()
            override suspend fun searchPlaylists(query: String): List<PlaylistResult> = emptyList()
            override suspend fun getSuggestions(query: String): List<String> = emptyList()
            override fun getRecentSearchQueries(): Flow<List<String>> = flowOf(emptyList())
            override suspend fun recordSearchQuery(query: String) {}
            override suspend fun removeSearchQuery(query: String) {}
            override suspend fun clearSearchHistory() {}
            override suspend fun getArtistPage(artist: Artist): ArtistPage? = null
            override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = emptyList()
        }

        val viewModel = SearchViewModel(fakeRepo)
        advanceUntilIdle()

        viewModel.onQueryChange("Song A")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.liveSongRecommendations.size)
        assertEquals("id_song_a", viewModel.uiState.value.liveSongRecommendations.first().id)

        // User now starts typing Song B
        viewModel.onQueryChange("Song B")
        // Stale Song A recommendations must be cleared immediately so they are not shown/clicked under Song B
        assertTrue("Previous live recommendations must be cleared immediately on query change", viewModel.uiState.value.liveSongRecommendations.isEmpty())

        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.liveSongRecommendations.size)
        assertEquals("id_song_b", viewModel.uiState.value.liveSongRecommendations.first().id)
    }

    @Test
    fun testRapidSequentialSearchesABC() = testScope.runTest {
        val fakeRepo = object : SearchRepository {
            override suspend fun search(query: String): SearchResults {
                delay(50)
                return when (query) {
                    "Song A" -> resultsA
                    "Song B" -> resultsB
                    "Song C" -> resultsC
                    else -> SearchResults()
                }
            }
            override suspend fun searchSongs(query: String): List<Track> = emptyList()
            override suspend fun searchAlbums(query: String): List<PlaylistResult> = emptyList()
            override suspend fun searchArtists(query: String): List<Artist> = emptyList()
            override suspend fun searchPlaylists(query: String): List<PlaylistResult> = emptyList()
            override suspend fun getSuggestions(query: String): List<String> = emptyList()
            override fun getRecentSearchQueries(): Flow<List<String>> = flowOf(emptyList())
            override suspend fun recordSearchQuery(query: String) {}
            override suspend fun removeSearchQuery(query: String) {}
            override suspend fun clearSearchHistory() {}
            override suspend fun getArtistPage(artist: Artist): ArtistPage? = null
            override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = emptyList()
        }

        val viewModel = SearchViewModel(fakeRepo)
        advanceUntilIdle()

        // Rapid fire searches
        viewModel.performSearch("Song A")
        advanceTimeBy(10)
        viewModel.performSearch("Song B")
        advanceTimeBy(10)
        viewModel.performSearch("Song C")
        advanceUntilIdle()

        assertEquals("Final query must be Song C", "Song C", viewModel.uiState.value.query)
        val topResult = (viewModel.uiState.value.searchResults.topResult as? SearchTopResult.SongResult)?.track
        assertNotNull("Top result must be present", topResult)
        assertEquals("id_song_c", topResult?.id)
        assertEquals("Song C Title", topResult?.title)
    }

    @Test
    fun testClearingSearchAndSearchingAgain() = testScope.runTest {
        val fakeRepo = object : SearchRepository {
            override suspend fun search(query: String): SearchResults {
                return when (query) {
                    "Song A" -> resultsA
                    "Song B" -> resultsB
                    else -> SearchResults()
                }
            }
            override suspend fun searchSongs(query: String): List<Track> = emptyList()
            override suspend fun searchAlbums(query: String): List<PlaylistResult> = emptyList()
            override suspend fun searchArtists(query: String): List<Artist> = emptyList()
            override suspend fun searchPlaylists(query: String): List<PlaylistResult> = emptyList()
            override suspend fun getSuggestions(query: String): List<String> = emptyList()
            override fun getRecentSearchQueries(): Flow<List<String>> = flowOf(emptyList())
            override suspend fun recordSearchQuery(query: String) {}
            override suspend fun removeSearchQuery(query: String) {}
            override suspend fun clearSearchHistory() {}
            override suspend fun getArtistPage(artist: Artist): ArtistPage? = null
            override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = emptyList()
        }

        val viewModel = SearchViewModel(fakeRepo)
        advanceUntilIdle()

        viewModel.performSearch("Song A")
        advanceUntilIdle()
        assertEquals("id_song_a", (viewModel.uiState.value.searchResults.topResult as? SearchTopResult.SongResult)?.track?.id)

        // Clear search
        viewModel.clearSearch()
        assertTrue("searchResults must be empty after clearSearch", viewModel.uiState.value.searchResults.isEmpty())
        assertEquals("", viewModel.uiState.value.query)
        assertFalse(viewModel.uiState.value.hasSubmittedSearch)

        // Now search B
        viewModel.performSearch("Song B")
        advanceUntilIdle()
        assertEquals("id_song_b", (viewModel.uiState.value.searchResults.topResult as? SearchTopResult.SongResult)?.track?.id)
    }

    @Test
    fun testClickTopResultAndSecondResultExactIdentityPreservation() = testScope.runTest {
        val fakeRepo = object : SearchRepository {
            override suspend fun search(query: String): SearchResults {
                return when (query) {
                    "Song A" -> resultsA
                    "Song B" -> resultsB
                    else -> SearchResults()
                }
            }
            override suspend fun searchSongs(query: String): List<Track> = emptyList()
            override suspend fun searchAlbums(query: String): List<PlaylistResult> = emptyList()
            override suspend fun searchArtists(query: String): List<Artist> = emptyList()
            override suspend fun searchPlaylists(query: String): List<PlaylistResult> = emptyList()
            override suspend fun getSuggestions(query: String): List<String> = emptyList()
            override fun getRecentSearchQueries(): Flow<List<String>> = flowOf(emptyList())
            override suspend fun recordSearchQuery(query: String) {}
            override suspend fun removeSearchQuery(query: String) {}
            override suspend fun clearSearchHistory() {}
            override suspend fun getArtistPage(artist: Artist): ArtistPage? = null
            override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = emptyList()
        }

        val viewModel = SearchViewModel(fakeRepo)
        advanceUntilIdle()

        // 1. Search A completes
        viewModel.performSearch("Song A")
        advanceUntilIdle()

        // 2. Search B completes
        viewModel.performSearch("Song B")
        advanceUntilIdle()

        val results = viewModel.uiState.value.searchResults

        // Simulate User Clicking Top Result (Song B)
        val clickedTopTrack = (results.topResult as SearchTopResult.SongResult).track
        var dispatchedTrackToPlayback: Track? = null
        val onTrackClick: (Track, List<Track>) -> Unit = { track, _ ->
            dispatchedTrackToPlayback = track
        }
        onTrackClick(clickedTopTrack, listOf(clickedTopTrack))

        assertEquals("Clicked top result must dispatch Song B!", "id_song_b", dispatchedTrackToPlayback?.id)
        assertEquals("Song B Title", dispatchedTrackToPlayback?.title)
        assertNotEquals("Must NOT dispatch Song A!", "id_song_a", dispatchedTrackToPlayback?.id)

        // Simulate User Clicking Second Result (Song B2)
        val clickedSecondTrack = results.songs[1]
        onTrackClick(clickedSecondTrack, results.songs)

        assertEquals("Clicked second result must dispatch Song B2!", "id_song_b2", dispatchedTrackToPlayback?.id)
        assertEquals("Song B2 - Second Result", dispatchedTrackToPlayback?.title)
        assertNotEquals("Must NOT dispatch Song A!", "id_song_a", dispatchedTrackToPlayback?.id)
    }

    @Test
    fun testAlbumAndArtistResultsIdentityPreservation() = testScope.runTest {
        val fakeRepo = object : SearchRepository {
            override suspend fun search(query: String): SearchResults = resultsB
            override suspend fun searchSongs(query: String): List<Track> = emptyList()
            override suspend fun searchAlbums(query: String): List<PlaylistResult> = emptyList()
            override suspend fun searchArtists(query: String): List<Artist> = emptyList()
            override suspend fun searchPlaylists(query: String): List<PlaylistResult> = emptyList()
            override suspend fun getSuggestions(query: String): List<String> = emptyList()
            override fun getRecentSearchQueries(): Flow<List<String>> = flowOf(emptyList())
            override suspend fun recordSearchQuery(query: String) {}
            override suspend fun removeSearchQuery(query: String) {}
            override suspend fun clearSearchHistory() {}
            override suspend fun getArtistPage(artist: Artist): ArtistPage? = null
            override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = emptyList()
        }

        val viewModel = SearchViewModel(fakeRepo)
        advanceUntilIdle()

        viewModel.performSearch("Song B")
        advanceUntilIdle()

        val results = viewModel.uiState.value.searchResults
        var openedAlbum: PlaylistResult? = null
        val onAlbumClick: (PlaylistResult) -> Unit = { openedAlbum = it }
        onAlbumClick(results.albums.first())
        assertEquals("album_b_id", openedAlbum?.id)
        assertEquals("Song B Album", openedAlbum?.title)

        var openedArtist: Artist? = null
        val onArtistClick: (Artist) -> Unit = { openedArtist = it }
        onArtistClick(results.artists.first())
        assertEquals("artist_b_id", openedArtist?.id)
        assertEquals("Artist B", openedArtist?.name)
    }
}
