package com.auralis.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.home.HomeScreen as PureHomeScreen
import com.auralis.music.ui.viewmodel.HomeUiState

/**
 * Backward-compatible HomeScreen delegating to the pure Jetpack Compose PureHomeScreen.
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    currentTrack: Track? = null,
    currentTrackId: String? = null,
    isPlaying: Boolean = false,
    userPlaylists: List<Playlist> = emptyList(),
    favoriteTracks: List<Track> = emptyList(),
    onTrackClick: (Track, List<Track>) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit,
    onCreatePlaylistAndAdd: (String, Track) -> Unit,
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onStartRadio: (Track) -> Unit = {},
    onOpenListenTogether: () -> Unit,
    onNavigateToExplore: () -> Unit = {},
    onMoodSelect: (String?) -> Unit = {},
    onChipToggle: (com.auralis.music.domain.model.HomeChip?) -> Unit = {},
    onSurpriseMe: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onArtistClick: (Artist) -> Unit = {},
    onAlbumClick: (PlaylistResult) -> Unit = {},
    onUnpinSpeedDial: ((String) -> Unit)? = null,
    savedAlbums: List<SavedAlbum> = emptyList(),
    isAlbumPinned: ((String) -> Boolean)? = null,
    onPinAlbumToSpeedDial: ((PlaylistResult) -> Unit)? = null,
    onToggleSaveAlbum: ((SavedAlbum) -> Unit)? = null,
    onShuffleAlbum: ((PlaylistResult) -> Unit)? = null,
    onPlayNextAlbum: ((PlaylistResult) -> Unit)? = null,
    onAddToQueueAlbum: ((PlaylistResult) -> Unit)? = null,
    onAddAlbumToPlaylist: ((String, PlaylistResult) -> Unit)? = null,
    onCreatePlaylistAndAddAlbum: ((String, PlaylistResult) -> Unit)? = null,
    onDownloadAlbum: ((PlaylistResult) -> Unit)? = null,
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    isTrackPinned: ((String) -> Boolean)? = null,
    onPinTrackToSpeedDial: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    PureHomeScreen(
        uiState = uiState,
        currentTrack = currentTrack,
        currentTrackId = currentTrackId,
        isPlaying = isPlaying,
        userPlaylists = userPlaylists,
        favoriteTracks = favoriteTracks,
        onTrackClick = onTrackClick,
        onFavoriteToggle = onFavoriteToggle,
        onAddToPlaylist = onAddToPlaylist,
        onCreatePlaylistAndAdd = onCreatePlaylistAndAdd,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onStartRadio = onStartRadio,
        onOpenListenTogether = onOpenListenTogether,
        onNavigateToExplore = onNavigateToExplore,
        onMoodSelect = onMoodSelect,
        onChipToggle = onChipToggle,
        onSurpriseMe = onSurpriseMe,
        onOpenProfile = onOpenProfile,
        onOpenHistory = onOpenHistory,
        onOpenStats = onOpenStats,
        onArtistClick = onArtistClick,
        onAlbumClick = onAlbumClick,
        onUnpinSpeedDial = onUnpinSpeedDial,
        savedAlbums = savedAlbums,
        isAlbumPinned = isAlbumPinned,
        onPinAlbumToSpeedDial = onPinAlbumToSpeedDial,
        onToggleSaveAlbum = onToggleSaveAlbum,
        onShuffleAlbum = onShuffleAlbum,
        onPlayNextAlbum = onPlayNextAlbum,
        onAddToQueueAlbum = onAddToQueueAlbum,
        onAddAlbumToPlaylist = onAddAlbumToPlaylist,
        onCreatePlaylistAndAddAlbum = onCreatePlaylistAndAddAlbum,
        onDownloadAlbum = onDownloadAlbum,
        isInListenTogetherRoom = isInListenTogetherRoom,
        onRecommendToRoom = onRecommendToRoom,
        isTrackPinned = isTrackPinned,
        onPinTrackToSpeedDial = onPinTrackToSpeedDial,
        modifier = modifier
    )
}
