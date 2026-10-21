package com.auralis.music.windows.home

import com.auralis.music.ui.i18n.desktopText

import com.auralis.music.ui.components.TextButton
import androidx.compose.runtime.*
import androidx.compose.material3.*
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.ui.viewmodel.HomeViewModel
import com.auralis.music.desktop.browse.DesktopBrowseState
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.data.network.LocalizedContent
import com.auralis.music.ui.components.AlbumOptionsMenu
import kotlinx.coroutines.launch

/** The same persisted pins used by song, playlist, album and artist menus, visible on every page. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SidebarShortcuts(viewModel: HomeViewModel, library: LibraryRepository,
    playback: DesktopPlaybackController?, browse: DesktopBrowseState, collapsed: Boolean,
    onOpenPlaylist: (String) -> Unit, onBrowse: () -> Unit,
    searchRepository: com.auralis.music.domain.repository.SearchRepository, showMessage: (String) -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val playlists by library.getPlaylists().collectAsState(emptyList())
    val artists by library.getSavedArtists().collectAsState(emptyList())
    val savedAlbums by library.getSavedAlbums().collectAsState(emptyList())
    val savedArtistIds = remember(artists) { artists.map { it.id }.toSet() }
    val scope = rememberCoroutineScope()
    val platform = com.auralis.music.ui.components.LocalPlatformActions.current
    // An artist shortcut's ⋮ opens the Familiar Artists menu (radio, shortcut, share, library, block).
    val artistActions = FamiliarArtistActions(
        subscribed = { null }, subscribeBusy = { false }, toggleSubscribe = {},
        startRadio = { a -> scope.launch {
            showMessage("Starting ${a.name} radio")
            val tracks = artistRadioTracks(searchRepository, Artist(id = a.id, name = a.name, thumbnail = a.photo))
            if (tracks.isEmpty()) showMessage("Couldn't start ${a.name} radio. Try again.") else playback?.playFrom(tracks.first(), tracks)
        } },
        isShortcut = { true },
        toggleShortcut = { a -> viewModel.togglePinArtist(Artist(id = a.id, name = a.name, thumbnail = a.photo)) },
        share = { a -> if (a.id.isNotBlank()) platform.shareText(a.name, "https://music.youtube.com/channel/${a.id}", "Share artist") },
        inLibrary = { it.id.isNotBlank() && it.id in savedArtistIds },
        toggleLibrary = { a -> scope.launch {
            if (a.id in savedArtistIds) library.removeArtist(a.id)
            else library.saveArtist(SavedArtist(a.id, a.name, a.photo, a.subscribers))
        } },
        block = { a ->
            com.auralis.music.desktop.library.DesktopBlockedArtists.block(a.id.ifBlank { null }, a.name)
            showMessage("Blocked ${a.name}. Unblock in Settings > Player & audio.")
        })
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
        playlistCovers = allCovers, sidebar = true, collapsed = collapsed, artistActions = artistActions)
    // ⋮ opens the same options menu the item has everywhere else (Home, Library), at the ⋮.
    menu?.let { item ->
        val track = item.track
        val localId = (item.album?.id ?: item.id.removePrefix("album-")).takeIf { it.startsWith("local:") }?.removePrefix("local:")
            ?: item.id.removePrefix("playlist-").takeIf { item.type == SpeedDialType.PLAYLIST }
        val localPlaylist = localId?.let { id -> playlists.firstOrNull { it.id == id } }
        val album = item.album
        when {
            item.type == SpeedDialType.TRACK && track != null ->
                com.auralis.music.windows.library.DesktopTrackMenu(track, library, playback, browse, viewModel, showMessage, { menu = null })
            localPlaylist != null -> com.auralis.music.ui.library.PlaylistOptionsBottomSheet(playlist = localPlaylist,
                onDismiss = { menu = null },
                onEditPlaylist = { id, title, desc, cover -> scope.launch { library.updatePlaylist(id, title, desc, cover) } },
                onAddToQueue = { tracks -> tracks.forEach { playback?.addToQueue(it) } },
                onDeletePlaylist = { scope.launch { library.deletePlaylist(localPlaylist.id) }; menu = null })
            item.type == SpeedDialType.ALBUM && album != null -> {
                fun withTracks(action: suspend (List<Track>) -> Unit) { scope.launch {
                    val tracks = runCatching { LocalizedContent.run { searchRepository.getAlbumTracks(album) } }.getOrDefault(emptyList())
                    if (tracks.isNotEmpty()) action(tracks) else showMessage("Couldn't load this album. Please try again.")
                } }
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
                    onShuffle = { withTracks { tracks -> tracks.shuffled().let { playback?.playFrom(it.first(), it) } } },
                    onShare = { platform.shareText(album.title, "https://music.youtube.com/playlist?list=${album.id.removePrefix("VL")}", "Share album") },
                    onPlayNext = { withTracks { tracks -> tracks.asReversed().forEach { playback?.playNext(it) } } },
                    onAddToQueue = { withTracks { tracks -> tracks.forEach { playback?.addToQueue(it) } } },
                    onAddToPlaylist = { playlist -> withTracks { tracks -> tracks.forEach { library.addTrackToPlaylist(playlist.id, it) } } },
                    onCreatePlaylistAndAdd = { title -> withTracks { tracks ->
                        val id = library.createPlaylist(title).id
                        tracks.forEach { library.addTrackToPlaylist(id, it) }
                    } },
                    onPinToSpeedDial = { viewModel.togglePinAlbum(album) },
                    onDownload = { withTracks { tracks -> platform.downloadPlaylist(album.id.removePrefix("local:"), album.title, tracks, album.thumbnail) } },
                    onViewArtist = { album.author?.let { onBrowse(); browse.openArtist(Artist(id = "", name = it)) } },
                    onDismiss = { menu = null })
            }
            else -> com.auralis.music.ui.components.GlassAlertDialog(onDismissRequest = { menu = null },
                title = { Text(item.name) }, confirmButton = { TextButton({ menu = null; open(item) }) { Text(desktopText("Open")) } },
                dismissButton = { TextButton({ viewModel.unpinFromSpeedDial(item.id); menu = null }) { Text(desktopText("Remove shortcut")) } })
        }
    }
}
