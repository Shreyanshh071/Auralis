package com.auralis.music.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.components.MiniPlayer
import com.auralis.music.ui.profile.ProfileSheet
import com.auralis.music.ui.player.MiniPlayerHeight
import com.auralis.music.ui.screens.*
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.AuralisSpring
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.theme.PlayerMotion
import com.auralis.music.ui.theme.auralisFadeEnter
import com.auralis.music.ui.theme.auralisFadeExit
import com.auralis.music.ui.theme.auralisNavigationEnter
import com.auralis.music.ui.theme.auralisNavigationExit
import com.auralis.music.ui.theme.auralisPushEnter
import com.auralis.music.ui.theme.auralisPushExit
import com.auralis.music.ui.theme.auralisSheetEnter
import com.auralis.music.ui.theme.auralisSheetExit
import com.auralis.music.ui.theme.motionTween
import com.auralis.music.ui.viewmodel.*
import kotlinx.coroutines.launch

enum class AppDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    EXPLORE("Search", Icons.Default.Search),
    LIBRARY("Library", Icons.Default.LibraryMusic)
}

val AppDestinations = AppDestination.values()

/** How much the selected destination icon grows. Deliberately small — this reads as weight, not bounce. */
private const val SelectedNavIconScale = 1.08f

/**
 * Bottom-bar icon with a subtle spring on selection, so the tap has a visible
 * consequence at the point of contact rather than only further up the screen.
 * The M3 pill indicator and all colours are untouched.
 */
@Composable
private fun AnimatedNavIcon(destination: AppDestination, selected: Boolean) {
    val reducedMotion = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (selected && !reducedMotion) SelectedNavIconScale else 1f,
        animationSpec = AuralisSpring.NavIcon,
        label = "navIconScale"
    )
    Icon(
        imageVector = destination.icon,
        contentDescription = destination.label,
        modifier = if (reducedMotion) {
            Modifier
        } else {
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AuralisApp(
    homeViewModel: HomeViewModel,
    searchViewModel: SearchViewModel,
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    listenTogetherViewModel: ListenTogetherViewModel,
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier
) {
    val homeUiState by homeViewModel.uiState.collectAsState()
    val searchUiState by searchViewModel.uiState.collectAsState()
    val libraryUiState by libraryViewModel.uiState.collectAsState()
    val playerUiState by playerViewModel.uiState.collectAsState()
    val listenTogetherUiState by listenTogetherViewModel.uiState.collectAsState()
    val authUiState by authViewModel.uiState.collectAsState()
    val recognitionState by searchViewModel.recognitionState.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    var currentDestination by remember { mutableStateOf(AppDestination.HOME) }
    val destinationBackStack = remember { androidx.compose.runtime.mutableStateListOf<AppDestination>() }

    var isNowPlayingOpen by remember { mutableStateOf(false) }

    var isListenTogetherOpen by remember { mutableStateOf(false) }
    var isProfileOpen by remember { mutableStateOf(false) }
    var isHistoryOpen by remember { mutableStateOf(false) }
    var showMiniPlayerTrackOptions by remember { mutableStateOf(false) }

    fun navigateToDestination(dest: AppDestination) {
        if (currentDestination != dest) {
            destinationBackStack.add(currentDestination)
            currentDestination = dest
        }
        isHistoryOpen = false
        isProfileOpen = false
        isListenTogetherOpen = false
        searchViewModel.closeRecognitionModal()
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    // ── Dedicated Background Audio Engine Host ──
    // Hardware-accelerated audio WebView host placed at the base of the UI layer stack (zIndex -1f)
    // Provides full viewport dimensions for uninterrupted HTML5 audio playback while remaining behind the solid UI theme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(-1f)
    ) {
        AndroidView(
            factory = { ctx ->
                playerViewModel.getAudioPlayer()?.getOrCreateWebView(ctx) ?: android.view.View(ctx)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
    
    // Resolve Activity context for Credential Manager and OAuth popups
    fun android.content.Context.findActivity(): android.app.Activity? {
        var cur = this
        while (cur is android.content.ContextWrapper) {
            if (cur is android.app.Activity) return cur
            cur = cur.baseContext
        }
        return null
    }

    // Check if user is logged in (Firebase authenticated)
    val isFirebaseUserActive = try {
        val fbUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        fbUser != null && !fbUser.isAnonymous
    } catch (_: Exception) { false }

    val isUserLoggedIn = isFirebaseUserActive || (authUiState.profile.isGoogleConnected && authUiState.profile.uid.isNotBlank())
    val isAppUnlocked = isUserLoggedIn

    if (!isAppUnlocked) {
        com.auralis.music.ui.onboarding.WelcomeScreen(
            authUiState = authUiState,
            onContinueWithGoogle = {
                val act = context.findActivity()
                if (act != null) {
                    authViewModel.signInWithGoogle(act)
                } else {
                    android.widget.Toast.makeText(context, "Activity not found for Google Sign-In", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onSignUpWithEmail = { email, password, name ->
                authViewModel.signUpWithEmail(email, password, name) {}
            },
            onSignInWithEmail = { email, password ->
                authViewModel.signInWithEmail(email, password) {}
            }
        )
        return
    }

    val isGuestInRoom = listenTogetherUiState.activeRoom != null && !listenTogetherUiState.isHost

    fun notifyGuestControlBlocked() {
        android.widget.Toast.makeText(context, "Playback is controlled by the room host", android.widget.Toast.LENGTH_SHORT).show()
    }

    androidx.activity.compose.BackHandler(
        enabled = searchUiState.selectedArtistPage != null ||
                isNowPlayingOpen ||
                isHistoryOpen ||
                isProfileOpen ||
                isListenTogetherOpen ||
                destinationBackStack.isNotEmpty() ||
                currentDestination != AppDestination.HOME
    ) {
        if (searchUiState.selectedArtistPage != null) searchViewModel.closeArtist()
        else if (isNowPlayingOpen) isNowPlayingOpen = false
        else if (isHistoryOpen) isHistoryOpen = false
        else if (isProfileOpen) isProfileOpen = false
        else if (isListenTogetherOpen) isListenTogetherOpen = false
        else if (destinationBackStack.isNotEmpty()) {
            val prevDest = destinationBackStack.removeAt(destinationBackStack.lastIndex)
            currentDestination = prevDest
        } else if (currentDestination != AppDestination.HOME) {
            currentDestination = AppDestination.HOME
        }
    }

    // Wire Listen Together Sync Callbacks
    LaunchedEffect(Unit) {
        listenTogetherViewModel.onSyncTrackChange = { track, queue, startPosMs ->
            val idx = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playerViewModel.playTrack(track, queue, idx, initialPositionMs = startPosMs)
        }
        listenTogetherViewModel.onSyncResume = {
            playerViewModel.resume()
        }
        listenTogetherViewModel.onSyncPause = {
            playerViewModel.pause()
        }
        listenTogetherViewModel.onSyncSeek = { pos ->
            playerViewModel.seekTo(pos)
        }
        listenTogetherViewModel.onGetLocalPosition = {
            playerViewModel.uiState.value.playbackPositionMs
        }
        listenTogetherViewModel.onGetLocalIsPlaying = {
            playerViewModel.uiState.value.isPlaying
        }
        listenTogetherViewModel.onGetLocalTrackId = {
            playerViewModel.uiState.value.currentTrack?.id
        }
        listenTogetherViewModel.onHostPlayTrack = { track ->
            val curQueue = playerViewModel.uiState.value.queue
            val newQueue = if (curQueue.none { it.id == track.id }) curQueue + track else curQueue
            val index = newQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playerViewModel.playTrack(track, newQueue, index)
        }
        listenTogetherViewModel.onHostAddToQueue = { track ->
            playerViewModel.addToQueue(listOf(track))
            android.widget.Toast.makeText(context, "Added \"${track.title}\" to room queue", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Host Broadcast Sync & Periodic Heartbeat
    LaunchedEffect(
        listenTogetherUiState.isHost,
        listenTogetherUiState.activeRoom?.code,
        playerUiState.currentTrack?.id,
        playerUiState.isPlaying
    ) {
        if (listenTogetherUiState.isHost && listenTogetherUiState.activeRoom != null) {
            val track = playerUiState.currentTrack
            if (track != null) {
                // Immediate broadcast on track change, room open, or play/pause
                listenTogetherViewModel.broadcastHostPlayback(
                    currentTrack = track,
                    isPlaying = playerUiState.isPlaying,
                    playbackPositionMs = playerUiState.playbackPositionMs,
                    queue = playerUiState.queue
                )

                // Periodic drift/position sync heartbeat while host is actively playing
                while (playerUiState.isPlaying) {
                    kotlinx.coroutines.delay(5000L)
                    val curTrack = playerViewModel.uiState.value.currentTrack
                    if (curTrack != null && playerViewModel.uiState.value.isPlaying) {
                        listenTogetherViewModel.broadcastHostPlayback(
                            currentTrack = curTrack,
                            isPlaying = true,
                            playbackPositionMs = playerViewModel.uiState.value.playbackPositionMs,
                            queue = playerViewModel.uiState.value.queue
                        )
                    }
                }
            }
        }
    }

    // One SharedTransitionLayout for the whole app: the mini-player lives in the
    // Scaffold's bottom bar and Now Playing is a sibling overlay, so the only way the
    // two can hand the artwork over is through a shared parent that outlives both.
    // Behaves like a Box (children are stacked), so the existing layering is intact.
    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        val playerSharedScope = this

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                Column {
                    // Persistent Mini Player across all tabs.
                    // The fixed-height Box sits *outside* the AnimatedVisibility so the
                    // bottom bar's measured height never changes while the pill fades
                    // out — otherwise the Scaffold would relayout the entire screen on
                    // every frame of the transform.
                    if (playerUiState.currentTrack != null) {
                        val progressFrac = if (playerUiState.durationMs > 0) {
                            (playerUiState.playbackPositionMs.toFloat() / playerUiState.durationMs).coerceIn(0f, 1f)
                        } else 0f

                        Box(modifier = Modifier.height(MiniPlayerHeight)) {
                            this@Column.AnimatedVisibility(
                                visible = !isNowPlayingOpen,
                                // Synchronized with container transform so the pill seamlessly
                                // dissolves and emerges in lockstep with the artwork flight.
                                enter = auralisFadeEnter(PlayerMotion.ExitDuration),
                                exit = auralisFadeExit(PlayerMotion.EnterDuration)
                            ) {
                                MiniPlayer(
                                    track = playerUiState.currentTrack!!,
                                    isPlaying = playerUiState.isPlaying,
                                    progress = progressFrac,
                                    queue = playerUiState.queue,
                                    currentIndex = playerUiState.currentIndex,
                                    isFavorite = playerUiState.isFavorite,
                                    userScrollEnabled = !isGuestInRoom && !isNowPlayingOpen,
                                    onPlayPauseClick = {
                                        if (isGuestInRoom) notifyGuestControlBlocked()
                                        else playerViewModel.togglePlayPause()
                                    },
                                    onNextClick = {
                                        if (isGuestInRoom) notifyGuestControlBlocked()
                                        else playerViewModel.next()
                                    },
                                    onPreviousClick = {
                                        if (isGuestInRoom) notifyGuestControlBlocked()
                                        else playerViewModel.previous()
                                    },
                                    onSelectQueueTrack = { index ->
                                        if (isGuestInRoom) {
                                            notifyGuestControlBlocked()
                                        } else {
                                            val t = playerUiState.queue.getOrNull(index)
                                            if (t != null) {
                                                playerViewModel.playTrack(t, playerUiState.queue, index)
                                            }
                                        }
                                    },
                                    onFavoriteToggle = { playerViewModel.toggleFavorite() },
                                    onAddToPlaylist = {
                                        showMiniPlayerTrackOptions = true
                                    },
                                    onArtistClick = {
                                        searchViewModel.openArtist(com.auralis.music.domain.model.Artist(id = "", name = playerUiState.currentTrack!!.artist))
                                        navigateToDestination(AppDestination.EXPLORE)
                                    },
                                    onClick = { isNowPlayingOpen = true },
                                    sharedTransitionScope = playerSharedScope,
                                    animatedVisibilityScope = this@AnimatedVisibility
                                )
                            }
                        }
                    }

                    // Bottom Navigation Bar
                    NavigationBar(
                        modifier = Modifier.graphicsLayer {
                            // Hidden rather than removed, so the Scaffold never remeasures
                            // and relayouts the whole screen when NowPlaying opens/closes.
                            alpha = if (isNowPlayingOpen) 0f else 1f
                        },
                        containerColor = Color(0xFF0D0E0B),
                        tonalElevation = 8.dp
                    ) {
                        AppDestinations.forEach { destination ->
                            val isSelected = currentDestination == destination &&
                                    !isHistoryOpen && !isProfileOpen && !isListenTogetherOpen
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = {
                                    navigateToDestination(destination)
                                },
                                icon = { AnimatedNavIcon(destination = destination, selected = isSelected) },
                                label = { Text(destination.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFFD4E157),
                                    selectedTextColor = Color(0xFFD4E157),
                                    indicatorColor = Color(0xFF383C25),
                                    unselectedIconColor = Color.White.copy(alpha = 0.6f),
                                    unselectedTextColor = Color.White.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Active Listen Together Banner (if connected to a room)
                    if (listenTogetherUiState.activeRoom != null) {
                        val room = listenTogetherUiState.activeRoom!!
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isListenTogetherOpen = true }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Room: ${room.code} (${listenTogetherUiState.members.size} connected)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = if (listenTogetherUiState.isHost) "Streaming to room" else "Synced with host (controls locked)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                                EqualizerBars(
                                    isPlaying = playerUiState.isPlaying,
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Main Navigation Screen Container: Instant 0ms response with hardware-accelerated in-place transitions
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val reducedMotion = LocalReducedMotion.current
                        AppDestinations.forEach { destination ->
                            val isSelected = currentDestination == destination
                            val animAlpha by animateFloatAsState(
                                targetValue = if (isSelected) 1f else 0f,
                                animationSpec = if (reducedMotion) snap() else tween(
                                    durationMillis = if (isSelected) 160 else 120,
                                    easing = AuralisEasing.Standard
                                ),
                                label = "${destination.name}TabAlpha"
                            )
                            val animScale by animateFloatAsState(
                                targetValue = if (isSelected) 1f else 0.988f,
                                animationSpec = if (reducedMotion) snap() else tween(
                                    durationMillis = 160,
                                    easing = AuralisEasing.Decelerate
                                ),
                                label = "${destination.name}TabScale"
                            )

                            // Keep all 3 primary destinations continuously composed in memory to preserve scroll
                            // positions, carousel state, and search queries without rebuild or image reload overhead.
                            // Inactive destinations have alpha = 0f (RenderNode skip-draw) and swallow pointer events.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (isSelected) 10f else 0f)
                                    .graphicsLayer {
                                        alpha = animAlpha
                                        scaleX = animScale
                                        scaleY = animScale
                                        clip = true
                                    }
                                    .then(
                                        if (!isSelected) {
                                            Modifier.pointerInput(destination) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                                        event.changes.forEach { it.consume() }
                                                    }
                                                }
                                            }
                                        } else Modifier
                                    )
                            ) {
                                when (destination) {
                                    AppDestination.HOME -> {
                                        HomeScreen(
                                            uiState = homeUiState,
                                            currentTrackId = playerUiState.currentTrack?.id,
                                            isPlaying = playerUiState.isPlaying,
                                            userPlaylists = libraryUiState.playlists,
                                            favoriteTracks = libraryUiState.favorites,
                                            onTrackClick = { track, queue ->
                                                if (isGuestInRoom) notifyGuestControlBlocked()
                                                else playerViewModel.playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                                            },
                                            onFavoriteToggle = { track -> playerViewModel.toggleFavorite(track) },
                                            onAddToPlaylist = { plId, track -> libraryViewModel.addTrackToPlaylist(plId, track) },
                                            onCreatePlaylistAndAdd = { title, track ->
                                                libraryViewModel.createPlaylistAndAddTrack(title, track)
                                            },
                                            onPlayNext = { track ->
                                                playerViewModel.playNext(track)
                                                android.widget.Toast.makeText(context, "Playing next: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            onAddToQueue = { track ->
                                                playerViewModel.addToQueue(listOf(track))
                                                android.widget.Toast.makeText(context, "Added to queue: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            onStartRadio = { track ->
                                                if (isGuestInRoom) notifyGuestControlBlocked()
                                                else playerViewModel.playTrack(track, listOf(track), 0)
                                            },
                                            onOpenListenTogether = { isListenTogetherOpen = true },
                                            onNavigateToExplore = { navigateToDestination(AppDestination.EXPLORE) },
                                            onMoodSelect = { homeViewModel.selectMoodFilter(it) },
                                            onChipToggle = { homeViewModel.toggleChip(it) },
                                            onSurpriseMe = {
                                                if (isGuestInRoom) {
                                                    notifyGuestControlBlocked()
                                                } else {
                                                    val surpriseTrack = homeViewModel.getRandomSurpriseTrack()
                                                    if (surpriseTrack != null) {
                                                        playerViewModel.playTrack(surpriseTrack, listOf(surpriseTrack), 0)
                                                    }
                                                }
                                            },
                                            onOpenProfile = { isProfileOpen = true },
                                            onOpenHistory = { isHistoryOpen = true },
                                            onArtistClick = { artist ->
                                                searchViewModel.openArtist(artist)
                                                navigateToDestination(AppDestination.EXPLORE)
                                            },
                                            isInListenTogetherRoom = isGuestInRoom,
                                            onRecommendToRoom = { trk ->
                                                listenTogetherViewModel.recommendSong(trk)
                                                android.widget.Toast.makeText(context, "Recommended \"${trk.title}\" to room!", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                    AppDestination.EXPLORE -> {
                                        ExploreScreen(
                                            uiState = searchUiState,
                                            recognitionState = recognitionState,
                                            currentTrackId = playerUiState.currentTrack?.id,
                                            isPlaying = playerUiState.isPlaying,
                                            userPlaylists = libraryUiState.playlists,
                                            favoriteTracks = libraryUiState.favorites,
                                            onQueryChange = { searchViewModel.onQueryChange(it) },
                                            onSearch = { searchViewModel.performSearch(it) },
                                            onClearSearch = { searchViewModel.clearSearch() },
                                            onTrackClick = { track, queue ->
                                                if (isGuestInRoom) notifyGuestControlBlocked()
                                                else playerViewModel.playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                                            },
                                            onFavoriteToggle = { track -> playerViewModel.toggleFavorite(track) },
                                            onAddToPlaylist = { plId, track -> libraryViewModel.addTrackToPlaylist(plId, track) },
                                            onCreatePlaylistAndAdd = { title, track ->
                                                libraryViewModel.createPlaylistAndAddTrack(title, track)
                                            },
                                            onPlayNext = { track ->
                                                playerViewModel.playNext(track)
                                                android.widget.Toast.makeText(context, "Playing next: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            onAddToQueue = { track ->
                                                playerViewModel.addToQueue(listOf(track))
                                                android.widget.Toast.makeText(context, "Added to queue: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            onStartRadio = { track ->
                                                if (isGuestInRoom) notifyGuestControlBlocked()
                                                else playerViewModel.playTrack(track, listOf(track), 0)
                                            },
                                            onRemoveRecentQuery = { searchViewModel.removeRecentQuery(it) },
                                            onOpenRecognition = { searchViewModel.openRecognitionModal(it) },
                                            onCloseRecognition = { searchViewModel.closeRecognitionModal() },
                                            onModeSelect = { searchViewModel.setRecognitionMode(it) },
                                            onStartListening = { searchViewModel.startListening() },
                                            onStopListening = { searchViewModel.stopListening() },
                                            onOpenArtist = { searchViewModel.openArtist(it) },
                                            onCloseArtist = { searchViewModel.closeArtist() },
                                            onBack = {
                                                if (destinationBackStack.isNotEmpty()) {
                                                    val prevDest = destinationBackStack.removeAt(destinationBackStack.lastIndex)
                                                    currentDestination = prevDest
                                                } else {
                                                    currentDestination = AppDestination.HOME
                                                }
                                            },
                                            isInListenTogetherRoom = isGuestInRoom,
                                            onRecommendToRoom = { trk ->
                                                listenTogetherViewModel.recommendSong(trk)
                                                android.widget.Toast.makeText(context, "Recommended \"${trk.title}\" to room!", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                    AppDestination.LIBRARY -> {
                                        LibraryScreen(
                                            uiState = libraryUiState,
                                            currentTrackId = playerUiState.currentTrack?.id,
                                            isPlaying = playerUiState.isPlaying,
                                            userName = authUiState.profile.displayName.ifBlank { "You" },
                                            userAvatarUrl = authUiState.profile.avatarUrl,
                                            onFilterSelect = { libraryViewModel.setFilter(it) },
                                            onCreatePlaylist = { libraryViewModel.createPlaylist(it) },
                                            onDeletePlaylist = { libraryViewModel.deletePlaylist(it) },
                                            onPlaylistSelect = { libraryViewModel.selectPlaylist(it?.id, it) },
                                            onTrackClick = { track, queue ->
                                                if (isGuestInRoom) notifyGuestControlBlocked()
                                                else playerViewModel.playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                                            },
                                            onFavoriteToggle = { track -> playerViewModel.toggleFavorite(track) },
                                            onAddToPlaylist = { plId, track -> libraryViewModel.addTrackToPlaylist(plId, track) },
                                            onRemoveFromPlaylist = { plId, trackId -> libraryViewModel.removeTrackFromPlaylist(plId, trackId) },
                                            onImportYouTubePlaylist = { libraryViewModel.importYouTubePlaylist(it) },
                                            onImportSpotifyPlaylist = { libraryViewModel.importSpotifyPlaylist(it) },
                                            onExportBackup = suspend { libraryViewModel.exportLibraryJson() },
                                            onImportBackup = { libraryViewModel.importLibraryJson(it) },
                                            onSmartCollectionClick = { libraryViewModel.openSmartCollection(it) },
                                            onSortChange = { libraryViewModel.setSortOrder(it) },
                                            onToggleGridView = { libraryViewModel.toggleGridView() },
                                            onOpenHistory = { isHistoryOpen = true },
                                            onOpenListenTogether = { isListenTogetherOpen = true },
                                            onOpenProfile = { isProfileOpen = true },
                                            onSyncPlaylist = { pl -> libraryViewModel.syncPlaylist(pl) },
                                            onEditPlaylist = { id, title, desc, coverUrl -> libraryViewModel.editPlaylist(id, title, desc, coverUrl) },
                                            onAddToQueue = { tracks ->
                                                if (isGuestInRoom) notifyGuestControlBlocked()
                                                else playerViewModel.addToQueue(tracks)
                                            },
                                            onPlayNext = { track ->
                                                playerViewModel.playNext(track)
                                                android.widget.Toast.makeText(context, "Playing next: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            onAddToQueueTrack = { track ->
                                                playerViewModel.addToQueue(listOf(track))
                                                android.widget.Toast.makeText(context, "Added to queue: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            onStartRadio = { track ->
                                                if (isGuestInRoom) notifyGuestControlBlocked()
                                                else playerViewModel.playTrack(track, listOf(track), 0)
                                            },
                                            onOpenArtist = { artist ->
                                                searchViewModel.openArtist(artist)
                                                navigateToDestination(AppDestination.EXPLORE)
                                            },
                                            isInListenTogetherRoom = isGuestInRoom,
                                            onRecommendToRoom = { trk ->
                                                listenTogetherViewModel.recommendSong(trk)
                                                android.widget.Toast.makeText(context, "Recommended \"${trk.title}\" to room!", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fullscreen Expandable Now Playing Modal Sheet (Root Overlay, takes 100% of the screen)
        AnimatedVisibility(
            visible = isNowPlayingOpen,
            enter = auralisSheetEnter(),
            exit = auralisSheetExit()
        ) {
            NowPlayingSheet(
                uiState = playerUiState,
                userPlaylists = libraryUiState.playlists,
                onPlayPauseClick = {
                    if (isGuestInRoom) notifyGuestControlBlocked()
                    else playerViewModel.togglePlayPause()
                },
                onSeekTo = { posMs ->
                    if (isGuestInRoom) {
                        notifyGuestControlBlocked()
                    } else {
                        playerViewModel.seekTo(posMs)
                        if (listenTogetherUiState.isHost && listenTogetherUiState.activeRoom != null) {
                            playerUiState.currentTrack?.let { trk ->
                                listenTogetherViewModel.broadcastHostPlayback(
                                    currentTrack = trk,
                                    isPlaying = playerUiState.isPlaying,
                                    playbackPositionMs = posMs,
                                    queue = playerUiState.queue
                                )
                            }
                        }
                    }
                },
                onNextClick = {
                    if (isGuestInRoom) notifyGuestControlBlocked()
                    else playerViewModel.next()
                },
                onPreviousClick = {
                    if (isGuestInRoom) notifyGuestControlBlocked()
                    else playerViewModel.previous()
                },
                onToggleShuffle = {
                    if (isGuestInRoom) notifyGuestControlBlocked()
                    else playerViewModel.toggleShuffle()
                },
                onToggleRepeat = {
                    if (isGuestInRoom) notifyGuestControlBlocked()
                    else playerViewModel.toggleRepeat()
                },
                onToggleFavorite = { playerViewModel.toggleFavorite() },
                onToggleLyricsView = { playerViewModel.toggleLyricsView() },
                onLyricsOffsetChange = { playerViewModel.setLyricsOffset(it) },
                onSearchLyricsManually = { title, artist -> playerViewModel.searchLyricsManually(title, artist) },
                onSleepTimerSelect = { playerViewModel.setSleepTimer(it) },
                onSelectQueueTrack = { index ->
                    if (isGuestInRoom) {
                        notifyGuestControlBlocked()
                    } else {
                        val t = playerUiState.queue.getOrNull(index)
                        if (t != null) {
                            playerViewModel.playTrack(t, playerUiState.queue, index)
                        }
                    }
                },
                onAddToPlaylist = { plId, track -> libraryViewModel.addTrackToPlaylist(plId, track) },
                onCreatePlaylistAndAdd = { title, track -> libraryViewModel.createPlaylistAndAddTrack(title, track) },
                onArtistClick = { artist ->
                    isNowPlayingOpen = false
                    searchViewModel.openArtist(artist)
                    navigateToDestination(AppDestination.EXPLORE)
                },
                onDismiss = { isNowPlayingOpen = false },
                sharedTransitionScope = playerSharedScope,
                animatedVisibilityScope = this@AnimatedVisibility
            )
        }

        // Listen Together Sheet with unified navigation transition
        AnimatedVisibility(
            visible = isListenTogetherOpen,
            enter = auralisNavigationEnter(),
            exit = auralisNavigationExit()
        ) {
            ListenTogetherSheet(
                uiState = listenTogetherUiState,
                currentTrack = playerUiState.currentTrack,
                isPlaying = playerUiState.isPlaying,
                queue = playerUiState.queue,
                playbackPositionMs = playerUiState.playbackPositionMs,
                onNameChange = { listenTogetherViewModel.setDisplayName(it) },
                onCreateRoom = { trk, q, playing, pos ->
                    listenTogetherViewModel.createRoom(trk, q, playing, pos)
                },
                onJoinRoom = { code ->
                    listenTogetherViewModel.joinRoom(code)
                },
                onLeaveRoom = {
                    listenTogetherViewModel.leaveRoom()
                },
                onSearchRecommendations = { query ->
                    listenTogetherViewModel.searchRecommendations(query)
                },
                onClearRecommendationSearch = {
                    listenTogetherViewModel.clearRecommendationSearch()
                },
                onRecommendSong = { trk, note ->
                    listenTogetherViewModel.recommendSong(trk, note)
                },
                onUpvoteRecommendation = { recId ->
                    listenTogetherViewModel.upvoteRecommendation(recId)
                },
                onDismissRecommendation = { recId ->
                    listenTogetherViewModel.dismissRecommendation(recId)
                },
                onPlayRecommendationNow = { rec ->
                    listenTogetherViewModel.playRecommendationNow(rec)
                },
                onAddRecommendationToQueue = { rec ->
                    listenTogetherViewModel.addRecommendationToQueue(rec)
                },
                onDismiss = { isListenTogetherOpen = false }
            )
        }

        // Profile & YouTube Music Account Sync Modal Sheet
        AnimatedVisibility(
            visible = isProfileOpen,
            enter = auralisNavigationEnter(),
            exit = auralisNavigationExit()
        ) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            ProfileSheet(
                authUiState = authUiState,
                onImportYouTubePlaylist = { libraryViewModel.importYouTubePlaylist(it) },
                onClearYouTubeImportMessage = { libraryViewModel.clearYouTubeImportMessage() },
                isImportingYouTube = libraryUiState.isImporting,
                youtubeImportMessage = libraryUiState.importMessage,
                onOpenPlaylistSelector = { authViewModel.openPlaylistSelectDialog() },
                onSyncLikedMusic = { authViewModel.syncLikedMusic() },
                onDisconnect = {
                    authViewModel.disconnectAccount()
                    isProfileOpen = false
                },
                onClosePlaylistSelector = { authViewModel.closePlaylistSelectDialog() },
                onTogglePlaylistSelection = { authViewModel.togglePlaylistSelection(it) },
                onSelectAllPlaylists = { authViewModel.selectAllPlaylists() },
                onDeselectAllPlaylists = { authViewModel.deselectAllPlaylists() },
                onImportSelectedPlaylists = { authViewModel.importSelectedPlaylists() },
                onImportSpotifyPlaylist = { libraryViewModel.importSpotifyPlaylist(it) },
                onClearSpotifyImportMessage = { libraryViewModel.clearSpotifyImportMessage() },
                isImportingSpotify = libraryUiState.isImportingSpotify,
                spotifyImportMessage = libraryUiState.spotifyImportMessage,
                onDismiss = {
                    libraryViewModel.clearSpotifyImportMessage()
                    libraryViewModel.clearYouTubeImportMessage()
                    isProfileOpen = false
                }
            )
        }

        // Listening History Modal Sheet
        AnimatedVisibility(
            visible = isHistoryOpen,
            enter = auralisNavigationEnter(),
            exit = auralisNavigationExit()
        ) {
            com.auralis.music.ui.history.HistorySheet(
                history = homeUiState.recentTracks,
                currentTrackId = playerUiState.currentTrack?.id,
                isPlaying = playerUiState.isPlaying,
                onTrackClick = { track, queue ->
                    if (isGuestInRoom) notifyGuestControlBlocked()
                    else playerViewModel.playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                },
                onRemoveFromHistory = { homeViewModel.removeFromHistory(it) },
                onClearHistory = { homeViewModel.clearHistory() },
                onDismiss = { isHistoryOpen = false }
            )
        }

        // MiniPlayer Direct Add to Playlist Bottom Sheet
        if (showMiniPlayerTrackOptions && playerUiState.currentTrack != null) {
            val curTrack = playerUiState.currentTrack!!
            com.auralis.music.ui.components.PlaylistPickerBottomSheet(
                track = curTrack,
                userPlaylists = libraryUiState.playlists,
                onAddToPlaylist = { playlist ->
                    libraryViewModel.addTrackToPlaylist(playlist.id, curTrack)
                    android.widget.Toast.makeText(context, "Added to ${playlist.title}", android.widget.Toast.LENGTH_SHORT).show()
                    showMiniPlayerTrackOptions = false
                },
                onCreatePlaylistAndAdd = { title ->
                    libraryViewModel.createPlaylistAndAddTrack(title, curTrack)
                    android.widget.Toast.makeText(context, "Created and added to $title", android.widget.Toast.LENGTH_SHORT).show()
                    showMiniPlayerTrackOptions = false
                },
                onDismiss = { showMiniPlayerTrackOptions = false }
            )
        }
    }
}
