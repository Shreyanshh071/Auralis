package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.repository.SearchHistoryStore
import com.auralis.music.data.repository.SearchRepositoryImpl
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.viewmodel.SearchViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SearchProgressTest {
    private val song = Track("song", "Any Song", "Any Artist", album = "Studio Album", albumId = "MPREstudio", views = "100M plays")

    @Test fun songsAndTheirCardsAppearWhileOtherSearchesAreStillPending() = runBlocking {
        val client = mockk<InnerTubeClient>()
        val release = CompletableDeferred<Unit>()
        coEvery { client.search(any(), any()) } coAnswers { release.await(); SearchResults() }
        coEvery { client.search("any song", InnerTubeClient.FILTER_SONGS) } returns SearchResults(songs = listOf(song))
        val suggestions = mockk<SearchSuggestionsClient>()
        coEvery { suggestions.getSuggestions(any()) } returns emptyList()
        val repo = SearchRepositoryImpl(client, suggestions, mockk<SearchHistoryStore>())
        val first = CompletableDeferred<SearchResults>()
        val job = launch { repo.search("any song") { first.complete(it) } }
        try {
            val visible = withTimeout(5_000) { first.await() }
            assertEquals(song, visible.songs.first())
            assertEquals("Any Artist", visible.primaryArtist?.name)
            assertEquals("Studio Album", visible.primaryAlbum?.title)
            assertTrue("Optional responses should still be pending", job.isActive)
            release.complete(Unit)
            job.join()
        } finally { job.cancelAndJoin() }
    }

    @Test fun stalledSuggestionsCannotHoldUpSearchCompletion() = runBlocking {
        val client = mockk<InnerTubeClient>()
        coEvery { client.search(any(), any()) } returns SearchResults()
        coEvery { client.search("any song", InnerTubeClient.FILTER_SONGS) } returns SearchResults(songs = listOf(song))
        val suggestions = mockk<SearchSuggestionsClient>()
        var cancelled = false
        coEvery { suggestions.getSuggestions(any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val repo = SearchRepositoryImpl(client, suggestions, mockk<SearchHistoryStore>())
        val result = withTimeout(5_000) { repo.search("any song") }
        assertEquals(song, result.songs.first())
        assertTrue(cancelled)
    }

    @Test fun failedRequestsAreReportedAsFailureRatherThanNoMatchingSongs() = runBlocking {
        val client = mockk<InnerTubeClient>()
        coEvery { client.search(any(), any()) } returns SearchResults(requestFailed = true)
        val suggestions = mockk<SearchSuggestionsClient>()
        coEvery { suggestions.getSuggestions(any()) } returns emptyList()
        val repo = SearchRepositoryImpl(client, suggestions, mockk<SearchHistoryStore>())
        try { repo.search("anything"); fail("Expected a network failure") }
        catch (_: IOException) { }
    }

    @Test fun firstResponseStopsSpinnerAndLaterFailureKeepsReceivedSongs() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mockk<SearchRepository>(relaxed = true)
            coEvery { repo.getRecentSearchQueries() } returns emptyFlow()
            coEvery { repo.search("any song", any()) } coAnswers {
                delay(200)
                secondArg<(SearchResults) -> Unit>()(SearchResults(songs = listOf(song)))
                delay(20_000)
                throw IOException("Optional enrichment failed")
            }
            val vm = SearchViewModel(repo)
            vm.performSearch("any song")
            assertTrue(vm.uiState.value.isSearching)
            advanceTimeBy(201)
            runCurrent()
            assertFalse(vm.uiState.value.isSearching)
            assertEquals(listOf(song), vm.uiState.value.searchResults.songs)
            advanceUntilIdle()
            assertEquals(listOf(song), vm.uiState.value.searchResults.songs)
            assertTrue("Keep received songs but offer retry for incomplete results", vm.uiState.value.searchFailed)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun submissionKeepsSongsAlreadyShownDuringTyping() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mockk<SearchRepository>(relaxed = true)
            coEvery { repo.getRecentSearchQueries() } returns emptyFlow()
            coEvery { repo.searchLiveSongs(any(), any()) } coAnswers { secondArg<(List<Track>) -> Unit>()(listOf(song)) }
            coEvery { repo.search("any song", any()) } coAnswers { delay(20_000); SearchResults(songs = listOf(song)) }
            val vm = SearchViewModel(repo)
            vm.onQueryChange("any song")
            advanceUntilIdle()
            vm.performSearch("any song")
            assertFalse(vm.uiState.value.isSearching)
            assertEquals(listOf(song), vm.uiState.value.searchResults.songs)
            advanceUntilIdle()
        } finally { Dispatchers.resetMain() }
    }
}
