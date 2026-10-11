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
    modifier: Modifier = Modifier
) {
    val top = browse.top ?: return
    val libraryState by libraryViewModel.uiState.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val queue = playback?.queue?.collectAsState()?.value
    val state = playback?.state?.collectAsState()?.value
    val currentTrackId = queue?.current?.id
    val isPlaying = state == VlcAudioPlayer.State.PLAYING
    val play: (com.auralis.music.domain.model.Track, List<com.auralis.music.domain.model.Track>) -> Unit = { track, list ->
        if (playback != null) playback.playFrom(track, list) else onUnavailable("Playback needs VLC")
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedContent(
            targetState = browse.stack.size to top,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            contentKey = { it.first },
            label = "browsePage",
            modifier = Modifier.fillMaxSize()
        ) { (_, page) ->
            // Albums and playlists span the window (their own header stays centred);
            // the phone's Artist page keeps a centred column.
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
                    onStartRadio = { onUnavailable("Radio on Windows comes later") },
                    onOpenArtist = browse::openArtist,
                    onBack = { browse.pop() }
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
                    onStartRadio = { onUnavailable("Radio on Windows comes later") },
                    onOpenArtist = browse::openArtist,
                    onAlbumClick = { browse.openAlbum(it, fromArtist = page.page) },
                    onBack = { browse.pop() },
                    modifier = Modifier.widthIn(max = PageMaxWidth).fillMaxWidth().fillMaxHeight()
                )
            }
            }
        }
    }
}
