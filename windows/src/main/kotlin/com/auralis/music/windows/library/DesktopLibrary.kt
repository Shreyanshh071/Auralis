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

/**
 * The phone's Library screen on Windows: playlists, Liked and Most-played collections, saved
 * albums and artists, playlist pages, YouTube Music / Spotify link import and backups, backed by
 * the desktop library files. Downloads use the desktop file store and grouped downloads page.
 */
@Composable
fun DesktopLibrary(
    viewModel: LibraryViewModel,
    homeViewModel: HomeViewModel,
    library: com.auralis.music.domain.repository.LibraryRepository,
    playback: DesktopPlaybackController?,
    browse: DesktopBrowseState,
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenListenTogether: () -> Unit,
    showMessage: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val queue = playback?.queue?.collectAsState()?.value
    val state = playback?.state?.collectAsState()?.value
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The phone opens "New playlist" from its bottom dock; on Windows it is a button on the Library page.
    var showCreate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val savedAlbumEntries = uiState.savedAlbums.associateBy(::savedAlbumEntryId)
    val displayedState = uiState.copy(playlists = desktopLibraryItems(uiState.playlists, uiState.savedAlbums))

    if (uiState.selectedSmartCollection == com.auralis.music.ui.viewmodel.SmartCollectionType.DOWNLOADED) {
        DesktopDownloadsPage(playback, back = { viewModel.closeSmartCollection() }, playlists = uiState.playlists)
        return
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LibraryScreen(
            uiState = displayedState,
            playlistSource = ::desktopPlaylistSource,
            onRefresh = { viewModel.refresh() },
            currentTrackId = queue?.current?.id,
            isPlaying = state == VlcAudioPlayer.State.PLAYING,
            onFilterSelect = viewModel::setFilter,
            onCreatePlaylist = viewModel::createPlaylist,
            onDeletePlaylist = { id ->
                val album = savedAlbumEntries[id]
                if (album != null) viewModel.toggleSaveAlbum(album) else viewModel.deletePlaylist(id)
            },
            onPlaylistSelect = { playlist ->
                val album = savedAlbumEntries[playlist?.id]
                if (album != null) browse.openAlbum(album.asBrowseAlbum())
                else viewModel.selectPlaylist(playlist?.id, playlist)
            },
            onTrackClick = { track, list ->
                if (playback != null) playback.playFrom(track, list, automatic = false) else showMessage("Playback needs VLC")
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
            onOpenHistory = onOpenHistory,
            onOpenListenTogether = onOpenListenTogether,
            onSyncPlaylist = {
                val album = savedAlbumEntries[it.id]
                if (album != null) browse.openAlbum(album.asBrowseAlbum()) else viewModel.syncPlaylist(it)
            },
            onEditPlaylist = { id, title, desc, cover ->
                if (id in savedAlbumEntries) showMessage("Album details come from the music provider")
                else viewModel.editPlaylist(id, title, desc, cover)
            },
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
            emptyLibraryContent = { filter, query ->
                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    androidx.compose.material3.Text(
                        when {
                            query.isNotBlank() -> "No saved items match your search."
                            filter == com.auralis.music.ui.library.LibrarySourceFilter.ALBUMS -> "No saved albums yet. Save an album from its menu to find it here."
                            filter == com.auralis.music.ui.library.LibrarySourceFilter.ARTISTS -> "No saved artists yet. Save an artist from its menu to find it here."
                            filter == com.auralis.music.ui.library.LibrarySourceFilter.SPOTIFY -> "No Spotify playlists imported yet."
                            filter == com.auralis.music.ui.library.LibrarySourceFilter.YOUTUBE -> "No YouTube Music playlists imported yet."
                            else -> "No playlists here yet. Create or import a playlist."
                        }, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (query.isBlank() && filter !in listOf(com.auralis.music.ui.library.LibrarySourceFilter.ALBUMS, com.auralis.music.ui.library.LibrarySourceFilter.ARTISTS)) {
                        androidx.compose.material3.TextButton(onClick = onOpenProfile) {
                            androidx.compose.material3.Text("Import playlists")
                        }
                    }
                }
            },
            // The whole panel, as the other pages: a centred column left empty bands either side.
            modifier = Modifier.fillMaxSize()
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
