package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.repository.SearchHistoryStore
import com.auralis.music.data.repository.SearchRepositoryImpl
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.viewmodel.SearchViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchReliabilityTest {
    private fun viewModelRepository(): SearchRepository {
        val repository = mockk<SearchRepository>(relaxed = true)
        coEvery { repository.getRecentSearchQueries() } returns emptyFlow()
        coEvery { repository.search(any(), any()) } coAnswers {
            repository.search(firstArg<String>()).also(secondArg<(SearchResults) -> Unit>())
        }
        return repository
    }
    private val song = Track(id = "misery", title = "Misery", artist = "Maroon 5",
        album = "Hands All Over", albumId = "MPREalbum", views = "100M plays")

    @Test
    fun searchWaitsForCompleteCardsWithoutTheAddedTwelveSecondDeadline() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = viewModelRepository()
            val artist = Artist("UCmaroon", "Maroon 5")
            val album = com.auralis.music.domain.model.PlaylistResult("MPREalbum", "Hands All Over", author = artist.name)
            coEvery { repository.search("misery") } coAnswers {
                delay(13_000)
                SearchResults(songs = listOf(song), primaryArtist = artist, primaryAlbum = album)
            }
            val vm = SearchViewModel(repository)
            vm.performSearch("misery")
            runCurrent()
            assertTrue(vm.uiState.value.isSearching)
            assertTrue(vm.uiState.value.searchResults.isEmpty())
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isSearching)
            assertEquals(listOf(song), vm.uiState.value.searchResults.songs)
            assertEquals(artist, vm.uiState.value.searchResults.primaryArtist)
            assertEquals(album, vm.uiState.value.searchResults.primaryAlbum)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun artistLookupIsNotDiscardedByTheAddedShortDeadline() = runBlocking {
        val client = mockk<InnerTubeClient>()
        val suggestions = mockk<SearchSuggestionsClient>()
        val artist = Artist("UCmaroon", "Maroon 5", thumbnail = "https://example.com/artist.jpg")
        coEvery { suggestions.getSuggestions(any()) } returns emptyList()
        coEvery { client.search(any(), any()) } returns SearchResults()
        coEvery { client.search("misery", InnerTubeClient.FILTER_SONGS) } returns SearchResults(songs = listOf(song))
        coEvery { client.search("misery", InnerTubeClient.FILTER_ARTISTS) } coAnswers {
            delay(1_700)
            SearchResults(artists = listOf(artist))
        }
        val repository = SearchRepositoryImpl(client, suggestions, mockk<SearchHistoryStore>())
        val result = repository.search("misery")
        assertEquals(artist, result.primaryArtist)
        assertEquals("Hands All Over", result.primaryAlbum?.title)
        assertTrue(result.rankedMatches.isEmpty())
    }

    @Test
    fun failedArtistRequestDoesNotPresentSearchSongsAsTheArtistPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = viewModelRepository()
            coEvery { repository.search("misery") } returns SearchResults(songs = listOf(song))
            coEvery { repository.getArtistPage(any()) } throws IOException("offline")
            val vm = SearchViewModel(repository)
            vm.performSearch("misery")
            advanceUntilIdle()
            vm.openArtist(Artist("yt:Maroon 5", "Maroon 5"))
            assertTrue(vm.uiState.value.selectedArtistPage!!.topSongs.isEmpty())
            assertTrue(vm.uiState.value.isLoadingArtist)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.selectedArtistPage!!.topSongs.isEmpty())
            assertFalse(vm.uiState.value.isLoadingArtist)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun preloadedArtistOpensWithFullSongsAndAlbumsImmediately() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = viewModelRepository()
            val artist = Artist("UCmaroon", "Maroon 5")
            val page = com.auralis.music.domain.model.ArtistPage(artist = artist,
                topSongs = listOf(song, song.copy(id = "second", title = "Animals")),
                albums = listOf(com.auralis.music.domain.model.PlaylistResult("MPREalbum", "Hands All Over")))
            coEvery { repository.search("misery") } returns SearchResults(songs = listOf(song), primaryArtist = artist)
            coEvery { repository.getArtistPage(any()) } returns page
            val vm = SearchViewModel(repository)
            vm.performSearch("misery")
            advanceUntilIdle()
            vm.openArtist(Artist("", "Maroon 5"))
            assertEquals(page, vm.uiState.value.selectedArtistPage)
            assertFalse(vm.uiState.value.isLoadingArtist)
            advanceUntilIdle()
            coVerify(exactly = 1) { repository.getArtistPage(any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun openingWhilePrefetchRunsSharesRequestAndShowsNoSingleSongPreview() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = viewModelRepository()
            val artist = Artist("UCmaroon", "Maroon 5")
            val page = com.auralis.music.domain.model.ArtistPage(artist = artist,
                topSongs = listOf(song, song.copy(id = "second", title = "Animals")))
            coEvery { repository.search("misery") } returns SearchResults(songs = listOf(song), primaryArtist = artist)
            coEvery { repository.getArtistPage(any()) } coAnswers { delay(2_000); page }
            val vm = SearchViewModel(repository)
            vm.performSearch("misery")
            runCurrent()
            vm.openArtist(artist)
            assertTrue(vm.uiState.value.selectedArtistPage!!.topSongs.isEmpty())
            assertTrue(vm.uiState.value.isLoadingArtist)
            advanceUntilIdle()
            assertEquals(page, vm.uiState.value.selectedArtistPage)
            assertFalse(vm.uiState.value.isLoadingArtist)
            coVerify(exactly = 1) { repository.getArtistPage(any()) }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun failedEmptyArtistPageCanBeRetried() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = viewModelRepository()
            val artist = Artist("UCmaroon", "Maroon 5")
            coEvery { repository.getArtistPage(any()) } returns null
            val vm = SearchViewModel(repository)
            vm.openArtist(artist)
            advanceUntilIdle()
            assertTrue((vm.uiState.value.detailStack.last() as com.auralis.music.ui.viewmodel.ExploreDetail.Artist).loadFailed)
            coEvery { repository.getArtistPage(any()) } returns com.auralis.music.domain.model.ArtistPage(
                artist = artist, topSongs = listOf(song))
            vm.refresh()
            assertEquals(listOf(song), vm.uiState.value.selectedArtistPage?.topSongs)
            assertFalse((vm.uiState.value.detailStack.last() as com.auralis.music.ui.viewmodel.ExploreDetail.Artist).loadFailed)
        } finally { Dispatchers.resetMain() }
    }
}
