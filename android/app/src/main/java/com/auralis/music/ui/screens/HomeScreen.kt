package com.auralis.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.home.HomeScreen as PureHomeScreen
import com.auralis.music.ui.viewmodel.HomeUiState

/**
 * Backward-compatible HomeScreen delegating to the pure Jetpack Compose PureHomeScreen.
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    currentTrackId: String?,
    isPlaying: Boolean,
    userPlaylists: List<Playlist> = emptyList(),
    onTrackClick: (Track, List<Track>) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit,
    onCreatePlaylistAndAdd: (String, Track) -> Unit,
    onOpenListenTogether: () -> Unit,
    onNavigateToExplore: () -> Unit = {},
    onMoodSelect: (String?) -> Unit = {},
    onChipToggle: (com.auralis.music.domain.model.HomeChip?) -> Unit = {},
    onSurpriseMe: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onArtistClick: (Artist) -> Unit = {},
    modifier: Modifier = Modifier
) {
    PureHomeScreen(
        uiState = uiState,
        currentTrackId = currentTrackId,
        isPlaying = isPlaying,
        userPlaylists = userPlaylists,
        onTrackClick = onTrackClick,
        onFavoriteToggle = onFavoriteToggle,
        onAddToPlaylist = onAddToPlaylist,
        onCreatePlaylistAndAdd = onCreatePlaylistAndAdd,
        onOpenListenTogether = onOpenListenTogether,
        onNavigateToExplore = onNavigateToExplore,
        onMoodSelect = onMoodSelect,
        onChipToggle = onChipToggle,
        onSurpriseMe = onSurpriseMe,
        onOpenProfile = onOpenProfile,
        onOpenHistory = onOpenHistory,
        onArtistClick = onArtistClick,
        modifier = modifier
    )
}
