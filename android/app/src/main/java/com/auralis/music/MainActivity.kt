package com.auralis.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
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
        enableEdgeToEdge()

        // Request POST_NOTIFICATIONS permission for Android 13+ (API 33+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Initialize Core Singletons / Repositories
        val db = AuralisDatabase.getInstance(applicationContext)
        val settingsDataStore = SettingsDataStore(applicationContext)
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

        val googleAccountSyncManager = GoogleAccountSyncManager(
            context = applicationContext,
            libraryRepository = libraryRepository,
            historyRepository = historyRepository,
            searchRepository = searchRepository
        )

        setContent {
            AuralisTheme {
                val homeViewModel: HomeViewModel = viewModel {
                    HomeViewModel(historyRepository, searchRepository, innerTubeClient)
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
                        searchRepository = searchRepository
                    )
                }
                val listenTogetherViewModel: ListenTogetherViewModel = viewModel {
                    ListenTogetherViewModel(searchRepository = searchRepository)
                }
                val authViewModel: AuthViewModel = viewModel {
                    AuthViewModel(googleAccountSyncManager)
                }

                AuralisApp(
                    homeViewModel = homeViewModel,
                    searchViewModel = searchViewModel,
                    libraryViewModel = libraryViewModel,
                    playerViewModel = playerViewModel,
                    listenTogetherViewModel = listenTogetherViewModel,
                    authViewModel = authViewModel
                )
            }
        }
    }
}
