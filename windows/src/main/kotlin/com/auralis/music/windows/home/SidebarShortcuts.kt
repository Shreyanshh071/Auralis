package com.auralis.music.windows.home

import androidx.compose.runtime.*
import androidx.compose.material3.*
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.ui.viewmodel.HomeViewModel
import com.auralis.music.desktop.browse.DesktopBrowseState
import com.auralis.music.desktop.playback.DesktopPlaybackController

/** The same persisted pins used by song, playlist, album and artist menus, visible on every page. */
@Composable
internal fun SidebarShortcuts(viewModel: HomeViewModel, library: LibraryRepository,
    playback: DesktopPlaybackController?, browse: DesktopBrowseState, collapsed: Boolean,
    onOpenPlaylist: (String) -> Unit, onBrowse: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val playlists by library.getPlaylists().collectAsState(emptyList())
    val artists by library.getSavedArtists().collectAsState(emptyList())
    var collages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(playlists) {
        val updated = mutableMapOf<String, String>()
        playlists.filter { it.coverUrl.isNullOrBlank() }.forEach { p ->
            com.auralis.music.windows.library.playlistCollage(p.tracks)?.let { updated[p.id] = it }
        }
        collages = updated
    }
    val candidates = playlists.map { p ->
        val album = PlaylistResult("local:${p.id}", p.title, p.coverUrl ?: collages[p.id] ?: p.tracks.firstOrNull()?.thumbnail,
            "Playlist · ${p.tracks.size} songs")
        SpeedDialItem("album-${album.id}", p.title, SpeedDialType.ALBUM, album.thumbnail, album = album)
    } + artists.map { a -> SpeedDialItem(a.id, a.name, SpeedDialType.ARTIST, a.thumbnail, artistQuery = a.query ?: a.name) }
    val pins = state.speedDialPages.flatten().filter { it.isPinned || it.id in state.pinnedSpeedDialIds || it.track?.id in state.pinnedSpeedDialIds }.distinctBy { it.id }
    var menu by remember { mutableStateOf<SpeedDialItem?>(null) }
    val open: (SpeedDialItem) -> Unit = { item ->
        when (item.type) {
            SpeedDialType.TRACK -> item.track?.let { playback?.playSingle(it) }
            SpeedDialType.PLAYLIST -> onOpenPlaylist(item.id.removePrefix("playlist-"))
            SpeedDialType.ALBUM -> {
                val album = item.album ?: PlaylistResult(item.id.removePrefix("album-"), item.name, item.image)
                if (album.id.startsWith("local:")) onOpenPlaylist(album.id.removePrefix("local:"))
                else { onBrowse(); browse.openAlbum(album) }
            }
            SpeedDialType.ARTIST -> {
                onBrowse(); browse.openArtist(Artist(item.id.takeIf { it.startsWith("UC") }.orEmpty(), item.artistQuery ?: item.name, item.image))
            }
            else -> Unit
        }
    }
    HomeShortcuts(pins, candidates, open, { item ->
        when (item.type) {
            SpeedDialType.ALBUM -> item.album?.let(viewModel::togglePinAlbum)
            SpeedDialType.ARTIST -> viewModel.togglePinArtist(Artist(item.id, item.name, item.image))
            else -> Unit
        }
    }, { viewModel.unpinFromSpeedDial(it.id) }, onMenu = { menu = it },
        playlistCovers = playlists.associateBy { "local:${it.id}" }, sidebar = true, collapsed = collapsed)
    menu?.let { item ->
        val track = item.track
        if (item.type == SpeedDialType.TRACK && track != null) {
            com.auralis.music.windows.library.DesktopTrackMenu(track, library, playback, browse, viewModel, {}, { menu = null })
        } else com.auralis.music.ui.components.GlassAlertDialog(onDismissRequest = { menu = null },
            title = { Text(item.name) }, confirmButton = { TextButton({ menu = null; open(item) }) { Text("Open") } },
            dismissButton = { TextButton({ viewModel.unpinFromSpeedDial(item.id); menu = null }) { Text("Remove shortcut") } })
    }
}
