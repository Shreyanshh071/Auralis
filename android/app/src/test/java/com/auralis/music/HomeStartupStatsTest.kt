package com.auralis.music

import androidx.lifecycle.ViewModelStore
import com.auralis.music.data.local.dao.SpeedDialListen
import com.auralis.music.data.local.mapper.toEntity
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.viewmodel.HomeViewModel
import com.auralis.music.ui.viewmodel.forDisplay
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeStartupStatsTest {
    @Test fun firstPresentationWaitsForLocalEligibilityAndAllowsAnEmptyNewLibrary() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val history = mockk<HistoryRepository>(relaxed = true)
            every { history.getHistory() } returns flow { awaitCancellation() }
            every { history.getTopPlayedTracks() } returns flow { awaitCancellation() }
            val listens = kotlinx.coroutines.flow.MutableSharedFlow<List<SpeedDialListen>>(replay = 1)
            val home = HomeViewModel(history, mockk<SearchRepository>(relaxed = true),
                ioDispatcher = dispatcher, defaultDispatcher = dispatcher,
                isOnline = MutableStateFlow(false), speedDialListens = listens)
            store.put("home", home)
            runCurrent()
            assertFalse(home.initialContentReady.value)
            listens.emit(emptyList())
            runCurrent()
            assertTrue(home.initialContentReady.value)
            assertTrue(home.uiState.value.forDisplay().speedDialPages.isEmpty())
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun overlappingHomeReloadsShareOneJob() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val history = mockk<HistoryRepository>(relaxed = true)
            every { history.getHistory() } returns flow { awaitCancellation() }
            every { history.getTopPlayedTracks() } returns flow { awaitCancellation() }
            val home = HomeViewModel(history, mockk<SearchRepository>(relaxed = true),
                ioDispatcher = dispatcher, defaultDispatcher = dispatcher,
                isOnline = MutableStateFlow(false))
            store.put("home", home)
            runCurrent()
            val first = home.loadHomeData()
            assertSame(first, home.loadHomeData())
            assertTrue(first.isActive)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun measuredSpeedDialCanAppearBeforeTheHistoryQueriesFinish() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val history = mockk<HistoryRepository>(relaxed = true)
            every { history.getHistory() } returns flow { awaitCancellation() }
            every { history.getTopPlayedTracks() } returns flow { awaitCancellation() }
            val measured = (1..6).map { index ->
                SpeedDialListen(Track("song$index", "Song $index", "Artist $index").toEntity(),
                    totalMs = 45_000L, lastPlayed = index.toLong(), listenCount = 1)
            }
            val home = HomeViewModel(history, mockk<SearchRepository>(relaxed = true),
                ioDispatcher = dispatcher, defaultDispatcher = dispatcher,
                isOnline = MutableStateFlow(false), speedDialListens = flowOf(measured))
            store.put("home", home)
            runCurrent()
            assertTrue(home.uiState.value.speedDialReady)
            assertTrue(home.initialContentReady.value)
            assertEquals((1..6).map { "song$it" }.toSet(),
                home.uiState.value.forDisplay().speedDialPages.flatten().mapNotNull { it.track?.id }.toSet())
            val restored = home.uiState.value
            repeat(2) { home.applyOnboardingPicks() }
            assertEquals("Replayed onboarding must not erase the restored dial", restored, home.uiState.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
