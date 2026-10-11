package com.auralis.music.windows.home

import androidx.compose.runtime.*
import com.auralis.music.desktop.browse.DesktopBrowseState
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.data.network.LocalizedContent
import com.auralis.music.ui.components.AlbumOptionsMenu
import com.auralis.music.ui.components.LocalPlatformActions
import com.auralis.music.ui.viewmodel.HomeViewModel
import com.auralis.music.windows.library.DesktopTrackMenu
import kotlinx.coroutines.launch

/** Desktop presentation over the existing recommendation engine; Android's Home stays unchanged. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun DesktopHome(
    viewModel: HomeViewModel,
    library: LibraryRepository,
    searchRepository: SearchRepository,
    playback: DesktopPlaybackController?,
    browse: DesktopBrowseState,
    onOpenPlaylist: (String) -> Unit,
    onNavigateToSearch: () -> Unit,
    showMessage: (String) -> Unit,
    findMusic: (@Composable () -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsState()
    val queue = playback?.queue?.collectAsState()?.value
    val playing = playback?.state?.collectAsState()?.value == VlcAudioPlayer.State.PLAYING
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var menuAlbum by remember { mutableStateOf<PlaylistResult?>(null) }
    val playlists by library.getPlaylists().collectAsState(emptyList())
    val savedAlbums by library.getSavedAlbums().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val platform = LocalPlatformActions.current
    val play: (Track, List<Track>) -> Unit = { track, tracks ->
        if (playback != null) playback.playFrom(track, tracks) else showMessage("Playback needs VLC")
    }
    fun withAlbumTracks(album: PlaylistResult, action: suspend (List<Track>) -> Unit) {
        scope.launch {
            val tracks = runCatching { LocalizedContent.run { searchRepository.getAlbumTracks(album) } }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) action(tracks) else showMessage("Couldn't load this album. Please try again.")
        }
    }
    DesktopHomeScreen(
        state = state,
        currentTrackId = queue?.current?.id,
        isPlaying = playing,
        onPlay = play,
        onShortcut = { item ->
            when (item.type) {
                SpeedDialType.TRACK -> item.track?.let { track ->
                    val tracks = desktopHomeContent(state).shortcuts.mapNotNull { it.track }
                    play(track, tracks.ifEmpty { listOf(track) })
                }
                SpeedDialType.ALBUM -> browse.openAlbum(item.album ?: PlaylistResult(
                    item.id.removePrefix("album-"), item.name, item.image, item.artistQuery))
                SpeedDialType.PLAYLIST -> onOpenPlaylist(item.id.removePrefix("playlist-"))
                SpeedDialType.ARTIST -> browse.openArtist(Artist(id = item.id.takeIf { it.startsWith("UC") }.orEmpty(),
                    name = item.artistQuery ?: item.name, thumbnail = item.image))
                else -> Unit
            }
        },
        onAlbum = browse::openAlbum,
        onMenu = { menuTrack = it },
        onSearch = onNavigateToSearch,
        onSurprise = { viewModel.getRandomSurpriseTrack()?.let { play(it, listOf(it)) } },
        onChip = viewModel::toggleChip,
        onAlbumMenu = { menuAlbum = it },
        onUnpinShortcut = { viewModel.unpinFromSpeedDial(it.id) },
        findMusic = findMusic
    )
    menuTrack?.let { track ->
        DesktopTrackMenu(track, library, playback, browse, viewModel, showMessage, { menuTrack = null })
    }
    menuAlbum?.let { album ->
        AlbumOptionsMenu(
            album = album,
            isSingleOrEp = album.releaseType in listOf("single", "ep"),
            isFavorite = savedAlbums.any { it.id == album.id },
            userPlaylists = playlists,
            isPinned = viewModel.isAlbumPinned(album.id),
            onToggleFavorite = { scope.launch {
                if (savedAlbums.any { it.id == album.id }) library.removeAlbum(album.id)
                else library.saveAlbum(SavedAlbum(album.id, album.title, album.author.orEmpty(), album.thumbnail))
            } },
            onShuffle = { withAlbumTracks(album) { tracks -> tracks.shuffled().let { play(it.first(), it) } } },
            onShare = { platform.shareText(album.title, "https://music.youtube.com/playlist?list=${album.id.removePrefix("VL")}", "Share album") },
            onPlayNext = { withAlbumTracks(album) { tracks -> tracks.asReversed().forEach { playback?.playNext(it) } } },
            onAddToQueue = { withAlbumTracks(album) { tracks -> tracks.forEach { playback?.addToQueue(it) } } },
            onAddToPlaylist = { playlist -> withAlbumTracks(album) { tracks -> tracks.forEach { library.addTrackToPlaylist(playlist.id, it) } } },
            onCreatePlaylistAndAdd = { title -> withAlbumTracks(album) { tracks ->
                val id = library.createPlaylist(title).id
                tracks.forEach { library.addTrackToPlaylist(id, it) }
            } },
            onPinToSpeedDial = { viewModel.togglePinAlbum(album) },
            onDownload = { showMessage("Downloads aren't available on Windows yet") },
            onViewArtist = { album.author?.let { browse.openArtist(Artist(id = "", name = it)) } },
            onDismiss = { menuAlbum = null }
        )
    }
}
