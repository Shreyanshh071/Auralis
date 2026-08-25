package com.auralis.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.library.LibraryScreen as PureLibraryScreen
import com.auralis.music.ui.viewmodel.LibraryFilter
import com.auralis.music.ui.viewmodel.LibraryUiState
import com.auralis.music.ui.viewmodel.SmartCollectionType

/**
 * Backward-compatible LibraryScreen delegating to the pure Jetpack Compose PureLibraryScreen.
 */
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    currentTrackId: String?,
    isPlaying: Boolean,
    onFilterSelect: (LibraryFilter) -> Unit = {},
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onPlaylistSelect: (Playlist?) -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
    onImportYouTubePlaylist: (String) -> Unit,
    onImportSpotifyPlaylist: (String) -> Unit = {},
    onExportBackup: suspend () -> String = { "" },
    onImportBackup: (String) -> Unit = {},
    onSmartCollectionClick: (SmartCollectionType) -> Unit = {},
    onSortChange: (String) -> Unit = {},
    onToggleGridView: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenListenTogether: () -> Unit = {},
    onSyncPlaylist: (Playlist) -> Unit = {},
    onEditPlaylist: (String, String, String?, String?) -> Unit = { _, _, _, _ -> },
    onAddToQueue: (List<Track>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    PureLibraryScreen(
        uiState = uiState,
        currentTrackId = currentTrackId,
        isPlaying = isPlaying,
        onFilterSelect = onFilterSelect,
        onCreatePlaylist = onCreatePlaylist,
        onDeletePlaylist = onDeletePlaylist,
        onPlaylistSelect = onPlaylistSelect,
        onTrackClick = onTrackClick,
        onFavoriteToggle = onFavoriteToggle,
        onAddToPlaylist = onAddToPlaylist,
        onRemoveFromPlaylist = onRemoveFromPlaylist,
        onImportYouTubePlaylist = onImportYouTubePlaylist,
        onImportSpotifyPlaylist = onImportSpotifyPlaylist,
        onExportBackup = onExportBackup,
        onImportBackup = onImportBackup,
        onSmartCollectionClick = onSmartCollectionClick,
        onSortChange = onSortChange,
        onToggleGridView = onToggleGridView,
        onOpenProfile = onOpenProfile,
        onOpenHistory = onOpenHistory,
        onOpenListenTogether = onOpenListenTogether,
        onSyncPlaylist = onSyncPlaylist,
        onEditPlaylist = onEditPlaylist,
        onAddToQueue = onAddToQueue,
        modifier = modifier
    )
}
