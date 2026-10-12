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
    familiarArtists: List<FamiliarArtist> = emptyList()
) {
    val savedArtists by library.getSavedArtists().collectAsState(emptyList())
    val followedIds = remember(savedArtists) { savedArtists.map { it.id }.toSet() }
    val favorites by library.getFavoriteTracks().collectAsState(emptyList())
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }
    val state by viewModel.uiState.collectAsState()
    val queue = playback?.queue?.collectAsState()?.value
    val playing = playback?.state?.collectAsState()?.value == VlcAudioPlayer.State.PLAYING
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var menuAlbum by remember { mutableStateOf<PlaylistResult?>(null) }
    val playlists by library.getPlaylists().collectAsState(emptyList())
    val savedAlbums by library.getSavedAlbums().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val youtubeSignedIn by com.auralis.music.data.network.YouTubeSession.signedIn.collectAsState()
    val youtubeAccount by com.auralis.music.data.network.YouTubeSession.accountLabel.collectAsState()
    val remote = remember { DesktopRemoteHome(scope) }
    val remoteState by remote.state.collectAsState()
    LaunchedEffect(youtubeSignedIn, youtubeAccount) { remote.refresh() }
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
        if (playback != null) playback.playFrom(track, tracks) else showMessage("Playback needs VLC")
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
        onSurprise = { viewModel.getRandomSurpriseTrack()?.let { play(it, listOf(it)) } },
        onChip = viewModel::toggleChip,
        onAlbumMenu = { menuAlbum = it },
        onUnpinShortcut = { viewModel.unpinFromSpeedDial(it.id) },
        findMusic = findMusic,
        onRefresh = { if (youtubeSignedIn) remote.refresh() else { paging.reset(state.dynamicSections); viewModel.refresh() } },
        quickPicks = quickPicks,
        familiarArtists = familiarArtists,
        followedArtistIds = followedIds,
        onOpenFamiliarArtist = { a -> browse.openArtist(Artist(id = a.id, name = a.name, thumbnail = a.photo)) },
        onToggleFollowArtist = { a -> scope.launch {
            if (a.id in followedIds) library.removeArtist(a.id)
            else library.saveArtist(com.auralis.music.domain.model.SavedArtist(a.id, a.name, a.photo))
        } },
        favoriteIds = favoriteIds,
        onToggleFavorite = { track -> scope.launch { library.toggleFavorite(track) } },
        serviceShelves = if (youtubeSignedIn) listOfNotNull(tasteShelf ?: remoteState.releases) + remoteState.shelves else null,
        serviceRecents = if (youtubeSignedIn) remoteState.recents else null,
        serviceLoading = if (youtubeSignedIn) remoteState.loading else state.isLoading,
        serviceError = if (youtubeSignedIn) remoteState.error else null,
        libraryShelf = youtubePlaylists.filter { it.id != "VLLM" } +
            playlists.filter { it.isCustom }.map { PlaylistResult("local:${it.id}", it.title,
                it.coverUrl ?: it.tracks.firstOrNull()?.thumbnail, "Playlist · ${it.tracks.size} tracks") } +
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
