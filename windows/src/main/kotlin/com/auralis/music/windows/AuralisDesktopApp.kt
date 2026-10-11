package com.auralis.music.windows

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
    val homeViewModel = remember {
        com.auralis.music.ui.viewmodel.HomeViewModel(
            historyRepository = history,
            searchRepository = searchRepository,
            platform = homePlatform,
            libraryRepository = library
        )
    }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val showUnavailable: (String) -> Unit = { message ->
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) }
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
    val artworkPalette = com.auralis.music.windows.settings.rememberArtworkPalette(queue?.current?.thumbnail)
    val liquidify by com.auralis.music.windows.settings.DesktopLiquidify.enabled.collectAsState()
    val initialSidebars = remember { SidebarLayoutPreferences.load() }
    var leftSidebarWidth by remember { mutableStateOf(initialSidebars.left) }
    var rightSidebarWidth by remember { mutableStateOf(initialSidebars.right) }
    var leftCollapsed by remember { mutableStateOf(initialSidebars.leftCollapsed) }
    var rightCollapsed by remember { mutableStateOf(initialSidebars.rightCollapsed) }
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
    com.auralis.music.windows.player.ArtworkAppBackground(if (liquidify) queue?.current?.thumbnail else null, artworkPalette) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(nativeDensity.density * densityScale, nativeDensity.fontScale),
        com.auralis.music.data.datastore.LocalSettingsStores provides stores,
        com.auralis.music.ui.settings.LocalSettingsPlatform provides settingsPlatform,
        com.auralis.music.ui.theme.LocalAppearanceSettingsStore provides stores.appearance,
        com.auralis.music.ui.components.LocalTrackContextMenu provides { items, dismiss ->
            com.auralis.music.windows.library.DesktopSongContextMenu(items, dismiss)
        }
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                titleBar(TitleBarActions(
                    canGoBack = showPlayer || navigation.canGoBack,
                    canGoForward = navigation.canGoForward,
                    back = { if (showPlayer) showPlayer = false else restoreRoute(navigation.back()) },
                    forward = { restoreRoute(navigation.forward()) },
                    refresh = {
                        when (val page = browse.top) {
                            is com.auralis.music.desktop.browse.BrowsePage.Artist -> browse.retryArtist()
                            is com.auralis.music.desktop.browse.BrowsePage.Album -> { browse.pop(); browse.openAlbum(page.album) }
                            null -> when (destination) {
                                DesktopDestination.SEARCH -> if (searchState.query.isNotBlank()) searchState.search(searchState.query)
                                DesktopDestination.LIBRARY -> scope.launch { libraryViewModel.refresh() }
                                DesktopDestination.HOME -> homeViewModel.refresh()
                                else -> Unit
                            }
                        }
                    },
                    accountLabel = accountState.profile?.displayName?.takeIf { it.isNotBlank() } ?: "Sign in",
                    openAccount = { showPlayer = false; browse.clear(); destination = DesktopDestination.PROFILE },
                    openTogether = { showPlayer = false; browse.clear(); destination = DesktopDestination.TOGETHER },
                    openSettings = { showPlayer = false; browse.clear(); destination = DesktopDestination.SETTINGS },
                    home = { showPlayer = false; browse.clear(); destination = DesktopDestination.HOME },
                    canRefresh = browse.top != null || destination in setOf(DesktopDestination.HOME, DesktopDestination.LIBRARY) ||
                        (destination == DesktopDestination.SEARCH && searchState.query.isNotBlank())
                ))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                  val showNowPlaying = queue?.current != null && playback != null && lyrics != null
                  val leftTarget = if (leftCollapsed) 64f else sidebarWidth(leftSidebarWidth, 180f, 320f)
                  val rightLimit = (maxWidth.value - leftTarget - 360f - 6f).coerceAtMost(460f)
                  val rightIsCollapsed = rightCollapsed || rightLimit < 280f
                  val rightTarget = if (!showNowPlaying) 0f else if (rightIsCollapsed) 40f else sidebarWidth(rightSidebarWidth, 280f, rightLimit)
                  val leftLimit = (maxWidth.value - rightTarget - 360f - 12f).coerceIn(180f, 320f)
                  val leftDisplay by androidx.compose.animation.core.animateDpAsState(
                      (if (leftCollapsed) 64f else sidebarWidth(leftSidebarWidth, 180f, leftLimit)).dp,
                      if (resizingLeft) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(280), label = "left sidebar width")
                  val rightDisplay by androidx.compose.animation.core.animateDpAsState(rightTarget.dp,
                      if (resizingRight) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(280), label = "right sidebar width")
                  Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(leftDisplay).fillMaxHeight().clip(androidx.compose.ui.graphics.RectangleShape)) {
                        Sidebar(selected = destination, onSelect = { destination = it; browse.clear() },
                            collapsed = leftCollapsed, onToggle = { leftCollapsed = !leftCollapsed })
                    }
                    if (!leftCollapsed) SidebarResizeHandle(
                        onDrag = { leftSidebarWidth = sidebarWidth(leftSidebarWidth + it, 180f, leftLimit) },
                        onDragging = { resizingLeft = it })
                    Box(Modifier.weight(1f).fillMaxHeight()) {
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
                                findMusic = {
                                    com.auralis.music.windows.search.FindMusicDropdown(
                                        state = searchState,
                                        currentTrackId = queue?.current?.id,
                                        isPlaying = playerState == com.auralis.music.desktop.playback.VlcAudioPlayer.State.PLAYING,
                                        onPlay = { track, tracks -> playback?.playFrom(track, tracks) ?: showUnavailable("Playback needs VLC (64-bit)") },
                                        onSearch = { query -> searchState.search(query); destination = DesktopDestination.SEARCH },
                                        onPlayNext = { playback?.playNext(it) },
                                        onAddToQueue = { playback?.addToQueue(it) },
                                        onMenuClick = { menuTrack = it },
                                        onTypingChange = { typing = it },
                                        liquidify = liquidify,
                                        artwork = queue?.current?.thumbnail
                                    )
                                }
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
                                onBack = { destination = DesktopDestination.HOME }
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
                        androidx.compose.material3.SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
                    }
                    if (showNowPlaying && playback != null && lyrics != null) {
                      if (!rightIsCollapsed) SidebarResizeHandle(
                          onDrag = { rightSidebarWidth = sidebarWidth(rightSidebarWidth - it, 280f, rightLimit) },
                          onDragging = { resizingRight = it })
                      Row(Modifier.width(rightDisplay).fillMaxHeight().clip(androidx.compose.ui.graphics.RectangleShape)) {
                        androidx.compose.material3.IconButton(onClick = { rightCollapsed = !rightCollapsed }, modifier = Modifier.width(40.dp), enabled = rightLimit >= 280f) {
                            Icon(if (rightIsCollapsed) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                                if (rightIsCollapsed) "Expand now playing" else "Collapse now playing")
                        }
                        androidx.compose.animation.AnimatedVisibility(visible = !rightIsCollapsed,
                            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)),
                            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))) {
                        com.auralis.music.windows.player.NowPlayingSidebar(playback, lyrics,
                            onExpand = { playerPanel = com.auralis.music.windows.player.PlayerPanel.LYRICS; showPlayer = true },
                            modifier = Modifier.width((sidebarWidth(rightSidebarWidth, 280f, rightLimit) - 40f).dp).fillMaxHeight(), palette = artworkPalette)
                        }
                      }
                    }
                  }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                com.auralis.music.windows.player.NowPlayingBar(
                    playback,
                    onOpenPlayer = { if (canShowPlayer) showPlayer = true },
                    onOpenQueue = { if (canShowPlayer) { playerPanel = com.auralis.music.windows.player.PlayerPanel.QUEUE; showPlayer = true } }
                )
            }
            // The full player slides up over everything, like the phone's player sheet.
            androidx.compose.animation.AnimatedVisibility(
                modifier = Modifier.padding(top = 36.dp),
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
                        onClose = { showPlayer = false }
                    )
                }
            }
        }
    }
    }
}

}

@Composable
internal fun Sidebar(selected: DesktopDestination, onSelect: (DesktopDestination) -> Unit, collapsed: Boolean, onToggle: () -> Unit) {
    val logo = remember {
        Thread.currentThread().contextClassLoader.getResourceAsStream("auralis_logo.png")
            ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, bottom = 20.dp)
        ) {
            if (!collapsed) {
            if (logo != null) Image(logo, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text("Auralis", modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
            androidx.compose.material3.IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                Icon(if (collapsed) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                    if (collapsed) "Expand navigation" else "Collapse navigation")
            }
        }
        DesktopDestination.entries.filter { it != DesktopDestination.SEARCH }.forEach { item ->
            SidebarItem(item, isSelected = item == selected, onClick = { onSelect(item) }, collapsed = collapsed)
        }
    }
}

@Composable
private fun SidebarItem(item: DesktopDestination, isSelected: Boolean, onClick: () -> Unit, collapsed: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) colors.surfaceContainerHigh else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = if (collapsed) 8.dp else 12.dp)
    ) {
        Icon(item.icon, contentDescription = item.label, tint = if (isSelected) colors.primary else colors.onSurfaceVariant)
        androidx.compose.animation.AnimatedVisibility(!collapsed,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(14.dp))
        Text(
            item.label,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) colors.onSurface else colors.onSurfaceVariant, maxLines = 1
        )
        }
        }
    }
}

@Composable
private fun NotBuiltYet(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("Not built yet. $detail", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
