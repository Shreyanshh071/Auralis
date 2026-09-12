package com.auralis.music.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.components.MiniPlayer
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.profile.ProfileSheet
import com.auralis.music.ui.player.MiniPlayerHeight
import com.auralis.music.ui.screens.*
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
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
    viewModelProvider: AppViewModelProvider,
    googleAccountSyncManager: com.auralis.music.domain.auth.GoogleAccountSyncManager? = null,
    appearanceSettings: com.auralis.music.domain.model.AppearanceSettings = com.auralis.music.domain.model.AppearanceSettings(),
    initialNavDestination: String? = null,
    modifier: Modifier = Modifier
) {
    // Only HomeViewModel is created eagerly on cold launch to display Home immediately
    val homeViewModel = remember { viewModelProvider.getHomeViewModel() }
    val homeUiState by homeViewModel.uiState.collectAsState()

    val initialDestination = remember(appearanceSettings.defaultOpenTab) {
        when (appearanceSettings.defaultOpenTab) {
            "Explore" -> AppDestination.EXPLORE
            "Library" -> AppDestination.LIBRARY
            else -> AppDestination.HOME
        }
    }
    var currentDestination by remember { mutableStateOf(initialDestination) }
    val destinationBackStack = remember { androidx.compose.runtime.mutableStateListOf<AppDestination>() }
    val visitedDestinations = remember { androidx.compose.runtime.mutableStateListOf(initialDestination) }
    LaunchedEffect(currentDestination) {
        if (!visitedDestinations.contains(currentDestination)) {
            visitedDestinations.add(currentDestination)
        }
    }

    // ── On-Demand ViewModels ──
    // PlayerViewModel: only initialized early if there is already an active track playing in background
    var playerViewModelState by remember {
        mutableStateOf<PlayerViewModel?>(
            if (viewModelProvider.audioPlayer.currentTrack.value != null) {
                viewModelProvider.getPlayerViewModel()
            } else {
                null
            }
        )
    }
    fun obtainPlayerViewModel(): PlayerViewModel {
        val existing = playerViewModelState
        if (existing != null) return existing
        val vm = viewModelProvider.getPlayerViewModel()
        playerViewModelState = vm
        return vm
    }
    LaunchedEffect(viewModelProvider.audioPlayer) {
        viewModelProvider.audioPlayer.currentTrack.collect { track ->
            if (track != null && playerViewModelState == null) {
                playerViewModelState = viewModelProvider.getPlayerViewModel()
            }
        }
    }
    val playerUiState = playerViewModelState?.uiState?.collectAsState()?.value ?: com.auralis.music.ui.viewmodel.PlayerUiState()
    val playerSettings = playerViewModelState?.playerSettings?.collectAsState()?.value ?: com.auralis.music.domain.model.PlayerSettings()

    // SearchViewModel: only initialized when Explore tab is active or search/artist/album/recognition is used
    var searchViewModelState by remember {
        mutableStateOf<SearchViewModel?>(
            if (initialDestination == AppDestination.EXPLORE) viewModelProvider.getSearchViewModel() else null
        )
    }
    fun obtainSearchViewModel(): SearchViewModel {
        val existing = searchViewModelState
        if (existing != null) return existing
        val vm = viewModelProvider.getSearchViewModel()
        searchViewModelState = vm
        return vm
    }
    val searchUiState = searchViewModelState?.uiState?.collectAsState()?.value ?: com.auralis.music.ui.viewmodel.SearchUiState()
    val recognitionState = searchViewModelState?.recognitionState?.collectAsState()?.value ?: com.auralis.music.domain.recognition.RecognitionState()
    val recognitionHistory = searchViewModelState?.recognitionHistory?.collectAsState()?.value ?: emptyList()

    // LibraryViewModel: only initialized when Library tab is active or playlist actions are used
    var libraryViewModelState by remember {
        mutableStateOf<LibraryViewModel?>(
            if (initialDestination == AppDestination.LIBRARY) viewModelProvider.getLibraryViewModel() else null
        )
    }
    fun obtainLibraryViewModel(): LibraryViewModel {
        val existing = libraryViewModelState
        if (existing != null) return existing
        val vm = viewModelProvider.getLibraryViewModel()
        libraryViewModelState = vm
        return vm
    }
    val libraryUiState = libraryViewModelState?.uiState?.collectAsState()?.value ?: com.auralis.music.ui.viewmodel.LibraryUiState()

    // ListenTogetherViewModel: only initialized when Listen Together feature is opened
    var listenTogetherViewModelState by remember {
        mutableStateOf<ListenTogetherViewModel?>(null)
    }
    fun obtainListenTogetherViewModel(): ListenTogetherViewModel {
        val existing = listenTogetherViewModelState
        if (existing != null) return existing
        val vm = viewModelProvider.getListenTogetherViewModel()
        listenTogetherViewModelState = vm
        return vm
    }
    val listenTogetherUiState = listenTogetherViewModelState?.uiState?.collectAsState()?.value ?: com.auralis.music.ui.viewmodel.ListenTogetherUiState()

    // Auth-related state: does not block first frame
    val isFirebaseUserActive = try {
        val fbUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        fbUser != null && !fbUser.isAnonymous
    } catch (_: Exception) { false }
    val isGoogleUserConnected = googleAccountSyncManager?.userProfile?.value?.isGoogleConnected == true &&
            googleAccountSyncManager.userProfile.value.uid.isNotBlank()
    val isInitialLoggedIn = isFirebaseUserActive || isGoogleUserConnected

    var authViewModelState by remember {
        mutableStateOf<AuthViewModel?>(
            if (!isInitialLoggedIn) viewModelProvider.getAuthViewModel() else null
        )
    }
    fun obtainAuthViewModel(): AuthViewModel {
        val existing = authViewModelState
        if (existing != null) return existing
        val vm = viewModelProvider.getAuthViewModel()
        authViewModelState = vm
        return vm
    }
    val authUiState = authViewModelState?.uiState?.collectAsState()?.value ?: com.auralis.music.ui.viewmodel.AuthUiState()

    // StatsViewModel: only initialized when Stats screen is opened
    var statsViewModelState by remember {
        mutableStateOf<StatsViewModel?>(null)
    }
    fun obtainStatsViewModel(): StatsViewModel {
        val existing = statsViewModelState
        if (existing != null) return existing
        val vm = viewModelProvider.getStatsViewModel()
        statsViewModelState = vm
        return vm
    }

    LaunchedEffect(currentDestination) {
        when (currentDestination) {
            AppDestination.EXPLORE -> obtainSearchViewModel()
            AppDestination.LIBRARY -> obtainLibraryViewModel()
            else -> Unit
        }
    }

    var showUpdaterFromNav by remember { mutableStateOf(initialNavDestination == "updater") }
    LaunchedEffect(initialNavDestination) {
        if (initialNavDestination == "updater") {
            showUpdaterFromNav = true
        }
    }
    val coroutineScope = rememberCoroutineScope()
    var hasAppliedDefaultTab by remember { mutableStateOf(false) }

    LaunchedEffect(appearanceSettings.defaultOpenTab) {
        val target = when (appearanceSettings.defaultOpenTab) {
            "Explore" -> AppDestination.EXPLORE
            "Library" -> AppDestination.LIBRARY
            else -> AppDestination.HOME
        }
        if (!hasAppliedDefaultTab || destinationBackStack.isEmpty()) {
            currentDestination = target
            hasAppliedDefaultTab = true
        }
    }

    var isNowPlayingOpen by remember { mutableStateOf(false) }
    var isStatsOpen by rememberSaveable { mutableStateOf(false) }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(isNowPlayingOpen) {
        if (isNowPlayingOpen) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    LaunchedEffect(currentDestination) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    var isListenTogetherOpen by remember { mutableStateOf(false) }
    var isProfileOpen by remember { mutableStateOf(false) }
    var isHistoryOpen by remember { mutableStateOf(false) }
    var showMiniPlayerTrackOptions by remember { mutableStateOf(false) }
    var isExternalCreatePlaylistOpen by remember { mutableStateOf(false) }
    var isHomeMenuOpen by remember { mutableStateOf(false) }

    fun navigateToDestination(dest: AppDestination) {
        if (currentDestination != dest) {
            destinationBackStack.add(currentDestination)
            currentDestination = dest
        }
        isHomeMenuOpen = false
        isHistoryOpen = false
        isProfileOpen = false
        isListenTogetherOpen = false
        isStatsOpen = false
        searchViewModelState?.closeRecognitionModal()
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    // ── Dedicated Background Audio Engine Host ──
    val needsWebView by (playerViewModelState?.getAudioPlayer()?.needsWebView?.collectAsState()
        ?: remember { mutableStateOf(false) })
    if (needsWebView) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(-1f)
        ) {
            AndroidView(
                factory = { ctx ->
                    playerViewModelState?.getAudioPlayer()?.getOrCreateWebView(ctx) ?: android.view.View(ctx)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
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

    val isUserLoggedIn = isFirebaseUserActive || (authUiState.profile.isGoogleConnected && authUiState.profile.uid.isNotBlank())
    val isAppUnlocked = isUserLoggedIn

    if (!isAppUnlocked) {
        val authVM = obtainAuthViewModel()
        com.auralis.music.ui.onboarding.WelcomeScreen(
            authUiState = authUiState,
            onContinueWithGoogle = {
                val act = context.findActivity()
                if (act != null) {
                    authVM.signInWithGoogle(act)
                } else {
                    android.widget.Toast.makeText(context, "Activity not found for Google Sign-In", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onSignUpWithEmail = { email, password, name ->
                authVM.signUpWithEmail(email, password, name) {}
            },
            onSignInWithEmail = { email, password ->
                authVM.signInWithEmail(email, password) {}
            }
        )
        return
    }

    val isGuestInRoom = listenTogetherUiState.activeRoom != null && !listenTogetherUiState.isHost

    fun notifyGuestControlBlocked() {
        android.widget.Toast.makeText(context, "Playback is controlled by the room host", android.widget.Toast.LENGTH_SHORT).show()
    }

    androidx.activity.compose.BackHandler(
        enabled = isHomeMenuOpen ||
                searchUiState.detailStack.isNotEmpty() ||
                searchUiState.selectedArtistPage != null ||
                searchUiState.selectedAlbum != null ||
                isNowPlayingOpen ||
                isHistoryOpen ||
                isStatsOpen ||
                isProfileOpen ||
                isListenTogetherOpen ||
                destinationBackStack.isNotEmpty() ||
                currentDestination != AppDestination.HOME
    ) {
        if (isHomeMenuOpen) isHomeMenuOpen = false
        else if (searchUiState.detailStack.isNotEmpty()) searchViewModelState?.popDetail()
        else if (searchUiState.selectedArtistPage != null) searchViewModelState?.closeArtist()
        else if (searchUiState.selectedAlbum != null) searchViewModelState?.closeAlbum()
        else if (isNowPlayingOpen) isNowPlayingOpen = false
        else if (isStatsOpen) isStatsOpen = false
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

    // Sync guest mode with audio player
    LaunchedEffect(listenTogetherUiState.activeRoom, listenTogetherUiState.isHost, playerViewModelState) {
        val isGuest = listenTogetherUiState.activeRoom != null && !listenTogetherUiState.isHost
        playerViewModelState?.getAudioPlayer()?.setGuestListenTogether(isGuest)
    }

    // Wire Listen Together Sync Callbacks
    val currentLT = listenTogetherViewModelState
    val currentPV = playerViewModelState
    if (currentLT != null && currentPV != null) {
        LaunchedEffect(currentLT, currentPV) {
            currentLT.onSyncTrackChange = { track, queue, startPosMs ->
                val idx = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                currentPV.getAudioPlayer()?.syncPlayTrack(track, queue, idx, initialPositionMs = startPosMs)
            }
            currentLT.onSyncResume = {
                currentPV.getAudioPlayer()?.syncResume()
            }
            currentLT.onSyncPause = {
                currentPV.getAudioPlayer()?.syncPause()
            }
            currentLT.onSyncSeek = { pos ->
                currentPV.getAudioPlayer()?.syncSeek(pos)
            }
            currentLT.onGetLocalPosition = {
                currentPV.getPlaybackPosition()
            }
            currentLT.onGetLocalIsPlaying = {
                currentPV.uiState.value.isPlaying
            }
            currentLT.onGetLocalTrackId = {
                currentPV.uiState.value.currentTrack?.id
            }
            currentLT.onHostPlayTrack = { track ->
                val curQueue = currentPV.uiState.value.queue
                val newQueue = if (curQueue.none { it.id == track.id }) curQueue + track else curQueue
                val index = newQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                currentPV.playTrack(track, newQueue, index)
            }
            currentLT.onHostAddToQueue = { track ->
                currentPV.addToQueue(listOf(track))
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
                    currentLT.broadcastHostPlayback(
                        currentTrack = track,
                        isPlaying = playerUiState.isPlaying,
                        playbackPositionMs = currentPV.getPlaybackPosition(),
                        queue = playerUiState.queue
                    )

                    while (playerUiState.isPlaying) {
                        kotlinx.coroutines.delay(5000L)
                        val curTrack = currentPV.uiState.value.currentTrack
                        if (curTrack != null && currentPV.uiState.value.isPlaying) {
                            currentLT.broadcastHostPlayback(
                                currentTrack = curTrack,
                                isPlaying = true,
                                playbackPositionMs = currentPV.getPlaybackPosition(),
                                queue = currentPV.uiState.value.queue
                            )
                        }
                    }
                }
            }
        }
    }

    // One SharedTransitionLayout for the whole app: the mini-player lives in the
    // Scaffold's bottom bar and Now Playing is a sibling overlay, so the only way the
    // two can hand the artwork over is through a shared parent that outlives both.
    // Behaves like a Box (children are stacked), so the existing layering is intact.
    SharedTransitionLayout(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.dynamicBackground)
    ) {
        val playerSharedScope = this
        val hazeState = remember { dev.chrisbanes.haze.HazeState() }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.dynamicBackground,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                val isSubScreenOpen = isProfileOpen || isHistoryOpen || isListenTogetherOpen || isStatsOpen
                if (!isSubScreenOpen) {
                    // Floating Bottom Navigation Dock
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .graphicsLayer {
                                alpha = if (isNowPlayingOpen) 0f else 1f
                            }
                    ) {
                        com.auralis.music.ui.components.AuralisFloatingDock(
                            currentDestination = currentDestination,
                            hazeState = hazeState,
                            artworkUrl = playerUiState.currentTrack?.thumbnail,
                            isPlaylistDetailOpen = libraryUiState.selectedPlaylist != null,
                            onDestinationClick = { destination ->
                                isHomeMenuOpen = false
                                navigateToDestination(destination)
                            },
                            onToggleHomeMenu = {
                                isHomeMenuOpen = !isHomeMenuOpen
                            },
                            onCreatePlaylist = {
                                isExternalCreatePlaylistOpen = true
                            }
                        )
                    }
                }
            }
        ) { _ ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .hazeSource(state = hazeState)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Active Listen Together Banner (if connected to a room)
                    if (listenTogetherUiState.activeRoom != null) {
                        val room = listenTogetherUiState.activeRoom!!
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    obtainListenTogetherViewModel()
                                    isListenTogetherOpen = true
                                }
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
                            if (visitedDestinations.contains(destination)) {
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
                                                currentTrack = playerUiState.currentTrack,
                                                currentTrackId = playerUiState.currentTrack?.id,
                                                isPlaying = playerUiState.isPlaying,
                                                userPlaylists = libraryUiState.playlists,
                                                favoriteTracks = libraryUiState.favorites,
                                                onTrackClick = { track, queue ->
                                                    if (isGuestInRoom) notifyGuestControlBlocked()
                                                    else obtainPlayerViewModel().playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                                                },
                                                onFavoriteToggle = { track -> obtainPlayerViewModel().toggleFavorite(track) },
                                                onAddToPlaylist = { plId, track -> obtainLibraryViewModel().addTrackToPlaylist(plId, track) },
                                                onCreatePlaylistAndAdd = { title, track ->
                                                    obtainLibraryViewModel().createPlaylistAndAddTrack(title, track)
                                                },
                                                onPlayNext = { track ->
                                                    obtainPlayerViewModel().playNext(track)
                                                    android.widget.Toast.makeText(context, "Playing next: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onAddToQueue = { track ->
                                                    obtainPlayerViewModel().addToQueue(listOf(track))
                                                    android.widget.Toast.makeText(context, "Added to queue: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onStartRadio = { track ->
                                                    if (isGuestInRoom) notifyGuestControlBlocked()
                                                    else obtainPlayerViewModel().playTrack(track, listOf(track), 0)
                                                },
                                                onOpenListenTogether = {
                                                    obtainListenTogetherViewModel()
                                                    isListenTogetherOpen = true
                                                },
                                                onNavigateToExplore = { navigateToDestination(AppDestination.EXPLORE) },
                                                onMoodSelect = { mood -> homeViewModel.selectMoodFilter(mood) },
                                                onChipToggle = { chip -> homeViewModel.toggleChip(chip) },
                                                onSurpriseMe = {
                                                    if (isGuestInRoom) {
                                                        notifyGuestControlBlocked()
                                                    } else {
                                                        val surpriseTrack = homeViewModel.getRandomSurpriseTrack()
                                                        if (surpriseTrack != null) {
                                                            obtainPlayerViewModel().playTrack(surpriseTrack, listOf(surpriseTrack), 0)
                                                        }
                                                    }
                                                },
                                                onOpenProfile = {
                                                    obtainAuthViewModel()
                                                    obtainLibraryViewModel()
                                                    isProfileOpen = true
                                                },
                                                onOpenHistory = { isHistoryOpen = true },
                                                onOpenStats = {
                                                    obtainStatsViewModel()
                                                    isStatsOpen = true
                                                },
                                                onArtistClick = { artist ->
                                                    obtainSearchViewModel().openArtist(artist)
                                                    navigateToDestination(AppDestination.EXPLORE)
                                                },
                                                onAlbumClick = { album ->
                                                    obtainSearchViewModel().openAlbum(album)
                                                    navigateToDestination(AppDestination.EXPLORE)
                                                },
                                                isInListenTogetherRoom = isGuestInRoom,
                                                onRecommendToRoom = { trk ->
                                                    obtainListenTogetherViewModel().recommendSong(trk)
                                                    android.widget.Toast.makeText(context, "Recommended \"${trk.title}\" to room!", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }

                                        AppDestination.EXPLORE -> {
                                            val searchVM = obtainSearchViewModel()
                                            val libVM = obtainLibraryViewModel()
                                            com.auralis.music.ui.explore.ExploreScreen(
                                                uiState = searchUiState,
                                                recognitionState = recognitionState,
                                                currentTrackId = playerUiState.currentTrack?.id,
                                                isPlaying = playerUiState.isPlaying,
                                                userPlaylists = libraryUiState.playlists,
                                                favoriteTracks = libraryUiState.favorites,
                                                savedArtists = libraryUiState.savedArtists,
                                                onToggleSubscribe = { libVM.toggleSaveArtist(it) },
                                                onQueryChange = { searchVM.onQueryChange(it) },
                                                onSearch = { searchVM.performSearch(it) },
                                                onClearSearch = { searchVM.clearSearch() },
                                                onTrackClick = { track, list ->
                                                    if (isGuestInRoom) notifyGuestControlBlocked()
                                                    else obtainPlayerViewModel().playTrack(
                                                        track = track,
                                                        newQueue = if (list.isNotEmpty()) list else listOf(track),
                                                        startIndex = if (list.isNotEmpty()) list.indexOfFirst { it.id == track.id }.coerceAtLeast(0) else 0
                                                    )
                                                },
                                                onFavoriteToggle = { track -> obtainPlayerViewModel().toggleFavorite(track) },
                                                onAddToPlaylist = { plId, track -> libVM.addTrackToPlaylist(plId, track) },
                                                onCreatePlaylistAndAdd = { title, track ->
                                                    libVM.createPlaylistAndAddTrack(title, track)
                                                },
                                                onPlayNext = { track ->
                                                    obtainPlayerViewModel().playNext(track)
                                                    android.widget.Toast.makeText(context, "Playing next: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onAddToQueue = { track ->
                                                    obtainPlayerViewModel().addToQueue(listOf(track))
                                                    android.widget.Toast.makeText(context, "Added to queue: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onStartRadio = { track ->
                                                    if (isGuestInRoom) notifyGuestControlBlocked()
                                                    else obtainPlayerViewModel().playTrack(track, listOf(track), 0)
                                                },
                                                onRemoveRecentQuery = { searchVM.removeRecentQuery(it) },
                                                onOpenRecognition = { searchVM.openRecognitionModal(it) },
                                                onCloseRecognition = { searchVM.closeRecognitionModal() },
                                                onModeSelect = { searchVM.setRecognitionMode(it) },
                                                onStartListening = { searchVM.startListening() },
                                                onStopListening = { searchVM.stopListening() },
                                                onOpenArtist = { searchVM.openArtist(it) },
                                                onCloseArtist = { searchVM.closeArtist() },
                                                onOpenAlbum = { searchVM.openAlbum(it) },
                                                onCloseAlbum = { searchVM.closeAlbum() },
                                                onAlbumClick = { album ->
                                                    searchVM.openAlbum(album)
                                                },
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
                                                    obtainListenTogetherViewModel().recommendSong(trk)
                                                    android.widget.Toast.makeText(context, "Recommended \"${trk.title}\" to room!", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }

                                        AppDestination.LIBRARY -> {
                                            val libVM = obtainLibraryViewModel()
                                            LibraryScreen(
                                                uiState = libraryUiState,
                                                currentTrackId = playerUiState.currentTrack?.id,
                                                isPlaying = playerUiState.isPlaying,
                                                userName = authUiState.profile.displayName.ifBlank { "You" },
                                                userAvatarUrl = authUiState.profile.avatarUrl,
                                                onFilterSelect = { libVM.setFilter(it) },
                                                onCreatePlaylist = { libVM.createPlaylist(it) },
                                                onDeletePlaylist = { libVM.deletePlaylist(it) },
                                                onPlaylistSelect = { libVM.selectPlaylist(it?.id, it) },
                                                onTrackClick = { track, queue ->
                                                    if (isGuestInRoom) notifyGuestControlBlocked()
                                                    else obtainPlayerViewModel().playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                                                },
                                                onFavoriteToggle = { track -> obtainPlayerViewModel().toggleFavorite(track) },
                                                onAddToPlaylist = { plId, track -> libVM.addTrackToPlaylist(plId, track) },
                                                onRemoveFromPlaylist = { plId, trackId -> libVM.removeTrackFromPlaylist(plId, trackId) },
                                                onImportYouTubePlaylist = { libVM.importYouTubePlaylist(it) },
                                                onImportSpotifyPlaylist = { libVM.importSpotifyPlaylist(it) },
                                                onExportBackup = suspend { libVM.exportLibraryJson() },
                                                onImportBackup = { libVM.importLibraryJson(it) },
                                                onSmartCollectionClick = { libVM.openSmartCollection(it) },
                                                onSortChange = { libVM.setSortOrder(it) },
                                                onToggleGridView = { libVM.toggleGridView() },
                                                onOpenHistory = { isHistoryOpen = true },
                                                onOpenListenTogether = {
                                                    obtainListenTogetherViewModel()
                                                    isListenTogetherOpen = true
                                                },
                                                onOpenProfile = {
                                                    obtainAuthViewModel()
                                                    obtainLibraryViewModel()
                                                    isProfileOpen = true
                                                },
                                                onSyncPlaylist = { pl -> libVM.syncPlaylist(pl) },
                                                onEditPlaylist = { id, title, desc, coverUrl -> libVM.editPlaylist(id, title, desc, coverUrl) },
                                                onAddToQueue = { tracks ->
                                                    if (isGuestInRoom) notifyGuestControlBlocked()
                                                    else obtainPlayerViewModel().addToQueue(tracks)
                                                },
                                                onPlayNext = { track ->
                                                    obtainPlayerViewModel().playNext(track)
                                                    android.widget.Toast.makeText(context, "Playing next: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onAddToQueueTrack = { track ->
                                                    obtainPlayerViewModel().addToQueue(listOf(track))
                                                    android.widget.Toast.makeText(context, "Added to queue: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onStartRadio = { track ->
                                                    if (isGuestInRoom) notifyGuestControlBlocked()
                                                    else obtainPlayerViewModel().playTrack(track, listOf(track), 0)
                                                },
                                                onOpenArtist = { artist ->
                                                    obtainSearchViewModel().openArtist(artist)
                                                    navigateToDestination(AppDestination.EXPLORE)
                                                },
                                                isInListenTogetherRoom = isGuestInRoom,
                                                onRecommendToRoom = { trk ->
                                                    obtainListenTogetherViewModel().recommendSong(trk)
                                                    android.widget.Toast.makeText(context, "Recommended \"${trk.title}\" to room!", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onReorderPlaylistTracks = { plId, from, to ->
                                                    libVM.reorderPlaylistTracks(plId, from, to)
                                                },
                                                isExternalCreateDialogOpen = isExternalCreatePlaylistOpen,
                                                onCloseExternalCreateDialog = { isExternalCreatePlaylistOpen = false }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Listen Together Sheet with unified navigation transition
        AnimatedVisibility(
            visible = isListenTogetherOpen,
            enter = auralisNavigationEnter(),
            exit = auralisNavigationExit()
        ) {
            val ltVM = obtainListenTogetherViewModel()
            ListenTogetherSheet(
                uiState = listenTogetherUiState,
                currentTrack = playerUiState.currentTrack,
                isPlaying = playerUiState.isPlaying,
                queue = playerUiState.queue,
                playbackPositionMs = playerUiState.playbackPositionMs,
                onNameChange = { ltVM.setDisplayName(it) },
                onCreateRoom = { trk, q, playing, pos ->
                    ltVM.createRoom(trk, q, playing, pos)
                },
                onJoinRoom = { code ->
                    ltVM.joinRoom(code)
                },
                onLeaveRoom = {
                    ltVM.leaveRoom()
                },
                onSearchRecommendations = { query ->
                    ltVM.searchRecommendations(query)
                },
                onClearRecommendationSearch = {
                    ltVM.clearRecommendationSearch()
                },
                onRecommendSong = { trk, note ->
                    ltVM.recommendSong(trk, note)
                },
                onUpvoteRecommendation = { recId ->
                    ltVM.upvoteRecommendation(recId)
                },
                onDismissRecommendation = { recId ->
                    ltVM.dismissRecommendation(recId)
                },
                onPlayRecommendationNow = { rec ->
                    ltVM.playRecommendationNow(rec)
                },
                onAddRecommendationToQueue = { rec ->
                    ltVM.addRecommendationToQueue(rec)
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
            val authVM = obtainAuthViewModel()
            val libVM = obtainLibraryViewModel()
            ProfileSheet(
                authUiState = authUiState,
                playerSettings = playerSettings,
                onThemeModeChange = { obtainPlayerViewModel().updateThemeMode(it) },
                onAudioQualityChange = { obtainPlayerViewModel().updateAudioQuality(it) },
                onToggleGaplessPlayback = { obtainPlayerViewModel().toggleGaplessPlayback(it) },
                onToggleSkipSilence = { obtainPlayerViewModel().toggleSkipSilence(it) },
                onToggleSpatialAudio = { obtainPlayerViewModel().toggleSpatialAudio(it) },
                onClearCache = {
                    com.auralis.music.data.network.AudioStreamResolver.clearCache()
                    com.auralis.music.ui.theme.ArtworkPaletteCache.clear()
                },
                onImportYouTubePlaylist = { libVM.importYouTubePlaylist(it) },
                onClearYouTubeImportMessage = { libVM.clearYouTubeImportMessage() },
                isImportingYouTube = libraryUiState.isImporting,
                youtubeImportMessage = libraryUiState.importMessage,
                onOpenPlaylistSelector = { authVM.openPlaylistSelectDialog() },
                onSyncLikedMusic = { authVM.syncLikedMusic() },
                onDisconnect = {
                    authVM.disconnectAccount()
                    isProfileOpen = false
                },
                onClosePlaylistSelector = { authVM.closePlaylistSelectDialog() },
                onTogglePlaylistSelection = { authVM.togglePlaylistSelection(it) },
                onSelectAllPlaylists = { authVM.selectAllPlaylists() },
                onDeselectAllPlaylists = { authVM.deselectAllPlaylists() },
                onImportSelectedPlaylists = { authVM.importSelectedPlaylists() },
                onImportSpotifyPlaylist = { libVM.importSpotifyPlaylist(it) },
                onClearSpotifyImportMessage = { libVM.clearSpotifyImportMessage() },
                isImportingSpotify = libraryUiState.isImportingSpotify,
                spotifyImportMessage = libraryUiState.spotifyImportMessage,
                onDismiss = {
                    libVM.clearSpotifyImportMessage()
                    libVM.clearYouTubeImportMessage()
                    isProfileOpen = false
                },
                historyRepository = viewModelProvider.historyRepository,
                searchRepository = viewModelProvider.searchRepository,
                hasActiveTrack = playerUiState.currentTrack != null
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
                    else obtainPlayerViewModel().playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                },
                onRemoveFromHistory = { homeViewModel.removeFromHistory(it) },
                onClearHistory = { homeViewModel.clearHistory() },
                onDismiss = { isHistoryOpen = false }
            )
        }

        // Listening Stats Modal Sheet
        AnimatedVisibility(
            visible = isStatsOpen,
            enter = auralisNavigationEnter(),
            exit = auralisNavigationExit()
        ) {
            val statsVM = obtainStatsViewModel()
            val libVM = obtainLibraryViewModel()
            com.auralis.music.ui.screens.StatsScreen(
                viewModel = statsVM,
                onDismiss = { isStatsOpen = false },
                onPlayTrack = { track, queue ->
                    if (isGuestInRoom) notifyGuestControlBlocked()
                    else obtainPlayerViewModel().playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                },
                onArtistClick = { artist ->
                    obtainSearchViewModel().openArtist(artist)
                    navigateToDestination(AppDestination.EXPLORE)
                    isStatsOpen = false
                },
                userPlaylists = libraryUiState.playlists,
                favoriteTracks = libraryUiState.favorites,
                onFavoriteToggle = { track -> obtainPlayerViewModel().toggleFavorite(track) },
                onAddToPlaylist = { plId, track -> libVM.addTrackToPlaylist(plId, track) },
                onCreatePlaylistAndAdd = { title, track ->
                    libVM.createPlaylistAndAddTrack(title, track)
                },
                onPlayNext = { track ->
                    obtainPlayerViewModel().playNext(track)
                    android.widget.Toast.makeText(context, "Playing next: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                },
                onAddToQueue = { track ->
                    obtainPlayerViewModel().addToQueue(listOf(track))
                    android.widget.Toast.makeText(context, "Added to queue: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                },
                hasActiveMiniPlayer = playerUiState.currentTrack != null
            )
        }

        // Fullscreen Expandable Now Playing Modal Sheet (Root Overlay, takes 100% of the screen above all sheets)
        AnimatedVisibility(
            visible = isNowPlayingOpen,
            enter = auralisSheetEnter(),
            exit = auralisSheetExit()
        ) {
            val currentPV = obtainPlayerViewModel()
            val currentLibVM = obtainLibraryViewModel()
            // Kept as State, not unwrapped with `by`: the modal takes the
            // position as State so the playback clock cannot drag this whole
            // overlay — or the modal — through a recomposition every tick.
            val modalPositionState = currentPV.playbackPositionMs.collectAsState()
            NowPlayingSheet(
                uiState = playerUiState,
                playbackPositionState = modalPositionState,
                lyricsClockSource = currentPV.playbackClockSource,
                userPlaylists = libraryUiState.playlists,
                onPlayPauseClick = {
                    if (isGuestInRoom) notifyGuestControlBlocked()
                    else currentPV.togglePlayPause()
                },
                onSeekTo = { posMs ->
                    if (isGuestInRoom) {
                        notifyGuestControlBlocked()
                    } else {
                        currentPV.seekTo(posMs)
                        if (listenTogetherUiState.isHost && listenTogetherUiState.activeRoom != null) {
                            playerUiState.currentTrack?.let { trk ->
                                obtainListenTogetherViewModel().broadcastHostPlayback(
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
                    else {
                        currentPV.next()
                    }
                },
                onPreviousClick = {
                    if (isGuestInRoom) notifyGuestControlBlocked()
                    else {
                        currentPV.previous()
                    }
                },
                onToggleShuffle = {
                    if (isGuestInRoom) notifyGuestControlBlocked()
                    else currentPV.toggleShuffle()
                },
                onToggleRepeat = {
                    if (isGuestInRoom) notifyGuestControlBlocked()
                    else currentPV.toggleRepeat()
                },
                onToggleFavorite = { currentPV.toggleFavorite() },
                onToggleLyricsView = { currentPV.toggleLyricsView() },
                onLyricsOffsetChange = { currentPV.setLyricsOffset(it) },
                onSearchLyricsManually = { title, artist -> currentPV.searchLyricsManually(title, artist) },
                onSleepTimerSelect = { currentPV.setSleepTimer(it) },
                onSelectQueueTrack = { index ->
                    if (isGuestInRoom) {
                        notifyGuestControlBlocked()
                    } else {
                        val q = playerUiState.queue
                        val t = q.getOrNull(index) ?: if (index == 0) playerUiState.currentTrack else null
                        if (t != null && !(index == playerUiState.currentIndex && t.id == playerUiState.currentTrack?.id)) {
                            currentPV.playTrack(t, if (q.isNotEmpty()) q else listOf(t), index)
                        }
                    }
                },
                onReorderQueue = { fromIndex, toIndex ->
                    if (isGuestInRoom) {
                        notifyGuestControlBlocked()
                    } else {
                        currentPV.moveQueueItem(fromIndex, toIndex)
                        if (listenTogetherUiState.isHost && listenTogetherUiState.activeRoom != null) {
                            playerUiState.currentTrack?.let { trk ->
                                obtainListenTogetherViewModel().broadcastHostPlayback(
                                    currentTrack = trk,
                                    isPlaying = playerUiState.isPlaying,
                                    playbackPositionMs = currentPV.playbackPositionMs.value,
                                    queue = playerUiState.queue
                                )
                            }
                        }
                    }
                },
                onAddToPlaylist = { plId, track -> currentLibVM.addTrackToPlaylist(plId, track) },
                onCreatePlaylistAndAdd = { title, track -> currentLibVM.createPlaylistAndAddTrack(title, track) },
                onArtistClick = { artist ->
                    isNowPlayingOpen = false
                    obtainSearchViewModel().openArtist(artist)
                    navigateToDestination(AppDestination.EXPLORE)
                },
                onDismiss = { isNowPlayingOpen = false },
                sharedTransitionScope = playerSharedScope,
                animatedVisibilityScope = this@AnimatedVisibility
            )
        }

        // Truly Floating Mini Player shown EVERYWHERE across all screens (Home, Explore, Library, Profile, Settings, Appearance, History, Listen Together)
        val activePV = playerViewModelState
        if (playerUiState.currentTrack != null && activePV != null) {
            val isSubScreenOpen = isProfileOpen || isHistoryOpen || isListenTogetherOpen || isStatsOpen
            MiniPlayerHost(
                playerViewModel = activePV,
                playerUiState = playerUiState,
                isGuestInRoom = isGuestInRoom,
                isNowPlayingOpen = isNowPlayingOpen,
                hazeState = hazeState,
                appearanceSettings = appearanceSettings,
                isSubScreenOpen = isSubScreenOpen,
                playerSharedScope = playerSharedScope,
                onOpenNowPlaying = { isNowPlayingOpen = true },
                onOpenArtist = {
                    val track = playerUiState.currentTrack
                    if (track != null) {
                        obtainSearchViewModel().openArtist(com.auralis.music.domain.model.Artist(id = "", name = track.artist))
                        navigateToDestination(AppDestination.EXPLORE)
                    }
                },
                onAddToPlaylist = {
                    showMiniPlayerTrackOptions = true
                },
                notifyGuestControlBlocked = { notifyGuestControlBlocked() }
            )
        }

        // MiniPlayer Direct Add to Playlist Bottom Sheet
        if (showMiniPlayerTrackOptions && playerUiState.currentTrack != null) {
            val curTrack = playerUiState.currentTrack!!
            val libVM = obtainLibraryViewModel()
            com.auralis.music.ui.components.PlaylistPickerBottomSheet(
                track = curTrack,
                userPlaylists = libraryUiState.playlists,
                onAddToPlaylist = { playlist ->
                    libVM.addTrackToPlaylist(playlist.id, curTrack)
                    android.widget.Toast.makeText(context, "Added to ${playlist.title}", android.widget.Toast.LENGTH_SHORT).show()
                    showMiniPlayerTrackOptions = false
                },
                onCreatePlaylistAndAdd = { title ->
                    libVM.createPlaylistAndAddTrack(title, curTrack)
                    android.widget.Toast.makeText(context, "Created and added to $title", android.widget.Toast.LENGTH_SHORT).show()
                    showMiniPlayerTrackOptions = false
                },
                onDismiss = { showMiniPlayerTrackOptions = false }
            )
        }

        // Fullscreen Voice & Music Recognition Modal (Root Overlay)
        if (searchUiState.isRecognitionOpen) {
            val searchVM = obtainSearchViewModel()
            com.auralis.music.ui.search.VoiceAndMusicRecognitionModal(
                state = recognitionState,
                historyItems = recognitionHistory,
                onModeSelect = { searchVM.setRecognitionMode(it) },
                onStartListening = { searchVM.startListening() },
                onStopListening = { searchVM.stopListening() },
                onPlayIdentifiedTrack = { track ->
                    obtainPlayerViewModel().playTrack(track, listOf(track), 0)
                    searchVM.closeRecognitionModal()
                },
                onSearchQuery = { query ->
                    searchVM.performSearch(query)
                    navigateToDestination(AppDestination.EXPLORE)
                    searchVM.closeRecognitionModal()
                },
                onClearHistory = { searchVM.clearRecognitionHistory() },
                onRemoveHistoryItem = { searchVM.removeRecognitionHistoryItem(it) },
                onDismiss = { searchVM.closeRecognitionModal() }
            )
        }

        // Floating Home More Options Popup Overlay with full-screen dark dimmed scrim backdrop (overlaps everything)
        AnimatedVisibility(
            visible = isHomeMenuOpen && !isNowPlayingOpen && currentDestination == AppDestination.HOME,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { isHomeMenuOpen = false }
                    )
                    .navigationBarsPadding()
                    .padding(
                        end = 16.dp,
                        bottom = if (appearanceSettings.slimBottomNavigationBar) 58.dp else 70.dp
                    ),
                contentAlignment = Alignment.BottomEnd
            ) {
                val menuShape = RoundedCornerShape(24.dp)
                val artwork = playerUiState.currentTrack?.thumbnail

                Box(
                    modifier = Modifier
                        .width(235.dp)
                        .shadow(
                            elevation = 16.dp,
                            shape = menuShape,
                            ambientColor = Color.Black.copy(alpha = 0.40f),
                            spotColor = Color.Black.copy(alpha = 0.50f)
                        )
                        .clip(menuShape)
                        .hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = MaterialTheme.colorScheme.surface,
                                tint = HazeTint(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                                blurRadius = 28.dp,
                                noiseFactor = 0.03f
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.28f),
                                    Color.White.copy(alpha = 0.10f),
                                    Color.White.copy(alpha = 0.04f)
                                )
                            ),
                            shape = menuShape
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {} // Swallow click so it doesn't dismiss when tapping inside
                        )
                ) {
                    // Menu Items Column
                    Column(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Music Recognition
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .clickable {
                                    isHomeMenuOpen = false
                                    obtainSearchViewModel().openRecognitionModal(com.auralis.music.domain.recognition.RecognitionMode.MUSIC_IDENTIFY)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f))
                                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Music Recognition",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Shuffle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .clickable {
                                    isHomeMenuOpen = false
                                    val surprise = homeViewModel.getRandomSurpriseTrack()
                                    if (surprise != null) {
                                        obtainPlayerViewModel().playTrack(surprise, listOf(surprise), 0)
                                    } else {
                                        obtainPlayerViewModel().toggleShuffle()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f))
                                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Shuffle",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // ── FLOATING PILL NOTIFICATION (Listen Together & System Alerts) ──
        AnimatedVisibility(
            visible = listenTogetherUiState.pillNotification != null,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(tween(180)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(220, easing = FastOutLinearInEasing)
            ) + fadeOut(tween(180)),
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1000f)
                .statusBarsPadding()
                .padding(top = 10.dp, start = 20.dp, end = 20.dp)
        ) {
            val pill = listenTogetherUiState.pillNotification
            if (pill != null) {
                val (pillIcon, pillTint) = when (pill.type) {
                    com.auralis.music.ui.viewmodel.PillType.MEMBER_JOINED -> Icons.Default.Person to Color(0xFFD4E157)
                    com.auralis.music.ui.viewmodel.PillType.MEMBER_LEFT -> Icons.Default.Close to Color(0xFFFF8A80)
                    com.auralis.music.ui.viewmodel.PillType.HOST_DISCONNECTED -> Icons.Default.Close to Color(0xFFFF5252)
                    com.auralis.music.ui.viewmodel.PillType.INFO -> Icons.Default.Info to Color.White
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(
                        onClick = { obtainListenTogetherViewModel().dismissPill() },
                        shape = RoundedCornerShape(32.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            pillTint.copy(alpha = 0.45f)
                        ),
                        shadowElevation = 12.dp,
                        modifier = Modifier.wrapContentSize()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(pillTint.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = pillIcon,
                                    contentDescription = null,
                                    tint = pillTint,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = pill.message,
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        if (showUpdaterFromNav) {
            com.auralis.music.ui.screens.UpdaterScreen(
                onDismiss = { showUpdaterFromNav = false }
            )
        }
    }
}

/**
 * Isolated hosting container for [MiniPlayer] that observes the playback clock strictly within its own scope.
 * This prevents the high-frequency playback position updates from invalidating or recomposing the root [AuralisApp],
 * destination screens, or playlist rows.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MiniPlayerHost(
    playerViewModel: PlayerViewModel,
    playerUiState: PlayerUiState,
    isGuestInRoom: Boolean,
    isNowPlayingOpen: Boolean,
    hazeState: HazeState,
    appearanceSettings: com.auralis.music.domain.model.AppearanceSettings,
    isSubScreenOpen: Boolean,
    playerSharedScope: SharedTransitionScope,
    onOpenNowPlaying: () -> Unit,
    onOpenArtist: () -> Unit,
    onAddToPlaylist: () -> Unit,
    notifyGuestControlBlocked: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val miniPositionState: State<Long> = playerViewModel.playbackPositionMs.collectAsState()
    val currentTrack = playerUiState.currentTrack ?: return
    val miniDurationState: State<Long> = remember(playerUiState.durationMs, currentTrack.duration) {
        derivedStateOf {
            val d = playerUiState.durationMs
            if (d > 0L) d else (currentTrack.duration * 1000L)
        }
    }
    val miniProgressState = remember(miniPositionState, miniDurationState) {
        com.auralis.music.ui.player.ProgressState(
            positionState = miniPositionState,
            durationState = miniDurationState
        )
    }
    val miniProgressProvider: () -> Float = remember(miniProgressState) {
        { miniProgressState.progress }
    }
    val isClassicMini = appearanceSettings.miniPlayerDesign == "Classic mini player"
    val targetBottomPadding = if (isSubScreenOpen) {
        if (isClassicMini) 0.dp else 10.dp
    } else {
        if (appearanceSettings.slimBottomNavigationBar) 56.dp else 68.dp
    }
    val reducedMotion = LocalReducedMotion.current
    val miniPlayerBottomPadding by animateDpAsState(
        targetValue = targetBottomPadding,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "miniPlayerBottomPadding"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(bottom = miniPlayerBottomPadding),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = !isNowPlayingOpen,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140))
        ) {
            MiniPlayer(
                track = currentTrack,
                isPlaying = playerUiState.isPlaying,
                progressState = miniProgressState,
                progressProvider = miniProgressProvider,
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
                    else {
                        playerViewModel.next()
                    }
                },
                onPreviousClick = {
                    if (isGuestInRoom) notifyGuestControlBlocked()
                    else {
                        playerViewModel.previous()
                    }
                },
                onSelectQueueTrack = { index ->
                    if (isGuestInRoom) {
                        notifyGuestControlBlocked()
                    } else {
                        val q = playerUiState.queue
                        val t = q.getOrNull(index) ?: if (index == 0) currentTrack else null
                        if (t != null && !(index == playerUiState.currentIndex && t.id == currentTrack.id)) {
                            playerViewModel.playTrack(t, if (q.isNotEmpty()) q else listOf(t), index)
                        } else if (t == null) {
                            if (index > playerUiState.currentIndex) {
                                playerViewModel.next()
                            } else if (index < playerUiState.currentIndex) {
                                playerViewModel.previous()
                            }
                        }
                    }
                },
                onFavoriteToggle = { playerViewModel.toggleFavorite() },
                onAddToPlaylist = onAddToPlaylist,
                onArtistClick = onOpenArtist,
                onClose = {
                    playerViewModel.closePlayer()
                },
                onClick = onOpenNowPlaying,
                sharedTransitionScope = playerSharedScope,
                animatedVisibilityScope = this@AnimatedVisibility,
                hazeState = hazeState
            )
        }
    }
}

