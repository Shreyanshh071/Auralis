package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.domain.repository.LyricsRepository
import com.auralis.music.domain.repository.SettingsRepository
import com.auralis.music.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoPlayInfiniteRadioTest {

    private fun sampleTrack(id: String, title: String) = Track(
        id = id,
        title = title,
        artist = "Artist $id",
        duration = 180,
        thumbnail = "https://thumb/$id.jpg"
    )

    private class MockInnerTubeClient(
        var radioTracksToReturn: List<Track> = emptyList()
    ) : InnerTubeClient() {
        override suspend fun getRadioTracks(
            videoId: String,
            artist: String?,
            title: String?
        ): List<Track> {
            return radioTracksToReturn
        }
    }

    private class MockLibraryRepo : LibraryRepository {
        override fun getFavoriteTracks(): Flow<List<Track>> = flowOf(emptyList())
        override fun isFavorite(trackId: String): Flow<Boolean> = flowOf(false)
        override suspend fun toggleFavorite(track: Track) {}
        override suspend fun setFavorite(track: Track, isFavorite: Boolean) {}
        override fun getPlaylists(): Flow<List<Playlist>> = flowOf(emptyList())
        override fun getPlaylist(playlistId: String): Flow<Playlist?> = flowOf(null)
        override suspend fun createPlaylist(title: String, description: String?, coverUrl: String?): Playlist = Playlist(id = "1", title = title, coverUrl = coverUrl)
        override suspend fun updatePlaylist(playlistId: String, title: String, description: String?, coverUrl: String?) {}
        override suspend fun addTrackToPlaylist(playlistId: String, track: Track) {}
        override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {}
        override suspend fun deletePlaylist(playlistId: String) {}
        override suspend fun reorderPlaylist(playlistId: String, tracks: List<Track>) {}
        override suspend fun replacePlaylistTracks(playlistId: String, tracks: List<Track>) {}
        override fun getSavedArtists(): Flow<List<SavedArtist>> = flowOf(emptyList())
        override fun isArtistSaved(artistId: String): Flow<Boolean> = flowOf(false)
        override suspend fun saveArtist(artist: SavedArtist) {}
        override suspend fun removeArtist(artistId: String) {}
        override fun getSavedAlbums(): Flow<List<SavedAlbum>> = flowOf(emptyList())
        override fun isAlbumSaved(albumId: String): Flow<Boolean> = flowOf(false)
        override suspend fun saveAlbum(album: SavedAlbum) {}
        override suspend fun removeAlbum(albumId: String) {}
    }

    private class MockHistoryRepo : HistoryRepository {
        val historyList = mutableListOf<HistoryEntry>()
        override fun getHistory(): Flow<List<HistoryEntry>> = flowOf(historyList)
        override suspend fun addToHistory(track: Track) {
            historyList.add(HistoryEntry(track))
        }
        override suspend fun removeFromHistory(trackId: String) {}
        override suspend fun clearHistory() { historyList.clear() }
        override fun getTopPlayedTracks(): Flow<List<PlayCountEntry>> = flowOf(emptyList())
        override suspend fun recordPlay(track: Track) {}
        override suspend fun getPlayCounts(): List<PlayCountEntry> = emptyList()
        override suspend fun getForgottenFavorites(cutoffTimestamp: Long): List<Track> = emptyList()
        override suspend fun getRecentHeavyRotation(fromTimestamp: Long): List<Track> = listOf(
            Track(id = "heavy1", title = "Heavy Track 1", artist = "Artist H1", duration = 180, thumbnail = ""),
            Track(id = "heavy2", title = "Heavy Track 2", artist = "Artist H2", duration = 200, thumbnail = "")
        )
        override suspend fun getLikedSeeds(limit: Int): List<Track> = emptyList()
    }

    private class MockLyricsRepo : LyricsRepository {
        override suspend fun getCachedLyrics(
            title: String,
            artist: String,
            durationSec: Long?,
            videoId: String?
        ): LyricsData? = null

        override suspend fun getLyrics(
            title: String,
            artist: String,
            durationSec: Long?,
            videoId: String?,
            forceRefresh: Boolean
        ): LyricsData? = null
    }

    private class MockSettingsRepo : SettingsRepository {
        override val settingsFlow: Flow<PlayerSettings> = flowOf(PlayerSettings())
        override suspend fun updateSettings(settings: PlayerSettings) {}
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun setAudioQuality(quality: AudioQuality) {}
        override suspend fun setGaplessPlayback(enabled: Boolean) {}
        override suspend fun setSkipSilence(enabled: Boolean) {}
        override suspend fun setSpatialAudio(enabled: Boolean) {}
        override suspend fun setVolume(volume: Float) {}
        override suspend fun setPlaybackRate(rate: Float) {}
    }

    private lateinit var testDispatcher: kotlinx.coroutines.test.TestDispatcher

    @Before
    fun setUp() {
        testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `playing single song initializes auto-radio queue, fetches radio in background and appends to queue`() = runTest(testDispatcher) {
        val mockInnerTube = MockInnerTubeClient(
            radioTracksToReturn = listOf(sampleTrack("radio1", "Radio Song 1"), sampleTrack("radio2", "Radio Song 2"))
        )
        val viewModel = PlayerViewModel(
            libraryRepository = MockLibraryRepo(),
            historyRepository = MockHistoryRepo(),
            lyricsRepository = MockLyricsRepo(),
            settingsRepository = MockSettingsRepo(),
            audioPlayer = null,
            innerTubeClient = mockInnerTube
        )

        val seedTrack = sampleTrack("seed1", "Seed Song")
        viewModel.playTrack(seedTrack)

        // Advance coroutines for background radio fetch
        for (i in 0 until 40) {
            testScheduler.advanceTimeBy(300)
            advanceUntilIdle()
            if (viewModel.uiState.value.queue.size >= 3) break
            Thread.sleep(50)
        }

        val stateAfterRadio = viewModel.uiState.value
        assertEquals(3, stateAfterRadio.queue.size)
        assertEquals("Seed Song", stateAfterRadio.queue[0].title)
        assertEquals("Radio Song 1", stateAfterRadio.queue[1].title)
        assertEquals("Radio Song 2", stateAfterRadio.queue[2].title)
        viewModel.closePlayer()
    }

    @Test
    fun `skipping next on auto-radio queue plays next track without pausing`() = runTest(testDispatcher) {
        val mockInnerTube = MockInnerTubeClient(
            radioTracksToReturn = listOf(sampleTrack("radio1", "Radio Song 1"), sampleTrack("radio2", "Radio Song 2"))
        )
        val viewModel = PlayerViewModel(
            libraryRepository = MockLibraryRepo(),
            historyRepository = MockHistoryRepo(),
            lyricsRepository = MockLyricsRepo(),
            settingsRepository = MockSettingsRepo(),
            audioPlayer = null,
            innerTubeClient = mockInnerTube
        )

        val seedTrack = sampleTrack("seed1", "Seed Song")
        viewModel.playTrack(seedTrack)
        for (i in 0 until 40) {
            testScheduler.advanceTimeBy(300)
            advanceUntilIdle()
            if (viewModel.uiState.value.queue.size >= 3) break
            Thread.sleep(50)
        }

        assertEquals(3, viewModel.uiState.value.queue.size)

        // Skip to next track
        viewModel.next()
        for (i in 0 until 40) {
            testScheduler.advanceTimeBy(300)
            advanceUntilIdle()
            if (viewModel.uiState.value.currentIndex == 1) break
            Thread.sleep(50)
        }

        val nextState = viewModel.uiState.value
        assertEquals("Radio Song 1", nextState.currentTrack?.title)
        assertTrue(nextState.isPlaying)
        assertEquals(1, nextState.currentIndex)
        viewModel.closePlayer()
    }

    @Test
    fun `skipping next when queue is empty in auto-radio mode fetches fallback track and keeps playing infinitely`() = runTest(testDispatcher) {
        val historyRepo = MockHistoryRepo()
        val mockInnerTube = MockInnerTubeClient(radioTracksToReturn = emptyList())
        val viewModel = PlayerViewModel(
            libraryRepository = MockLibraryRepo(),
            historyRepository = historyRepo,
            lyricsRepository = MockLyricsRepo(),
            settingsRepository = MockSettingsRepo(),
            audioPlayer = null,
            innerTubeClient = mockInnerTube
        )

        val seedTrack = sampleTrack("seed1", "Seed Song")
        viewModel.playTrack(seedTrack) // Queue size is 1, currentIndex is 0
        for (i in 0 until 40) {
            testScheduler.advanceTimeBy(300)
            advanceUntilIdle()
        }

        // User hits Next when there's no next track in queue
        viewModel.next()
        for (i in 0 until 40) {
            testScheduler.advanceTimeBy(300)
            advanceUntilIdle()
            if (viewModel.uiState.value.currentTrack?.id?.startsWith("heavy") == true) break
        }

        val stateAfterNext = viewModel.uiState.value
        assertNotNull(stateAfterNext.currentTrack)
        assertTrue(stateAfterNext.isPlaying)
        assertNotEquals("seed1", stateAfterNext.currentTrack?.id)
        assertTrue(stateAfterNext.currentTrack?.id?.startsWith("heavy") == true)
        viewModel.closePlayer()
    }

    @Test
    fun `user playlist queue with isUserQueue true respects finite queue boundary`() = runTest(testDispatcher) {
        val viewModel = PlayerViewModel(
            libraryRepository = MockLibraryRepo(),
            historyRepository = MockHistoryRepo(),
            lyricsRepository = MockLyricsRepo(),
            settingsRepository = MockSettingsRepo(),
            audioPlayer = null,
            innerTubeClient = MockInnerTubeClient()
        )

        val playlistTracks = listOf(sampleTrack("pl1", "Playlist Song 1"), sampleTrack("pl2", "Playlist Song 2"))
        viewModel.playTrack(playlistTracks[0], playlistTracks, startIndex = 0, isUserQueue = true)

        // Advance to 2nd song
        viewModel.next()
        assertEquals("Playlist Song 2", viewModel.uiState.value.currentTrack?.title)
        assertTrue(viewModel.uiState.value.isPlaying)

        // Advance past end of finite user queue
        viewModel.next()
        assertFalse(viewModel.uiState.value.isPlaying)
        viewModel.closePlayer()
    }
}
