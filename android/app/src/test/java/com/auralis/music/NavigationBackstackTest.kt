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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationBackstackTest {

    private val testDispatcher = StandardTestDispatcher()

    private val fakeRepository = object : SearchRepository {
        override suspend fun search(query: String): SearchResults = SearchResults()
        override suspend fun searchSongs(query: String): List<Track> = emptyList()
        override suspend fun searchAlbums(query: String): List<PlaylistResult> = emptyList()
        override suspend fun searchArtists(query: String): List<Artist> = emptyList()
        override suspend fun searchPlaylists(query: String): List<PlaylistResult> = emptyList()
        override suspend fun getSuggestions(query: String): List<String> = emptyList()
        override fun getRecentSearchQueries(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun recordSearchQuery(query: String) {}
        override suspend fun removeSearchQuery(query: String) {}
        override suspend fun clearSearchHistory() {}
        override suspend fun getArtistPage(artist: Artist): ArtistPage? = ArtistPage(artist = artist)
        override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = emptyList()
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
    fun testMultiLevelDetailBackstackNavigation() = runTest(testDispatcher) {
        val viewModel = SearchViewModel(fakeRepository)

        // 1. Initial state: stack is empty
        assertTrue(viewModel.uiState.value.detailStack.isEmpty())

        // 2. Open OK Computer Album
        val okComputer = PlaylistResult(id = "alb_1", title = "OK Computer", author = "Radiohead")
        viewModel.openAlbum(okComputer)
        assertEquals(1, viewModel.uiState.value.detailStack.size)
        assertTrue(viewModel.uiState.value.detailStack.last() is ExploreDetail.Album)
        assertEquals("OK Computer", (viewModel.uiState.value.detailStack.last() as ExploreDetail.Album).album.title)

        // 3. Open Radiohead Artist Profile from Album
        val radiohead = Artist(id = "art_1", name = "Radiohead")
        viewModel.openArtist(radiohead)
        assertEquals(2, viewModel.uiState.value.detailStack.size)
        assertTrue(viewModel.uiState.value.detailStack.last() is ExploreDetail.Artist)
        assertEquals("Radiohead", (viewModel.uiState.value.detailStack.last() as ExploreDetail.Artist).artistPage.artist.name)

        // 4. Open Sonic Youth Profile from "Fans might also like"
        val sonicYouth = Artist(id = "art_2", name = "Sonic Youth")
        viewModel.openArtist(sonicYouth)
        assertEquals(3, viewModel.uiState.value.detailStack.size)
        assertTrue(viewModel.uiState.value.detailStack.last() is ExploreDetail.Artist)
        assertEquals("Sonic Youth", (viewModel.uiState.value.detailStack.last() as ExploreDetail.Artist).artistPage.artist.name)

        // 5. Back press #1 from Sonic Youth -> Returns to Radiohead
        val popped1 = viewModel.popDetail()
        assertTrue(popped1)
        assertEquals(2, viewModel.uiState.value.detailStack.size)
        assertTrue(viewModel.uiState.value.detailStack.last() is ExploreDetail.Artist)
        assertEquals("Radiohead", (viewModel.uiState.value.detailStack.last() as ExploreDetail.Artist).artistPage.artist.name)

        // 6. Back press #2 from Radiohead -> Returns to OK Computer Album
        val popped2 = viewModel.popDetail()
        assertTrue(popped2)
        assertEquals(1, viewModel.uiState.value.detailStack.size)
        assertTrue(viewModel.uiState.value.detailStack.last() is ExploreDetail.Album)
        assertEquals("OK Computer", (viewModel.uiState.value.detailStack.last() as ExploreDetail.Album).album.title)

        // 7. Back press #3 from OK Computer -> Returns to Search Root
        val popped3 = viewModel.popDetail()
        assertTrue(popped3)
        assertTrue(viewModel.uiState.value.detailStack.isEmpty())
    }
}
