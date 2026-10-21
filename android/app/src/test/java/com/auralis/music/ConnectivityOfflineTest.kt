package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.reconnects
import com.auralis.music.data.network.settleOffline
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.HistoryEntry
import com.auralis.music.domain.model.PlayCountEntry
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.viewmodel.forDisplay
import com.auralis.music.ui.viewmodel.HomeViewModel
import com.auralis.music.ui.viewmodel.LibraryUiState
import com.auralis.music.ui.viewmodel.OfflineLibrary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityOfflineTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── settleOffline ──

    @Test
    fun startingOfflineIsReportedAtOnce() = runTest {
        val raw = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { raw.settleOffline(1_500).toList(seen) }
        runCurrent()
        assertEquals(listOf(false), seen)
    }

    @Test
    fun briefDropDuringHandoverIsNotOffline() = runTest {
        val raw = MutableStateFlow(true)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { raw.settleOffline(1_500).toList(seen) }
        runCurrent()
        raw.value = false
        advanceTimeBy(800)
        raw.value = true
        advanceTimeBy(5_000)
        assertEquals(listOf(true), seen)
    }

    @Test
    fun lastingDropBecomesOfflineAndReturnIsImmediate() = runTest {
        val raw = MutableStateFlow(true)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { raw.settleOffline(1_500).toList(seen) }
        runCurrent()
        raw.value = false
        advanceTimeBy(1_400)
        assertEquals(listOf(true), seen)
        advanceTimeBy(200)
        assertEquals(listOf(true, false), seen)
        raw.value = true
        runCurrent()
        assertEquals(listOf(true, false, true), seen)
    }

    // ── reconnects ──

    @Test
    fun reconnectFiresOncePerOfflineToOnline() = runTest {
        val count = flowOf(true, false, true, true, false, false, true).reconnects().toList().size
        assertEquals(2, count)
    }

    @Test
    fun startingOnlineIsNotAReconnect() = runTest {
        assertEquals(0, flowOf(true, true).reconnects().toList().size)
        assertEquals(1, flowOf(false, true).reconnects().toList().size)
    }

    // ── Home ──

    @Test
    fun speedDialAppearsLiveOnlyAfterSixMeasuredListensAndNeverPadsWithArtistPicks() = runTest(testDispatcher) {
        val songs = (1..6).map { Track("real$it", "Distinct song $it", "Artist $it") }
        val repo = mockk<HistoryRepository>()
        coEvery { repo.getHistory() } returns flowOf(songs.map { HistoryEntry(it, 1L) })
        coEvery { repo.getTopPlayedTracks() } returns flowOf(songs.map { PlayCountEntry(it.id, 1, 1L, it) })
        coEvery { repo.getLikedSeeds(any()) } returns emptyList()
        coEvery { repo.getRecentHeavyRotation(any()) } returns emptyList()
        coEvery { repo.getForgottenFavorites(any()) } returns emptyList()
        val times = MutableStateFlow<Map<String, Long>>(emptyMap())
        val provider = com.auralis.music.domain.recommendations.NewUserSeedProvider
        provider.setStarter(listOf("Chosen Artist"), listOf(Track("not-played", "Chosen song", "Chosen Artist")))
        try {
            val vm = HomeViewModel(repo, EmptySearch, mockk(relaxed = true),
                ioDispatcher = testDispatcher, defaultDispatcher = testDispatcher,
                isOnline = MutableStateFlow(false), listeningTimes = times)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.forDisplay().speedDialPages.isEmpty())
            times.value = songs.take(5).associate { it.id to 30_000L }
            advanceUntilIdle()
            assertTrue(vm.uiState.value.forDisplay().speedDialPages.isEmpty())
            times.value = songs.associate { it.id to 30_000L }
            advanceUntilIdle()
            assertEquals(songs.map { it.id }.toSet(), vm.uiState.value.forDisplay().speedDialPages.flatten()
                .mapNotNull { it.track?.id }.toSet())
            times.value = emptyMap() // Clearing listening stats hides the row again.
            advanceUntilIdle()
            assertTrue(vm.uiState.value.forDisplay().speedDialPages.isEmpty())
        } finally { provider.setStarter(emptyList(), emptyList()) }
    }

    @Test
    fun homeIsOfflineAndFetchesNothingThenReloadsWhenNetworkReturns() = runTest(testDispatcher) {
        val online = MutableStateFlow(false)
        val innerTube = mockk<InnerTubeClient>(relaxed = true)
        coEvery { innerTube.getHome(any(), any()) } returns (emptyList<com.auralis.music.domain.model.HomeChip>() to emptyList())
        val vm = HomeViewModel(
            historyRepository = EmptyHistory,
            searchRepository = EmptySearch,
            innerTubeClient = innerTube,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            isOnline = online
        )
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isOffline)
        coVerify(exactly = 0) { innerTube.getHome(any(), any()) }

        online.value = true
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isOffline)
        coVerify(timeout = 5_000) { innerTube.getHome(any(), any()) }
    }

    // ── Library ──

    @Test
    fun offlineLibraryKeepsOnlyDownloads() {
        val a = Track(id = "a", title = "A", album = "Blue")
        val b = Track(id = "b", title = "B", album = "Red")
        val aDownloaded = a.copy(thumbnail = "file:///art/a.jpg")
        val state = LibraryUiState(
            playlists = listOf(
                Playlist(id = "mixed", title = "Mixed", tracks = listOf(a, b)),
                Playlist(id = "streamed", title = "Streamed", tracks = listOf(b))
            ),
            favorites = listOf(a, b),
            downloadedTracks = listOf(aDownloaded),
            savedArtists = listOf(SavedArtist(id = "UC1", name = "Someone")),
            savedAlbums = listOf(SavedAlbum(id = "1", title = "Blue"), SavedAlbum(id = "2", title = "Red")),
            top50Tracks = listOf(b),
            cachedTracks = listOf(b)
        )

        val offline = OfflineLibrary.filter(state)

        assertEquals(listOf("mixed"), offline.playlists.map { it.id })
        assertEquals(listOf(aDownloaded), offline.playlists.single().tracks)
        assertEquals(listOf(aDownloaded), offline.favorites)
        assertEquals(listOf("Blue"), offline.savedAlbums.map { it.title })
        assertTrue(offline.savedArtists.isEmpty())
        assertTrue(offline.top50Tracks.isEmpty())
        assertTrue(offline.cachedTracks.isEmpty())
        assertEquals(listOf(aDownloaded), offline.downloadedTracks)
    }

    private object EmptyHistory : HistoryRepository {
        override fun getHistory() = flowOf(emptyList<HistoryEntry>())
        override suspend fun addToHistory(track: Track) {}
        override suspend fun removeFromHistory(trackId: String) {}
        override suspend fun clearHistory() {}
        override fun getTopPlayedTracks() = flowOf(emptyList<PlayCountEntry>())
        override suspend fun recordPlay(track: Track) {}
        override suspend fun getPlayCounts() = emptyList<PlayCountEntry>()
        override suspend fun getForgottenFavorites(cutoffTimestamp: Long) = emptyList<Track>()
        override suspend fun getRecentHeavyRotation(fromTimestamp: Long) = emptyList<Track>()
        override suspend fun getLikedSeeds(limit: Int) = emptyList<Track>()
    }

    private object EmptySearch : SearchRepository {
        override suspend fun search(query: String) = SearchResults()
        override suspend fun searchSongs(query: String) = emptyList<Track>()
        override suspend fun searchAlbums(query: String) = emptyList<PlaylistResult>()
        override suspend fun searchArtists(query: String) = emptyList<Artist>()
        override suspend fun searchPlaylists(query: String) = emptyList<PlaylistResult>()
        override suspend fun getSuggestions(query: String) = emptyList<String>()
        override suspend fun getArtistPage(artist: Artist) = null
        override suspend fun getAlbumTracks(album: PlaylistResult) = emptyList<Track>()
        override fun getRecentSearchQueries() = flowOf(emptyList<String>())
        override suspend fun recordSearchQuery(query: String) {}
        override suspend fun removeSearchQuery(query: String) {}
        override suspend fun clearSearchHistory() {}
    }
}
