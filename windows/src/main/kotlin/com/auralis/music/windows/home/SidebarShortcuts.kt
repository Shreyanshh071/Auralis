package com.auralis.music.windows.home

import com.auralis.music.ui.components.TextButton
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
        val thumb = p.coverUrl?.takeIf { it.isNotBlank() } ?: collages[p.id] ?: p.tracks.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail
        val album = PlaylistResult("local:${p.id}", p.title, thumb, "Playlist · ${p.tracks.size} songs")
        SpeedDialItem("album-${album.id}", p.title, SpeedDialType.ALBUM, album.thumbnail, album = album)
    } + artists.map { a -> SpeedDialItem(a.id, a.name, SpeedDialType.ARTIST, a.thumbnail, artistQuery = a.query ?: a.name) }
    val pinnedCandidateMatches = candidates.filter { candidate ->
        val candAlbum = candidate.album
        val candAlbumId = candAlbum?.id
        candidate.id in state.pinnedSpeedDialIds ||
        (candAlbumId != null && (candAlbumId in state.pinnedSpeedDialIds || "album-$candAlbumId" in state.pinnedSpeedDialIds)) ||
        state.pinnedItems.any { pin ->
            val pinAlbum = pin.album
            pin.id == candidate.id ||
            (pinAlbum != null && candAlbum != null && (pinAlbum.id == candAlbum.id || pinAlbum.id.removePrefix("local:") == candAlbum.id.removePrefix("local:"))) ||
            (pin.type == SpeedDialType.ARTIST && candidate.type == SpeedDialType.ARTIST && (pin.id == candidate.id || pin.name.equals(candidate.name, true)))
        }
    }
    val pagePins = state.speedDialPages.flatten().filter {
        it.isPinned || it.id in state.pinnedSpeedDialIds || it.track?.id in state.pinnedSpeedDialIds
    }
    val pins = (state.pinnedItems + pinnedCandidateMatches + pagePins).filter { item ->
        val itemAlbum = item.album
        item.isPinned ||
        item.id in state.pinnedSpeedDialIds ||
        itemAlbum?.id in state.pinnedSpeedDialIds ||
        item.track?.id in state.pinnedSpeedDialIds ||
        state.pinnedItems.any { p ->
            val pAlbum = p.album
            p.id == item.id ||
            (pAlbum != null && itemAlbum != null && (pAlbum.id == itemAlbum.id || pAlbum.id.removePrefix("local:") == itemAlbum.id.removePrefix("local:")))
        }
    }.distinctBy { item ->
        when (item.type) {
            SpeedDialType.TRACK -> item.track?.id ?: item.id
            SpeedDialType.ALBUM -> item.album?.id?.removePrefix("local:") ?: item.id.removePrefix("album-").removePrefix("VL")
            SpeedDialType.ARTIST -> item.id
            else -> item.id
        }
    }
    var menu by remember { mutableStateOf<SpeedDialItem?>(null) }
    val open: (SpeedDialItem) -> Unit = { item ->
        when (item.type) {
            SpeedDialType.TRACK -> item.track?.let { playback?.playSingle(it) }
            SpeedDialType.PLAYLIST -> onOpenPlaylist(item.id.removePrefix("playlist-"))
            SpeedDialType.ALBUM -> {
                val album = item.album ?: PlaylistResult(item.id.removePrefix("album-"), item.name, item.image)
                if (album.id.startsWith("local:")) onOpenPlaylist(album.id.removePrefix("local:"))
                else if (item.id.removePrefix("album-").startsWith("local:")) onOpenPlaylist(item.id.removePrefix("album-").removePrefix("local:"))
                else { onBrowse(); browse.openAlbum(album) }
            }
            SpeedDialType.ARTIST -> {
                onBrowse(); browse.openArtist(Artist(item.id.takeIf { it.startsWith("UC") }.orEmpty(), item.artistQuery ?: item.name, item.image))
            }
            else -> Unit
        }
    }
    val allCovers = remember(playlists) {
        buildMap {
            playlists.forEach {
                put("local:${it.id}", it)
                put(it.id, it)
            }
        }
    }
    HomeShortcuts(pins, candidates, open, { item ->
        when (item.type) {
            SpeedDialType.ALBUM -> item.album?.let(viewModel::togglePinAlbum)
            SpeedDialType.ARTIST -> viewModel.togglePinArtist(Artist(item.id, item.name, item.image))
            else -> Unit
        }
    }, { viewModel.unpinFromSpeedDial(it.id) }, onMenu = { menu = it },
        playlistCovers = allCovers, sidebar = true, collapsed = collapsed)
    menu?.let { item ->
        val track = item.track
        if (item.type == SpeedDialType.TRACK && track != null) {
            com.auralis.music.windows.library.DesktopTrackMenu(track, library, playback, browse, viewModel, {}, { menu = null })
        } else com.auralis.music.ui.components.GlassAlertDialog(onDismissRequest = { menu = null },
            title = { Text(item.name) }, confirmButton = { TextButton({ menu = null; open(item) }) { Text("Open") } },
            dismissButton = { TextButton({ viewModel.unpinFromSpeedDial(item.id); menu = null }) { Text("Remove shortcut") } })
    }
}
