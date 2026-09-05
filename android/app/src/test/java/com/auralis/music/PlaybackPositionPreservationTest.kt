package com.auralis.music

import androidx.lifecycle.ViewModel
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPositionPreservationTest {

    private val testDispatcher = StandardTestDispatcher()

    private fun sampleTrack(id: String, title: String) = Track(
        id = id,
        title = title,
        artist = "Artist $id",
        duration = 200,
        thumbnail = "https://thumb/$id.jpg"
    )

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
        override fun getHistory(): Flow<List<HistoryEntry>> = flowOf(emptyList())
        override suspend fun addToHistory(track: Track) {}
        override suspend fun removeFromHistory(trackId: String) {}
        override suspend fun clearHistory() {}
        override fun getTopPlayedTracks(): Flow<List<PlayCountEntry>> = flowOf(emptyList())
        override suspend fun recordPlay(track: Track) {}
        override suspend fun getPlayCounts(): List<PlayCountEntry> = emptyList()
        override suspend fun getForgottenFavorites(cutoffTimestamp: Long): List<Track> = emptyList()
        override suspend fun getRecentHeavyRotation(fromTimestamp: Long): List<Track> = emptyList()
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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onCleared does not stop playback or clear queue`() = runTest(testDispatcher) {
        val viewModel = PlayerViewModel(
            libraryRepository = MockLibraryRepo(),
            historyRepository = MockHistoryRepo(),
            lyricsRepository = MockLyricsRepo(),
            settingsRepository = MockSettingsRepo(),
            audioPlayer = null
        )

        val track = sampleTrack("t1", "Track One")
        viewModel.playTrack(track)

        val stateBefore = viewModel.uiState.value
        assertEquals("t1", stateBefore.currentTrack?.id)
        assertEquals(1, stateBefore.queue.size)
        assertTrue(stateBefore.isPlaying)

        // Simulate Activity / UI destruction triggering ViewModel.onCleared
        val onClearedMethod = ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(viewModel)
        testScheduler.advanceUntilIdle()

        // Crucial check: UI lifecycle destruction must NOT wipe playback state or queue
        val stateAfter = viewModel.uiState.value
        assertEquals("Track must remain active after UI onCleared", "t1", stateAfter.currentTrack?.id)
        assertEquals("Queue must remain intact after UI onCleared", 1, stateAfter.queue.size)
        assertTrue("Playback must remain playing after UI onCleared", stateAfter.isPlaying)
    }

    @Test
    fun `explicit closePlayer clears playback and queue`() = runTest(testDispatcher) {
        val viewModel = PlayerViewModel(
            libraryRepository = MockLibraryRepo(),
            historyRepository = MockHistoryRepo(),
            lyricsRepository = MockLyricsRepo(),
            settingsRepository = MockSettingsRepo(),
            audioPlayer = null
        )

        val track = sampleTrack("t1", "Track One")
        viewModel.playTrack(track)
        assertEquals("t1", viewModel.uiState.value.currentTrack?.id)

        // Explicit user action to dismiss player
        viewModel.closePlayer()

        val state = viewModel.uiState.value
        assertNull(state.currentTrack)
        assertTrue(state.queue.isEmpty())
        assertFalse(state.isPlaying)
        assertEquals(0L, state.playbackPositionMs)
    }

    @Test
    fun `seekTo preserves position across pause and resume`() = runTest(testDispatcher) {
        val viewModel = PlayerViewModel(
            libraryRepository = MockLibraryRepo(),
            historyRepository = MockHistoryRepo(),
            lyricsRepository = MockLyricsRepo(),
            settingsRepository = MockSettingsRepo(),
            audioPlayer = null
        )

        val track = sampleTrack("t1", "Track One")
        viewModel.playTrack(track)

        // Seek to 157,000 ms (2:37)
        viewModel.seekTo(157000L)
        assertEquals(157000L, viewModel.playbackPositionMs.value)
        assertEquals(157000L, viewModel.getPlaybackPosition())
        assertEquals(157000L, viewModel.uiState.value.playbackPositionMs)

        // Pause
        viewModel.pause()
        assertFalse(viewModel.uiState.value.isPlaying)
        assertEquals(157000L, viewModel.playbackPositionMs.value)
        assertEquals(157000L, viewModel.uiState.value.playbackPositionMs)

        // Resume
        viewModel.resume()
        assertTrue(viewModel.uiState.value.isPlaying)
        assertEquals(157000L, viewModel.playbackPositionMs.value)
        assertEquals(157000L, viewModel.uiState.value.playbackPositionMs)
    }
}
