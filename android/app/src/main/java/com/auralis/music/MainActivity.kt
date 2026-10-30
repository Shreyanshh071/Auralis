package com.auralis.music

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import com.auralis.music.data.datastore.AppearanceSettingsDataStore
import com.auralis.music.data.datastore.SettingsDataStore
import com.auralis.music.data.local.AuralisDatabase
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.network.SpotifyPlaylistImporter
import com.auralis.music.data.network.CompleteYouTubePlaylistImporter as YouTubePlaylistImporter
import com.discord.socialsdk.DiscordSocialSdkInit
import com.auralis.music.data.repository.*
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.auth.GoogleAccountSyncManager
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.AuralisApp
import com.auralis.music.ui.components.afterLaunchFrame
import com.auralis.music.ui.theme.AuralisTheme
import com.auralis.music.ui.theme.ArtworkPaletteCache
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.viewmodel.AuthViewModel
import com.auralis.music.ui.viewmodel.HomeViewModel
import com.auralis.music.ui.viewmodel.LibraryViewModel
import com.auralis.music.ui.viewmodel.ListenTogetherViewModel
import com.auralis.music.ui.viewmodel.PlayerViewModel
import com.auralis.music.ui.viewmodel.SearchViewModel

@UnstableApi
class MainActivity : ComponentActivity() {

    private val launchTouchGate = com.auralis.music.ui.components.LaunchTouchGate()
    private var launchDrawingComplete by mutableStateOf(false)
    private var launchOverlayRemoved by mutableStateOf(false)
    private var activeLaunchLogo: com.auralis.music.ui.components.LaunchLogoView? = null

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (launchTouchGate.shouldConsume(event.actionMasked)) return true
        return super.dispatchTouchEvent(event)
    }

    private var liveNavDestination = androidx.compose.runtime.mutableStateOf<String?>(null)

    private fun extractNavDestination(intent: android.content.Intent?): String? {
        if (intent == null) return null
        val explicit = intent.getStringExtra("NAV_DESTINATION")
            ?: intent.getStringExtra("nav_destination")
            ?: intent.extras?.getString("NAV_DESTINATION")
            ?: intent.extras?.getString("nav_destination")
            ?: intent.extras?.getString("destination")
        if (!explicit.isNullOrBlank()) return explicit

        if (intent.hasExtra("google.message_id") || intent.hasExtra("google.sent_time") || intent.hasExtra("from")) {
            return "updater"
        }
        return null
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.auralis.music.ui.i18n.AppLanguage.wrap(newBase))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.auralis.music.ui.i18n.AppLanguage.onConfigurationChanged(this, newConfig)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        liveNavDestination.value = extractNavDestination(intent)
        handleRetryIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.auralis.music.ui.i18n.AppLanguage.attach(this)
        super.onCreate(savedInstanceState)
        // Retained state means an in-process recreation; a saved Bundle alone can also
        // mean Android killed the process, in which case the launch animation is needed.
        val recreatingInProcess = savedInstanceState != null && lastNonConfigurationInstance != null
        launchDrawingComplete = recreatingInProcess
        launchOverlayRemoved = recreatingInProcess
        if (!recreatingInProcess) showFastLaunch(waitForSystemSplash = savedInstanceState == null)
        else launchTouchGate.splashRemoved()
        DiscordSocialSdkInit.setEngineActivity(this)
        liveNavDestination.value = extractNavDestination(intent)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()

        // Optional notification access is explained and requested from the app tour.

        // Initialize Core Singletons / Repositories
        val db = AuralisDatabase.getInstance(applicationContext)
        val settingsDataStore = SettingsDataStore(applicationContext)
        val appearanceDataStore = AppearanceSettingsDataStore(applicationContext)
        // Apply the refresh rate before the first frame. Applied from Compose, it switched the
        // panel (90 -> 120 Hz) right after Home first drew, and that switch blanks the screen for
        // ~0.5 s on many phones: the app flashed Home, went blank, then faded back in.
        val startupAppearance = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(150L) { appearanceDataStore.settingsFlow.first() }
            }
        }.getOrNull() ?: appearanceDataStore.getInitialSettings()
        applyRefreshRate(startupAppearance.highRefreshRate)
        val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)
        com.auralis.music.data.service.ListeningTimeTracker.start(applicationContext)

        val trackDao = db.trackDao()
        val playlistDao = db.playlistDao()
        val libraryDao = db.libraryDao()
        val historyDao = db.historyDao()
        val playCountDao = db.playCountDao()
        val searchHistoryDao = db.searchHistoryDao()
        val lyricsDao = db.lyricsDao()

        val innerTubeClient = InnerTubeClient()
        val suggestionsClient = SearchSuggestionsClient()
        val lyricsClient = LyricsClient()
        val youtubeImporter = YouTubePlaylistImporter()
        val spotifyImporter = SpotifyPlaylistImporter()

        com.auralis.music.data.network.ArtistPhotoProvider.init(applicationContext, innerTubeClient, libraryDao)

        val libraryRepository = LibraryRepositoryImpl(trackDao, playlistDao, libraryDao)
        val historyRepository = HistoryRepositoryImpl(trackDao, historyDao, playCountDao, db.playbackEventDao())
        val statsRepository = com.auralis.music.data.repository.StatsRepositoryImpl(trackDao, db.playbackEventDao(), historyDao, playCountDao)
        val settingsRepository = SettingsRepositoryImpl(settingsDataStore)
        val searchRepository = com.auralis.music.data.repository.CatalogSearchRepository(SearchRepositoryImpl(innerTubeClient, suggestionsClient, com.auralis.music.data.repository.RoomSearchHistoryStore(searchHistoryDao)))
        com.auralis.music.data.local.ImportedLyricsStore.init(applicationContext)
        val lyricsRepository = LyricsRepositoryImpl(lyricsClient, lyricsDao, db.negativeLyricsDao())

        Log.d("AuralisPlayback", "[MainActivity] onCreate - connected to AuralisAudioPlayer (track=${audioPlayer.currentTrack.value?.title}, isPlaying=${audioPlayer.isPlaying.value})")

        val googleAccountSyncManager = GoogleAccountSyncManager(
            context = applicationContext,
            libraryRepository = libraryRepository,
            historyRepository = historyRepository,
            searchRepository = searchRepository
        )
        // Defer cloud sync slightly past initial frame draw so DB/network doesn't contend with cold startup
        lifecycleScope.launch(Dispatchers.IO) {
            com.auralis.music.data.sync.StatsCloudSync.init(applicationContext, statsRepository)
            kotlinx.coroutines.delay(3000L)
            com.auralis.music.data.sync.AndroidAccountPreferences(applicationContext).start(this)
            googleAccountSyncManager.startContinuousCloudSync(this)
            // Listening stats live on the account too, so a reinstall doesn't wipe them.
            com.auralis.music.data.sync.StatsCloudSync.start(this)
        }

        // Background update check & notification on startup
        lifecycleScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(1000L)
            try {
                val updaterStore = com.auralis.music.data.datastore.UpdaterDataStore(applicationContext)
                val autoCheck = updaterStore.settingsFlow.first().autoCheckUpdates
                if (autoCheck) {
                    val updateInfo = com.auralis.music.data.network.UpdateChecker.checkForUpdates(applicationContext)
                    if (updateInfo.hasUpdate) {
                        Log.d("AuralisUpdater", "New update detected on startup: v${updateInfo.latestVersion}")
                        com.auralis.music.data.network.UpdateChecker.showUpdateNotification(applicationContext, updateInfo)
                    }
                }
            } catch (e: Exception) {
                Log.w("AuralisUpdater", "Startup update check failed: ${e.message}")
            }
        }

        // Cold app startup: initialize immediately from the phone's CURRENT System Dynamic Color.
        // Never restore the previous song's artwork palette as the initial theme.
        ArtworkPaletteCache.resetToDefault()

        // Restore local Home data while the native logo is drawing, rather than
        // starting the database/cache work only after Home's first empty frame.
        val viewModelProvider = com.auralis.music.ui.viewmodel.AppViewModelProvider(
            activity = this, historyRepository = historyRepository,
            searchRepository = searchRepository, libraryRepository = libraryRepository,
            statsRepository = statsRepository, settingsRepository = settingsRepository,
            lyricsRepository = lyricsRepository, audioPlayer = audioPlayer,
            innerTubeClient = innerTubeClient, googleAccountSyncManager = googleAccountSyncManager,
            youtubeImporter = youtubeImporter, spotifyImporter = spotifyImporter
        )
        val startupHome = viewModelProvider.getHomeViewModel()

        setContent {
            val homeContentReady by startupHome.initialContentReady.collectAsState()
            if (!launchDrawingComplete || !homeContentReady) {
                // Let the small native logo animate without composing Home, artwork, or effects.
                Box(Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Color(getColor(R.color.launch_background))))
                return@setContent
            }
            DisposableEffect(Unit) {
                val logo = activeLaunchLogo
                val cancel = logo?.let {
                    findViewById<android.view.View>(android.R.id.content).afterLaunchFrame {
                        it.contentReady()
                    }
                }
                onDispose { cancel?.invoke() }
            }
            val appearanceSettings by appearanceDataStore.settingsFlow.collectAsState(
                // The startup read above already obtained these settings. Using
                // defaults here built a normal-theme Home first, then rebuilt it
                // as glass when the same saved preferences arrived from the flow.
                initial = remember { startupAppearance }
            )
            val privacyDataStore = remember { com.auralis.music.data.datastore.PrivacyDataStore(applicationContext) }
            val privacySettings by privacyDataStore.settingsFlow.collectAsState(
                initial = com.auralis.music.domain.model.PrivacySettings()
            )

            // Only when the setting really changes: re-applying the same rate after the first frame
            // is what used to blank the screen at launch.
            LaunchedEffect(appearanceSettings.highRefreshRate) {
                applyRefreshRate(appearanceSettings.highRefreshRate)
            }

            // Secure Flag (Disable Screenshots / Recents Preview)
            LaunchedEffect(privacySettings.disableScreenshot) {
                if (privacySettings.disableScreenshot) {
                    window.setFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SECURE,
                        android.view.WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            val currentTrack by audioPlayer.currentTrack.collectAsState()
            val isPlaying by audioPlayer.isPlaying.collectAsState()
            var hasActiveSongInSession by rememberSaveable { mutableStateOf(false) }

            LaunchedEffect(isPlaying) {
                if (isPlaying) {
                    hasActiveSongInSession = true
                }
            }

            val activeArtworkPalette by ArtworkPaletteCache.currentPalette.collectAsState()
            LaunchedEffect(activeArtworkPalette) {
                if (activeArtworkPalette != ArtworkPaletteCache.defaultPalette && currentTrack != null) {
                    hasActiveSongInSession = true
                }
            }

            // Dynamic Theme Lifecycle:
            // - Cold app start: currentTrack == null -> phone's CURRENT System Dynamic Color
            // - Active song / playing: currentTrack != null -> song artwork drives Dynamic theme
            // - Paused / Stopped: currentTrack != null & hasActiveSongInSession is true -> KEEP artwork theme!
            // - Player closed / cleared: currentTrack == null -> reset to phone's System Dynamic Color cleanly & synchronously!
            LaunchedEffect(currentTrack?.id) {
                if (currentTrack != null) {
                    hasActiveSongInSession = true
                    ArtworkPaletteCache.updateForTrack(applicationContext, currentTrack)
                } else {
                    hasActiveSongInSession = false
                    ArtworkPaletteCache.resetToDefault()
                }
            }
            val isSongActive = currentTrack != null && hasActiveSongInSession
            val artworkPaletteForTheme = if (isSongActive) activeArtworkPalette else ArtworkPaletteCache.defaultPalette

            AuralisTheme(
                appearanceSettings = appearanceSettings,
                artworkPalette = artworkPaletteForTheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.dynamicBackground,
                    contentColor = MaterialTheme.dynamicOnBackground
                ) {
                    // Same "signed in" check AuralisApp makes for its own welcome/app switch.
                    // Duplicated (not hoisted) so a drift between the two only ever mistimes this
                    // veil by a frame; it can never block or skew the real gate inside AuralisApp.
                    val authVM = remember { viewModelProvider.getAuthViewModel() }
                    val authUiState by authVM.uiState.collectAsState()
                    val isFirebaseUserActiveHere = try {
                        val fbUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                        fbUser != null && !fbUser.isAnonymous
                    } catch (_: Exception) { false }
                    val isAppUnlockedHere = isFirebaseUserActiveHere ||
                        (authUiState.profile.isGoogleConnected && authUiState.profile.uid.isNotBlank())

                    Box(modifier = Modifier.fillMaxSize()) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            com.auralis.music.ui.components.LocalLaunchContentVisible provides launchOverlayRemoved
                        ) {
                        AuralisApp(
                            viewModelProvider = viewModelProvider,
                            appearanceSettings = appearanceSettings,
                            googleAccountSyncManager = googleAccountSyncManager,
                            initialNavDestination = liveNavDestination.value
                        )
                        }

                        // Soft veil masking the moment sign-in hands off to the app: the welcome
                        // screen is mid-dissolve (see AuralisApp) while the app underneath still
                        // pops onto screen with no animation of its own, so this holds a covering
                        // fade across that handoff and then lifts, reading as one smooth motion
                        // rather than a dissolve immediately followed by a hard cut.
                        var showUnlockVeil by remember { mutableStateOf(false) }
                        val veilAlpha = remember { Animatable(0f) }
                        // Only for a real sign-in during this session. It used to run on every
                        // launch of a signed-in user too, covering Home for ~0.9 s right after the
                        // splash: the "opening the app isn't smooth" blank.
                        var wasUnlocked by remember { mutableStateOf(isAppUnlockedHere) }
                        LaunchedEffect(isAppUnlockedHere) {
                            val justSignedIn = isAppUnlockedHere && !wasUnlocked
                            wasUnlocked = isAppUnlockedHere
                            if (justSignedIn) {
                                showUnlockVeil = true
                                veilAlpha.snapTo(0f)
                                veilAlpha.animateTo(1f, tween(150))
                                kotlinx.coroutines.delay(420L)
                                veilAlpha.animateTo(0f, tween(320))
                                showUnlockVeil = false
                            } else {
                                showUnlockVeil = false
                                veilAlpha.snapTo(0f)
                            }
                        }
                        if (showUnlockVeil) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(veilAlpha.value)
                                    .background(MaterialTheme.dynamicBackground)
                            )
                        }
                        com.auralis.music.ui.theme.ThemeCrossfade.Overlay()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.auralis.music.data.connect.AuralisConnect.setForeground(true)
        android.util.Log.d("AuralisPlayback", "[MainActivity] onStart")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == com.auralis.music.data.download.PlaylistDownloadCoordinator.LEGACY_STORAGE_PERMISSION_REQUEST) {
            com.auralis.music.data.download.PlaylistDownloadCoordinator.onLegacyStoragePermissionResult(
                this,
                grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
            )
        }
    }

    override fun onResume() {
        super.onResume()
        com.auralis.music.data.connect.AuralisConnect.refresh()
        android.util.Log.d("AuralisPlayback", "[MainActivity] onResume")
        intent?.let { handleRetryIntent(it) }
    }

    private fun handleRetryIntent(intent: android.content.Intent) {
        val retryJobId = intent.takeIf { it.getBooleanExtra("RETRY_PLAYLIST_DOWNLOAD", false) }
            ?.getStringExtra("PLAYLIST_DOWNLOAD_JOB_ID")
        if (!retryJobId.isNullOrBlank()) {
            intent.removeExtra("RETRY_PLAYLIST_DOWNLOAD")
            com.auralis.music.data.download.PlaylistDownloadCoordinator.retry(this, retryJobId)
        }
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.d("AuralisPlayback", "[MainActivity] onPause")
    }

    override fun onStop() {
        super.onStop()
        com.auralis.music.data.connect.AuralisConnect.setForeground(false)
        android.util.Log.d("AuralisPlayback", "[MainActivity] onStop")
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            com.auralis.music.data.connect.AuralisConnect.onTaskRemoved()
        }
        android.util.Log.d("AuralisPlayback", "[MainActivity] onDestroy - Activity destroyed, background service and player remain intact")
        super.onDestroy()
    }

    private var appliedHighRefresh: Boolean? = null

    /**
     * Asks for the fastest refresh rate at the current resolution (or the system default). A mode
     * with another resolution always blanks the panel, so those are never chosen. No-op when the
     * requested state is already applied.
     */
    private fun applyRefreshRate(high: Boolean) {
        if (appliedHighRefresh == high) return
        appliedHighRefresh = high
        val lp = window.attributes
        if (high) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val current = display?.mode
                val best = display?.supportedModes
                    ?.filter { current == null || (it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight) }
                    ?.maxByOrNull { it.refreshRate }
                lp.preferredDisplayModeId = best?.modeId ?: 0
                lp.preferredRefreshRate = best?.refreshRate ?: 0f
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                lp.preferredRefreshRate = 120f
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.preferredDisplayModeId = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) lp.preferredRefreshRate = 0f
        }
        window.attributes = lp
    }


    /** Draw the logo first, then keep its final shape over the first actual app frame. */
    private fun showFastLaunch(waitForSystemSplash: Boolean) {
        val startMs = android.os.SystemClock.uptimeMillis()
        val content = findViewById<android.view.View>(android.R.id.content)
        val decor = window.decorView as android.view.ViewGroup
        // Keep one fixed icon size across the system/app splash handoff. Android's
        // starting-window icon surface can otherwise resize when handed to the activity.
        val launchLogo = com.auralis.music.ui.components.LaunchLogoView(
            this, onDrawingComplete = { launchDrawingComplete = true }
        ) {
            launchDrawingComplete = true
            launchOverlayRemoved = true
            activeLaunchLogo = null
            launchTouchGate.splashRemoved()
            Log.d("AuralisStartup", "Home revealed ${android.os.SystemClock.uptimeMillis() - startMs}ms after launch setup")
        }
        activeLaunchLogo = launchLogo
        decor.addView(launchLogo, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        content.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                content.viewTreeObserver.removeOnPreDrawListener(this)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !waitForSystemSplash) {
                    // A restored activity may not get an Android splash-exit callback. Let any
                    // default starting-window fade finish before drawing our own visible mark.
                    launchLogo.postDelayed({ launchLogo.reveal() }, if (waitForSystemSplash) 0L else 250L)
                } else {
                    // Android does not promise an exit callback when no starting splash
                    // was shown (for example some launcher/task restoration paths).
                    launchLogo.postDelayed({ launchLogo.reveal() }, 1_000L)
                }
                return true
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && waitForSystemSplash) {
            splashScreen.setOnExitAnimationListener { splashView ->
                splashView.remove()
                launchLogo.reveal()
            }
        }
    }

}
