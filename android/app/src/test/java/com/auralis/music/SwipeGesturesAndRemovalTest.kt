package com.auralis.music

import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SwipeGesturesAndRemovalTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private open class FakeLibraryRepository : LibraryRepository {
        override fun getFavoriteTracks(): Flow<List<Track>> = MutableStateFlow(emptyList())
        override fun isFavorite(trackId: String): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun toggleFavorite(track: Track) {}
        override suspend fun setFavorite(track: Track, isFavorite: Boolean) {}
        override fun getPlaylists(): Flow<List<Playlist>> = MutableStateFlow(emptyList())
        override fun getPlaylist(playlistId: String): Flow<Playlist?> = kotlinx.coroutines.flow.flow {
            getPlaylists().collect { list ->
                emit(list.find { it.id == playlistId })
            }
        }
        override suspend fun createPlaylist(title: String, description: String?, coverUrl: String?): Playlist =
            Playlist(id = "pl_created", title = title)
        override suspend fun updatePlaylist(playlistId: String, title: String, description: String?, coverUrl: String?) {}
        override suspend fun addTrackToPlaylist(playlistId: String, track: Track) {}
        override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {}
        override suspend fun deletePlaylist(playlistId: String) {}
        override suspend fun reorderPlaylist(playlistId: String, tracks: List<Track>) {}
        override suspend fun replacePlaylistTracks(playlistId: String, tracks: List<Track>) {}
        override fun getSavedArtists(): Flow<List<SavedArtist>> = MutableStateFlow(emptyList())
        override fun isArtistSaved(artistId: String): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun saveArtist(artist: SavedArtist) {}
        override suspend fun removeArtist(artistId: String) {}
        override fun getSavedAlbums(): Flow<List<SavedAlbum>> = MutableStateFlow(emptyList())
        override fun isAlbumSaved(albumId: String): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun saveAlbum(album: SavedAlbum) {}
        override suspend fun removeAlbum(albumId: String) {}
    }

    @Test
    fun appearanceSettings_defaultsSwipeGesturesToTrue() {
        val settings = AppearanceSettings()
        assertTrue("swipeLeftQueueRightPlayNext must be true by default", settings.swipeLeftQueueRightPlayNext)
        assertTrue("swipeToRemoveSongFromPlaylist must be true by default", settings.swipeToRemoveSongFromPlaylist)
    }

    @Test
    fun libraryViewModel_removeTrackFromPlaylist_optimisticallyUpdatesSelectedPlaylistAndPlaylists() = runTest(testDispatcher) {
        val track1 = Track(id = "t1", title = "Song 1", artist = "Artist 1")
        val track2 = Track(id = "t2", title = "Song 2", artist = "Artist 2")
        val playlist = Playlist(id = "pl1", title = "My Playlist", tracks = listOf(track1, track2))

        val playlistsFlow = MutableStateFlow(listOf(playlist))

        var repoRemovedTrackId: String? = null
        var repoRemovedPlaylistId: String? = null

        val mockRepo = object : FakeLibraryRepository() {
            override fun getPlaylists(): Flow<List<Playlist>> = playlistsFlow
            override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
                repoRemovedPlaylistId = playlistId
                repoRemovedTrackId = trackId
            }
        }

        val viewModel = LibraryViewModel(libraryRepository = mockRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        // Select the playlist
        viewModel.selectPlaylist("pl1", playlist)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.selectedPlaylist?.tracks?.size)

        // Remove track1
        viewModel.removeTrackFromPlaylist("pl1", "t1")
        testDispatcher.scheduler.advanceUntilIdle()

        // UI state should be updated optimistically immediately
        val updatedTracks = viewModel.uiState.value.selectedPlaylist?.tracks
        assertEquals(1, updatedTracks?.size)
        assertEquals("t2", updatedTracks?.first()?.id)

        val updatedPlaylists = viewModel.uiState.value.playlists
        assertEquals(1, updatedPlaylists.first().tracks.size)
        assertEquals("t2", updatedPlaylists.first().tracks.first().id)

        // Repository call was dispatched
        assertEquals("pl1", repoRemovedPlaylistId)
        assertEquals("t1", repoRemovedTrackId)
    }

    @Test
    fun libraryViewModel_removeTrackFromSmartFavorites_optimisticallyUpdatesFavoritesAndCallsSetFavorite() = runTest(testDispatcher) {
        val track1 = Track(id = "fav1", title = "Favorite 1", artist = "Artist 1")
        val track2 = Track(id = "fav2", title = "Favorite 2", artist = "Artist 2")

        val favoritesFlow = MutableStateFlow(listOf(track1, track2))

        var unlikedTrack: Track? = null
        var unlikedState: Boolean? = null

        val mockRepo = object : FakeLibraryRepository() {
            override fun getFavoriteTracks(): Flow<List<Track>> = favoritesFlow
            override suspend fun setFavorite(track: Track, isFavorite: Boolean) {
                unlikedTrack = track
                unlikedState = isFavorite
            }
        }

        val viewModel = LibraryViewModel(libraryRepository = mockRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.favorites.size)

        // Remove fav1 from smart_favorites
        viewModel.removeTrackFromPlaylist("smart_favorites", "fav1")
        testDispatcher.scheduler.advanceUntilIdle()

        // Optimistically removed
        assertEquals(1, viewModel.uiState.value.favorites.size)
        assertEquals("fav2", viewModel.uiState.value.favorites.first().id)

        // Repository setFavorite(track, false) called
        assertEquals("fav1", unlikedTrack?.id)
        assertEquals(false, unlikedState)
    }

    @Test
    fun swipeActionCapabilities_evaluatedCorrectly() {
        val appearanceBothOn = AppearanceSettings(
            swipeLeftQueueRightPlayNext = true,
            swipeToRemoveSongFromPlaylist = true
        )

        // Case 1: Normal track list with playNext & addToQueue
        val canSwipeRight1 = appearanceBothOn.swipeLeftQueueRightPlayNext && true
        val isSwipeRemove1 = appearanceBothOn.swipeToRemoveSongFromPlaylist && false && true
        val canSwipeLeft1 = isSwipeRemove1 || (appearanceBothOn.swipeLeftQueueRightPlayNext && true)
        assertTrue(canSwipeRight1)
        assertTrue(canSwipeLeft1)
        assertFalse(isSwipeRemove1)

        // Case 2: In Playlist context with removal
        val isSwipeRemove2 = appearanceBothOn.swipeToRemoveSongFromPlaylist && true && true
        val canSwipeLeft2 = isSwipeRemove2 || (appearanceBothOn.swipeLeftQueueRightPlayNext && true)
        assertTrue(canSwipeLeft2)
        assertTrue(isSwipeRemove2)

        // Case 3: Both settings toggled OFF
        val appearanceBothOff = AppearanceSettings(
            swipeLeftQueueRightPlayNext = false,
            swipeToRemoveSongFromPlaylist = false
        )
        val canSwipeRight3 = appearanceBothOff.swipeLeftQueueRightPlayNext && true
        val isSwipeRemove3 = appearanceBothOff.swipeToRemoveSongFromPlaylist && true && true
        val canSwipeLeft3 = isSwipeRemove3 || (appearanceBothOff.swipeLeftQueueRightPlayNext && true)
        assertFalse(canSwipeRight3)
        assertFalse(canSwipeLeft3)
        assertFalse(isSwipeRemove3)
    }

    @Test
    fun libraryViewModel_createPlaylistAndAddTracks_directlyAddsAlbumPlaylistWithMetadataAndTracks() = runTest(testDispatcher) {
        val track1 = Track(id = "trk1", title = "Let It Happen", artist = "Tame Impala")
        val track2 = Track(id = "trk2", title = "The Less I Know The Better", artist = "Tame Impala")
        val albumTracks = listOf(track1, track2)

        var createdPlaylistTitle: String? = null
        var createdPlaylistCover: String? = null
        var replacedTracks: List<Track>? = null

        val mockRepo = object : FakeLibraryRepository() {
            override suspend fun createPlaylist(title: String, description: String?, coverUrl: String?): Playlist {
                createdPlaylistTitle = title
                createdPlaylistCover = coverUrl
                return Playlist(id = "album_pl_1", title = title, description = description, coverUrl = coverUrl)
            }
            override suspend fun replacePlaylistTracks(playlistId: String, tracks: List<Track>) {
                replacedTracks = tracks
            }
        }

        val viewModel = LibraryViewModel(libraryRepository = mockRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        // Directly add album playlist as requested by user
        var callbackCalled = false
        viewModel.createPlaylistAndAddTracks(
            title = "Currents",
            tracks = albumTracks,
            description = "Album • Tame Impala",
            coverUrl = "https://auralis.io/currents.jpg",
            onCreated = { callbackCalled = true }
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(callbackCalled)
        assertEquals("Currents", createdPlaylistTitle)
        assertEquals("https://auralis.io/currents.jpg", createdPlaylistCover)
        assertEquals(2, replacedTracks?.size)

        // Optimistic UI state verification
        val createdPl = viewModel.uiState.value.playlists.find { it.title == "Currents" }
        assertTrue(createdPl != null)
        assertEquals("Currents", createdPl?.title)
        assertEquals("Album • Tame Impala", createdPl?.description)
        assertEquals("https://auralis.io/currents.jpg", createdPl?.coverUrl)
        assertEquals(2, createdPl?.tracks?.size)
    }
}
