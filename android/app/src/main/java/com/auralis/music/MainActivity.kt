package com.auralis.music

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.auralis.music.data.network.YouTubePlaylistImporter
import com.auralis.music.data.repository.*
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.auth.GoogleAccountSyncManager
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.AuralisApp
import com.auralis.music.ui.theme.AuralisTheme
import com.auralis.music.ui.viewmodel.AuthViewModel
import com.auralis.music.ui.viewmodel.HomeViewModel
import com.auralis.music.ui.viewmodel.LibraryViewModel
import com.auralis.music.ui.viewmodel.ListenTogetherViewModel
import com.auralis.music.ui.viewmodel.PlayerViewModel
import com.auralis.music.ui.viewmodel.SearchViewModel

@UnstableApi
class MainActivity : ComponentActivity() {

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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        liveNavDestination.value = extractNavDestination(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveNavDestination.value = extractNavDestination(intent)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()

        // Request POST_NOTIFICATIONS permission for Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Initialize Core Singletons / Repositories
        val db = AuralisDatabase.getInstance(applicationContext)
        val settingsDataStore = SettingsDataStore(applicationContext)
        val appearanceDataStore = AppearanceSettingsDataStore(applicationContext)
        val audioPlayer = AuralisAudioPlayer.getInstance(applicationContext)

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

        val libraryRepository = LibraryRepositoryImpl(trackDao, playlistDao, libraryDao)
        val historyRepository = HistoryRepositoryImpl(trackDao, historyDao, playCountDao)
        val settingsRepository = SettingsRepositoryImpl(settingsDataStore)
        val searchRepository = SearchRepositoryImpl(innerTubeClient, suggestionsClient, searchHistoryDao)
        val lyricsRepository = LyricsRepositoryImpl(lyricsClient, lyricsDao, db.negativeLyricsDao())

        Log.d("AuralisPlayback", "[MainActivity] onCreate - connected to AuralisAudioPlayer (track=${audioPlayer.currentTrack.value?.title}, isPlaying=${audioPlayer.isPlaying.value})")

        val googleAccountSyncManager = GoogleAccountSyncManager(
            context = applicationContext,
            libraryRepository = libraryRepository,
            historyRepository = historyRepository,
            searchRepository = searchRepository
        )
        googleAccountSyncManager.startContinuousCloudSync(lifecycleScope)

        // Background update check & notification on startup
        lifecycleScope.launch(Dispatchers.IO) {
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

        setContent {
            val appearanceSettings by appearanceDataStore.settingsFlow.collectAsState(
                initial = AppearanceSettings()
            )
            val privacyDataStore = remember { com.auralis.music.data.datastore.PrivacyDataStore(applicationContext) }
            val privacySettings by privacyDataStore.settingsFlow.collectAsState(
                initial = com.auralis.music.domain.model.PrivacySettings()
            )

            // Dynamic High Refresh Rate Enforcer (120Hz / 144Hz / 90Hz)
            LaunchedEffect(appearanceSettings.highRefreshRate) {
                val lp = window.attributes
                if (appearanceSettings.highRefreshRate) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val maxDisplayMode = display?.supportedModes?.maxByOrNull { it.refreshRate }
                        lp.preferredDisplayModeId = maxDisplayMode?.modeId ?: 0
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val maxRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 120f
                        } else 120f
                        lp.preferredRefreshRate = maxRate
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        lp.preferredDisplayModeId = 0
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        lp.preferredRefreshRate = 0f
                    }
                }
                window.attributes = lp
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

            AuralisTheme(appearanceSettings = appearanceSettings) {
                val homeViewModel: HomeViewModel = viewModel {
                    HomeViewModel(historyRepository, searchRepository, innerTubeClient, applicationContext)
                }
                val searchViewModel: SearchViewModel = viewModel {
                    SearchViewModel(searchRepository, applicationContext)
                }
                val libraryViewModel: LibraryViewModel = viewModel {
                    LibraryViewModel(libraryRepository, youtubeImporter, spotifyImporter)
                }
                val playerViewModel: PlayerViewModel = viewModel {
                    PlayerViewModel(
                        libraryRepository = libraryRepository,
                        historyRepository = historyRepository,
                        lyricsRepository = lyricsRepository,
                        settingsRepository = settingsRepository,
                        audioPlayer = audioPlayer,
                        innerTubeClient = innerTubeClient,
                        searchRepository = searchRepository,
                        context = applicationContext
                    )
                }
                val listenTogetherViewModel: ListenTogetherViewModel = viewModel {
                    ListenTogetherViewModel(
                        searchRepository = searchRepository,
                        syncManager = googleAccountSyncManager
                    )
                }
                val authViewModel: AuthViewModel = viewModel {
                    AuthViewModel(googleAccountSyncManager)
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    AuralisApp(
                        homeViewModel = homeViewModel,
                        searchViewModel = searchViewModel,
                        libraryViewModel = libraryViewModel,
                        playerViewModel = playerViewModel,
                        listenTogetherViewModel = listenTogetherViewModel,
                        authViewModel = authViewModel,
                        googleAccountSyncManager = googleAccountSyncManager,
                        appearanceSettings = appearanceSettings,
                        initialNavDestination = liveNavDestination.value
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.d("AuralisPlayback", "[MainActivity] onStart")
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("AuralisPlayback", "[MainActivity] onResume")
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.d("AuralisPlayback", "[MainActivity] onPause")
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.d("AuralisPlayback", "[MainActivity] onStop")
    }

    override fun onDestroy() {
        android.util.Log.d("AuralisPlayback", "[MainActivity] onDestroy - Activity destroyed, background service and player remain intact")
        super.onDestroy()
    }
}
