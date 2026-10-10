package com.auralis.music.windows.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.auralis.music.data.network.AlbumMetadataResolver
import com.auralis.music.desktop.browse.DesktopBrowseState
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

/**
 * The phone's song options sheet on Windows, for any song row: favourite, playlists and Speed Dial
 * go to the desktop library and Home, queueing to the desktop player, artist/album to the browse pages.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DesktopTrackMenu(
    track: Track,
    library: LibraryRepository,
    playback: DesktopPlaybackController?,
    browse: DesktopBrowseState,
    homeViewModel: HomeViewModel,
    showMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Set when the song is shown inside a playlist, to offer removing it from there. */
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val favorites by library.getFavoriteTracks().collectAsState(emptyList())
    val playlists by library.getPlaylists().collectAsState(emptyList())
    TrackOptionsMenu(
        track = track,
        isFavorite = favorites.any { it.id == track.id },
        userPlaylists = playlists,
        onToggleFavorite = { scope.launch { library.toggleFavorite(track) } },
        onPlayNext = {
            playback?.playNext(track)
            showMessage(str(Strings.playing_next_x, track.title))
        },
        onAddToQueue = {
            playback?.addToQueue(track)
            showMessage(str(Strings.added_to_queue_x, track.title))
        },
        onRemoveFromPlaylist = onRemoveFromPlaylist,
        onStartRadio = { playback?.playFrom(track, listOf(track)) },
        isPinned = homeViewModel.isTrackPinned(track.id),
        onPinToSpeedDial = { homeViewModel.togglePinTrack(track) },
        onGoToArtist = { name -> browse.openArtist(Artist(id = "", name = name)) },
        onGoToAlbum = { albumId, albumTitle, albumArtist, albumArt ->
            val cached = AlbumMetadataResolver.getCached(track.title, track.artist, track.album)
            browse.openAlbum(
                PlaylistResult(
                    id = albumId ?: cached?.albumId ?: "album-${track.id}",
                    title = albumTitle,
                    author = albumArtist ?: cached?.artistName ?: track.artist,
                    thumbnail = albumArt ?: cached?.albumArt ?: track.thumbnail
                )
            )
        },
        onAddToPlaylist = { playlist -> scope.launch { library.addTrackToPlaylist(playlist.id, track) } },
        onCreatePlaylistAndAdd = { title -> scope.launch { library.addTrackToPlaylist(library.createPlaylist(title).id, track) } },
        onDismiss = onDismiss
    )
}
