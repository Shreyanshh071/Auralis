package com.auralis.music.domain.repository

import com.auralis.music.domain.model.*
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun getFavoriteTracks(): Flow<List<Track>>
    fun isFavorite(trackId: String): Flow<Boolean>
    suspend fun toggleFavorite(track: Track)
    suspend fun setFavorite(track: Track, isFavorite: Boolean)

    fun getPlaylists(): Flow<List<Playlist>>
    fun getPlaylist(playlistId: String): Flow<Playlist?>
    suspend fun createPlaylist(title: String, description: String? = null): Playlist
    suspend fun updatePlaylist(playlistId: String, title: String, description: String? = null, coverUrl: String? = null)
    suspend fun addTrackToPlaylist(playlistId: String, track: Track)
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String)
    suspend fun deletePlaylist(playlistId: String)
    suspend fun reorderPlaylist(playlistId: String, tracks: List<Track>)

    fun getSavedArtists(): Flow<List<SavedArtist>>
    fun isArtistSaved(artistId: String): Flow<Boolean>
    suspend fun saveArtist(artist: SavedArtist)
    suspend fun removeArtist(artistId: String)

    fun getSavedAlbums(): Flow<List<SavedAlbum>>
    fun isAlbumSaved(albumId: String): Flow<Boolean>
    suspend fun saveAlbum(album: SavedAlbum)
    suspend fun removeAlbum(albumId: String)
}

interface HistoryRepository {
    fun getHistory(): Flow<List<HistoryEntry>>
    suspend fun addToHistory(track: Track)
    suspend fun removeFromHistory(trackId: String)
    suspend fun clearHistory()

    fun getTopPlayedTracks(): Flow<List<PlayCountEntry>>
    suspend fun recordPlay(track: Track)
    suspend fun getPlayCounts(): List<PlayCountEntry>

    suspend fun getForgottenFavorites(cutoffTimestamp: Long = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000): List<Track>
    suspend fun getRecentHeavyRotation(fromTimestamp: Long = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000): List<Track>
    suspend fun getLikedSeeds(limit: Int = 10): List<Track>
}

interface SettingsRepository {
    val settingsFlow: Flow<PlayerSettings>
    suspend fun updateSettings(settings: PlayerSettings)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setVolume(volume: Float)
    suspend fun setPlaybackRate(rate: Float)
}
