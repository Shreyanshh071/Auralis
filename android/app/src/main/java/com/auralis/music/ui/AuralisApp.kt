package com.auralis.music.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.components.MiniPlayer
import com.auralis.music.ui.profile.ProfileSheet
import com.auralis.music.ui.screens.*
import com.auralis.music.ui.viewmodel.*

enum class AppDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    EXPLORE("Search", Icons.Default.Search),
    LIBRARY("Library", Icons.Default.LibraryMusic)
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
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
    
    // Check if user is logged in (Firebase authenticated or Google Account connected)
    val isFirebaseUserActive = try {
        val fbUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        fbUser != null && !fbUser.isAnonymous
    } catch (_: Exception) { false }

    val isUserLoggedIn = (isFirebaseUserActive && authUiState.profile.email != "Not connected") ||
                         (authUiState.profile.isGoogleConnected && 
                          authUiState.profile.uid.isNotBlank() && 
                          authUiState.profile.email != "Not connected")
    
    var isGuestSessionActive by remember { mutableStateOf(false) }
    val isAppUnlocked = isUserLoggedIn || isGuestSessionActive

    if (!isAppUnlocked) {
        com.auralis.music.ui.onboarding.WelcomeScreen(
            authUiState = authUiState,
            onContinueWithGoogle = {
                authViewModel.signInWithGoogle(context)
            },
            onSignUpWithEmail = { email, password, name ->
                authViewModel.signUpWithEmail(email, password, name) {}
            },
            onSignInWithEmail = { email, password ->
                authViewModel.signInWithEmail(email, password) {}
            },
            onContinueAsGuest = {
                isGuestSessionActive = true
            }
        )
        return
    }

    androidx.activity.compose.BackHandler(
        enabled = isNowPlayingOpen ||
                isHistoryOpen ||
                isProfileOpen ||
                isListenTogetherOpen ||
                destinationBackStack.isNotEmpty()
    ) {
        if (isNowPlayingOpen) isNowPlayingOpen = false
        else if (isHistoryOpen) isHistoryOpen = false
        else if (isProfileOpen) isProfileOpen = false
        else if (isListenTogetherOpen) isListenTogetherOpen = false
        else if (destinationBackStack.isNotEmpty()) {
            currentDestination = destinationBackStack.removeAt(destinationBackStack.lastIndex)
        }
    }

    // Wire Listen Together Sync Callbacks
    LaunchedEffect(Unit) {
        listenTogetherViewModel.onSyncTrackChange = { track, queue ->
            playerViewModel.playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
        }
        listenTogetherViewModel.onSyncPlayPause = { isPlaying ->
            if (playerUiState.isPlaying != isPlaying) {
                playerViewModel.togglePlayPause()
            }
        }
        listenTogetherViewModel.onSyncSeek = { pos ->
            playerViewModel.seekTo(pos)
        }
    }

    // Host Broadcast Sync
    LaunchedEffect(playerUiState.currentTrack, playerUiState.isPlaying) {
        if (listenTogetherUiState.isHost && listenTogetherUiState.activeRoom != null) {
            val track = playerUiState.currentTrack
            if (track != null) {
                listenTogetherViewModel.broadcastHostPlayback(
                    currentTrack = track,
                    isPlaying = playerUiState.isPlaying,
                    playbackPositionMs = playerUiState.playbackPositionMs,
                    queue = playerUiState.queue
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (!isNowPlayingOpen) {
                    Column {
                        // Persistent Mini Player across all tabs
                        if (playerUiState.currentTrack != null) {
                            val progressFrac = if (playerUiState.durationMs > 0) {
                                (playerUiState.playbackPositionMs.toFloat() / playerUiState.durationMs).coerceIn(0f, 1f)
                            } else 0f

                            MiniPlayer(
                                track = playerUiState.currentTrack!!,
                                isPlaying = playerUiState.isPlaying,
                                progress = progressFrac,
                                isFavorite = playerUiState.isFavorite,
                                onPlayPauseClick = { playerViewModel.togglePlayPause() },
                                onNextClick = { playerViewModel.next() },
                                onFavoriteToggle = { playerViewModel.toggleFavorite() },
                                onAddToPlaylist = {
                                    showMiniPlayerTrackOptions = true
                                },
                                onArtistClick = {
                                    isListenTogetherOpen = true
                                },
                                onClick = { isNowPlayingOpen = true }
                            )
                        }

                        // Bottom Navigation Bar
                        NavigationBar(
                            containerColor = Color(0xFF0D0E0B),
                            tonalElevation = 8.dp
                        ) {
                            AppDestination.values().forEach { destination ->
                                NavigationBarItem(
                                    selected = currentDestination == destination && !isHistoryOpen && !isProfileOpen && !isListenTogetherOpen,
                                    onClick = {
                                        navigateToDestination(destination)
                                    },
                                    icon = { Icon(destination.icon, contentDescription = destination.label) },
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
            }
        )
 { paddingValues ->
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
                                        text = if (listenTogetherUiState.isHost) "Streaming to room" else "Synced with host",
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

                    // Main Navigation Screen
                    Box(modifier = Modifier.weight(1f)) {
                        when (currentDestination) {
                            AppDestination.HOME -> {
                                HomeScreen(
                                    uiState = homeUiState,
                                    currentTrackId = playerUiState.currentTrack?.id,
                                    isPlaying = playerUiState.isPlaying,
                                    userPlaylists = libraryUiState.playlists,
                                    onTrackClick = { track, queue ->
                                        playerViewModel.playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                                    },
                                    onFavoriteToggle = { playerViewModel.toggleFavorite() },
                                    onAddToPlaylist = { plId, track -> libraryViewModel.addTrackToPlaylist(plId, track) },
                                    onCreatePlaylistAndAdd = { title, track ->
                                        libraryViewModel.createPlaylist(title)
                                    },
                                    onOpenListenTogether = { isListenTogetherOpen = true },
                                    onNavigateToExplore = { navigateToDestination(AppDestination.EXPLORE) },
                                    onMoodSelect = { homeViewModel.selectMoodFilter(it) },
                                    onChipToggle = { homeViewModel.toggleChip(it) },
                                    onSurpriseMe = {
                                        val surpriseTrack = homeViewModel.getRandomSurpriseTrack()
                                        if (surpriseTrack != null) {
                                            playerViewModel.playTrack(surpriseTrack, listOf(surpriseTrack), 0)
                                        }
                                    },
                                    onOpenProfile = { isProfileOpen = true },
                                    onOpenHistory = { isHistoryOpen = true }
                                )
                            }
                            AppDestination.EXPLORE -> {
                                ExploreScreen(
                                    uiState = searchUiState,
                                    recognitionState = recognitionState,
                                    currentTrackId = playerUiState.currentTrack?.id,
                                    isPlaying = playerUiState.isPlaying,
                                    userPlaylists = libraryUiState.playlists,
                                    onQueryChange = { searchViewModel.onQueryChange(it) },
                                    onSearch = { searchViewModel.performSearch(it) },
                                    onClearSearch = { searchViewModel.clearSearch() },
                                    onTrackClick = { track, queue ->
                                        playerViewModel.playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                                    },
                                    onFavoriteToggle = { playerViewModel.toggleFavorite() },
                                    onAddToPlaylist = { plId, track -> libraryViewModel.addTrackToPlaylist(plId, track) },
                                    onCreatePlaylistAndAdd = { title, track ->
                                        libraryViewModel.createPlaylist(title)
                                    },
                                    onRemoveRecentQuery = { searchViewModel.removeRecentQuery(it) },
                                    onOpenRecognition = { searchViewModel.openRecognitionModal(it) },
                                    onCloseRecognition = { searchViewModel.closeRecognitionModal() },
                                    onModeSelect = { searchViewModel.setRecognitionMode(it) },
                                    onStartListening = { searchViewModel.startListening() },
                                    onStopListening = { searchViewModel.stopListening() }
                                )
                            }
                            AppDestination.LIBRARY -> {
                                LibraryScreen(
                                    uiState = libraryUiState,
                                    currentTrackId = playerUiState.currentTrack?.id,
                                    isPlaying = playerUiState.isPlaying,
                                    onFilterSelect = { libraryViewModel.setFilter(it) },
                                    onCreatePlaylist = { libraryViewModel.createPlaylist(it) },
                                    onDeletePlaylist = { libraryViewModel.deletePlaylist(it) },
                                    onPlaylistSelect = { libraryViewModel.selectPlaylist(it?.id) },
                                    onTrackClick = { track, queue ->
                                        playerViewModel.playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                                    },
                                    onFavoriteToggle = { playerViewModel.toggleFavorite() },
                                    onAddToPlaylist = { plId, track -> libraryViewModel.addTrackToPlaylist(plId, track) },
                                    onRemoveFromPlaylist = { plId, trackId -> libraryViewModel.removeTrackFromPlaylist(plId, trackId) },
                                    onImportYouTubePlaylist = { libraryViewModel.importYouTubePlaylist(it) },
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
                                    onAddToQueue = { tracks -> playerViewModel.addToQueue(tracks) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Fullscreen Expandable Now Playing Modal Sheet (Root Overlay, takes 100% of the screen)
        AnimatedVisibility(
            visible = isNowPlayingOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            NowPlayingSheet(
                uiState = playerUiState,
                userPlaylists = libraryUiState.playlists,
                onPlayPauseClick = { playerViewModel.togglePlayPause() },
                onSeekTo = { playerViewModel.seekTo(it) },
                onNextClick = { playerViewModel.next() },
                onPreviousClick = { playerViewModel.previous() },
                onToggleShuffle = { playerViewModel.toggleShuffle() },
                onToggleRepeat = { playerViewModel.toggleRepeat() },
                onToggleFavorite = { playerViewModel.toggleFavorite() },
                onToggleLyricsView = { playerViewModel.toggleLyricsView() },
                onLyricsOffsetChange = { playerViewModel.setLyricsOffset(it) },
                onSleepTimerSelect = { playerViewModel.setSleepTimer(it) },
                onSelectQueueTrack = { index ->
                    val t = playerUiState.queue.getOrNull(index)
                    if (t != null) {
                        playerViewModel.playTrack(t, playerUiState.queue, index)
                    }
                },
                onAddToPlaylist = { plId, track -> libraryViewModel.addTrackToPlaylist(plId, track) },
                onCreatePlaylistAndAdd = { title, track -> libraryViewModel.createPlaylist(title) },
                onDismiss = { isNowPlayingOpen = false }
            )
        }
        // Listen Together Sheet
        if (isListenTogetherOpen) {
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
                onDismiss = { isListenTogetherOpen = false }
            )
        }

        // Profile & YouTube Music Account Sync Modal Sheet
        if (isProfileOpen) {
            val context = androidx.compose.ui.platform.LocalContext.current
            ProfileSheet(
                authUiState = authUiState,
                onConnectWithGoogleOAuth = { authViewModel.connectWithGoogleOAuth(context) },
                onConnectOAuthToken = { token -> authViewModel.connectWithOAuthToken(token) },
                onOpenPlaylistSelector = { authViewModel.openPlaylistSelectDialog() },
                onSyncLikedMusic = { authViewModel.syncLikedMusic() },
                onDisconnect = {
                    authViewModel.disconnectAccount()
                    isGuestSessionActive = false
                    isProfileOpen = false
                },
                onClosePlaylistSelector = { authViewModel.closePlaylistSelectDialog() },
                onTogglePlaylistSelection = { authViewModel.togglePlaylistSelection(it) },
                onSelectAllPlaylists = { authViewModel.selectAllPlaylists() },
                onImportSelectedPlaylists = { authViewModel.importSelectedPlaylists() },
                onDismiss = { isProfileOpen = false }
            )
        }

        // Listening History Modal Sheet
        if (isHistoryOpen) {
            com.auralis.music.ui.history.HistorySheet(
                history = homeUiState.recentTracks,
                currentTrackId = playerUiState.currentTrack?.id,
                isPlaying = playerUiState.isPlaying,
                onTrackClick = { track, queue ->
                    playerViewModel.playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
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
                    libraryViewModel.createPlaylist(title)
                    android.widget.Toast.makeText(context, "Created playlist $title", android.widget.Toast.LENGTH_SHORT).show()
                    showMiniPlayerTrackOptions = false
                },
                onDismiss = { showMiniPlayerTrackOptions = false }
            )
        }
    }
}
