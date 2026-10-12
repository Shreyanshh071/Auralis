package com.auralis.music.windows

import com.kyant.backdrop.backdrops.layerBackdrop

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.blur
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import javax.imageio.ImageIO
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.first

enum class DesktopDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Explore),
    SEARCH("Search", Icons.Outlined.Search),
    LIBRARY("Library", Icons.Outlined.GridView),
    TOGETHER("Listen Together", Icons.Outlined.Groups),
    HISTORY("History", Icons.Outlined.History),
    STATS("Stats", Icons.Outlined.BarChart),
    WRAPPED("Wrapped", Icons.Outlined.AutoAwesome),
    PROFILE("Profile", Icons.Outlined.AccountCircle),
    SETTINGS("Settings", Icons.Outlined.Settings),
    // Opened from the title bar, not listed in the sidebar.
    IMPORT("Import playlists", Icons.Outlined.PlaylistAdd),
    DISCORD("Discord", Icons.Outlined.Forum),
}

@Composable
fun AuralisDesktopApp(
    searchRepository: com.auralis.music.domain.repository.SearchRepository,
    library: com.auralis.music.domain.repository.LibraryRepository,
    history: com.auralis.music.domain.repository.HistoryRepository,
    homePlatform: com.auralis.music.ui.viewmodel.HomePlatform,
    libraryFlags: com.auralis.music.util.KeyValueStore,
    stores: com.auralis.music.desktop.settings.DesktopDataStores,
    playback: com.auralis.music.desktop.playback.DesktopPlaybackController?,
    lyrics: com.auralis.music.desktop.lyrics.DesktopLyricsController?,
    /** Main points the window's key handler here. */
    onShortcutHandler: (((androidx.compose.ui.input.key.KeyEvent) -> Boolean)?) -> Unit = {},
    selfTest: SelfTest? = null,
    onSelfTestDone: (Boolean) -> Unit = {},
    /** Discord status through the desktop Discord app; null when unavailable. */
    discordControl: com.auralis.music.ui.settings.DiscordControl? = null,
    listeningStats: com.auralis.music.desktop.library.DesktopListeningStats,
    together: com.auralis.music.windows.together.DesktopTogether,
    account: com.auralis.music.windows.profile.DesktopAccount,
    titleBar: @Composable (TitleBarActions) -> Unit = {},
) {
    var showPlayer by remember { mutableStateOf(false) }
    var playerPanel by remember { mutableStateOf(com.auralis.music.windows.player.PlayerPanel.LYRICS) }
    var searchFocusRequests by remember { mutableStateOf(0) }
    var typing by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(DesktopDestination.HOME) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val searchState = remember { com.auralis.music.windows.search.DesktopSearchState(searchRepository, scope) { !stores.privacy.settingsFlow.first().pauseSearchHistory } }
    val browse = remember { com.auralis.music.desktop.browse.DesktopBrowseState(searchRepository, scope) }
    val navigation = remember { DesktopNavigation() }
    val browsePages = browse.stack.toList()
    androidx.compose.runtime.LaunchedEffect(destination, browsePages) { navigation.record(DesktopRoute(destination, browsePages)) }
    fun restoreRoute(route: DesktopRoute?) {
        if (route == null) return
        showPlayer = false
        destination = route.destination
        browse.stack.clear()
        val last = route.pages.lastOrNull()
        when {
            last is com.auralis.music.desktop.browse.BrowsePage.Album && last.isLoading -> {
                browse.stack.addAll(route.pages.dropLast(1))
                browse.openAlbum(last.album)
            }
            last is com.auralis.music.desktop.browse.BrowsePage.Artist && last.isLoading -> {
                browse.stack.addAll(route.pages.dropLast(1))
                browse.openArtist(last.page.artist)
            }
            else -> browse.stack.addAll(route.pages)
        }
    }
    val accountState by account.state.collectAsState()
    val youtubeSignedIn by com.auralis.music.data.network.YouTubeSession.signedIn.collectAsState()
    val youtubeLabel by com.auralis.music.data.network.YouTubeSession.accountLabel.collectAsState()
    val youtubeLibrary = remember { com.auralis.music.windows.profile.DesktopYouTubeLibrary(scope) }
    val youtubeState by youtubeLibrary.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(youtubeSignedIn, youtubeLabel) { youtubeLibrary.refresh() }
    var showSignIn by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var showYouTubeAccounts by remember { mutableStateOf(false) }
    // Bumped by the title bar's refresh: the current page is rebuilt, re-running all its loads.
    var refreshTick by remember { mutableStateOf(0) }
    var youtubePageAccount by remember { mutableStateOf<String?>(null) }
    var youtubePageId by remember { mutableStateOf<String?>(null) }
    // Albums & singles that follow what the listener plays (see TasteReleases).
    // Background lookups queue behind one paced gate (a launch burst got the network blocked).
    val backgroundSearch = remember(searchRepository) { com.auralis.music.windows.home.PacedSearchRepository(searchRepository) }
    val tasteReleases = remember { com.auralis.music.windows.home.TasteReleases(listeningStats, backgroundSearch, scope).also { it.start() } }
    val tasteShelf by tasteReleases.shelf.collectAsState()
    // Quick picks: most played in the last 30 days (all time when that's too few), by plays then time.
    val allListens by listeningStats.listens.collectAsState()
    val quickPicks = remember(allListens) {
        fun ranked(listens: List<com.auralis.music.desktop.library.DesktopListeningStats.Listen>) =
            listens.groupBy { com.auralis.music.desktop.library.DesktopListeningStats.songKey(it.track) }.values
                .sortedWith(compareByDescending<List<com.auralis.music.desktop.library.DesktopListeningStats.Listen>> { l -> l.count { it.countsAsPlay } }
                    .thenByDescending { l -> l.sumOf { it.timeMs } })
                .map { l -> l.groupBy { it.track.id }.values.maxBy { v -> v.sumOf { it.timeMs } }.first().track }
        val recent = ranked(allListens.filter { it.startedAt >= System.currentTimeMillis() - 30L * 86_400_000L })
        (if (recent.size >= 12) recent else (recent + ranked(allListens)).distinctBy { it.id }).take(12)
    }
    // Familiar Artists (Li Music): top artists by the listener's own play count, with their photo
    // and channel. Portraits load independently of YouTube and successful lookups survive restarts.
    val artistLookups = remember { androidx.compose.runtime.mutableStateMapOf<String, com.auralis.music.domain.model.Artist?>() }
    val blockedArtists by com.auralis.music.desktop.library.DesktopBlockedArtists.artists.collectAsState()
    val topArtistPlays = remember(allListens, blockedArtists) {
        allListens.filter { it.countsAsPlay && !com.auralis.music.desktop.library.DesktopBlockedArtists.blocksTrack(it.track, blockedArtists) }
            .groupBy { com.auralis.music.domain.search.SearchQueryMatcher.normalize(
                it.track.artist.split(Regex("""\s*(?:,|&| feat\.? | ft\.? | x )\s*""", RegexOption.IGNORE_CASE)).first().removeSuffix(" - Topic")) }
            .filterKeys { it.isNotBlank() && it != "unknown artist" }
            .map { (_, l) -> l.first().track.artist.split(Regex("""\s*(?:,|&| feat\.? | ft\.? | x )\s*""", RegexOption.IGNORE_CASE)).first().trim().removeSuffix(" - Topic") to l.size }
            .sortedByDescending { it.second }.take(10)
    }
    val artistLookup = remember {
        val portraitClient = com.auralis.music.data.network.InnerTubeClient()
        com.auralis.music.windows.home.FamiliarArtistLookup(
            com.auralis.music.util.PropertiesFileKeyValueStore(java.io.File(com.auralis.music.util.desktopAppDataDir(), "cache/artist-portraits.properties")),
            backgroundSearch::searchArtists, portraitClient::fetchWikipediaArtistPortrait)
    }
    androidx.compose.runtime.LaunchedEffect(topArtistPlays.map { it.first }) {
        topArtistPlays.forEach { (name, _) ->
            artistLookup.cached(name)?.let { artistLookups[name] = it }
            launch {
                while (true) {
                    artistLookup.resolve(name) { artistLookups[name] = it }
                    val found = artistLookups[name]
                    if (!found?.id.isNullOrBlank() && !found?.thumbnail.isNullOrBlank()) break
                    kotlinx.coroutines.delay(60_000L)
                }
            }
        }
    }
    val familiarArtists = topArtistPlays.map { (name, plays) ->
        val found = artistLookups[name]
        com.auralis.music.windows.home.FamiliarArtist(found?.name ?: name, plays, found?.id.orEmpty(), found?.thumbnail)
    }.filterNot { com.auralis.music.desktop.library.DesktopBlockedArtists.blocksArtist(it.id.ifBlank { null }, it.name, blockedArtists) }.take(10)
    val homeViewModel = remember {
        com.auralis.music.ui.viewmodel.HomeViewModel(
            historyRepository = history,
            searchRepository = backgroundSearch,
            platform = homePlatform,
            libraryRepository = library
        )
    }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val showUnavailable: (String) -> Unit = { message ->
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) }
    }
    fun openYouTubePlaylist(playlist: com.auralis.music.domain.model.PlaylistResult) {
        showPlayer = false
        youtubePageAccount = youtubeState.activeId
        youtubePageId = playlist.id
        val expectedAccount = youtubeState.activeId
        var page = com.auralis.music.desktop.browse.BrowsePage.Album(playlist)
        browse.stack += page
        fun update(tracks: List<com.auralis.music.domain.model.Track>) {
            if (youtubeLibrary.state.value.activeId != expectedAccount) return
            val index = browse.stack.indexOfFirst { it === page }
            if (index >= 0) {
                page = page.copy(tracks = tracks, isLoading = false)
                browse.stack[index] = page
            }
        }
        scope.launch {
            try {
                val tracks = youtubeLibrary.tracks(playlist) { partial -> scope.launch { update(partial) } }
                update(tracks)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { update(page.tracks); showUnavailable("Couldn't finish loading this YouTube playlist. Try opening it again.") }
        }
    }
    androidx.compose.runtime.LaunchedEffect(youtubeState.activeId) {
        if (youtubePageAccount != null && youtubePageAccount != youtubeState.activeId &&
            (browse.top as? com.auralis.music.desktop.browse.BrowsePage.Album)?.album?.id == youtubePageId) browse.clear()
    }
    androidx.compose.runtime.LaunchedEffect(playback) { playback?.errors?.collect { showUnavailable(it) } }
    androidx.compose.runtime.LaunchedEffect(together) {
        together.state.map { it.message }.distinctUntilChanged().collect { message -> message?.let { showUnavailable(it) } }
    }
    val platformActions = remember { com.auralis.music.ui.components.DesktopPlatformActions(showUnavailable) }
    val libraryViewModel = remember {
        com.auralis.music.ui.viewmodel.LibraryViewModel(
            libraryRepository = library,
            historyRepository = history,
            statsRepository = com.auralis.music.desktop.library.DesktopStatsRepository(listeningStats),
            platform = com.auralis.music.desktop.library.DesktopLibraryPlatform(libraryFlags, showUnavailable)
        )
    }
    // Signing in with the phone's Auralis account brings its playlists, likes and stats here;
    // the title bar's refresh pulls again.
    androidx.compose.runtime.LaunchedEffect(accountState.profile?.uid, refreshTick) {
        if (accountState.profile?.uid.isNullOrBlank()) return@LaunchedEffect
        try {
            account.pullCloudBackup(library, listeningStats)?.let { if (refreshTick == 0) showUnavailable(it) }
            libraryViewModel.refresh()
            // Then send this PC's side: the merged library, and listens measured here.
            account.pushLibrary(library)
            account.pushListens(listeningStats)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: Exception) { com.auralis.music.util.Log.w("AccountSync", "Couldn't sync with the account: ${e.message}") }
    }
    // While signed in, keep the account current: library edits a few seconds after they happen,
    // listening within about 20 seconds, and the phone's new listening each minute.
    androidx.compose.runtime.LaunchedEffect(accountState.profile?.uid) {
        if (accountState.profile?.uid.isNullOrBlank()) return@LaunchedEffect
        launch {
            kotlinx.coroutines.flow.combine(library.getPlaylists(), library.getFavoriteTracks(),
                library.getSavedAlbums(), library.getSavedArtists()) { a, b, c, d -> listOf<Any>(a, b, c, d) }
                .distinctUntilChanged()
                .collectLatest {
                    kotlinx.coroutines.delay(5_000)
                    runCatching { account.pushLibrary(library) }
                        .onFailure { com.auralis.music.util.Log.w("AccountSync", "Library upload failed: ${it.message}") }
                }
        }
        launch {
            // The recorder saves every 5 s while playing, so sample rather than debounce.
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            listeningStats.listens.sample(20_000).collect {
                runCatching { account.pushListens(listeningStats) }
                    .onFailure { com.auralis.music.util.Log.w("AccountSync", "Listening upload failed: ${it.message}") }
            }
        }
        while (true) {
            kotlinx.coroutines.delay(60_000)
            runCatching { account.pullNewListens(listeningStats) }
                .onFailure { com.auralis.music.util.Log.w("AccountSync", "Listening check failed: ${it.message}") }
        }
    }
    // The song options sheet, for song rows that hand their menu to the window (search, playlists).
    var menuTrack by remember { mutableStateOf<com.auralis.music.domain.model.Track?>(null) }
    val queue = playback?.queue?.collectAsState()?.value
    val playerState = playback?.state?.collectAsState()?.value
    val canShowPlayer = playback != null && lyrics != null && queue?.current != null

    val actions = remember(playback) {
        object : ShortcutActions {
            override fun togglePlayPause() { playback?.togglePlayPause() }
            override fun next() { playback?.next() }
            override fun previous() { playback?.previous() }
            override fun seekBy(deltaMs: Long) { playback?.seekBy(deltaMs) }
            override fun volumeBy(delta: Float) { playback?.volumeBy(delta) }
            override fun toggleMute() { playback?.toggleMute() }
            override fun focusSearch() {
                showPlayer = false
                destination = DesktopDestination.SEARCH
                searchFocusRequests++
            }
            override fun togglePlayer() { showPlayer = !showPlayer && playback?.queue?.value?.current != null }
            override fun showLyrics() = openPanel(com.auralis.music.windows.player.PlayerPanel.LYRICS)
            override fun showQueue() = openPanel(com.auralis.music.windows.player.PlayerPanel.QUEUE)
            private fun openPanel(panel: com.auralis.music.windows.player.PlayerPanel) {
                if (playback?.queue?.value?.current == null) return
                playerPanel = panel
                showPlayer = true
            }
            override fun closePlayer(): Boolean {
                // Esc: closes the player first, then goes back like the phone's back gesture.
                if (!showPlayer) return com.auralis.music.ui.components.DesktopBackDispatcher.back()
                showPlayer = false
                return true
            }
        }
    }
    if (selfTest != null) {
        androidx.compose.runtime.LaunchedEffect(selfTest) {
            val passed = selfTest.run(searchState, playback, lyrics, openPlayer = { showPlayer = true })
            onSelfTestDone(passed)
        }
    }
    // The player covers the search field, so it must not keep the keyboard.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(showPlayer) { if (showPlayer) focusManager.clearFocus() }
    val currentTyping = androidx.compose.runtime.rememberUpdatedState(typing && !showPlayer)
    androidx.compose.runtime.DisposableEffect(actions) {
        onShortcutHandler { event -> handleShortcut(event, currentTyping.value, actions) }
        onDispose { onShortcutHandler(null) }
    }

    val appearance by stores.appearance.settingsFlow.collectAsState(stores.appearance.getInitialSettings())
    // One-time move to Spicy Lyrics' player (asked for on 2026-10-11): the Dynamic background and
    // left-aligned lyrics. Later choices in Appearance are kept.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (libraryFlags.getString("spicy_player_defaults", "0") != "1") {
            val current = stores.appearance.settingsFlow.first()
            stores.appearance.updateSettings(current.copy(playerBackgroundStyle = "Dynamic", lyricsTextPosition = "Left"))
            libraryFlags.putStrings(mapOf("spicy_player_defaults" to "1"))
        }
        // The Spicy Lyrics style was removed (2026-10-12); whoever had it moves to Auralis (Fluid).
        stores.appearance.settingsFlow.first().let { current ->
            if (current.lyricsAnimation.contains("spicy", ignoreCase = true)) stores.appearance.updateSettings(
                current.copy(lyricsAnimation = com.auralis.music.domain.model.LyricsAnimationMode.FLUID.displayName))
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        destination = when (stores.appearance.settingsFlow.first().defaultOpenTab) {
            "Search" -> DesktopDestination.SEARCH
            "Library" -> DesktopDestination.LIBRARY
            else -> DesktopDestination.HOME
        }
    }
    val settingsPlatform = remember(playback) {
        com.auralis.music.windows.settings.DesktopSettingsPlatform(playback, history, searchRepository, discord = discordControl)
    }
    val backgroundLibrary by libraryViewModel.uiState.collectAsState()
    val playlistCover = when (val page = browse.top) {
        is com.auralis.music.desktop.browse.BrowsePage.Album -> page.album.thumbnail
            ?: page.tracks.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail
        null -> if (destination == DesktopDestination.LIBRARY) backgroundLibrary.selectedPlaylist?.let { playlist ->
            playlist.coverUrl?.takeIf { it.isNotBlank() }
                ?: playlist.tracks.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail
        } else null
        else -> null
    }
    // Artwork backgrounds are always active, independent of the selected theme mode.
    val backgroundArtwork = queue?.current?.thumbnail?.takeIf { it.isNotBlank() } ?: playlistCover
    val artworkPalette = com.auralis.music.windows.settings.rememberArtworkPalette(backgroundArtwork ?: queue?.current?.thumbnail)
    val initialSidebars = remember { SidebarLayoutPreferences.load() }
    var leftSidebarWidth by remember { mutableStateOf(initialSidebars.left) }
    var rightSidebarWidth by remember { mutableStateOf(initialSidebars.right) }
    var leftCollapsed by remember { mutableStateOf(initialSidebars.leftCollapsed) }
    // The Your Library row last opened, highlighted while its page shows.
    var selectedLibraryKey by remember { mutableStateOf<String?>(null) }
    // The playlist whose options are open (right-click in Your Library).
    var playlistMenu by remember { mutableStateOf<com.auralis.music.domain.model.Playlist?>(null) }
    var rightCollapsed by remember { mutableStateOf(initialSidebars.rightCollapsed) }
    // The right panel shows Now playing, or lyrics / the queue from the player bar's buttons.
    var sidePanel by remember { mutableStateOf(com.auralis.music.windows.player.SidePanel.NOW_PLAYING) }
    var resizingLeft by remember { mutableStateOf(false) }
    var resizingRight by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(leftSidebarWidth, rightSidebarWidth, leftCollapsed, rightCollapsed) {
        kotlinx.coroutines.delay(400)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SidebarLayoutPreferences.save(SidebarLayout(leftSidebarWidth, rightSidebarWidth, leftCollapsed, rightCollapsed))
        }
    }
    val nativeDensity = androidx.compose.ui.platform.LocalDensity.current
    val densityScale = when (appearance.displayDensity) { "Compact (85%)" -> 0.85f; "Large (115%)" -> 1.15f; else -> 1f }
    com.auralis.music.ui.theme.AuralisDesktopTheme(appearanceSettings = appearance, artworkPalette = artworkPalette, platformActions = platformActions) {
    val popupGlass = rememberPopupGlassSource()
    com.auralis.music.windows.player.ArtworkAppBackground(backgroundArtwork, artworkPalette,
        modifier = Modifier.recordPopupGlass(popupGlass)) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPopupGlassSource provides popupGlass,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(nativeDensity.density * densityScale, nativeDensity.fontScale),
        com.auralis.music.data.datastore.LocalSettingsStores provides stores,
        com.auralis.music.ui.settings.LocalSettingsPlatform provides settingsPlatform,
        com.auralis.music.ui.theme.LocalAppearanceSettingsStore provides stores.appearance,
        // Spotify sign-in reads the session from the user's own browser (no embedded browser here).
        com.auralis.music.ui.screens.LocalWebSignIn provides remember {
            com.auralis.music.ui.screens.WebSignIn(spotifySignIn = { signedIn, dismiss ->
                com.auralis.music.windows.profile.DesktopSpotifySignIn(signedIn, dismiss)
            })
        },
        com.auralis.music.ui.components.LocalTrackLinks provides com.auralis.music.ui.components.TrackLinks(
            openArtist = { name -> showPlayer = false; browse.openArtist(com.auralis.music.domain.model.Artist(id = "", name = name)) },
            openAlbum = { track -> showPlayer = false; browse.openTrackAlbum(track) { showUnavailable("Couldn't find that album") } }
        ),
        // Song menus frost the app's own backdrop (like the search dropdown), not the clicked song's cover.
        com.auralis.music.ui.components.LocalTrackContextMenu provides { items, _, dismiss ->
            com.auralis.music.windows.library.DesktopSongContextMenu(items, dismiss) {
                com.auralis.music.windows.library.SongMenuFrost(if (liquidify) queue?.current?.thumbnail else null)
            }
        }
    ) {
        if (showCreatePlaylist) com.auralis.music.windows.library.CreatePlaylistDialog(
            youtubeAvailable = youtubeSignedIn && youtubeState.identity != null && !youtubeState.loading && youtubeState.error == null,
            accountName = youtubeState.identity?.name,
            onDismiss = { showCreatePlaylist = false },
            onCreate = { name, onYouTube ->
                if (onYouTube) {
                    val created = youtubeLibrary.createPlaylist(name)
                    com.auralis.music.windows.library.retainCreatedYouTubePlaylist(library, created)
                    openYouTubePlaylist(created)
                } else {
                    val created = library.createPlaylist(name)
                    browse.clear(); showPlayer = false
                    libraryViewModel.selectPlaylist(created.id, created)
                    destination = DesktopDestination.LIBRARY
                }
                showUnavailable("Playlist created")
            })
        if (showSignIn) com.auralis.music.windows.profile.DesktopSignInDialog(account,
            onConnected = { youtubeLibrary.refresh(force = true) },
            onDisconnected = { youtubeState.activeId?.let(youtubeLibrary::removeAccount); youtubeLibrary.refresh(force = true) }) { showSignIn = false }
        // Blur the actual page beneath sign-in, rather than letting crisp rows show through
        // its translucent surface. No screenshot/readback is needed to open the dialog.
        Surface(modifier = Modifier.fillMaxSize().then(
            if (showSignIn || showYouTubeAccounts) Modifier.blur(18.dp) else Modifier
        ), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                titleBar(TitleBarActions(
                    canGoBack = showPlayer || navigation.canGoBack,
                    canGoForward = navigation.canGoForward,
                    back = { if (showPlayer) showPlayer = false else restoreRoute(navigation.back()) },
                    forward = { restoreRoute(navigation.forward()) },
                    refresh = {
                        refreshTick++
                        when (val page = browse.top) {
                            is com.auralis.music.desktop.browse.BrowsePage.Artist -> browse.retryArtist()
                            is com.auralis.music.desktop.browse.BrowsePage.Album -> {
                                browse.pop()
                                if (youtubeState.playlists.any { it.id == page.album.id }) openYouTubePlaylist(page.album)
                                else browse.openAlbum(page.album)
                            }
                            null -> when (destination) {
                                DesktopDestination.SEARCH -> if (searchState.query.isNotBlank()) searchState.search(searchState.query)
                                DesktopDestination.LIBRARY -> scope.launch { libraryViewModel.refresh() }
                                DesktopDestination.HOME -> homeViewModel.refresh()
                                else -> Unit
                            }
                        }
                    },
                    accountLabel = if (youtubeSignedIn) youtubeState.identity?.name ?: "YouTube Music" else accountState.profile?.displayName?.takeIf { it.isNotBlank() } ?: "Sign in",
                    accountAvatar = if (youtubeSignedIn) youtubeState.identity?.avatar else null,
                    accountMenu = {
                        if (showYouTubeAccounts) com.auralis.music.windows.profile.YouTubeAccountMenu(youtubeState,
                            onSelect = { youtubeLibrary.switchAccount(it); showYouTubeAccounts = false },
                            onRemove = youtubeLibrary::removeAccount,
                            onAdd = { showYouTubeAccounts = false; showSignIn = true },
                            onSettings = { showYouTubeAccounts = false; browse.clear(); destination = DesktopDestination.SETTINGS },
                            onRefresh = { youtubeLibrary.refresh(force = true) }, onDismiss = { showYouTubeAccounts = false })
                    },
                    openAccount = { if (youtubeSignedIn) showYouTubeAccounts = true else showSignIn = true },
                    openTogether = { showPlayer = false; browse.clear(); destination = DesktopDestination.TOGETHER },
                    openSettings = { showPlayer = false; browse.clear(); destination = DesktopDestination.SETTINGS },
                    search = {
                        com.auralis.music.windows.search.FindMusicDropdown(
                            bar = true,
                            focusRequests = searchFocusRequests,
                            state = searchState,
                            currentTrackId = queue?.current?.id,
                            isPlaying = playerState == com.auralis.music.desktop.playback.VlcAudioPlayer.State.PLAYING,
                            onPlay = { track, tracks -> playback?.playFrom(track, tracks) ?: showUnavailable("Playback needs VLC (64-bit)") },
                            onSearch = { query -> searchState.search(query); showPlayer = false; browse.clear(); destination = DesktopDestination.SEARCH },
                            onPlayNext = { playback?.playNext(it) },
                            onAddToQueue = { playback?.addToQueue(it) },
                            onMenuClick = { menuTrack = it },
                            onTypingChange = { typing = it },
                            liquidify = true,
                            artwork = queue?.current?.thumbnail
                        )
                    },
                    openImport = { showPlayer = false; browse.clear(); destination = DesktopDestination.IMPORT },
                    openDiscord = { showPlayer = false; browse.clear(); destination = DesktopDestination.DISCORD },
                    openStats = { showPlayer = false; browse.clear(); destination = DesktopDestination.STATS },
                    home = { showPlayer = false; browse.clear(); destination = DesktopDestination.HOME },
                    canRefresh = true
                ))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                  val showNowPlaying = queue?.current != null && playback != null && lyrics != null
                  val leftTarget = if (leftCollapsed) 64f else sidebarWidth(leftSidebarWidth, 180f, 320f)
                  val rightLimit = (maxWidth.value - leftTarget - 360f - 6f).coerceAtMost(460f)
                  val rightIsCollapsed = (rightCollapsed && sidePanel == com.auralis.music.windows.player.SidePanel.NOW_PLAYING) || rightLimit < 280f
                  val rightTarget = if (!showNowPlaying) 0f else if (rightIsCollapsed) 40f else sidebarWidth(rightSidebarWidth, 280f, rightLimit)
                  val leftLimit = (maxWidth.value - rightTarget - 360f - 12f).coerceIn(180f, 320f)
                  val leftDisplay by androidx.compose.animation.core.animateDpAsState(
                      (if (leftCollapsed) 64f else sidebarWidth(leftSidebarWidth, 180f, leftLimit)).dp,
                      if (resizingLeft) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(280), label = "left sidebar width")
                  val rightDisplay by androidx.compose.animation.core.animateDpAsState(rightTarget.dp,
                      if (resizingRight) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(280), label = "right sidebar width")
                  // Spotify-style layout: Your Library, page and Now playing are rounded panels with even gaps.
                  val panelShape = RoundedCornerShape(12.dp)
                  // Your Library (left panel): Liked Songs, then the playlists you imported or synced from the
                  // phone (the local library). The YouTube account's own playlists stay out until imported.
                  val appLibrary by libraryViewModel.uiState.collectAsState()
                  fun openLibrary(key: String, select: () -> Unit) {
                      showPlayer = false; browse.clear(); selectedLibraryKey = key; select(); destination = DesktopDestination.LIBRARY
                  }
                  val libraryEntries = buildList {
                      add(LibraryEntry("liked", "Liked Songs", "Playlist · ${appLibrary.favorites.size} songs", null, liked = true) {
                          openLibrary("liked") { libraryViewModel.openSmartCollection(com.auralis.music.ui.viewmodel.SmartCollectionType.LIKED) }
                      })
                      appLibrary.playlists.forEach { p ->
                          // Where it came from, so two imports with the same name can be told apart.
                          val source = when (com.auralis.music.windows.library.desktopPlaylistSource(p)) {
                              com.auralis.music.ui.library.LibrarySourceFilter.SPOTIFY -> "Spotify"
                              com.auralis.music.ui.library.LibrarySourceFilter.YOUTUBE -> "YouTube Music"
                              else -> "Playlist"
                          }
                          add(LibraryEntry("local:${p.id}", p.title, "$source · ${p.tracks.size} songs", p.coverUrl ?: p.tracks.firstOrNull()?.thumbnail) {
                              openLibrary("local:${p.id}") { libraryViewModel.selectPlaylist(p.id, p) }
                          })
                      }
                  }.distinctBy { it.key }
                  Row(Modifier.fillMaxSize().padding(8.dp)) {
                    Box(Modifier.width(leftDisplay).fillMaxHeight().clip(panelShape)) {
                        NavigationSidebar(
                            selected = if (browse.top != null) null else when (destination) {
                                DesktopDestination.HOME -> SidebarPage.HOME
                                DesktopDestination.HISTORY -> SidebarPage.HISTORY
                                DesktopDestination.LIBRARY -> if (appLibrary.selectedSmartCollection == com.auralis.music.ui.viewmodel.SmartCollectionType.DOWNLOADED) SidebarPage.DOWNLOADS else SidebarPage.LIBRARY
                                else -> null
                            }, collapsed = leftCollapsed, onToggle = { leftCollapsed = !leftCollapsed },
                            onSelect = { page ->
                                if (page != SidebarPage.NEW_PLAYLIST) { showPlayer = false; browse.clear(); selectedLibraryKey = null }
                                when (page) {
                                    SidebarPage.NEW_PLAYLIST -> showCreatePlaylist = true
                                    SidebarPage.HOME -> destination = DesktopDestination.HOME
                                    SidebarPage.HISTORY -> destination = DesktopDestination.HISTORY
                                    SidebarPage.LIBRARY -> {
                                        libraryViewModel.closeSmartCollection(); libraryViewModel.selectPlaylist(null)
                                        destination = DesktopDestination.LIBRARY
                                    }
                                    SidebarPage.DOWNLOADS -> {
                                        libraryViewModel.openSmartCollection(com.auralis.music.ui.viewmodel.SmartCollectionType.DOWNLOADED)
                                        destination = DesktopDestination.LIBRARY
                                    }
                                }
                            })
                    }
                    if (!leftCollapsed) SidebarResizeHandle(
                        onDrag = { leftSidebarWidth = sidebarWidth(leftSidebarWidth + it, 180f, leftLimit) },
                        onDragging = { resizingLeft = it })
                    else Spacer(Modifier.width(8.dp))
                    val dockBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
                    Box(Modifier.weight(1f).fillMaxHeight().clip(panelShape).background(MaterialTheme.colorScheme.surface)) {
                        Box(Modifier.fillMaxSize().layerBackdrop(dockBackdrop)) {
                        androidx.compose.runtime.key(refreshTick) {
                        if (browse.top != null) com.auralis.music.windows.browse.BrowsePages(browse, playback, showUnavailable, libraryViewModel, library)
                        else when (destination) {
                            DesktopDestination.HOME -> com.auralis.music.windows.home.DesktopHome(
                                viewModel = homeViewModel,
                                library = library,
                                searchRepository = searchRepository,
                                playback = playback,
                                browse = browse,
                                onOpenPlaylist = { id ->
                                    libraryViewModel.selectPlaylist(id)
                                    destination = DesktopDestination.LIBRARY
                                },
                                onNavigateToSearch = { destination = DesktopDestination.SEARCH },
                                showMessage = showUnavailable,
                                youtubePlaylists = youtubeState.playlists,
                                onYouTubePlaylist = ::openYouTubePlaylist,
                                tasteShelf = tasteShelf,
                                quickPicks = quickPicks,
                                familiarArtists = familiarArtists,
                                // The greeting's name: the Auralis account's, else the YouTube account's.
                                greetingName = accountState.profile?.displayName?.takeIf { it.isNotBlank() }
                                    ?: youtubeState.identity?.name?.takeIf { youtubeSignedIn && it.isNotBlank() },
                                // Search lives in the title bar now; Home shows no search button.
                                findMusic = {},
                            )
                            DesktopDestination.SEARCH -> com.auralis.music.windows.search.SearchScreen(
                                state = searchState,
                                currentTrackId = queue?.current?.id,
                                isPlaying = playerState == com.auralis.music.desktop.playback.VlcAudioPlayer.State.PLAYING,
                                onPlay = playback?.let { it::playFrom },
                                onUnavailable = showUnavailable,
                                onOpenArtist = browse::openArtist,
                                onOpenAlbum = { browse.openAlbum(it) },
                                onPlayNext = { track ->
                                    playback?.playNext(track)
                                    showUnavailable(com.auralis.music.ui.i18n.str(com.auralis.music.ui.i18n.Strings.playing_next_x, track.title))
                                },
                                onAddToQueue = { track ->
                                    playback?.addToQueue(track)
                                    showUnavailable(com.auralis.music.ui.i18n.str(com.auralis.music.ui.i18n.Strings.added_to_queue_x, track.title))
                                },
                                onMenuClick = { menuTrack = it },
                                focusRequests = searchFocusRequests,
                                onTypingChange = { typing = it }
                            )
                            DesktopDestination.TOGETHER -> com.auralis.music.windows.together.ListenTogetherPage(together) {
                                destination = DesktopDestination.HOME
                            }
                            DesktopDestination.HISTORY -> {
                                val entries by history.getHistory().collectAsState(emptyList())
                                com.auralis.music.windows.history.HistorySheet(
                                    history = entries, currentTrack = queue?.current,
                                    isPlaying = playerState == com.auralis.music.desktop.playback.VlcAudioPlayer.State.PLAYING,
                                    onTrackClick = { track, list -> playback?.playFrom(track, list) },
                                    onRemoveFromHistory = { id -> scope.launch { history.removeFromHistory(id) } },
                                    onClearHistory = { scope.launch { history.clearHistory() } },
                                    onDismiss = { destination = DesktopDestination.HOME },
                                    onPlayNext = { playback?.playNext(it) }, onAddToQueue = { playback?.addToQueue(it) }
                                )
                            }
                            DesktopDestination.STATS, DesktopDestination.WRAPPED -> com.auralis.music.windows.history.ListeningStatsPage(
                                listeningStats, playback, destination == DesktopDestination.WRAPPED,
                                onBack = { navigation.back()?.let(::restoreRoute) ?: run { destination = DesktopDestination.HOME } },
                                onOpenWrapped = { destination = DesktopDestination.WRAPPED },
                                search = backgroundSearch,
                                onOpenArtist = browse::openArtist,
                                onTrackMenu = { menuTrack = it }
                            )
                            DesktopDestination.PROFILE -> com.auralis.music.windows.profile.DesktopProfile(
                                account = account,
                                stores = stores,
                                libraryViewModel = libraryViewModel,
                                history = history,
                                search = searchRepository,
                                hasActiveTrack = queue?.current != null,
                                onDismiss = { destination = DesktopDestination.HOME }
                            )
                            DesktopDestination.SETTINGS -> com.auralis.music.windows.profile.DesktopSettings(
                                stores = stores,
                                history = history,
                                search = searchRepository,
                                hasActiveTrack = queue?.current != null,
                                onOpenProfile = { destination = DesktopDestination.PROFILE },
                                onDismiss = { destination = DesktopDestination.HOME }
                            )
                            DesktopDestination.IMPORT -> {
                                val libraryState by libraryViewModel.uiState.collectAsState()
                                com.auralis.music.ui.screens.ImportPlaylistsScreen(
                                    onDismiss = { navigation.back()?.let(::restoreRoute) ?: run { destination = DesktopDestination.HOME } },
                                    onNavigateToYouTubeAccount = { showSignIn = true },
                                    onImportYouTubeLibraryPlaylists = libraryViewModel::importYouTubeLibraryPlaylists,
                                    isImportingYouTube = libraryState.isImporting,
                                    youtubeImportMessage = libraryState.importMessage,
                                    onClearYouTubeImportMessage = libraryViewModel::clearYouTubeImportMessage,
                                    onImportYouTubePlaylist = libraryViewModel::importYouTubePlaylist,
                                    onImportSpotifyPlaylist = { libraryViewModel.importSpotifyPlaylist(it) },
                                    onImportSpotifyLibraryPlaylists = libraryViewModel::importSpotifyLibraryPlaylists,
                                    isImportingSpotify = libraryState.isImportingSpotify,
                                    spotifyImportMessage = libraryState.spotifyImportMessage,
                                    onClearSpotifyImportMessage = libraryViewModel::clearSpotifyImportMessage)
                            }
                            DesktopDestination.DISCORD -> com.auralis.music.ui.screens.DiscordIntegrationScreen(
                                onDismiss = { navigation.back()?.let(::restoreRoute) ?: run { destination = DesktopDestination.HOME } })
                            DesktopDestination.LIBRARY -> com.auralis.music.windows.library.DesktopLibrary(
                                viewModel = libraryViewModel,
                                homeViewModel = homeViewModel,
                                library = library,
                                playback = playback,
                                browse = browse,
                                onOpenProfile = { destination = DesktopDestination.PROFILE },
                                onOpenHistory = { destination = DesktopDestination.HISTORY },
                                onOpenListenTogether = { destination = DesktopDestination.TOGETHER },
                                showMessage = showUnavailable
                            )
                        }
                        }
                        menuTrack?.let { track ->
                            com.auralis.music.windows.library.DesktopTrackMenu(
                                track = track,
                                library = library,
                                playback = playback,
                                browse = browse,
                                homeViewModel = homeViewModel,
                                showMessage = showUnavailable,
                                onDismiss = { menuTrack = null }
                            )
                        }
                        }
                        androidx.compose.material3.SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 18.dp))
                    }
                    if (showNowPlaying && playback != null && lyrics != null) {
                      if (!rightIsCollapsed) SidebarResizeHandle(
                          onDrag = { rightSidebarWidth = sidebarWidth(rightSidebarWidth - it, 280f, rightLimit) },
                          onDragging = { resizingRight = it })
                      else Spacer(Modifier.width(8.dp))
                      Row(Modifier.width(rightDisplay).fillMaxHeight().clip(panelShape)
                          .then(if (rightIsCollapsed) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)) {
                        if (rightIsCollapsed) {
                        androidx.compose.material3.IconButton(onClick = { rightCollapsed = !rightCollapsed }, modifier = Modifier.width(40.dp), enabled = rightLimit >= 280f) {
                            Icon(if (rightIsCollapsed) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                                if (rightIsCollapsed) "Expand now playing" else "Collapse now playing")
                        }
                        }
                        androidx.compose.animation.AnimatedVisibility(visible = !rightIsCollapsed,
                            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)),
                            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))) {
                        val panelModifier = Modifier.width(sidebarWidth(rightSidebarWidth, 280f, rightLimit).dp).fillMaxHeight()
                        val closePanel = { sidePanel = com.auralis.music.windows.player.SidePanel.NOW_PLAYING }
                        when (sidePanel) {
                            com.auralis.music.windows.player.SidePanel.LYRICS ->
                                com.auralis.music.windows.player.SidePanelSheet("Lyrics", closePanel, panelModifier) {
                                    com.auralis.music.windows.player.SideLyricsPanel(playback, lyrics)
                                }
                            com.auralis.music.windows.player.SidePanel.QUEUE ->
                                com.auralis.music.windows.player.SidePanelSheet("Queue", closePanel, panelModifier) {
                                    com.auralis.music.windows.player.QueueView(playback, Modifier.fillMaxSize())
                                }
                            com.auralis.music.windows.player.SidePanel.NOW_PLAYING ->
                                com.auralis.music.windows.player.NowPlayingSidebar(playback, lyrics,
                                    onExpand = { playerPanel = com.auralis.music.windows.player.PlayerPanel.LYRICS; showPlayer = true },
                                    modifier = panelModifier, palette = artworkPalette,
                                    onCollapse = { rightCollapsed = true })
                        }
                        }
                      }
                    }
                  }
                }
                com.auralis.music.windows.player.NowPlayingBar(
                    playback,
                    onOpenPlayer = { if (canShowPlayer) showPlayer = true },
                    // Toggle the right panel, as Spotify's lyrics and queue buttons do.
                    onOpenQueue = { if (canShowPlayer) sidePanel = if (sidePanel == com.auralis.music.windows.player.SidePanel.QUEUE)
                        com.auralis.music.windows.player.SidePanel.NOW_PLAYING else com.auralis.music.windows.player.SidePanel.QUEUE },
                    onOpenLyrics = { if (canShowPlayer) sidePanel = if (sidePanel == com.auralis.music.windows.player.SidePanel.LYRICS)
                        com.auralis.music.windows.player.SidePanel.NOW_PLAYING else com.auralis.music.windows.player.SidePanel.LYRICS }
                )
            }
            // The full player slides up over everything, like the phone's player sheet.
            androidx.compose.animation.AnimatedVisibility(
                modifier = Modifier.padding(top = TITLE_BAR_HEIGHT),
                visible = showPlayer && playback != null && lyrics != null,
                enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(380)) { it } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)),
                exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(320)) { it } +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(240))
            ) {
                if (playback != null && lyrics != null) {
                    com.auralis.music.windows.player.PlayerScreen(
                        playback,
                        lyrics,
                        panel = playerPanel,
                        onPanelChange = { playerPanel = it },
                        onClose = { showPlayer = false },
                        palette = artworkPalette
                    )
                }
            }
        }
    }
    }
}

}

/** The dock's tabs: the pages the sidebar used to list (Listen Together and Stats are in the title bar). */
internal val DockTabs = listOf(DesktopDestination.HOME, DesktopDestination.LIBRARY,
    DesktopDestination.WRAPPED, DesktopDestination.SETTINGS)

@Composable
private fun NotBuiltYet(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("Not built yet. $detail", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
