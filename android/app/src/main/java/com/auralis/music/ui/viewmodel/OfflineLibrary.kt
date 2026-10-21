package com.auralis.music.ui.viewmodel

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track

/**
 * What Library shows with no network: downloaded songs only, and only the playlists and albums
 * that hold at least one of them. Songs come back as their downloaded copies (local artwork).
 */
object OfflineLibrary {
    fun filter(state: LibraryUiState): LibraryUiState {
        val downloaded = state.downloadedTracks.associateBy { it.id }
        fun onlyDownloaded(tracks: List<Track>): List<Track> = tracks.mapNotNull { downloaded[it.id] }
        fun playlist(p: Playlist): Playlist = p.copy(tracks = onlyDownloaded(p.tracks))

        val downloadedAlbums = state.downloadedTracks
            .mapNotNull { it.album?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
            .toSet()
        val selected = state.selectedPlaylist?.let { open ->
            // The Downloaded collection and its folders already hold only downloads.
            if (state.selectedSmartCollection == SmartCollectionType.DOWNLOADED) open else playlist(open)
        }
        return state.copy(
            playlists = state.playlists.map(::playlist).filter { it.tracks.isNotEmpty() },
            favorites = onlyDownloaded(state.favorites),
            top50Tracks = onlyDownloaded(state.top50Tracks),
            weeklyMostTracks = onlyDownloaded(state.weeklyMostTracks),
            monthlyMostTracks = onlyDownloaded(state.monthlyMostTracks),
            // Streamed-cache songs and artist pages need the network.
            cachedTracks = emptyList(),
            savedArtists = emptyList(),
            savedAlbums = state.savedAlbums.filter { it.title.trim().lowercase() in downloadedAlbums },
            selectedPlaylist = selected
        )
    }
}
