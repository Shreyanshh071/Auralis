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
internal fun DesktopHome(
    viewModel: HomeViewModel,
    library: LibraryRepository,
    searchRepository: SearchRepository,
    playback: DesktopPlaybackController?,
    browse: DesktopBrowseState,
    onOpenPlaylist: (String) -> Unit,
    onNavigateToSearch: () -> Unit,
    showMessage: (String) -> Unit,
    findMusic: (@Composable () -> Unit)? = null,
    youtubePlaylists: List<PlaylistResult> = emptyList(),
    onYouTubePlaylist: (PlaylistResult) -> Unit = {},
    /** Albums & singles from the listener's own taste; YouTube's new releases until there is one. */
    tasteShelf: DesktopHomeShelf? = null,
    /** The listener's most-played songs (Quick picks). */
    quickPicks: List<Track> = emptyList(),
    /** The listener's most-played artists (Familiar Artists). */
    familiarArtists: List<FamiliarArtist> = emptyList(),
    /** The listener's name for the greeting above Recents; null greets without one. */
    greetingName: String? = null
) {
    val savedArtists by library.getSavedArtists().collectAsState(emptyList())
    val savedArtistIds = remember(savedArtists) { savedArtists.map { it.id }.toSet() }
    val blocked by com.auralis.music.desktop.library.DesktopBlockedArtists.artists.collectAsState()
    val subscriptions = remember { ArtistSubscriptions() }
    val favorites by library.getFavoriteTracks().collectAsState(emptyList())
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }
    val state by viewModel.uiState.collectAsState()
    val queue = playback?.queue?.collectAsState()?.value
    val playing = playback?.state?.collectAsState()?.value == VlcAudioPlayer.State.PLAYING
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var menuPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var menuAlbum by remember { mutableStateOf<PlaylistResult?>(null) }
    val playlists by library.getPlaylists().collectAsState(emptyList())
    val savedAlbums by library.getSavedAlbums().collectAsState(emptyList())
    var collageUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(playlists) {
        playlists.filter { it.coverUrl.isNullOrBlank() }.forEach { playlist ->
            com.auralis.music.windows.library.playlistCollage(playlist.tracks)?.let { image -> collageUrls = collageUrls + (playlist.id to image) }
        }
    }
    val scope = rememberCoroutineScope()
    val youtubeSignedIn by com.auralis.music.data.network.YouTubeSession.signedIn.collectAsState()
    val youtubeAccount by com.auralis.music.data.network.YouTubeSession.accountLabel.collectAsState()
    val remote = remember { DesktopRemoteHome(scope) }
    val remoteState by remote.state.collectAsState()
    val youtubeSessionRevision by com.auralis.music.data.network.YouTubeSession.revision.collectAsState()
    LaunchedEffect(youtubeSessionRevision) { remote.refresh() }
    LaunchedEffect(youtubeSignedIn, youtubeAccount, familiarArtists.map { it.id }) { subscriptions.load(familiarArtists.map { it.id }) }
    val platform = LocalPlatformActions.current
    val preferences = remember { com.auralis.music.util.PropertiesFileKeyValueStore(
        java.io.File(com.auralis.music.util.desktopAppDataDir(), "desktop-home.properties")) }
    var recentsGrid by remember { mutableStateOf(preferences.getString("recentsGrid", "false") == "true") }
    val paging = remember { DesktopHomePaging { token ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            LocalizedContent.run { com.auralis.music.data.network.InnerTubeClient().getHome(continuation = token).second }
        }
    } }
    val pageState by paging.state.collectAsState()
    LaunchedEffect(state.dynamicSections, state.selectedChip, state.isLoading) {
        paging.reset(if (state.isLoading) emptyList() else state.dynamicSections)
    }
    val play: (Track, List<Track>) -> Unit = { track, tracks ->
        if (playback != null) playback.playFrom(track, tracks, automatic = false) else showMessage("Playback needs VLC")
    }
    fun withAlbumTracks(album: PlaylistResult, action: suspend (List<Track>) -> Unit) {
        scope.launch {
            val tracks = runCatching {
                when {
                    album.id.startsWith("local:") -> playlists.firstOrNull { it.id == album.id.removePrefix("local:") }?.tracks.orEmpty()
                    youtubeSignedIn && album.id.startsWith("VL") -> remote.playlistTracks(album)
                    else -> LocalizedContent.run { searchRepository.getAlbumTracks(album) }
                }
            }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) action(tracks) else showMessage("Couldn't load this album. Please try again.")
        }
    }
    DesktopHomeScreen(
        state = state,
        moreSections = pageState.sections,
        currentTrackId = queue?.current?.id,
        isPlaying = playing,
        onPlay = { track, _ -> if (playback != null) playback.playSingle(track) else showMessage("Playback needs VLC") },
        onShortcut = { item ->
            when (item.type) {
                SpeedDialType.TRACK -> item.track?.let { track ->
                    playback?.playSingle(track)
                }
                SpeedDialType.ALBUM -> {
                    val album = item.album ?: PlaylistResult(item.id.removePrefix("album-"), item.name, item.image, item.artistQuery)
                    if (album.id.startsWith("local:")) onOpenPlaylist(album.id.removePrefix("local:")) else browse.openAlbum(album)
                }
                SpeedDialType.PLAYLIST -> onOpenPlaylist(item.id.removePrefix("playlist-"))
                SpeedDialType.ARTIST -> browse.openArtist(Artist(id = item.id.takeIf { it.startsWith("UC") }.orEmpty(),
                    name = item.artistQuery ?: item.name, thumbnail = item.image))
                else -> Unit
            }
        },
        onAlbum = { album -> when {
            album.id.startsWith("local:") -> onOpenPlaylist(album.id.removePrefix("local:"))
            album.id.startsWith("UC") -> browse.openArtist(Artist(album.id, album.title, album.thumbnail))
            youtubePlaylists.any { it.id == album.id } -> onYouTubePlaylist(album)
            // Any YouTube playlist on Home (Listen again, mixes): open it through the signed-in
            // account, which can read private ones; it is shown, not added to the Library.
            youtubeSignedIn && (album.id.startsWith("VL") || album.id.startsWith("PL") || album.id.startsWith("RD")) ->
                onYouTubePlaylist(if (album.id.startsWith("VL")) album else album.copy(id = "VL" + album.id))
            else -> browse.openAlbum(album)
        } },
        onMenu = { menuTrack = it },
        onSearch = onNavigateToSearch,
        onSurprise = { viewModel.getRandomSurpriseTrack()?.let { playback?.playSingle(it) } },
        onChip = viewModel::toggleChip,
        onAlbumMenu = { album ->
            val local = playlists.firstOrNull { "local:${it.id}" == album.id }
            if (local != null) menuPlaylist = local else menuAlbum = album
        },
        playlistCovers = playlists.associateBy { "local:${it.id}" },
        onShortcutMenu = { item ->
            when (item.type) {
                SpeedDialType.TRACK -> menuTrack = item.track
                SpeedDialType.PLAYLIST -> menuPlaylist = playlists.firstOrNull { it.id == item.id.removePrefix("playlist-") }
                SpeedDialType.ALBUM -> {
                    val album = item.album ?: PlaylistResult(item.id.removePrefix("album-"), item.name, item.image)
                    val local = playlists.firstOrNull { "local:${it.id}" == album.id }
                    if (local != null) menuPlaylist = local else menuAlbum = album
                }
                else -> Unit
            }
        },
        onUnpinShortcut = { viewModel.unpinFromSpeedDial(it.id) },
        shortcutCandidates = playlists.map { p ->
            val album = PlaylistResult("local:${p.id}", p.title, p.coverUrl ?: collageUrls[p.id] ?: p.tracks.firstOrNull()?.thumbnail,
                "Playlist · ${p.tracks.size} songs")
            SpeedDialItem(id = "album-${album.id}", name = p.title, type = SpeedDialType.ALBUM, image = album.thumbnail, album = album)
        } + savedArtists.map { a -> SpeedDialItem(id = a.id, name = a.name, type = SpeedDialType.ARTIST,
            image = a.thumbnail, artistQuery = a.query ?: a.name) } + familiarArtists.map { a -> SpeedDialItem(id = a.id.ifBlank { "yt:${a.name}" }, name = a.name,
            type = SpeedDialType.ARTIST, image = a.photo, artistQuery = a.name) },
        onPinShortcut = { item ->
            when (item.type) {
                SpeedDialType.ALBUM -> item.album?.let { viewModel.togglePinAlbum(it) }
                SpeedDialType.ARTIST -> viewModel.togglePinArtist(Artist(item.id, item.name, item.image))
                else -> Unit
            }
        },
        findMusic = findMusic,
        onRefresh = { if (youtubeSignedIn) remote.refresh() else { paging.reset(state.dynamicSections); viewModel.refresh() } },
        quickPicks = quickPicks.filterNot { com.auralis.music.desktop.library.DesktopBlockedArtists.blocksTrack(it, blocked) },
        familiarArtists = familiarArtists,
        greetingName = greetingName,
        onOpenFamiliarArtist = { a -> browse.openArtist(Artist(id = a.id, name = a.name, thumbnail = a.photo)) },
        familiarActions = FamiliarArtistActions(
            subscribed = { subscriptions.subscribed(it.id) },
            subscribeBusy = { subscriptions.busy(it.id) },
            toggleSubscribe = { a -> scope.launch { subscriptions.toggle(a.id, a.name).takeIf { it.isNotBlank() }?.let(showMessage) } },
            startRadio = { a -> scope.launch {
                showMessage("Starting ${a.name} radio")
                val tracks = artistRadioTracks(searchRepository, Artist(id = a.id, name = a.name, thumbnail = a.photo))
                if (tracks.isEmpty()) showMessage("Couldn't start ${a.name} radio. Try again.") else play(tracks.first(), tracks)
            } },
            isShortcut = { it.id.isNotBlank() && it.id in state.pinnedSpeedDialIds },
            toggleShortcut = { a ->
                if (a.id.isBlank()) showMessage("Couldn't find ${a.name} on YouTube Music")
                else showMessage(if (viewModel.togglePinArtist(Artist(id = a.id, name = a.name, thumbnail = a.photo)))
                    "Added ${a.name} to shortcuts" else "Removed ${a.name} from shortcuts")
            },
            share = { a ->
                if (a.id.isBlank()) showMessage("Couldn't find ${a.name} on YouTube Music")
                else platform.shareText(a.name, "https://music.youtube.com/channel/${a.id}", "Share artist")
            },
            inLibrary = { it.id.isNotBlank() && it.id in savedArtistIds },
            toggleLibrary = { a -> scope.launch {
                when {
                    a.id.isBlank() -> showMessage("Couldn't find ${a.name} on YouTube Music")
                    a.id in savedArtistIds -> { library.removeArtist(a.id); showMessage("Removed ${a.name} from your library") }
                    else -> { library.saveArtist(com.auralis.music.domain.model.SavedArtist(a.id, a.name, a.photo, a.subscribers)); showMessage("Saved ${a.name} to your library") }
                }
            } },
            block = { a ->
                com.auralis.music.desktop.library.DesktopBlockedArtists.block(a.id.ifBlank { null }, a.name)
                showMessage("Blocked ${a.name}. Unblock in Settings > Player & audio.")
            }
        ),
        favoriteIds = favoriteIds,
        onToggleFavorite = { track -> scope.launch { library.toggleFavorite(track) } },
        serviceShelves = if (youtubeSignedIn) (listOfNotNull(tasteShelf ?: remoteState.releases) + remoteState.shelves).map { shelf ->
            // Blocked artists are left out of recommendations (Li Music's block list).
            if (blocked.isEmpty()) shelf else shelf.copy(tracks = shelf.tracks.filterNot {
                com.auralis.music.desktop.library.DesktopBlockedArtists.blocksTrack(it, blocked) },
                albums = shelf.albums.filterNot { it.id.startsWith("UC") &&
                    com.auralis.music.desktop.library.DesktopBlockedArtists.blocksArtist(it.id, it.title, blocked) })
        } else null,
        serviceRecents = if (youtubeSignedIn) remoteState.recents else null,
        serviceLoading = if (youtubeSignedIn) remoteState.loading else state.isLoading,
        serviceError = if (youtubeSignedIn) remoteState.error else null,
        libraryShelf = playlists.filter { it.isCustom }.map { PlaylistResult("local:${it.id}", it.title,
                it.coverUrl ?: collageUrls[it.id] ?: it.tracks.firstOrNull()?.thumbnail, "Playlist · ${it.tracks.size} tracks") } +
            savedAlbums.map { PlaylistResult(it.id, it.title, it.thumbnail, it.artist) },
        loadingMore = if (youtubeSignedIn) remoteState.loadingMore else pageState.loading,
        canLoadMore = if (youtubeSignedIn) remoteState.token != null else pageState.canLoadMore,
        loadMoreFailed = if (youtubeSignedIn) remoteState.error != null else pageState.failed,
        onLoadMore = { scope.launch { if (youtubeSignedIn) remote.loadMore() else paging.loadMore() } },
        recentsGrid = recentsGrid,
        onToggleRecents = {
            recentsGrid = !recentsGrid
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                preferences.putStrings(mapOf("recentsGrid" to recentsGrid.toString()))
            }
        }
    )
    menuTrack?.let { track ->
        DesktopTrackMenu(track, library, playback, browse, viewModel, showMessage, { menuTrack = null })
    }
    menuPlaylist?.let { playlist ->
        com.auralis.music.ui.library.PlaylistOptionsBottomSheet(playlist = playlist,
            onDismiss = { menuPlaylist = null },
            onEditPlaylist = { id, title, desc, cover -> scope.launch { library.updatePlaylist(id, title, desc, cover) } },
            onAddToQueue = { tracks -> tracks.forEach { playback?.addToQueue(it) } },
            onDeletePlaylist = { scope.launch { library.deletePlaylist(playlist.id) }; menuPlaylist = null })
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
            onDownload = { withAlbumTracks(album) { tracks -> platform.downloadPlaylist(album.id.removePrefix("local:"), album.title, tracks, album.thumbnail) } },
            onViewArtist = { album.author?.let { browse.openArtist(Artist(id = "", name = it)) } },
            onDismiss = { menuAlbum = null }
        )
    }
}
