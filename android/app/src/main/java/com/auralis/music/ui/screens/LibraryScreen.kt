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
    userName: String = "You",
    userAvatarUrl: String? = null,
    onFilterSelect: (LibraryFilter) -> Unit = {},
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onPlaylistSelect: (Playlist?) -> Unit,
    onTrackClick: (Track, List<Track>, String?) -> Unit,
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
    onPlayNext: (Track) -> Unit = {},
    onAddToQueueTrack: (Track) -> Unit = {},
    onStartRadio: (Track) -> Unit = {},
    onOpenArtist: (com.auralis.music.domain.model.Artist) -> Unit = {},
    onOpenAlbum: ((com.auralis.music.domain.model.PlaylistResult) -> Unit)? = null,
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    onReorderPlaylistTracks: ((String, Int, Int) -> Unit)? = null,
    isExternalCreateDialogOpen: Boolean = false,
    onCloseExternalCreateDialog: () -> Unit = {},
    onCloseSmartCollection: () -> Unit = {},
    onDeletePlaylistJob: (String) -> Unit = {},
    onRetryPlaylistJob: (String) -> Unit = {},
    isTrackPinned: ((String) -> Boolean)? = null,
    onPinTrackToSpeedDial: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    PureLibraryScreen(
        uiState = uiState,
        currentTrackId = currentTrackId,
        isPlaying = isPlaying,
        userName = userName,
        userAvatarUrl = userAvatarUrl,
        onFilterSelect = onFilterSelect,
        onCreatePlaylist = onCreatePlaylist,
        onDeletePlaylist = onDeletePlaylist,
        onPlaylistSelect = onPlaylistSelect,
        onTrackClick = { track, list -> onTrackClick(track, list, null) },
        onFavoriteToggle = onFavoriteToggle,
        onAddToPlaylist = onAddToPlaylist,
        onRemoveFromPlaylist = onRemoveFromPlaylist,
        onImportYouTubePlaylist = onImportYouTubePlaylist,
        onImportSpotifyPlaylist = onImportSpotifyPlaylist,
        onExportBackup = onExportBackup,
        onImportBackup = onImportBackup,
        onSmartCollectionClick = onSmartCollectionClick,
        onCloseSmartCollection = onCloseSmartCollection,
        onDeletePlaylistJob = onDeletePlaylistJob,
        onRetryPlaylistJob = onRetryPlaylistJob,
        onSortChange = onSortChange,
        onToggleGridView = onToggleGridView,
        onOpenProfile = onOpenProfile,
        onOpenHistory = onOpenHistory,
        onOpenListenTogether = onOpenListenTogether,
        onSyncPlaylist = onSyncPlaylist,
        onEditPlaylist = onEditPlaylist,
        onAddToQueue = onAddToQueue,
        onPlayNext = onPlayNext,
        onAddToQueueTrack = onAddToQueueTrack,
        onStartRadio = onStartRadio,
        onOpenArtist = onOpenArtist,
        onOpenAlbum = onOpenAlbum,
        isInListenTogetherRoom = isInListenTogetherRoom,
        onRecommendToRoom = onRecommendToRoom,
        onReorderPlaylistTracks = onReorderPlaylistTracks,
        isExternalCreateDialogOpen = isExternalCreateDialogOpen,
        onCloseExternalCreateDialog = onCloseExternalCreateDialog,
        isTrackPinned = isTrackPinned,
        onPinTrackToSpeedDial = onPinTrackToSpeedDial,
        modifier = modifier
    )
}
