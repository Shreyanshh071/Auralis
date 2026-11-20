package com.auralis.music.desktop.library

import com.auralis.music.data.network.YouTubeShortsFilter
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A playable view of saved data. Sync and account backups retain the original entries. */
class MusicLibraryRepository(
    private val source: LibraryRepository,
    private val filter: suspend (List<Track>) -> List<Track> = YouTubeShortsFilter::filter
) : LibraryRepository by source {
    override fun getPlaylists(): Flow<List<Playlist>> = source.getPlaylists().map { playlists ->
        val allowed = filter(playlists.flatMap { it.tracks }.distinctBy { it.id }).map { it.id }.toSet()
        playlists.map { playlist -> playlist.copy(tracks = playlist.tracks.filter { it.id in allowed }) }
    }

    override fun getPlaylist(playlistId: String): Flow<Playlist?> = source.getPlaylist(playlistId).map { playlist ->
        playlist?.copy(tracks = filter(playlist.tracks))
    }

    override fun getFavoriteTracks(): Flow<List<Track>> = source.getFavoriteTracks().map { filter(it) }
    override suspend fun getAllTracks(): List<Track> = filter(source.getAllTracks())

    // Reordering or enriching the visible songs must not turn hidden entries into
    // deletions when the raw playlist is subsequently synced to YouTube.
    private suspend fun retainHidden(playlistId: String, tracks: List<Track>): List<Track> {
        val original = source.getPlaylist(playlistId).first()?.tracks.orEmpty()
        val visible = filter(original).map { it.id }.toSet()
        val hidden = original.withIndex().filter { it.value.id !in visible }
        val result = tracks.toMutableList()
        for ((index, track) in hidden) {
            if (result.none { it.id == track.id }) result.add(index.coerceAtMost(result.size), track)
        }
        return result
    }

    override suspend fun reorderPlaylist(playlistId: String, tracks: List<Track>) =
        source.reorderPlaylist(playlistId, retainHidden(playlistId, tracks))

    override suspend fun replacePlaylistTracks(playlistId: String, tracks: List<Track>) =
        source.replacePlaylistTracks(playlistId, retainHidden(playlistId, tracks))
}
