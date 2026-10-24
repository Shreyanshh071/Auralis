package com.auralis.music.windows.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.music.desktop.browse.BrowsePage
import com.auralis.music.desktop.browse.DesktopBrowseState
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.ui.screens.AlbumScreen
import com.auralis.music.ui.screens.ArtistScreen
import kotlinx.coroutines.launch

/** Phone pages are built for a column; on a wide window they sit in a centred column this wide. */
val PageMaxWidth = 960.dp

/**
 * The phone's Album and Artist pages on desktop, on top of the main pages. Library actions that
 * need the desktop library (save, favourite, playlists) arrive with the Library page.
 */
@Composable
fun BrowsePages(
    browse: DesktopBrowseState,
    playback: DesktopPlaybackController?,
    onUnavailable: (String) -> Unit,
    libraryViewModel: com.auralis.music.ui.viewmodel.LibraryViewModel,
    library: com.auralis.music.domain.repository.LibraryRepository,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val top = browse.top ?: return
    val back: () -> Unit = onBack ?: { browse.pop(); Unit }
    val libraryState by libraryViewModel.uiState.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val queue = playback?.queue?.collectAsState()?.value
    val state = playback?.state?.collectAsState()?.value
    val currentTrackId = queue?.current?.id
    val isPlaying = state == VlcAudioPlayer.State.PLAYING
    val play: (com.auralis.music.domain.model.Track, List<com.auralis.music.domain.model.Track>) -> Unit = { track, list ->
        if (playback != null) playback.playFrom(track, list, automatic = false) else onUnavailable("Playback needs VLC")
    }
    // A song's radio: the song, then the radio engine's picks, without blocked artists.
    val startRadio: (com.auralis.music.domain.model.Track) -> Unit = { track ->
        playback?.playSingle(track)
    }
    val forwardEnter = com.auralis.music.ui.theme.auralisDetailForwardEnter()
    val forwardExit = com.auralis.music.ui.theme.auralisDetailForwardExit()
    val backwardEnter = com.auralis.music.ui.theme.auralisDetailBackwardEnter()
    val backwardExit = com.auralis.music.ui.theme.auralisDetailBackwardExit()
    val pageStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // A page left by Back is gone: opening it again later starts from the top.
    val depthNow = browse.stack.size
    var deepest by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(depthNow) }
    androidx.compose.runtime.LaunchedEffect(depthNow) {
        for (gone in depthNow + 1..deepest) pageStates.removeState(gone)
        deepest = depthNow
    }
    FloatingBrowseNavigation(onBack = back, modifier = modifier) {
        AnimatedContent(
            targetState = browse.stack.size to top,
            // Opening slides the page in from the right; Back reverses it (Library's detail motion).
            transitionSpec = {
                if (targetState.first >= initialState.first) forwardEnter togetherWith forwardExit
                else backwardEnter togetherWith backwardExit
            },
            contentKey = { it.first },
            label = "browsePage",
            modifier = Modifier.fillMaxSize()
        ) { (depth, page) ->
            // A page under the top keeps its scroll, so Back lands exactly where it was left.
            pageStates.SaveableStateProvider(depth) {
            // Album, playlist and artist pages span the whole panel, as on Spotify; a centred
            // column left the artist header cut off with the background showing either side.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (page) {
                is BrowsePage.Album -> AlbumScreen(
                    album = page.album,
                    tracks = page.tracks,
                    isLoading = page.isLoading,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    userPlaylists = libraryState.playlists,
                    favoriteTracks = libraryState.favorites,
                    savedAlbums = libraryState.savedAlbums,
                    onToggleSaveAlbum = libraryViewModel::toggleSaveAlbum,
                    onAddToPlaylist = libraryViewModel::addTrackToPlaylist,
                    onPlayNextAlbum = { page.tracks.asReversed().forEach { playback?.playNext(it) } },
                    onAddToQueueAlbum = { page.tracks.forEach { playback?.addToQueue(it) } },
                    onShuffleAlbum = { page.tracks.shuffled().let { if (it.isNotEmpty()) play(it.first(), it) } },
                    onTrackClick = play,
                    onFavoriteToggle = { track -> scope.launch { library.toggleFavorite(track) } },
                    onPlayNext = { playback?.playNext(it) },
                    onAddToQueue = { playback?.addToQueue(it) },
                    onStartRadio = startRadio,
                    onOpenArtist = browse::openArtist,
                    onBack = back,
                    showBackButton = false,
                    loadFailed = page.loadFailed,
                    onRetry = browse::retryAlbum
                )
                is BrowsePage.Artist -> ArtistScreen(
                    artistPage = page.page,
                    isLoading = page.isLoading,
                    loadFailed = page.loadFailed,
                    onRetry = browse::retryArtist,
                    currentTrackId = currentTrackId,
                    isPlaying = isPlaying,
                    userPlaylists = libraryState.playlists,
                    favoriteTracks = libraryState.favorites,
                    savedAlbums = libraryState.savedAlbums,
                    savedArtists = libraryState.savedArtists,
                    onToggleSaveAlbum = libraryViewModel::toggleSaveAlbum,
                    onToggleSubscribe = libraryViewModel::toggleSaveArtist,
                    onAddToPlaylist = libraryViewModel::addTrackToPlaylist,
                    onTrackClick = play,
                    onFavoriteToggle = { track -> scope.launch { library.toggleFavorite(track) } },
                    onPlayNext = { playback?.playNext(it) },
                    onAddToQueue = { playback?.addToQueue(it) },
                    onStartRadio = startRadio,
                    onOpenArtist = browse::openArtist,
                    onAlbumClick = { browse.openAlbum(it, fromArtist = page.page) },
                    onBack = back,
                    modifier = Modifier.fillMaxSize(),
                    showBackButton = false
                )
            }
            }
            }
        }
    }
}
