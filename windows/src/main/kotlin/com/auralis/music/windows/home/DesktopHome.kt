package com.auralis.music.windows.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.music.data.network.LocalizedContent
import com.auralis.music.desktop.browse.DesktopBrowseState
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.home.HomeScreen
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

/** Home's shelves are horizontal rows, so it may grow wider than the column-shaped pages. */
private val HomeMaxWidth = 1280.dp

/**
 * The phone's Home screen on Windows. Its view model is the phone's, backed by the desktop
 * library and history files; playing, queueing and album/artist pages go to the desktop player
 * and browse stack. Phone-only entries (Listen Together, Stats, Wrapped, profile) explain that.
 */
@Composable
fun DesktopHome(
    viewModel: HomeViewModel,
    library: LibraryRepository,
    searchRepository: SearchRepository,
    playback: DesktopPlaybackController?,
    browse: DesktopBrowseState,
    onOpenPlaylist: (String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onOpenProfile: () -> Unit,
    showMessage: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val playlists by library.getPlaylists().collectAsState(emptyList())
    val favorites by library.getFavoriteTracks().collectAsState(emptyList())
    val savedAlbums by library.getSavedAlbums().collectAsState(emptyList())
    val queue = playback?.queue?.collectAsState()?.value
    val state = playback?.state?.collectAsState()?.value
    val scope = rememberCoroutineScope()

    val play: (Track, List<Track>) -> Unit = { track, list ->
        if (playback != null) playback.playFrom(track, list) else showMessage("Playback needs VLC")
    }
    fun withAlbumTracks(album: PlaylistResult, use: (List<Track>) -> Unit) {
        scope.launch {
            val tracks = runCatching { LocalizedContent.run { searchRepository.getAlbumTracks(album) } }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) use(tracks)
        }
    }
    val phoneOnly = { showMessage("This part of Auralis is on the phone for now") }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        HomeScreen(
            uiState = uiState,
            currentTrack = queue?.current,
            currentTrackId = queue?.current?.id,
            isPlaying = state == VlcAudioPlayer.State.PLAYING,
            userPlaylists = playlists,
            favoriteTracks = favorites,
            onTrackClick = play,
            onFavoriteToggle = { track -> scope.launch { library.toggleFavorite(track) } },
            onAddToPlaylist = { playlistId, track -> scope.launch { library.addTrackToPlaylist(playlistId, track) } },
            onCreatePlaylistAndAdd = { title, track ->
                scope.launch { library.addTrackToPlaylist(library.createPlaylist(title).id, track) }
            },
            onPlayNext = { track ->
                playback?.playNext(track)
                showMessage(str(Strings.playing_next_x, track.title))
            },
            onAddToQueue = { track ->
                playback?.addToQueue(track)
                showMessage(str(Strings.added_to_queue_x, track.title))
            },
            onStartRadio = { track -> play(track, listOf(track)) },
            onOpenListenTogether = phoneOnly,
            onNavigateToExplore = onNavigateToSearch,
            onMoodSelect = viewModel::selectMoodFilter,
            onChipToggle = viewModel::toggleChip,
            onSurpriseMe = { viewModel.getRandomSurpriseTrack()?.let { play(it, listOf(it)) } },
            onOpenProfile = onOpenProfile,
            onOpenHistory = phoneOnly,
            onOpenStats = phoneOnly,
            onOpenWrapped = phoneOnly,
            onRefresh = viewModel::refresh,
            onArtistClick = browse::openArtist,
            onAlbumClick = { browse.openAlbum(it) },
            onPlaylistClick = onOpenPlaylist,
            onEditPlaylist = { id, title, desc, cover -> scope.launch { library.updatePlaylist(id, title, desc, cover) } },
            onDeletePlaylist = { id -> scope.launch { library.deletePlaylist(id) } },
            onAddPlaylistToQueue = { tracks -> tracks.forEach { playback?.addToQueue(it) } },
            onUnpinSpeedDial = viewModel::unpinFromSpeedDial,
            savedAlbums = savedAlbums,
            isAlbumPinned = viewModel::isAlbumPinned,
            onPinAlbumToSpeedDial = { album ->
                val pinned = viewModel.togglePinAlbum(album)
                showMessage(
                    if (pinned) str(Strings.pinned_x_to_speed_dial, album.title)
                    else str(Strings.unpinned_x_from_speed_dial, album.title)
                )
            },
            isTrackPinned = viewModel::isTrackPinned,
            onPinTrackToSpeedDial = { track -> viewModel.togglePinTrack(track) },
            onToggleSaveAlbum = { album ->
                scope.launch {
                    if (savedAlbums.any { it.id == album.id }) library.removeAlbum(album.id) else library.saveAlbum(album)
                }
            },
            onShuffleAlbum = { album ->
                withAlbumTracks(album) { tracks ->
                    val shuffled = tracks.shuffled()
                    play(shuffled.first(), shuffled)
                    showMessage(str(Strings.shuffling_x, album.title))
                }
            },
            onPlayNextAlbum = { album ->
                withAlbumTracks(album) { tracks ->
                    tracks.asReversed().forEach { playback?.playNext(it) }
                    showMessage(str(Strings.playing_next_x, album.title))
                }
            },
            onAddToQueueAlbum = { album ->
                withAlbumTracks(album) { tracks ->
                    tracks.forEach { playback?.addToQueue(it) }
                    showMessage(str(Strings.added_x_songs_to_queue, tracks.size))
                }
            },
            onAddAlbumToPlaylist = { playlistId, album ->
                withAlbumTracks(album) { tracks -> scope.launch { tracks.forEach { library.addTrackToPlaylist(playlistId, it) } } }
            },
            onCreatePlaylistAndAddAlbum = { title, album ->
                withAlbumTracks(album) { tracks ->
                    scope.launch {
                        val id = library.createPlaylist(title).id
                        tracks.forEach { library.addTrackToPlaylist(id, it) }
                    }
                }
            },
            onDownloadAlbum = { showMessage("Downloads aren't available on Windows yet") },
            modifier = Modifier.widthIn(max = HomeMaxWidth).fillMaxWidth().fillMaxHeight()
        )
    }
}
