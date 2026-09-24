package com.auralis.music

import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.viewmodel.ExploreDetail
import com.auralis.music.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelNavigationTest {

    private val testDispatcher = StandardTestDispatcher()

    private val fakeRepo = object : SearchRepository {
        override suspend fun search(query: String): SearchResults = SearchResults()
        override suspend fun searchSongs(query: String): List<Track> = emptyList()
        override suspend fun searchAlbums(query: String): List<PlaylistResult> = emptyList()
        override suspend fun searchArtists(query: String): List<Artist> = emptyList()
        override suspend fun searchPlaylists(query: String): List<PlaylistResult> = emptyList()
        override suspend fun getSuggestions(query: String): List<String> = emptyList()
        override suspend fun getArtistPage(artist: Artist): ArtistPage = ArtistPage(artist = artist)
        override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = emptyList()
        override fun getRecentSearchQueries(): Flow<List<String>> = emptyFlow()
        override suspend fun recordSearchQuery(query: String) {}
        override suspend fun removeSearchQuery(query: String) {}
        override suspend fun clearSearchHistory() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testOpenArtistWithResetStackTrueClearsOldStack() {
        val viewModel = SearchViewModel(fakeRepo)
        // Simulate previous search navigation
        viewModel.openArtist(Artist(id = "old1", name = "Old Artist 1"))
        viewModel.openAlbum(PlaylistResult(id = "oldAlbum", title = "Old Album"))
        assertEquals(2, viewModel.uiState.value.detailStack.size)

        // Now user navigates from Home: openArtist with resetStack = true
        val drake = Artist(id = "drake", name = "Drake")
        viewModel.openArtist(drake, resetStack = true)

        val state = viewModel.uiState.value
        assertEquals(1, state.detailStack.size)
        assertTrue(state.detailStack.first() is ExploreDetail.Artist)
        assertEquals("Drake", (state.detailStack.first() as ExploreDetail.Artist).artistPage.artist.name)
        assertNull(state.selectedAlbum)
        assertEquals(0, state.selectedAlbumTracks.size)

        // Popping the detail returns true and stack is empty
        val popped = viewModel.popDetail()
        assertTrue(popped)
        assertTrue(viewModel.uiState.value.detailStack.isEmpty())
        assertNull(viewModel.uiState.value.selectedArtistPage)
    }

    @Test
    fun testOpenAlbumWithResetStackTrueClearsOldStack() {
        val viewModel = SearchViewModel(fakeRepo)
        viewModel.openArtist(Artist(id = "old", name = "Old"))
        assertEquals(1, viewModel.uiState.value.detailStack.size)

        val album = PlaylistResult(id = "album1", title = "New Album")
        viewModel.openAlbum(album, resetStack = true)

        val state = viewModel.uiState.value
        assertEquals(1, state.detailStack.size)
        assertTrue(state.detailStack.first() is ExploreDetail.Album)
        assertEquals("New Album", (state.detailStack.first() as ExploreDetail.Album).album.title)
        assertNull(state.selectedArtistPage)

        val popped = viewModel.popDetail()
        assertTrue(popped)
        assertTrue(viewModel.uiState.value.detailStack.isEmpty())
    }

    @Test
    fun testMultiLevelNavigationAndBackTracking() {
        val viewModel = SearchViewModel(fakeRepo)

        // User opens Artist from Home (resetStack = true)
        viewModel.openArtist(Artist(id = "artist1", name = "Artist 1"), resetStack = true)
        assertEquals(1, viewModel.uiState.value.detailStack.size)

        // Inside Artist 1, user clicks Album 1 (nested detail: resetStack = false)
        viewModel.openAlbum(PlaylistResult(id = "album1", title = "Album 1"), resetStack = false)
        assertEquals(2, viewModel.uiState.value.detailStack.size)

        // Inside Album 1, user clicks Artist 2 (featured artist: resetStack = false)
        viewModel.openArtist(Artist(id = "artist2", name = "Artist 2"), resetStack = false)
        assertEquals(3, viewModel.uiState.value.detailStack.size)

        // User presses Back: Artist 2 is popped, 2 remain
        viewModel.closeArtist()
        assertEquals(2, viewModel.uiState.value.detailStack.size)
        assertTrue(viewModel.uiState.value.detailStack.last() is ExploreDetail.Album)

        // User presses Back: Album 1 is popped, 1 remains
        viewModel.closeAlbum()
        assertEquals(1, viewModel.uiState.value.detailStack.size)
        assertTrue(viewModel.uiState.value.detailStack.last() is ExploreDetail.Artist)

        // User presses Back: Artist 1 is popped, 0 remain -> signals origin destination restore!
        viewModel.closeArtist()
        assertEquals(0, viewModel.uiState.value.detailStack.size)
        assertTrue(viewModel.uiState.value.detailStack.isEmpty())
    }
}
