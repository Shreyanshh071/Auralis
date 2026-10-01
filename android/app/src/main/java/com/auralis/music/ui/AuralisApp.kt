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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
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
import com.auralis.music.ui.components.BottomChrome
import com.auralis.music.ui.components.LocalBottomChrome
import com.auralis.music.ui.player.MiniPlayerHeight
import com.auralis.music.ui.player.PlayerBackground
import com.auralis.music.ui.player.PlayerBackgroundStyle
import com.auralis.music.ui.screens.*
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.AuralisSpring
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.theme.PlayerMotion
import com.auralis.music.ui.theme.auralisFadeEnter
import com.auralis.music.ui.theme.auralisFadeExit
import com.auralis.music.ui.theme.auralisDetailBackwardExit
import com.auralis.music.ui.theme.auralisDetailForwardEnter
import com.auralis.music.ui.theme.auralisPushEnter
import com.auralis.music.ui.theme.auralisPushExit
import com.auralis.music.ui.theme.auralisSheetEnter
import com.auralis.music.ui.theme.auralisSheetExit
import com.auralis.music.ui.theme.motionTween
import com.auralis.music.ui.viewmodel.*
import com.auralis.music.domain.model.Track
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
    var detailOriginDestination by remember { mutableStateOf<AppDestination?>(null) }
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
            if (viewModelProvider.audioPlayer.currentTrack.value != null ||
                viewModelProvider.audioPlayer.isPlaying.value ||
                viewModelProvider.audioPlayer.isBuffering.value
            ) {
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
    // Ground-truth track from the playback singleton — used as a fallback presence check
    // when the ViewModel mirror transiently becomes null during lifecycle gaps or rapid transitions.
    val audioPlayerTrack by viewModelProvider.audioPlayer.currentTrack.collectAsState()
    val audioPlayerIsPlaying by viewModelProvider.audioPlayer.isPlaying.collectAsState()
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
        mutableStateOf<ListenTogetherViewModel?>(viewModelProvider.existingListenTogetherViewModel())
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
        if (!hasAppliedDefaultTab) {
            currentDestination = target
            hasAppliedDefaultTab = true
        }
    }

    val playerSheetProgress = remember { Animatable(0f) }
    var isNowPlayingOpen by remember { mutableStateOf(false) }
    // The window flag, not View.keepScreenOn: some phones (seen on a Moto) ignored the view
    // setting. "Playing" comes from the audio player itself, which the screen's copy can lag.
    val keepAwakeActivity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val keepPlayerAwake = playerSettings.keepScreenOn && isNowPlayingOpen && (playerUiState.isPlaying || audioPlayerIsPlaying)
    DisposableEffect(keepAwakeActivity, keepPlayerAwake) {
        val window = keepAwakeActivity?.window
        if (keepPlayerAwake) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val isPlayerSheetActive by remember {
        derivedStateOf { playerSheetProgress.value > 0f || isNowPlayingOpen }
    }
    val isMiniPlayerVisible by remember {
        derivedStateOf { playerSheetProgress.value < 1f || !isNowPlayingOpen }
    }
    val isFullyCollapsed by remember {
        derivedStateOf { playerSheetProgress.value == 0f }
    }
    val dismissOffsetY = remember { Animatable(0f) }
    var isStatsOpen by rememberSaveable { mutableStateOf(false) }
    var sheetAnimationJob by remember { mutableStateOf<Job?>(null) }
    var dismissAnimationJob by remember { mutableStateOf<Job?>(null) }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val reducedMotion = LocalReducedMotion.current

    val expandPlayer: () -> Unit = {
        isNowPlayingOpen = true
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        sheetAnimationJob?.cancel()
        dismissAnimationJob?.cancel()
        sheetAnimationJob = coroutineScope.launch {
            try {
                playerSheetProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = if (reducedMotion) snap() else spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            } finally {
                isNowPlayingOpen = true
                if (playerSheetProgress.value > 0.95f) {
                    playerSheetProgress.snapTo(1f)
                }
            }
        }
    }

    val collapsePlayer: () -> Unit = {
        sheetAnimationJob?.cancel()
        dismissAnimationJob?.cancel()
        sheetAnimationJob = coroutineScope.launch {
            try {
                playerSheetProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = if (reducedMotion) snap() else spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            } finally {
                isNowPlayingOpen = false
                if (playerSheetProgress.value < 0.05f) {
                    playerSheetProgress.snapTo(0f)
                }
            }
        }
    }

    LaunchedEffect(playerUiState.currentTrack) {
        if (playerUiState.currentTrack == null) {
            sheetAnimationJob?.cancel()
            dismissAnimationJob?.cancel()
            playerSheetProgress.snapTo(0f)
            dismissOffsetY.snapTo(0f)
            isNowPlayingOpen = false
        }
    }

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
        if (currentDestination == dest) {
            // Re-tapping current tab: pop any nested detail to root
            if (dest == AppDestination.EXPLORE) {
                if (searchUiState.detailStack.isNotEmpty() || searchUiState.selectedArtistPage != null || searchUiState.selectedAlbum != null) {
                    searchViewModelState?.clearSearch()
                }
            } else if (dest == AppDestination.LIBRARY) {
                if (libraryUiState.selectedPlaylist != null || libraryUiState.selectedSmartCollection != null) {
                    libraryViewModelState?.selectPlaylist(null)
                    libraryViewModelState?.closeSmartCollection()
                }
            }
        } else {
            currentDestination = dest
        }
        isHomeMenuOpen = false
        isHistoryOpen = false
        isProfileOpen = false
        isListenTogetherOpen = false
        isStatsOpen = false
        searchViewModelState?.closeRecognitionModal()
    }

    fun openArtistDetail(artist: com.auralis.music.domain.model.Artist) {
        val alreadyInDetail = currentDestination == AppDestination.EXPLORE && searchUiState.detailStack.isNotEmpty()
        if (detailOriginDestination == null && currentDestination != AppDestination.EXPLORE) {
            detailOriginDestination = currentDestination
        }
        obtainSearchViewModel().openArtist(artist, resetStack = !alreadyInDetail)
        navigateToDestination(AppDestination.EXPLORE)
    }

    fun openAlbumDetail(album: com.auralis.music.domain.model.PlaylistResult) {
        val alreadyInDetail = currentDestination == AppDestination.EXPLORE && searchUiState.detailStack.isNotEmpty()
        if (detailOriginDestination == null && currentDestination != AppDestination.EXPLORE) {
            detailOriginDestination = currentDestination
        }
        obtainSearchViewModel().openAlbum(album, resetStack = !alreadyInDetail)
        navigateToDestination(AppDestination.EXPLORE)
    }

    val handleCloseDetail: () -> Unit = {
        searchViewModelState?.popDetail()
        val remaining = searchViewModelState?.uiState?.value?.detailStack?.size ?: 0
        if (remaining <= 0 && detailOriginDestination != null) {
            val origin = detailOriginDestination
            detailOriginDestination = null
            if (origin != null && origin != AppDestination.EXPLORE) {
                currentDestination = origin
            }
        }
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

    // Keeps the welcome screen mounted through its own exit animation instead of cutting to the
    // app the instant sign-in succeeds, so unlocking reads as one smooth motion. The delay here
    // matches the AnimatedVisibility exit's own duration below.
    var showWelcomeScreen by remember { mutableStateOf(!isAppUnlocked) }
    LaunchedEffect(isAppUnlocked) {
        if (isAppUnlocked) {
            if (showWelcomeScreen) {
                kotlinx.coroutines.delay(420L)
                showWelcomeScreen = false
            }
        } else {
            showWelcomeScreen = true
        }
    }

    if (showWelcomeScreen) {
        val authVM = obtainAuthViewModel()
        androidx.compose.animation.AnimatedVisibility(
            visible = !isAppUnlocked,
            modifier = Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(tween(220)),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            ) + androidx.compose.animation.scaleOut(
                targetScale = 1.08f,
                animationSpec = tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            )
        ) {
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
                },
                onSendPasswordReset = { email ->
                    authVM.sendPasswordResetEmail(email)
                },
                onDismissPasswordResetMessage = {
                    authVM.clearPasswordResetMessage()
                }
            )
        }
        return
    }

    val isGuestInRoom = listenTogetherUiState.activeRoom != null && !listenTogetherUiState.isHost
    // The host's room rules: a guest's play/pause/skip/seek go to the host, songs go to the room.
    val guestCanControl = isGuestInRoom && listenTogetherUiState.activeRoom?.settings?.guestsCanControlPlayback == true
    val guestCanPlaySongs = isGuestInRoom && listenTogetherUiState.activeRoom?.settings?.guestsCanPlaySongs == true
    // Swiping changes the song on the spot, so it's for rooms where guests may do that without asking.
    val guestCanSwipe = guestCanPlaySongs && listenTogetherUiState.activeRoom?.settings?.approvalApplies == false
    val guestCanAddSongs = isGuestInRoom && listenTogetherUiState.activeRoom?.settings?.guestsMayAddSongs == true
    // Tap handlers read the room's rules at the moment of the tap, not the values from when the
    // screen was last drawn: a permission the host just gave or took applies to the very next tap.
    fun liveRoomUi() = listenTogetherViewModelState?.uiState?.value
    fun guestNow(): Boolean = liveRoomUi()?.let { it.activeRoom != null && !it.isHost } == true
    fun guestMay(rule: (com.auralis.music.data.sync.RoomSettings) -> Boolean): Boolean =
        guestNow() && liveRoomUi()?.activeRoom?.settings?.let(rule) == true
    fun guestControlBlocked(): Boolean = guestNow() && !guestMay { it.guestsCanControlPlayback }
    /** Next/previous change the song: that follows "Guests can play songs" (or approval), not playback control. */
    fun guestSkipBlocked(): Boolean = guestNow() && !guestMay { it.guestsMayPlaySongs }
    fun notifyGuestSkipBlocked() = com.auralis.music.ui.components.AppPillManager.showPill("Only the host can change what's playing")

    /** True (and tells the user) while next/previous is locked after a song change in the room. */
    fun skipLocked(): Boolean {
        val waitMs = listenTogetherViewModelState?.trackChangeLockRemainingMs() ?: 0L
        if (waitMs <= 0L) return false
        val seconds = ((waitMs + 999L) / 1000L).coerceAtLeast(1L)
        com.auralis.music.ui.components.AppPillManager.showPill("Song just changed \u2014 you can skip again in ${seconds}s")
        return true
    }

    fun notifyGuestControlBlocked() {
        com.auralis.music.ui.components.AppPillManager.showPill("Only the host can change what's playing")
    }

    /**
     * Every "play this song" tap: a host (or someone not in a room) plays it; a guest the host
     * lets control playback sends it to the host, who plays it for everyone; other guests are told
     * the host is in control.
     */
    fun playOrRequest(track: com.auralis.music.domain.model.Track?, play: () -> Unit) {
        when {
            !guestNow() -> play()
            !guestMay { it.guestsMayPlaySongs } -> notifyGuestControlBlocked()
            track == null -> Unit
            skipLocked() -> Unit
            else -> obtainListenTogetherViewModel().requestPlayTrack(track)
        }
    }


    androidx.activity.compose.BackHandler(
        enabled = isHomeMenuOpen ||
                (currentDestination == AppDestination.EXPLORE && (searchUiState.detailStack.isNotEmpty() || searchUiState.selectedArtistPage != null || searchUiState.selectedAlbum != null)) ||
                isPlayerSheetActive ||
                isHistoryOpen ||
                isStatsOpen ||
                isProfileOpen ||
                isListenTogetherOpen ||
                currentDestination != AppDestination.HOME
    ) {
        if (isHomeMenuOpen) isHomeMenuOpen = false
        else if (isPlayerSheetActive) collapsePlayer()
        else if (isStatsOpen) isStatsOpen = false
        else if (isHistoryOpen) isHistoryOpen = false
        else if (isProfileOpen) isProfileOpen = false
        else if (isListenTogetherOpen) isListenTogetherOpen = false
        else if (currentDestination == AppDestination.EXPLORE && (searchUiState.detailStack.isNotEmpty() || searchUiState.selectedArtistPage != null || searchUiState.selectedAlbum != null)) {
            searchViewModelState?.popDetail()
            val remaining = searchViewModelState?.uiState?.value?.detailStack?.size ?: 0
            if (remaining <= 0 && detailOriginDestination != null) {
                val origin = detailOriginDestination
                detailOriginDestination = null
                if (origin != null && origin != AppDestination.EXPLORE) {
                    currentDestination = origin
                }
            }
        }
        else if (currentDestination != AppDestination.HOME) {
            val target = detailOriginDestination ?: AppDestination.HOME
            detailOriginDestination = null
            currentDestination = target
        }
    }

    // Sync guest mode with audio player. Straight to the player: the player's screen state may not
    // exist yet (fresh start, nothing played), and then a guest wasn't in guest mode at all.
    LaunchedEffect(listenTogetherUiState.activeRoom, listenTogetherUiState.isHost, playerViewModelState) {
        val isGuest = listenTogetherUiState.activeRoom != null && !listenTogetherUiState.isHost
        (playerViewModelState?.getAudioPlayer() ?: viewModelProvider.audioPlayer).setGuestListenTogether(isGuest)
    }
    // Room sync is wired up through the player's screen state, which used to appear only once a
    // song was playing. A guest joining with nothing played never got it, so the host's songs
    // never started on that phone. Being in a room is reason enough to set it up.
    LaunchedEffect(listenTogetherUiState.activeRoom != null) {
        if (listenTogetherUiState.activeRoom != null) obtainPlayerViewModel()
    }

    // Wire Listen Together Sync Callbacks
    val currentLT = listenTogetherViewModelState
    val currentPV = playerViewModelState
    if (currentLT != null && currentPV != null) {
        LaunchedEffect(currentLT, currentPV) {
            currentLT.bindGuestSync(currentPV.getAudioPlayer() ?: com.auralis.music.data.service.AuralisAudioPlayer.getInstance(context))
            currentLT.onHostPlayTrack = { track ->
                val curQueue = currentPV.uiState.value.queue
                val newQueue = if (curQueue.none { it.id == track.id }) curQueue + track else curQueue
                val index = newQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                currentPV.playTrack(track, newQueue, index)
            }
            currentLT.onHostReopenSession = { track, queue, index, positionMs ->
                currentPV.playTrack(track, queue.ifEmpty { listOf(track) }, index.coerceAtLeast(0), initialPositionMs = positionMs, preserveQueueSource = true)
            }
            currentLT.onHostAddToQueue = { track ->
                currentPV.addToQueue(listOf(track))
                android.widget.Toast.makeText(context, "Added \"${track.title}\" to room queue", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Host broadcasting runs off the player state inside the ViewModel, so it keeps working
        // when the song changes from the notification, lock screen or by ending, or the app is off screen.
        LaunchedEffect(currentLT) {
            val player = currentPV.getAudioPlayer() ?: com.auralis.music.data.service.AuralisAudioPlayer.getInstance(context)
            currentLT.bindHostPlayer(player)
            currentLT.bindGuestControls(player)
            currentLT.bindConnectionMonitor(player)
            currentLT.bindGuestReadiness(player)
            currentLT.onHostGuestCommand = { command ->
                when (command.type) {
                    com.auralis.music.data.sync.GuestCommand.TOGGLE -> currentPV.togglePlayPause()
                    // Already in the asked-for state (e.g. both pressed pause at once): nothing to do.
                    com.auralis.music.data.sync.GuestCommand.PLAY -> if (!player.intendsToPlay()) currentPV.togglePlayPause()
                    com.auralis.music.data.sync.GuestCommand.PAUSE -> if (player.intendsToPlay()) currentPV.togglePlayPause()
                    com.auralis.music.data.sync.GuestCommand.NEXT -> currentPV.next()
                    com.auralis.music.data.sync.GuestCommand.PREVIOUS -> currentPV.previous()
                    com.auralis.music.data.sync.GuestCommand.SEEK -> currentPV.seekTo(command.positionMs)
                    com.auralis.music.data.sync.GuestCommand.PLAY_TRACK -> command.track?.let { currentLT.onHostPlayTrack?.invoke(it) }
                }
            }
        }
    }

    // Real room taken by the floating dock + mini player, for scrolling pages (see BottomChrome).
    // The mini player's share glides in/out with it instead of pages guessing a fixed gap.
    val hasMiniPlayer = (playerUiState.currentTrack ?: audioPlayerTrack) != null
    val miniPlayerShown = animateFloatAsState(
        targetValue = if (hasMiniPlayer) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(AuralisDuration.Nav, easing = FastOutSlowInEasing),
        label = "miniPlayerInset"
    )
    val dockInset = rememberUpdatedState(if (appearanceSettings.slimBottomNavigationBar) 56.dp else 68.dp)
    val miniHeight = rememberUpdatedState(com.auralis.music.ui.player.miniPlayerHeightFor(appearanceSettings.miniPlayerDesign))
    val subScreenMiniGap = rememberUpdatedState(
        if (appearanceSettings.miniPlayerDesign == "Classic mini player") 0.dp else 10.dp
    )
    val mainBottomChrome = remember {
        BottomChrome { dockInset.value + miniHeight.value * miniPlayerShown.value }
    }
    // Profile / Settings / History / Stats / Listen Together hide the dock.
    val overlayBottomChrome = remember {
        BottomChrome { (miniHeight.value + subScreenMiniGap.value) * miniPlayerShown.value }
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
    CompositionLocalProvider(LocalBottomChrome provides mainBottomChrome) {
        val playerSharedScope = this
        val hazeState = remember { dev.chrisbanes.haze.HazeState() }
        // Whatever's directly under an overlay pill: the content pages normally, or the open
        // player's artwork once it's on screen (else a pill blurs the page hidden behind the
        // player and shows its colours instead of what's actually visible).
        val playerBackdropHazeState = remember { HazeState() }
        // Blur source only for the overlay pills: every layer that can be on screen behind them,
        // in drawing order (page 0, open sheet 2, player backdrop 3, player 4), so a pill blurs
        // exactly what's under it, including the open player's buttons. Separate from hazeState
        // and playerBackdropHazeState so their own effects keep blurring what they did.
        val pillHazeState = remember { HazeState() }

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
                                val dockProgress = playerSheetProgress.value
                                val dockAlpha = if (reducedMotion) {
                                    if (isNowPlayingOpen || dockProgress > 0f) 0f else 1f
                                } else {
                                    (1f - dockProgress * 2f).coerceIn(0f, 1f)
                                }
                                alpha = dockAlpha
                                if (!reducedMotion) {
                                    translationY = 64.dp.toPx() * dockProgress
                                }
                            }
                    ) {
                        com.auralis.music.ui.components.AuralisFloatingDock(
                            currentDestination = currentDestination,
                            hazeState = hazeState,
                            artworkUrl = playerUiState.currentTrack?.thumbnail,
                            isPlaylistDetailOpen = libraryUiState.selectedPlaylist != null || libraryUiState.selectedSmartCollection != null,
                            onDestinationClick = { destination ->
                                isHomeMenuOpen = false
                                detailOriginDestination = null
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
                    .hazeSource(state = pillHazeState, zIndex = 0f)
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

                    // Main Navigation Screen Container: Instant response with hardware-accelerated slide+fade transitions
                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val reducedMotion = LocalReducedMotion.current
                        val slideOffsetPx = constraints.maxWidth.toFloat() / 8f

                        var previousDestination by remember { mutableStateOf(currentDestination) }
                        var activeDestination by remember { mutableStateOf(currentDestination) }
                        var transitionDirection by remember { mutableIntStateOf(1) } // +1 forward (from right), -1 backward (from left)
                        val transitionProgress = remember { Animatable(1f) }

                        LaunchedEffect(currentDestination) {
                            if (currentDestination != activeDestination) {
                                val prevIdx = AppDestinations.indexOfFirst { it == activeDestination }
                                val newIdx = AppDestinations.indexOfFirst { it == currentDestination }
                                transitionDirection = if (newIdx >= prevIdx) 1 else -1
                                previousDestination = activeDestination
                                activeDestination = currentDestination
                                if (!reducedMotion) {
                                    transitionProgress.snapTo(0f)
                                    transitionProgress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(
                                            durationMillis = 200,
                                            easing = androidx.compose.animation.core.FastOutSlowInEasing
                                        )
                                    )
                                } else {
                                    transitionProgress.snapTo(1f)
                                }
                            }
                        }

                        val currentProgress = transitionProgress.value

                        AppDestinations.forEach { destination ->
                            if (visitedDestinations.contains(destination)) {
                                val isCurrentlyActive = destination == activeDestination
                                val wasPrevious = destination == previousDestination

                                val (alphaVal, transX) = when {
                                    reducedMotion -> {
                                        if (isCurrentlyActive) 1f to 0f else 0f to 0f
                                    }
                                    isCurrentlyActive -> {
                                        // Entering screen: slides in from (direction * slideOffsetPx) to 0f
                                        val a = currentProgress
                                        val tx = (1f - currentProgress) * (transitionDirection * slideOffsetPx)
                                        a to tx
                                    }
                                    wasPrevious && currentProgress < 1f -> {
                                        // Exiting screen: slides out from 0f to (-direction * slideOffsetPx)
                                        val a = 1f - currentProgress
                                        val tx = -currentProgress * (transitionDirection * slideOffsetPx)
                                        a to tx
                                    }
                                    else -> {
                                        0f to 0f
                                    }
                                }

                                // Keep all 3 primary destinations continuously composed in memory to preserve scroll
                                // positions, carousel state, and search queries without rebuild or image reload overhead.
                                // Inactive destinations have alpha = 0f (RenderNode skip-draw) and swallow pointer events.
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(if (isCurrentlyActive) 10f else 0f)
                                        .graphicsLayer {
                                            alpha = alphaVal
                                            translationX = transX
                                            clip = true
                                        }
                                        .then(
                                            if (!isCurrentlyActive) {
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
                                                    android.util.Log.d("AuralisPlayback", "[AuralisApp onTrackClick] track='${track.title}' id='${track.id}' queueSize=${queue.size}")
                                                    playOrRequest(track) { obtainPlayerViewModel().playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)) }
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
                                                    playOrRequest(track) { obtainPlayerViewModel().playTrack(track, listOf(track), 0) }
                                                },
                                                onOpenListenTogether = {
                                                    obtainListenTogetherViewModel()
                                                    isListenTogetherOpen = true
                                                },
                                                onNavigateToExplore = { navigateToDestination(AppDestination.EXPLORE) },
                                                onMoodSelect = { mood -> homeViewModel.selectMoodFilter(mood) },
                                                onChipToggle = { chip -> homeViewModel.toggleChip(chip) },
                                                onSurpriseMe = {
                                                    if (guestNow()) {
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
                                                    openArtistDetail(artist)
                                                },
                                                onAlbumClick = { album ->
                                                    openAlbumDetail(album)
                                                },
                                                onPlaylistClick = { playlistId ->
                                                    obtainLibraryViewModel().selectPlaylist(playlistId)
                                                    navigateToDestination(AppDestination.LIBRARY)
                                                },
                                                onUnpinSpeedDial = { homeViewModel.unpinFromSpeedDial(it) },
                                                savedAlbums = libraryUiState.savedAlbums,
                                                isAlbumPinned = { albumId -> homeViewModel.isAlbumPinned(albumId) },
                                                onPinAlbumToSpeedDial = { album ->
                                                    val isNowPinned = homeViewModel.togglePinAlbum(album)
                                                    val msg = if (isNowPinned) "Pinned \"${album.title}\" to Speed dial" else "Unpinned \"${album.title}\" from Speed dial"
                                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onToggleSaveAlbum = { obtainLibraryViewModel().toggleSaveAlbum(it) },
                                                onShuffleAlbum = { album ->
                                                    coroutineScope.launch {
                                                        val tracks = obtainSearchViewModel().getAlbumTracks(album)
                                                        if (tracks.isNotEmpty()) {
                                                            val shuffled = tracks.shuffled()
                                                            obtainPlayerViewModel().playTrack(shuffled.first(), shuffled, 0)
                                                            android.widget.Toast.makeText(context, "Shuffling: ${album.title}", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                onPlayNextAlbum = { album ->
                                                    coroutineScope.launch {
                                                        val tracks = obtainSearchViewModel().getAlbumTracks(album)
                                                        if (tracks.isNotEmpty()) {
                                                            obtainPlayerViewModel().playNext(tracks)
                                                            android.widget.Toast.makeText(context, "Playing next: ${album.title}", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                onAddToQueueAlbum = { album ->
                                                    coroutineScope.launch {
                                                        val tracks = obtainSearchViewModel().getAlbumTracks(album)
                                                        if (tracks.isNotEmpty()) {
                                                            obtainPlayerViewModel().addToQueue(tracks)
                                                            android.widget.Toast.makeText(context, "Added ${tracks.size} songs to queue", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                onAddAlbumToPlaylist = { plId, album ->
                                                    coroutineScope.launch {
                                                        val tracks = obtainSearchViewModel().getAlbumTracks(album)
                                                        if (tracks.isNotEmpty()) {
                                                            obtainLibraryViewModel().addTracksToPlaylist(plId, tracks)
                                                            android.widget.Toast.makeText(context, "Added ${tracks.size} songs to playlist", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                onCreatePlaylistAndAddAlbum = { title, album ->
                                                    val albumTitle = title.ifBlank { album.title }
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Adding \"$albumTitle\" to Library...",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                    coroutineScope.launch {
                                                        val tracks = obtainSearchViewModel().getAlbumTracks(album)
                                                        obtainLibraryViewModel().createPlaylistAndAddTracks(
                                                            title = albumTitle,
                                                            tracks = tracks,
                                                            description = "Album by ${album.author ?: "Various Artists"}",
                                                            coverUrl = album.thumbnail
                                                        )
                                                    }
                                                },
                                                onDownloadAlbum = { album ->
                                                    coroutineScope.launch {
                                                        val tracks = obtainSearchViewModel().getAlbumTracks(album)
                                                        if (tracks.isNotEmpty()) {
                                                            com.auralis.music.data.download.PlaylistDownloadCoordinator.enqueue(
                                                                context,
                                                                album.id,
                                                                album.title,
                                                                tracks
                                                            )
                                                            android.widget.Toast.makeText(context, "Downloading ${album.title} (${tracks.size} songs)...", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                isInListenTogetherRoom = guestCanAddSongs,
                                                onRecommendToRoom = { trk ->
                                                    obtainListenTogetherViewModel().recommendSong(trk)
                                                    android.widget.Toast.makeText(context, "Recommended \"${trk.title}\" to room!", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                isTrackPinned = { homeViewModel.isTrackPinned(it) },
                                                onPinTrackToSpeedDial = { homeViewModel.togglePinTrack(it) }
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
                                                savedAlbums = libraryUiState.savedAlbums,
                                                pinnedSpeedDialIds = homeUiState.pinnedSpeedDialIds,
                                                onToggleSubscribe = { libVM.toggleSaveArtist(it) },
                                                onToggleSaveAlbum = { libVM.toggleSaveAlbum(it) },
                                                onPlayNextAlbum = { album ->
                                                    coroutineScope.launch {
                                                        val tracks = searchVM.getAlbumTracks(album)
                                                        if (tracks.isNotEmpty()) {
                                                            obtainPlayerViewModel().playNext(tracks)
                                                            android.widget.Toast.makeText(context, "Playing next: ${album.title}", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                onAddToQueueAlbum = { album ->
                                                    coroutineScope.launch {
                                                        val tracks = searchVM.getAlbumTracks(album)
                                                        if (tracks.isNotEmpty()) {
                                                            obtainPlayerViewModel().addToQueue(tracks)
                                                            android.widget.Toast.makeText(context, "Added ${tracks.size} songs to queue", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                onShuffleAlbum = { album ->
                                                    coroutineScope.launch {
                                                        val tracks = searchVM.getAlbumTracks(album)
                                                        if (tracks.isNotEmpty()) {
                                                            val shuffled = tracks.shuffled()
                                                            obtainPlayerViewModel().playTrack(shuffled.first(), shuffled, 0)
                                                            android.widget.Toast.makeText(context, "Shuffling: ${album.title}", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                onDownloadAlbum = { album ->
                                                    coroutineScope.launch {
                                                        val tracks = searchVM.getAlbumTracks(album)
                                                        if (tracks.isNotEmpty()) {
                                                            com.auralis.music.data.download.PlaylistDownloadCoordinator.enqueue(
                                                                context,
                                                                album.id,
                                                                album.title,
                                                                tracks
                                                            )
                                                            android.widget.Toast.makeText(context, "Downloading ${album.title} (${tracks.size} songs)...", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                isAlbumPinned = { albumId -> homeViewModel.isAlbumPinned(albumId) },
                                                onPinAlbumToSpeedDial = { album ->
                                                    val isNowPinned = homeViewModel.togglePinAlbum(album)
                                                    val msg = if (isNowPinned) "Pinned \"${album.title}\" to Speed dial" else "Unpinned \"${album.title}\" from Speed dial"
                                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onAddAlbumToPlaylist = { plId, album ->
                                                    coroutineScope.launch {
                                                        val tracks = searchVM.getAlbumTracks(album)
                                                        if (tracks.isNotEmpty()) {
                                                            libVM.addTracksToPlaylist(plId, tracks)
                                                            android.widget.Toast.makeText(context, "Added ${tracks.size} songs to playlist", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                onCreatePlaylistAndAddAlbum = { title, album ->
                                                    val albumTitle = title.ifBlank { album.title }
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Adding \"$albumTitle\" to Library...",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                    coroutineScope.launch {
                                                        val tracks = searchVM.getAlbumTracks(album)
                                                        libVM.createPlaylistAndAddTracks(
                                                            title = albumTitle,
                                                            tracks = tracks,
                                                            description = "Album • ${album.author ?: "Unknown Artist"}",
                                                            coverUrl = album.thumbnail,
                                                            onCreated = {
                                                                android.widget.Toast.makeText(
                                                                    context,
                                                                    "Added album playlist \"$albumTitle\" to Library (${tracks.size} songs)",
                                                                    android.widget.Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        )
                                                    }
                                                },
                                                onQueryChange = { searchVM.onQueryChange(it) },
                                                onSearch = { searchVM.performSearch(it) },
                                                onClearSearch = { searchVM.clearSearch() },
                                                onTrackClick = { track, list ->
                                                    playOrRequest(track) {
                                                    obtainPlayerViewModel().playTrack(
                                                        track = track,
                                                        newQueue = if (list.isNotEmpty()) list else listOf(track),
                                                        startIndex = if (list.isNotEmpty()) list.indexOfFirst { it.id == track.id }.coerceAtLeast(0) else 0
                                                    )
                                                }
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
                                                    playOrRequest(track) { obtainPlayerViewModel().playTrack(track, listOf(track), 0) }
                                                },
                                                onRemoveRecentQuery = { searchVM.removeRecentQuery(it) },
                                                onOpenRecognition = { searchVM.openRecognitionModal(it) },
                                                onCloseRecognition = { searchVM.closeRecognitionModal() },
                                                onModeSelect = { searchVM.setRecognitionMode(it) },
                                                onStartListening = { searchVM.startListening() },
                                                onStopListening = { searchVM.stopListening() },
                                                onOpenArtist = { searchVM.openArtist(it) },
                                                onCloseArtist = handleCloseDetail,
                                                onOpenAlbum = { searchVM.openAlbum(it) },
                                                onCloseAlbum = handleCloseDetail,
                                                onAlbumClick = { album ->
                                                    searchVM.openAlbum(album)
                                                },
                                                onBack = {
                                                    val target = detailOriginDestination ?: AppDestination.HOME
                                                    detailOriginDestination = null
                                                    currentDestination = target
                                                },
                                                isInListenTogetherRoom = guestCanAddSongs,
                                                onRecommendToRoom = { trk ->
                                                    obtainListenTogetherViewModel().recommendSong(trk)
                                                    android.widget.Toast.makeText(context, "Recommended \"${trk.title}\" to room!", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                isTrackPinned = { homeViewModel.isTrackPinned(it) },
                                                onPinTrackToSpeedDial = { homeViewModel.togglePinTrack(it) }
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
                                                onTrackClick = { track, queue, _ ->
                                                    playOrRequest(track) {
                                                    obtainPlayerViewModel().playTrack(
                                                        track = track,
                                                        newQueue = queue,
                                                        startIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0),
                                                        isUserQueue = libraryUiState.selectedSmartCollection == com.auralis.music.ui.viewmodel.SmartCollectionType.DOWNLOADED,
                                                        sourcePlaylistTitle = libraryUiState.selectedPlaylist?.title,
                                                        sourcePlaylistId = libraryUiState.selectedPlaylist?.id
                                                    )
                                                }
                                                },
                                                onFavoriteToggle = { track -> obtainPlayerViewModel().toggleFavorite(track) },
                                                onAddToPlaylist = { plId, track -> libVM.addTrackToPlaylist(plId, track) },
                                                onRemoveFromPlaylist = { plId, trackId ->
                                                    libVM.removeTrackFromPlaylist(plId, trackId)
                                                    android.widget.Toast.makeText(context, "Removed from playlist", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onImportYouTubePlaylist = { libVM.importYouTubePlaylist(it) },
                                                onImportSpotifyPlaylist = { libVM.importSpotifyPlaylist(it) },
                                                onExportBackup = suspend { libVM.exportLibraryJson() },
                                                onImportBackup = { libVM.importLibraryJson(it) },
                                                onSmartCollectionClick = { libVM.openSmartCollection(it) },
                                                onCloseSmartCollection = { libVM.closeSmartCollection() },
                                                onDeletePlaylistJob = { jobId -> libVM.removePlaylistDownloads(context, jobId) },
                                                onRetryPlaylistJob = { jobId -> libVM.retryPlaylistDownload(context, jobId) },
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
                                                    when {
                                                        guestMay { it.guestsMayAddSongs } -> tracks.forEach { obtainListenTogetherViewModel().recommendSong(it) }
                                                        guestNow() -> notifyGuestControlBlocked()
                                                        else -> obtainPlayerViewModel().addToQueue(tracks)
                                                    }
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
                                                    playOrRequest(track) { obtainPlayerViewModel().playTrack(track, listOf(track), 0) }
                                                },
                                                onOpenArtist = { artist ->
                                                    openArtistDetail(artist)
                                                },
                                                onOpenAlbum = { album ->
                                                    openAlbumDetail(album)
                                                },
                                                isInListenTogetherRoom = guestCanAddSongs,
                                                onRecommendToRoom = { trk ->
                                                    obtainListenTogetherViewModel().recommendSong(trk)
                                                    android.widget.Toast.makeText(context, "Recommended \"${trk.title}\" to room!", android.widget.Toast.LENGTH_SHORT).show()
                                                },
                                                onReorderPlaylistTracks = { plId, from, to ->
                                                    libVM.reorderPlaylistTracks(plId, from, to)
                                                },
                                                isExternalCreateDialogOpen = isExternalCreatePlaylistOpen,
                                                onCloseExternalCreateDialog = { isExternalCreatePlaylistOpen = false },
                                                isTrackPinned = { homeViewModel.isTrackPinned(it) },
                                                onPinTrackToSpeedDial = { homeViewModel.togglePinTrack(it) }
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
            enter = auralisDetailForwardEnter(),
            exit = auralisDetailBackwardExit(),
            modifier = Modifier.fillMaxSize().hazeSource(state = hazeState, zIndex = 1f).hazeSource(state = pillHazeState, zIndex = 1f)
        ) {
            CompositionLocalProvider(LocalBottomChrome provides overlayBottomChrome) {
                val ltVM = obtainListenTogetherViewModel()
                ListenTogetherSheet(
                    uiState = listenTogetherUiState,
                    currentTrack = playerUiState.currentTrack,
                    isPlaying = playerUiState.isPlaying,
                    queue = playerUiState.queue,
                    playbackPositionMs = playerUiState.playbackPositionMs,
                    onNameChange = { ltVM.setDisplayName(it) },
                    onCreateRoom = { trk, q, playing, pos ->
                        val startIndex = com.auralis.music.data.sync.ListenTogetherSyncMath.resolveQueueIndex(q, playerUiState.currentIndex, trk?.id.orEmpty())
                        ltVM.createRoom(trk, q, startIndex, playing, pos)
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
                    onDeclineRecommendation = { rec -> ltVM.declineRecommendation(rec) },
                    onHostSettingsChange = { ltVM.updateHostSettings(it) },
                    onDismiss = { isListenTogetherOpen = false }
                )
            }
        }

        // A guest's song waiting for the host: Allow / Decline, wherever the host is in the app
        val songRequest = listenTogetherUiState.songRequests.firstOrNull()
        if (listenTogetherUiState.isHost && songRequest != null) {
            val ltVM = obtainListenTogetherViewModel()
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { },
                title = {
                    Text(
                        when {
                            songRequest.skip > 0 -> "Skip to the next song?"
                            songRequest.skip < 0 -> "Go back a song?"
                            songRequest.playNow -> "Play this song now?"
                            else -> "Add this song to the queue?"
                        }
                    )
                },
                text = {
                    Column {
                        Text(
                            when {
                                songRequest.skip != 0 -> "${songRequest.memberName} wants to skip to:"
                                songRequest.playNow -> "${songRequest.memberName} wants to play this for everyone:"
                                else -> "${songRequest.memberName} wants to add this to the queue:"
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.auralis.music.ui.components.ArtworkCard(
                                url = songRequest.track.thumbnail,
                                modifier = Modifier.size(48.dp),
                                cornerRadius = 8.dp,
                                contentDescription = songRequest.track.title
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(songRequest.track.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(songRequest.track.artist, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                        if (listenTogetherUiState.songRequests.size > 1) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("${listenTogetherUiState.songRequests.size - 1} more waiting")
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { ltVM.allowSongRequest(songRequest) }) { Text("Allow") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { ltVM.declineSongRequest(songRequest) }) { Text("Decline") }
                }
            )
        }

        // Slow connection in a Listen Together room: offer lower streaming quality
        if (listenTogetherUiState.showSlowConnectionPrompt) {
            val ltVM = obtainListenTogetherViewModel()
            val quality = playerViewModelState?.playerSettings?.collectAsState()?.value?.audioQuality
            if (quality == com.auralis.music.domain.model.AudioQuality.LOW) {
                LaunchedEffect(Unit) {
                    android.widget.Toast.makeText(
                        context,
                        "Your internet is slow right now, so songs may pause while they load",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    ltVM.dismissSlowConnectionPrompt()
                }
            } else {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { ltVM.dismissSlowConnectionPrompt() },
                    title = { Text("Your internet is slow right now") },
                    text = {
                        Text(
                            "Music keeps pausing to load, so you may fall behind the room. " +
                                "Data Saver quality needs much less data and keeps you in sync. " +
                                "It starts with the next song, and you can change it back in Settings."
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            obtainPlayerViewModel().updateAudioQuality(com.auralis.music.domain.model.AudioQuality.LOW)
                            ltVM.dismissSlowConnectionPrompt()
                            android.widget.Toast.makeText(context, "Switched to Data Saver quality", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("Use Data Saver") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { ltVM.dismissSlowConnectionPrompt() }) { Text("Not now") }
                    }
                )
            }
        }

        // Profile & YouTube Music Account Sync Modal Sheet
        AnimatedVisibility(
            visible = isProfileOpen,
            enter = auralisDetailForwardEnter(),
            exit = auralisDetailBackwardExit(),
            modifier = Modifier.fillMaxSize().hazeSource(state = hazeState, zIndex = 1f).hazeSource(state = pillHazeState, zIndex = 1f)
        ) {
            CompositionLocalProvider(LocalBottomChrome provides overlayBottomChrome) {
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
                    onImportYouTubeLibraryPlaylists = { libVM.importYouTubeLibraryPlaylists(it) },
                    onClearYouTubeImportMessage = { libVM.clearYouTubeImportMessage() },
                    isImportingYouTube = libraryUiState.isImporting,
                    youtubeImportMessage = libraryUiState.importMessage,
                    onOpenPlaylistSelector = { authVM.openPlaylistSelectDialog() },
                    onSyncLikedMusic = { authVM.syncLikedMusic() },
                    onDisconnect = {
                        authVM.disconnectAccount()
                        isProfileOpen = false
                    },
                    onDeleteAccount = { password ->
                        val act = context.findActivity()
                        if (act != null) {
                            authVM.deleteAccount(act, password) { isProfileOpen = false }
                        }
                    },
                    deleteAccountNeedsPassword = remember(authUiState.profile.uid) { authVM.deleteAccountNeedsPassword() },
                    onClearDeleteAccountError = { authVM.clearDeleteAccountError() },
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
                    hasActiveTrack = (playerUiState.currentTrack ?: audioPlayerTrack) != null
                )
            }
        }

        // Listening History Modal Sheet
        AnimatedVisibility(
            visible = isHistoryOpen,
            enter = auralisDetailForwardEnter(),
            exit = auralisDetailBackwardExit(),
            modifier = Modifier.fillMaxSize().hazeSource(state = hazeState, zIndex = 1f).hazeSource(state = pillHazeState, zIndex = 1f)
        ) {
            CompositionLocalProvider(LocalBottomChrome provides overlayBottomChrome) {
                val listeningHistory by viewModelProvider.historyRepository.getHistory().collectAsState(initial = emptyList())
                com.auralis.music.ui.history.HistorySheet(
                    history = listeningHistory,
                    currentTrack = playerUiState.currentTrack ?: audioPlayerTrack,
                    isPlaying = playerUiState.isPlaying || audioPlayerIsPlaying,
                    onTrackClick = { track, queue ->
                        playOrRequest(track) { obtainPlayerViewModel().playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)) }
                    },
                    onRemoveFromHistory = { homeViewModel.removeFromHistory(it) },
                    onClearHistory = { homeViewModel.clearHistory() },
                    onPlayNext = { track ->
                        obtainPlayerViewModel().playNext(track)
                        android.widget.Toast.makeText(context, "Playing next: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onAddToQueue = { track ->
                        obtainPlayerViewModel().addToQueue(listOf(track))
                        android.widget.Toast.makeText(context, "Added to queue: ${track.title}", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { isHistoryOpen = false }
                )
            }
        }

        // Listening Stats Modal Sheet
        AnimatedVisibility(
            visible = isStatsOpen,
            enter = auralisDetailForwardEnter(),
            exit = auralisDetailBackwardExit(),
            modifier = Modifier.fillMaxSize().hazeSource(state = hazeState, zIndex = 1f).hazeSource(state = pillHazeState, zIndex = 1f)
        ) {
            CompositionLocalProvider(LocalBottomChrome provides overlayBottomChrome) {
                val statsVM = obtainStatsViewModel()
                val libVM = obtainLibraryViewModel()
                com.auralis.music.ui.screens.StatsScreen(
                    viewModel = statsVM,
                    onDismiss = { isStatsOpen = false },
                    onPlayTrack = { track, queue ->
                        playOrRequest(track) { obtainPlayerViewModel().playTrack(track, queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)) }
                    },
                    onArtistClick = { artist ->
                        openArtistDetail(artist)
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
                    hasActiveMiniPlayer = (playerUiState.currentTrack ?: audioPlayerTrack) != null,
                    isTrackPinned = { homeViewModel.isTrackPinned(it) },
                    onPinTrackToSpeedDial = { homeViewModel.togglePinTrack(it) }
                )
            }
        }

        // ── Unified BottomSheet Container (MiniPlayer <-> Full Player) ──
        val effectiveTrack = playerUiState.currentTrack ?: audioPlayerTrack
        val activePV = playerViewModelState ?: if (effectiveTrack != null) obtainPlayerViewModel() else null
        if (effectiveTrack != null && activePV != null) {
            val isSubScreenOpen = isProfileOpen || isHistoryOpen || isListenTogetherOpen || isStatsOpen

            // Atmospheric background layer behind sheet (static fullscreen blurred artwork / gradient)
            val currentTrack = effectiveTrack
            val resolvedPlayerBgStyle = remember(appearanceSettings.playerBackgroundStyle) {
                val style = PlayerBackgroundStyle.fromKey(appearanceSettings.playerBackgroundStyle)
                if (style == PlayerBackgroundStyle.APPLE_MUSIC) PlayerBackgroundStyle.GRADIENT else style
            }
            val sharedPalette by com.auralis.music.ui.theme.ArtworkPaletteCache.currentPalette.collectAsState()

            if (isPlayerSheetActive) {
                Box(
                    modifier = Modifier
                        // Before graphicsLayer so the pills blur it at its current fade.
                        .hazeSource(state = pillHazeState, zIndex = 3f)
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = playerSheetProgress.value
                            alpha = if (reducedMotion) {
                                if (isNowPlayingOpen || p > 0f) 1f else 0f
                            } else {
                                (1.4f * kotlin.math.sqrt((p.coerceAtLeast(0.1f) - 0.1f))).coerceIn(0f, 1f)
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = isPlayerSheetActive && playerSheetProgress.value > 0.10f,
                            onClick = { collapsePlayer() }
                        )
                ) {
                    if (currentTrack != null) {
                        PlayerBackground(
                            style = resolvedPlayerBgStyle,
                            artworkUrl = currentTrack.thumbnail,
                            extractedColors = sharedPalette,
                            // Blur source for the Classic player's frosted lyrics controls.
                            modifier = Modifier
                                .fillMaxSize()
                                .hazeSource(state = playerBackdropHazeState, zIndex = 0f),
                            isMiniPlayer = false,
                            isPlaying = playerUiState.isPlaying
                        )
                    }
                }
            }

            // Single translating container hosting Full Player and MiniPlayer
            BoxWithConstraints(modifier = Modifier.hazeSource(state = pillHazeState, zIndex = 4f).fillMaxSize()) {
                val fullHeightPx = constraints.maxHeight.toFloat()
                val density = LocalDensity.current
                val isClassicMini = appearanceSettings.miniPlayerDesign == "Classic mini player"
                val targetBottomPadding = if (isSubScreenOpen) {
                    if (isClassicMini) 0.dp else 10.dp
                } else {
                    if (appearanceSettings.slimBottomNavigationBar) 56.dp else 68.dp
                }
                val bottomInset = with(density) { WindowInsets.systemBars.getBottom(density).toDp() }
                val miniPlayerHeight = com.auralis.music.ui.player.miniPlayerHeightFor(appearanceSettings.miniPlayerDesign)
                val collapsedBoundDp = miniPlayerHeight + targetBottomPadding + bottomInset
                val collapsedBoundPx = with(density) { collapsedBoundDp.toPx() }
                val travelDistance = (fullHeightPx - collapsedBoundPx).coerceAtLeast(0f)

                val sensitivityRatio = (appearanceSettings.miniPlayerSwipeSensitivity / 100f).coerceIn(0.10f, 1.0f)
                val dismissThresholdPx = with(density) { (90.dp * (1.15f - sensitivityRatio * 0.40f)).toPx() }
                val dismissVelocityThreshold = 1800f * (1.15f - sensitivityRatio * 0.40f)

                // 1. Full Player Sheet (Only active and taking touches when expanding/open)
                if (isPlayerSheetActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val p = playerSheetProgress.value
                                translationY = if (reducedMotion) {
                                    if (isNowPlayingOpen) 0f else travelDistance
                                } else {
                                    (1f - p) * travelDistance
                                }
                                shape = if (p < 1f) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) else androidx.compose.ui.graphics.RectangleShape
                                clip = p < 1f
                            }
                            .pointerInput(travelDistance) {
                                if (travelDistance <= 0f) return@pointerInput
                                val velocityTracker = VelocityTracker()
                                var currentProgress = 0f

                                detectVerticalDragGestures(
                                    onDragStart = {
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        sheetAnimationJob?.cancel()
                                        dismissAnimationJob?.cancel()
                                        currentProgress = playerSheetProgress.value
                                    },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        velocityTracker.addPointerInputChange(change)

                                        val deltaProgress = -dragAmount / travelDistance
                                        currentProgress = (currentProgress + deltaProgress).coerceIn(0f, 1f)
                                        sheetAnimationJob?.cancel()
                                        sheetAnimationJob = coroutineScope.launch {
                                            playerSheetProgress.snapTo(currentProgress)
                                        }
                                    },
                                    onDragCancel = {
                                        velocityTracker.resetTracking()
                                        val target = if (currentProgress < 0.5f) 0f else 1f
                                        sheetAnimationJob = coroutineScope.launch {
                                            try {
                                                playerSheetProgress.animateTo(
                                                    targetValue = target,
                                                    animationSpec = if (reducedMotion) snap() else spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = Spring.StiffnessLow
                                                    )
                                                )
                                            } finally {
                                                isNowPlayingOpen = (target == 1f)
                                                if (target == 0f && playerSheetProgress.value < 0.05f) {
                                                    playerSheetProgress.snapTo(0f)
                                                } else if (target == 1f && playerSheetProgress.value > 0.95f) {
                                                    playerSheetProgress.snapTo(1f)
                                                }
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        val rawVelocityY = velocityTracker.calculateVelocity().y
                                        val velocity = -rawVelocityY
                                        velocityTracker.resetTracking()

                                        val target = when {
                                            velocity < -300f -> 0f
                                            velocity > 300f -> 1f
                                            currentProgress < 0.5f -> 0f
                                            else -> 1f
                                        }
                                        sheetAnimationJob = coroutineScope.launch {
                                            try {
                                                playerSheetProgress.animateTo(
                                                    targetValue = target,
                                                    animationSpec = if (reducedMotion) snap() else spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = Spring.StiffnessLow
                                                    )
                                                )
                                            } finally {
                                                isNowPlayingOpen = (target == 1f)
                                                if (target == 0f && playerSheetProgress.value < 0.05f) {
                                                    playerSheetProgress.snapTo(0f)
                                                } else if (target == 1f && playerSheetProgress.value > 0.95f) {
                                                    playerSheetProgress.snapTo(1f)
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val p = playerSheetProgress.value
                                    alpha = if (reducedMotion) {
                                        if (isNowPlayingOpen) 1f else 0f
                                    } else {
                                        ((p - 0.15f) * 4f).coerceIn(0f, 1f)
                                    }
                                }
                        ) {
                            val currentPV = obtainPlayerViewModel()
                            val currentLibVM = obtainLibraryViewModel()
                            val modalPositionState = currentPV.playbackPositionMs.collectAsState()
                            CompositionLocalProvider(com.auralis.music.ui.player.LocalClassicLyricsHazeState provides playerBackdropHazeState) {
                                NowPlayingSheet(
                                    followHostOnly = isGuestInRoom && !guestCanSwipe,
                                    uiState = playerUiState,
                                    playbackPositionState = modalPositionState,
                                    lyricsClockSource = currentPV.playbackClockSource,
                                    userPlaylists = libraryUiState.playlists,
                                    renderBackground = false,
                                    onPlayPauseClick = {
                                        if (guestControlBlocked()) notifyGuestControlBlocked()
                                        else currentPV.togglePlayPause()
                                    },
                                    onSeekTo = { posMs ->
                                        if (guestControlBlocked()) {
                                            notifyGuestControlBlocked()
                                        } else {
                                            // Host seeks reach the room through the player's userSeekEvents.
                                            currentPV.seekTo(posMs)
                                        }
                                    },
                                    onNextClick = {
                                        if (guestSkipBlocked()) notifyGuestSkipBlocked()
                                        else if (!skipLocked()) currentPV.next()
                                    },
                                    onPreviousClick = {
                                        if (guestSkipBlocked()) notifyGuestSkipBlocked()
                                        else if (!skipLocked()) currentPV.previous()
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
                                        if (guestNow()) {
                                            when (index - playerUiState.currentIndex) {
                                                1 -> if (guestSkipBlocked()) notifyGuestSkipBlocked() else if (!skipLocked()) currentPV.next()
                                                -1 -> if (guestSkipBlocked()) notifyGuestSkipBlocked() else if (!skipLocked()) currentPV.previous()
                                                else -> playOrRequest(playerUiState.queue.getOrNull(index)) {}
                                            }
                                        } else {
                                            val q = playerUiState.queue
                                            val t = q.getOrNull(index) ?: if (index == 0) playerUiState.currentTrack else null
                                            val activeTrack = currentPV.getAudioPlayer()?.currentTrack?.value ?: playerUiState.currentTrack
                                            if (t != null && t.id != activeTrack?.id) {
                                                currentPV.playTrack(
                                                    t,
                                                    if (q.isNotEmpty()) q else listOf(t),
                                                    index,
                                                    preserveQueueSource = true
                                                )
                                            }
                                        }
                                    },
                                    onRemoveQueueItem = { index ->
                                        if (guestNow()) {
                                            notifyGuestControlBlocked()
                                        } else {
                                            currentPV.removeQueueItem(index)
                                            if (listenTogetherUiState.isHost && listenTogetherUiState.activeRoom != null) {
                                                val updated = currentPV.uiState.value
                                                updated.currentTrack?.let { trk ->
                                                    obtainListenTogetherViewModel().broadcastHostPlayback(
                                                        currentTrack = trk,
                                                        isPlaying = updated.isPlaying,
                                                        playbackPositionMs = currentPV.playbackPositionMs.value,
                                                        queue = updated.queue,
                                                        queueIndex = updated.currentIndex
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onReorderQueue = { fromIndex, toIndex ->
                                        if (guestNow()) {
                                            notifyGuestControlBlocked()
                                        } else {
                                            currentPV.moveQueueItem(fromIndex, toIndex)
                                            if (listenTogetherUiState.isHost && listenTogetherUiState.activeRoom != null) {
                                                val reordered = currentPV.uiState.value
                                                reordered.currentTrack?.let { trk ->
                                                    obtainListenTogetherViewModel().broadcastHostPlayback(
                                                        currentTrack = trk,
                                                        isPlaying = reordered.isPlaying,
                                                        playbackPositionMs = currentPV.playbackPositionMs.value,
                                                        queue = reordered.queue,
                                                        queueIndex = reordered.currentIndex
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onAddToPlaylist = { plId, track -> currentLibVM.addTrackToPlaylist(plId, track) },
                                    onCreatePlaylistAndAdd = { title, track -> currentLibVM.createPlaylistAndAddTrack(title, track) },
                                    onPlayNextTrack = { track ->
                                        when {
                                            guestMay { it.guestsMayAddSongs } -> obtainListenTogetherViewModel().recommendSong(track)
                                            guestNow() -> notifyGuestControlBlocked()
                                            else -> currentPV.playNext(track)
                                        }
                                    },
                                    onAddToQueueTrack = { track ->
                                        when {
                                            guestMay { it.guestsMayAddSongs } -> obtainListenTogetherViewModel().recommendSong(track)
                                            guestNow() -> notifyGuestControlBlocked()
                                            else -> currentPV.addToQueue(listOf(track))
                                        }
                                    },
                                    onToggleFavoriteTrack = { track -> currentPV.toggleFavorite(track) },
                                    isFavoriteTrack = { trackId -> libraryUiState.favorites.any { it.id == trackId } },
                                    onStartRadioTrack = { track ->
                                        playOrRequest(track) { currentPV.playTrack(track, listOf(track), 0) }
                                    },
                                    onAlbumClick = { album ->
                                        collapsePlayer()
                                        openAlbumDetail(album)
                                    },
                                    onArtistClick = { artist ->
                                        collapsePlayer()
                                        openArtistDetail(artist)
                                    },
                                    onDismiss = { collapsePlayer() },
                                    sharedTransitionScope = null,
                                    animatedVisibilityScope = null,
                                    currentQuality = currentPV.playerSettings.collectAsState().value.audioQuality,
                                    onAudioQualityChange = { currentPV.updateAudioQuality(it) },
                                    isTrackPinned = { homeViewModel.isTrackPinned(it) },
                                    onPinTrackToSpeedDial = { homeViewModel.togglePinTrack(it) }
                                )
                            }
                        }
                    }
                }

                // 2. Mini Player (Anchored strictly to bottom, touches only hit within its MiniPlayerHeight)
                if (isMiniPlayerVisible) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = targetBottomPadding + bottomInset)
                            .height(miniPlayerHeight)
                            .graphicsLayer {
                                val p = playerSheetProgress.value
                                val dismissY = dismissOffsetY.value
                                val progressFrac = (dismissY / (dismissThresholdPx * 2.2f)).coerceIn(0f, 1f)
                                translationY = dismissY - (p * with(density) { 36.dp.toPx() })
                                alpha = if (reducedMotion) {
                                    if (isNowPlayingOpen) 0f else (1f - progressFrac)
                                } else {
                                    ((1f - p * 3.5f) * (1f - progressFrac)).coerceIn(0f, 1f)
                                }
                                scaleX = 1f - (progressFrac * 0.12f)
                                scaleY = 1f - (progressFrac * 0.12f)
                            }
                            .pointerInput(travelDistance) {
                                if (travelDistance <= 0f) return@pointerInput
                                val velocityTracker = VelocityTracker()
                                var currentProgress = 0f
                                var isDraggingUp = false
                                // Drag-down-to-close state, tracked locally: reading the Animatable
                                // lagged a frame behind the snaps and dropped movement.
                                var dismissY = 0f
                                var isDismissDrag = false

                                // Always leave the mini player either closed or back in place.
                                fun settleDismiss(rawVelocityY: Float) {
                                    if (!isDismissDrag && dismissOffsetY.value <= 0f) return
                                    isDismissDrag = false
                                    if (dismissY > dismissThresholdPx || rawVelocityY > dismissVelocityThreshold) {
                                        dismissAnimationJob = coroutineScope.launch {
                                            dismissOffsetY.animateTo(fullHeightPx, tween(180))
                                            // A listener closing the player pauses the song for the whole room
                                            // (when the room lets them pause); anyone pressing play brings it back
                                            // on this phone too.
                                            if (guestNow() && !guestControlBlocked()) {
                                                activePV.getAudioPlayer()?.playFromControls(false)
                                            }
                                            // The host closing it pauses the room (see onHostClosingPlayer).
                                            listenTogetherViewModelState?.onHostClosingPlayer()
                                            activePV.closePlayer()
                                            dismissOffsetY.snapTo(0f)
                                        }
                                    } else {
                                        dismissAnimationJob = coroutineScope.launch {
                                            dismissOffsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                        }
                                    }
                                    dismissY = 0f
                                }

                                try {
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                            sheetAnimationJob?.cancel()
                                            dismissAnimationJob?.cancel()
                                            currentProgress = playerSheetProgress.value
                                            isDraggingUp = false
                                            dismissY = dismissOffsetY.value
                                            isDismissDrag = dismissY > 0f
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            velocityTracker.addPointerInputChange(change)

                                            // Once a drag-to-close has started it stays one: moving back up
                                            // only pulls the mini player back. Previously a tiny upward jitter
                                            // at release flipped into "expand" mode and left it stuck halfway.
                                            if (isDismissDrag || (dragAmount > 0 && playerSheetProgress.value <= 0f)) {
                                                isDismissDrag = true
                                                isDraggingUp = false
                                                dismissY = (dismissY + dragAmount).coerceAtLeast(0f)
                                                val target = dismissY
                                                dismissAnimationJob?.cancel()
                                                dismissAnimationJob = coroutineScope.launch {
                                                    dismissOffsetY.snapTo(target)
                                                }
                                            } else if (dragAmount < 0 || playerSheetProgress.value > 0f) {
                                                isDraggingUp = true
                                                val deltaProgress = -dragAmount / travelDistance
                                                currentProgress = (currentProgress + deltaProgress).coerceIn(0f, 1f)
                                                sheetAnimationJob?.cancel()
                                                sheetAnimationJob = coroutineScope.launch {
                                                    playerSheetProgress.snapTo(currentProgress)
                                                }
                                            }
                                        },
                                        onDragCancel = {
                                            velocityTracker.resetTracking()
                                            if (isDraggingUp) {
                                                val target = if (currentProgress < 0.5f) 0f else 1f
                                                sheetAnimationJob = coroutineScope.launch {
                                                    try {
                                                        playerSheetProgress.animateTo(
                                                            targetValue = target,
                                                            animationSpec = if (reducedMotion) snap() else spring(
                                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                                stiffness = Spring.StiffnessLow
                                                            )
                                                        )
                                                    } finally {
                                                        isNowPlayingOpen = (target == 1f)
                                                        if (target == 0f && playerSheetProgress.value < 0.05f) {
                                                            playerSheetProgress.snapTo(0f)
                                                        } else if (target == 1f && playerSheetProgress.value > 0.95f) {
                                                            playerSheetProgress.snapTo(1f)
                                                        }
                                                    }
                                                }
                                            }
                                            settleDismiss(0f)
                                        },
                                        onDragEnd = {
                                            val rawVelocityY = velocityTracker.calculateVelocity().y
                                            val velocity = -rawVelocityY
                                            velocityTracker.resetTracking()

                                            if (isDraggingUp) {
                                                val target = when {
                                                    velocity < -300f -> 0f
                                                    velocity > 300f -> 1f
                                                    currentProgress < 0.5f -> 0f
                                                    else -> 1f
                                                }
                                                sheetAnimationJob = coroutineScope.launch {
                                                    try {
                                                        playerSheetProgress.animateTo(
                                                            targetValue = target,
                                                            animationSpec = if (reducedMotion) snap() else spring(
                                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                                stiffness = Spring.StiffnessLow
                                                            )
                                                        )
                                                    } finally {
                                                        isNowPlayingOpen = (target == 1f)
                                                        if (target == 0f && playerSheetProgress.value < 0.05f) {
                                                            playerSheetProgress.snapTo(0f)
                                                        } else if (target == 1f && playerSheetProgress.value > 0.95f) {
                                                            playerSheetProgress.snapTo(1f)
                                                        }
                                                    }
                                                }
                                            }
                                            settleDismiss(rawVelocityY)
                                        }
                                    )
                                } finally {
                                    // Gesture torn down mid-drag (pointerInput restarted) without
                                    // end/cancel callbacks: never leave the mini player half-closed.
                                    if (isDismissDrag || dismissOffsetY.value > 0f) settleDismiss(0f)
                                }
                            },
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        MiniPlayerHost(
                            playerViewModel = activePV,
                            playerUiState = playerUiState,
                            audioPlayerFallbackTrack = audioPlayerTrack,
                            audioPlayerIsPlaying = audioPlayerIsPlaying,
                            isGuestInRoom = isGuestInRoom,
                            guestCanSwipe = guestCanSwipe,
                            guestControlBlocked = { guestControlBlocked() },
                            guestSkipBlocked = { guestSkipBlocked() },
                            isFullyCollapsed = isFullyCollapsed,
                            appearanceSettings = appearanceSettings,
                            hazeState = hazeState,
                            onOpenNowPlaying = { expandPlayer() },
                            onOpenArtist = {
                                val track = playerUiState.currentTrack ?: audioPlayerTrack
                                if (track != null) {
                                    openArtistDetail(com.auralis.music.domain.model.Artist(id = "", name = track.artist))
                                }
                            },
                            onAddToPlaylist = {
                                showMiniPlayerTrackOptions = true
                            },
                            notifyGuestControlBlocked = { notifyGuestControlBlocked() },
                            skipLocked = { skipLocked() },
                            requestGuestPlay = { track -> playOrRequest(track) {} }
                        )
                    }
                }
            }
        }

        // MiniPlayer Direct Add to Playlist Bottom Sheet
        if (showMiniPlayerTrackOptions && (playerUiState.currentTrack ?: audioPlayerTrack) != null) {
            val curTrack = (playerUiState.currentTrack ?: audioPlayerTrack)!!
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
                    val topPillShape = RoundedCornerShape(32.dp)
                    val surfaceColor = MaterialTheme.dynamicSurface
                    val isDark = surfaceColor.luminance() < 0.5f
                    Box(
                        modifier = Modifier
                            .wrapContentSize()
                            .shadow(
                                elevation = 10.dp,
                                shape = topPillShape,
                                ambientColor = Color.Black.copy(alpha = if (isDark) 0.35f else 0.12f),
                                spotColor = Color.Black.copy(alpha = if (isDark) 0.45f else 0.18f)
                            )
                            .clip(topPillShape)
                            // Blurs exactly what's on screen behind the pill (see pillHazeState).
                            .hazeEffect(
                                state = pillHazeState,
                                style = HazeStyle(
                                    backgroundColor = Color.Transparent,
                                    tint = HazeTint(surfaceColor.copy(alpha = if (isDark) 0.38f else 0.48f)),
                                    blurRadius = 24.dp,
                                    noiseFactor = 0.02f
                                )
                            ) {
                                // Haze turns blur off below API 32 and shows a flat scrim instead.
                                blurEnabled = true
                            }
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        pillTint.copy(alpha = 0.50f),
                                        pillTint.copy(alpha = 0.20f),
                                        pillTint.copy(alpha = 0.08f)
                                    )
                                ),
                                shape = topPillShape
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { obtainListenTogetherViewModel().dismissPill() }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
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
                                // Readable on the solid surface in light themes too.
                                color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // ── SLEEK COMPACT IN-APP TOAST PILL ──
        val appPill by com.auralis.music.ui.components.AppPillManager.pillState.collectAsState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1200f),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = appPill != null,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(tween(160)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(180, easing = FastOutLinearInEasing)
                ) + fadeOut(tween(160)),
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(
                        bottom = if (playerUiState.currentTrack != null && !isNowPlayingOpen) 125.dp else 70.dp,
                        start = 24.dp,
                        end = 24.dp
                    )
            ) {
                val pill = appPill
                if (pill != null) {
                    val pillShape = RoundedCornerShape(24.dp)
                    val surfaceColor = MaterialTheme.dynamicSurface
                    val isDark = surfaceColor.luminance() < 0.5f
                    val pillContentColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
                    Box(
                        modifier = Modifier
                            .wrapContentSize()
                            .shadow(
                                elevation = 10.dp,
                                shape = pillShape,
                                ambientColor = Color.Black.copy(alpha = if (isDark) 0.35f else 0.12f),
                                spotColor = Color.Black.copy(alpha = if (isDark) 0.45f else 0.18f)
                            )
                            .clip(pillShape)
                            // Blurs exactly what's on screen behind the pill (see pillHazeState).
                            .hazeEffect(
                                state = pillHazeState,
                                style = HazeStyle(
                                    backgroundColor = Color.Transparent,
                                    tint = HazeTint(surfaceColor.copy(alpha = if (isDark) 0.35f else 0.45f)),
                                    blurRadius = 24.dp,
                                    noiseFactor = 0.02f
                                )
                            ) {
                                blurEnabled = true
                            }
                            .border(
                                width = 0.75.dp,
                                color = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.12f),
                                shape = pillShape
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { com.auralis.music.ui.components.AppPillManager.dismiss() }
                            .padding(horizontal = 14.dp, vertical = 7.5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (pill.iconRes != null) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(id = pill.iconRes),
                                    contentDescription = null,
                                    tint = pillContentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = pill.message,
                                color = pillContentColor,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
    audioPlayerFallbackTrack: Track? = null,
    audioPlayerIsPlaying: Boolean = false,
    isGuestInRoom: Boolean,
    guestCanSwipe: Boolean,
    guestControlBlocked: () -> Boolean,
    guestSkipBlocked: () -> Boolean,
    isFullyCollapsed: Boolean,
    appearanceSettings: com.auralis.music.domain.model.AppearanceSettings,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    onOpenNowPlaying: () -> Unit,
    onOpenArtist: () -> Unit,
    onAddToPlaylist: () -> Unit,
    notifyGuestControlBlocked: () -> Unit,
    skipLocked: () -> Boolean,
    requestGuestPlay: (com.auralis.music.domain.model.Track?) -> Unit
) {
    val miniPositionState: State<Long> = playerViewModel.playbackPositionMs.collectAsState()
    val currentTrack = playerUiState.currentTrack ?: audioPlayerFallbackTrack ?: return
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

    key(appearanceSettings.miniPlayerDesign) {
        MiniPlayer(
            track = currentTrack,
            isPlaying = if (playerUiState.currentTrack != null) playerUiState.isPlaying else audioPlayerIsPlaying,
            progressState = miniProgressState,
            progressProvider = miniProgressProvider,
            queue = playerUiState.queue,
            currentIndex = playerUiState.currentIndex,
            isFavorite = playerUiState.isFavorite,
            userScrollEnabled = (!isGuestInRoom || guestCanSwipe) && isFullyCollapsed,
            hazeState = hazeState,
            onPlayPauseClick = {
                if (guestControlBlocked()) notifyGuestControlBlocked()
                else playerViewModel.togglePlayPause()
            },
            onNextClick = {
                if (guestSkipBlocked()) com.auralis.music.ui.components.AppPillManager.showPill("Only the host can change what's playing")
                else if (!skipLocked()) {
                    android.util.Log.d("AuralisPlayback", "[MiniPlayerHost] onNextClick -> playerViewModel.next()")
                    playerViewModel.next()
                }
            },
            onPreviousClick = {
                if (guestSkipBlocked()) com.auralis.music.ui.components.AppPillManager.showPill("Only the host can change what's playing")
                else if (!skipLocked()) {
                    android.util.Log.d("AuralisPlayback", "[MiniPlayerHost] onPreviousClick -> playerViewModel.previous()")
                    playerViewModel.previous()
                }
            },
            onSelectQueueTrack = { index ->
                if (isGuestInRoom) {
                    // A one-song swipe is a next/previous for the room, like the buttons.
                    when (index - playerUiState.currentIndex) {
                        1 -> if (guestSkipBlocked()) notifyGuestControlBlocked() else if (!skipLocked()) playerViewModel.next()
                        -1 -> if (guestSkipBlocked()) notifyGuestControlBlocked() else if (!skipLocked()) playerViewModel.previous()
                        else -> requestGuestPlay(playerUiState.queue.getOrNull(index))
                    }
                } else {
                    val q = playerUiState.queue
                    val t = q.getOrNull(index) ?: if (index == 0) currentTrack else null
                    val activeTrack = playerViewModel.getAudioPlayer()?.currentTrack?.value ?: currentTrack
                    if (t != null && t.id != activeTrack?.id) {
                        playerViewModel.playTrack(
                            t,
                            if (q.isNotEmpty()) q else listOf(t),
                            index,
                            preserveQueueSource = true
                        )
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
            onClose = null,
            onClick = {
                if (isFullyCollapsed) {
                    onOpenNowPlaying()
                }
            },
            sharedTransitionScope = null,
            animatedVisibilityScope = null
        )
    }
}

