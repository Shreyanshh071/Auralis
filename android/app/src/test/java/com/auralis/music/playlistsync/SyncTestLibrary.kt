package com.auralis.music.playlistsync

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LibraryRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Observable library double; transport and merge tests never contact a real account. */
internal class SyncTestLibrary(@Suppress("UNUSED_PARAMETER") file: java.io.File) : LibraryRepository by mockk(relaxed = true) {
    private val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    override fun getPlaylists() = playlists
    override fun getPlaylist(playlistId: String) = playlists.map { lists -> lists.find { it.id == playlistId } }
    override suspend fun createPlaylist(title: String, description: String?, coverUrl: String?): Playlist {
        val list = Playlist(java.util.UUID.randomUUID().toString(), title, description, coverUrl)
        playlists.value = playlists.value + list
        return list
    }
    override suspend fun restorePlaylist(id: String, title: String, description: String?, coverUrl: String?): Playlist {
        val list = Playlist(id, title, description, coverUrl)
        playlists.value = playlists.value.filterNot { it.id == id } + list
        return list
    }
    private fun edit(id: String, action: (Playlist) -> Playlist) {
        playlists.value = playlists.value.map { if (it.id == id) action(it) else it }
    }
    override suspend fun updatePlaylist(playlistId: String, title: String, description: String?, coverUrl: String?) {
        edit(playlistId) { it.copy(title = title, description = description, coverUrl = coverUrl) }
    }
    override suspend fun replacePlaylistTracks(playlistId: String, tracks: List<Track>) { edit(playlistId) { it.copy(tracks = tracks) } }
    override suspend fun addTrackToPlaylist(playlistId: String, track: Track): Boolean {
        edit(playlistId) { it.copy(tracks = it.tracks + track) }; return true
    }
    override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        edit(playlistId) { it.copy(tracks = it.tracks.filterNot { track -> track.id == trackId }) }
    }
}
