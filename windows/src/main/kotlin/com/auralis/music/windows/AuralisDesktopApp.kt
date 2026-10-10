package com.auralis.music.windows

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Explore
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

enum class DesktopDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Explore),
    SEARCH("Search", Icons.Outlined.Search),
    LIBRARY("Library", Icons.Outlined.GridView),
}

@Composable
fun AuralisDesktopApp(
    searchRepository: com.auralis.music.domain.repository.SearchRepository,
    library: com.auralis.music.domain.repository.LibraryRepository,
    history: com.auralis.music.domain.repository.HistoryRepository,
    homePlatform: com.auralis.music.ui.viewmodel.HomePlatform,
    libraryFlags: com.auralis.music.util.KeyValueStore,
    playback: com.auralis.music.desktop.playback.DesktopPlaybackController?,
    lyrics: com.auralis.music.desktop.lyrics.DesktopLyricsController?,
    /** Main points the window's key handler here. */
    onShortcutHandler: (((androidx.compose.ui.input.key.KeyEvent) -> Boolean)?) -> Unit = {},
    selfTest: SelfTest? = null,
    onSelfTestDone: (Boolean) -> Unit = {}
) {
    var showPlayer by remember { mutableStateOf(false) }
    var playerPanel by remember { mutableStateOf(com.auralis.music.windows.player.PlayerPanel.LYRICS) }
    var searchFocusRequests by remember { mutableStateOf(0) }
    var typing by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(DesktopDestination.HOME) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val searchState = remember { com.auralis.music.windows.search.DesktopSearchState(searchRepository, scope) }
    val browse = remember { com.auralis.music.desktop.browse.DesktopBrowseState(searchRepository, scope) }
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
    val platformActions = remember { com.auralis.music.ui.components.DesktopPlatformActions(showUnavailable) }
    val libraryViewModel = remember {
        com.auralis.music.ui.viewmodel.LibraryViewModel(
            libraryRepository = library,
            historyRepository = history,
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

    com.auralis.music.ui.theme.AuralisDesktopTheme(platformActions = platformActions) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Sidebar(selected = destination, onSelect = { destination = it; browse.clear() })
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (browse.top != null) com.auralis.music.windows.browse.BrowsePages(browse, playback, showUnavailable)
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
                                showMessage = showUnavailable
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
                            DesktopDestination.LIBRARY -> com.auralis.music.windows.library.DesktopLibrary(
                                viewModel = libraryViewModel,
                                homeViewModel = homeViewModel,
                                library = library,
                                playback = playback,
                                browse = browse,
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
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                com.auralis.music.windows.player.NowPlayingBar(
                    playback,
                    onOpenPlayer = { if (canShowPlayer) showPlayer = true }
                )
            }
            // The full player slides up over everything, like the phone's player sheet.
            androidx.compose.animation.AnimatedVisibility(
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

@Composable
private fun Sidebar(selected: DesktopDestination, onSelect: (DesktopDestination) -> Unit) {
    val logo = remember {
        Thread.currentThread().contextClassLoader.getResourceAsStream("auralis_logo.png")
            ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
    }
    Column(
        modifier = Modifier
            .width(232.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, bottom = 20.dp)
        ) {
            if (logo != null) Image(logo, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text("Auralis", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        DesktopDestination.entries.forEach { item ->
            SidebarItem(item, isSelected = item == selected, onClick = { onSelect(item) })
        }
    }
}

@Composable
private fun SidebarItem(item: DesktopDestination, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) colors.surfaceContainerHigh else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
    ) {
        Icon(item.icon, contentDescription = null, tint = if (isSelected) colors.primary else colors.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(
            item.label,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) colors.onSurface else colors.onSurfaceVariant
        )
    }
}

@Composable
private fun NotBuiltYet(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("Not built yet. $detail", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
