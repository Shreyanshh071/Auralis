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
import androidx.lifecycle.viewmodel.compose.viewModel
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        appearanceSettings = appearanceSettings
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
