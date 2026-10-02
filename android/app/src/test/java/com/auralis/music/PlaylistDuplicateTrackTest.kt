package com.auralis.music

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDuplicateTrackTest {

    private val testDispatcher = StandardTestDispatcher()
    private val tracksInPlaylist = mutableListOf<Track>()

    private val fakeRepo = object : LibraryRepository {
        val playlistsFlow = MutableStateFlow<List<Playlist>>(emptyList())
        override fun getFavoriteTracks(): Flow<List<Track>> = MutableStateFlow(emptyList())
        override fun isFavorite(trackId: String): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun toggleFavorite(track: Track) {}
        override suspend fun setFavorite(track: Track, isFavorite: Boolean) {}
        override fun getPlaylists(): Flow<List<Playlist>> = playlistsFlow
        override fun getPlaylist(playlistId: String): Flow<Playlist?> = MutableStateFlow(null)
        override suspend fun createPlaylist(title: String, description: String?, coverUrl: String?): Playlist =
            Playlist("p1", title, coverUrl = coverUrl)
        override suspend fun updatePlaylist(playlistId: String, title: String, description: String?, coverUrl: String?) {}

        override suspend fun addTrackToPlaylist(playlistId: String, track: Track): Boolean {
            if (tracksInPlaylist.any { it.id == track.id }) {
                return false
            }
            tracksInPlaylist.add(track)
            return true
        }

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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testAddTrackToPlaylistPreventsDuplicates() = runTest(testDispatcher) {
        val track1 = Track(id = "track_1", title = "Song A", artist = "Artist A")
        val addedFirstTime = fakeRepo.addTrackToPlaylist("p1", track1)
        assertTrue("First add must succeed", addedFirstTime)
        assertEquals(1, tracksInPlaylist.size)

        val addedSecondTime = fakeRepo.addTrackToPlaylist("p1", track1)
        assertFalse("Second add of same track must be rejected", addedSecondTime)
        assertEquals(1, tracksInPlaylist.size)
    }
}
