package com.auralis.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recognition.RecognitionMode
import com.auralis.music.domain.recognition.RecognitionState
import com.auralis.music.ui.explore.ExploreScreen as PureExploreScreen
import com.auralis.music.ui.viewmodel.SearchUiState

/**
 * Backward-compatible ExploreScreen delegating to the pure Jetpack Compose PureExploreScreen.
 */
@Composable
fun ExploreScreen(
    uiState: SearchUiState,
    recognitionState: RecognitionState = RecognitionState(),
    currentTrackId: String?,
    isPlaying: Boolean,
    userPlaylists: List<Playlist> = emptyList(),
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit,
    onCreatePlaylistAndAdd: (String, Track) -> Unit,
    onRemoveRecentQuery: (String) -> Unit,
    onClearRecentQueries: () -> Unit = {},
    onOpenRecognition: (RecognitionMode) -> Unit = {},
    onCloseRecognition: () -> Unit = {},
    onModeSelect: (RecognitionMode) -> Unit = {},
    onStartListening: () -> Unit = {},
    onStopListening: () -> Unit = {},
    onOpenArtist: (com.auralis.music.domain.model.Artist) -> Unit = {},
    onCloseArtist: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    PureExploreScreen(
        uiState = uiState,
        recognitionState = recognitionState,
        currentTrackId = currentTrackId,
        isPlaying = isPlaying,
        userPlaylists = userPlaylists,
        onQueryChange = onQueryChange,
        onSearch = onSearch,
        onClearSearch = onClearSearch,
        onTrackClick = onTrackClick,
        onFavoriteToggle = onFavoriteToggle,
        onAddToPlaylist = onAddToPlaylist,
        onCreatePlaylistAndAdd = onCreatePlaylistAndAdd,
        onRemoveRecentQuery = onRemoveRecentQuery,
        onClearRecentQueries = onClearRecentQueries,
        onOpenRecognition = onOpenRecognition,
        onCloseRecognition = onCloseRecognition,
        onModeSelect = onModeSelect,
        onStartListening = onStartListening,
        onStopListening = onStopListening,
        onOpenArtist = onOpenArtist,
        onCloseArtist = onCloseArtist,
        modifier = modifier
    )
}
