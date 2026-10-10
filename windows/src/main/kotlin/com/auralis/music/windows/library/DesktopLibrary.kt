package com.auralis.music.windows.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.music.desktop.browse.DesktopBrowseState
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.library.LibraryScreen
import com.auralis.music.ui.viewmodel.HomeViewModel
import com.auralis.music.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

/** The Library grid reads well up to about this wide; a playlist page sits in the same column. */
private val LibraryMaxWidth = 1280.dp

/**
 * The phone's Library screen on Windows: playlists, Liked and Most-played collections, saved
 * albums and artists, playlist pages, YouTube Music / Spotify link import and backups, backed by
 * the desktop library files. Offline downloads, Listen Together and the profile stay on the phone.
 */
@Composable
fun DesktopLibrary(
    viewModel: LibraryViewModel,
    homeViewModel: HomeViewModel,
    library: com.auralis.music.domain.repository.LibraryRepository,
    playback: DesktopPlaybackController?,
    browse: DesktopBrowseState,
    onOpenProfile: () -> Unit,
    showMessage: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val queue = playback?.queue?.collectAsState()?.value
    val state = playback?.state?.collectAsState()?.value
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The phone opens "New playlist" from its bottom dock; on Windows it is a button on the Library page.
    var showCreate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val phoneOnly = { showMessage("This part of Auralis is on the phone for now") }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LibraryScreen(
            uiState = uiState,
            onRefresh = { viewModel.refresh() },
            currentTrackId = queue?.current?.id,
            isPlaying = state == VlcAudioPlayer.State.PLAYING,
            onFilterSelect = viewModel::setFilter,
            onCreatePlaylist = viewModel::createPlaylist,
            onDeletePlaylist = viewModel::deletePlaylist,
            onPlaylistSelect = { viewModel.selectPlaylist(it?.id, it) },
            onTrackClick = { track, list ->
                if (playback != null) playback.playFrom(track, list) else showMessage("Playback needs VLC")
            },
            onFavoriteToggle = { track -> scope.launch { library.toggleFavorite(track) } },
            onAddToPlaylist = viewModel::addTrackToPlaylist,
            onRemoveFromPlaylist = viewModel::removeTrackFromPlaylist,
            onImportYouTubePlaylist = viewModel::importYouTubePlaylist,
            onImportSpotifyPlaylist = { viewModel.importSpotifyPlaylist(it) },
            onExportBackup = { viewModel.exportLibraryJson() },
            onImportBackup = viewModel::importLibraryJson,
            onSmartCollectionClick = viewModel::openSmartCollection,
            onCloseSmartCollection = viewModel::closeSmartCollection,
            onSortChange = viewModel::setSortOrder,
            onToggleGridView = viewModel::toggleGridView,
            onOpenProfile = onOpenProfile,
            onOpenHistory = phoneOnly,
            onOpenListenTogether = phoneOnly,
            onSyncPlaylist = { viewModel.syncPlaylist(it) },
            onEditPlaylist = { id, title, desc, cover -> viewModel.editPlaylist(id, title, desc, cover) },
            onAddToQueue = { tracks -> tracks.forEach { playback?.addToQueue(it) } },
            onPlayNext = { track ->
                playback?.playNext(track)
                showMessage(str(Strings.playing_next_x, track.title))
            },
            onAddToQueueTrack = { track ->
                playback?.addToQueue(track)
                showMessage(str(Strings.added_to_queue_x, track.title))
            },
            onStartRadio = { track -> playback?.playFrom(track, listOf(track)) },
            onOpenArtist = browse::openArtist,
            onOpenAlbum = { browse.openAlbum(it) },
            onReorderPlaylistTracks = viewModel::reorderPlaylistTracks,
            isExternalCreateDialogOpen = showCreate,
            onCloseExternalCreateDialog = { showCreate = false },
            isTrackPinned = homeViewModel::isTrackPinned,
            onPinTrackToSpeedDial = { homeViewModel.togglePinTrack(it) },
            modifier = Modifier.widthIn(max = LibraryMaxWidth).fillMaxWidth().fillMaxHeight()
        )
        if (uiState.selectedPlaylist == null && uiState.selectedSmartCollection == null) {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Add, contentDescription = null) },
                text = { androidx.compose.material3.Text(str(Strings.new_playlist)) },
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
            )
        }
    }
}
