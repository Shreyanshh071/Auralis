package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.repository.SearchHistoryStore
import com.auralis.music.data.repository.SearchRepositoryImpl
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.viewmodel.SearchViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchLiveSongsTest {
    private val song = Track("night", "Middle of the Night", "Elley Duhe", views = "1B plays")

    private fun firstResponseDoesNotWaitForOtherSearch(fastFilter: String?) = runBlocking {
        val client = mockk<InnerTubeClient>()
        val slowStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        coEvery { client.searchLive(any(), any()) } coAnswers {
            if (secondArg<String?>() == fastFilter) {
                slowStarted.await()
                SearchResults(songs = listOf(song))
            } else {
                slowStarted.complete(Unit)
                releaseSlow.await()
                SearchResults(songs = listOf(song.copy(id = "second", artist = "Cover Artist", views = "2M plays")))
            }
        }
        val repo = SearchRepositoryImpl(client, mockk<SearchSuggestionsClient>(), mockk<SearchHistoryStore>())
        val first = CompletableDeferred<List<Track>>()
        val updates = mutableListOf<List<Track>>()
        val job = launch {
            repo.searchLiveSongs("middle of the night") { updates += it; first.complete(it) }
        }
        try {
            assertEquals(listOf(song), withTimeout(5_000) { first.await() })
            assertTrue(job.isActive)
            releaseSlow.complete(Unit)
            job.join()
            assertEquals(listOf("night", "second"), updates.last().map { it.id })
        } finally { job.cancelAndJoin() }
    }

    @Test fun filteredSongsAppearBeforeSlowGeneralSearch() = firstResponseDoesNotWaitForOtherSearch(InnerTubeClient.FILTER_SONGS)
    @Test fun generalSongsAppearBeforeSlowFilteredSearch() = firstResponseDoesNotWaitForOtherSearch(null)

    @Test fun typingKeepsMatchingSongsAndCachedQueryAppearsImmediately() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mockk<SearchRepository>(relaxed = true)
            coEvery { repo.getRecentSearchQueries() } returns emptyFlow()
            coEvery { repo.searchLiveSongs(any(), any()) } coAnswers {
                delay(500)
                secondArg<(List<Track>) -> Unit>()(listOf(song))
            }
            val vm = SearchViewModel(repo)
            vm.onQueryChange("middle of the")
            advanceUntilIdle()
            assertEquals(listOf(song), vm.uiState.value.liveSongRecommendations)
            vm.onQueryChange("middle of the night")
            assertEquals(listOf(song), vm.uiState.value.liveSongRecommendations)
            vm.onQueryChange("misery")
            assertTrue(vm.uiState.value.liveSongRecommendations.isEmpty())
            vm.onQueryChange("middle of the")
            assertEquals(listOf(song), vm.uiState.value.liveSongRecommendations)
            vm.onQueryChange("")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.liveSongRecommendations.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun changingQueryCancelsRequestAndDoesNotShowStaleSongs() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = mockk<SearchRepository>(relaxed = true)
            coEvery { repo.getRecentSearchQueries() } returns emptyFlow()
            var cancelled = false
            coEvery { repo.searchLiveSongs("middle", any()) } coAnswers {
                try { awaitCancellation() } finally { cancelled = true }
            }
            coEvery { repo.searchLiveSongs("misery", any()) } coAnswers {
                secondArg<(List<Track>) -> Unit>()(listOf(song.copy(id = "misery", title = "Misery")))
            }
            val vm = SearchViewModel(repo)
            vm.onQueryChange("middle")
            advanceTimeBy(121)
            runCurrent()
            vm.onQueryChange("misery")
            advanceUntilIdle()
            assertTrue(cancelled)
            assertEquals("misery", vm.uiState.value.liveSongRecommendations.single().id)
        } finally { Dispatchers.resetMain() }
    }
}
