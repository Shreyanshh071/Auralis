package com.auralis.music.ui.viewmodel

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SpotifyPlaylistImporter
import com.auralis.music.data.network.YouTubePlaylistImporter
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.auth.GoogleAccountSyncManager
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.domain.repository.LyricsRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.domain.repository.SettingsRepository
import com.auralis.music.domain.repository.StatsRepository

/**
 * Lifecycle-safe, on-demand ViewModel provider scoped directly to [MainActivity].
 * ViewModels are only instantiated when their respective feature/screen is first accessed,
 * eliminating the cold-start bottleneck of eagerly creating all 7 ViewModels on the main thread.
 */
class AppViewModelProvider(
    private val activity: ComponentActivity,
    val historyRepository: HistoryRepository,
    val searchRepository: SearchRepository,
    val libraryRepository: LibraryRepository,
    val statsRepository: StatsRepository,
    val settingsRepository: SettingsRepository,
    val lyricsRepository: LyricsRepository,
    val audioPlayer: AuralisAudioPlayer,
    val innerTubeClient: InnerTubeClient,
    val googleAccountSyncManager: GoogleAccountSyncManager,
    val youtubeImporter: YouTubePlaylistImporter,
    val spotifyImporter: SpotifyPlaylistImporter
) {
    fun getHomeViewModel(): HomeViewModel {
        return ViewModelProvider(
            activity,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(
                        historyRepository,
                        searchRepository,
                        innerTubeClient,
                        activity.applicationContext
                    ) as T
                }
            }
        )[HomeViewModel::class.java]
    }

    fun getSearchViewModel(): SearchViewModel {
        return ViewModelProvider(
            activity,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SearchViewModel(
                        searchRepository,
                        activity.applicationContext
                    ) as T
                }
            }
        )[SearchViewModel::class.java]
    }

    fun getLibraryViewModel(): LibraryViewModel {
        return ViewModelProvider(
            activity,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(
                        libraryRepository,
                        youtubeImporter,
                        spotifyImporter
                    ) as T
                }
            }
        )[LibraryViewModel::class.java]
    }

    fun getPlayerViewModel(): PlayerViewModel {
        return ViewModelProvider(
            activity,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PlayerViewModel(
                        libraryRepository = libraryRepository,
                        historyRepository = historyRepository,
                        lyricsRepository = lyricsRepository,
                        settingsRepository = settingsRepository,
                        audioPlayer = audioPlayer,
                        innerTubeClient = innerTubeClient,
                        searchRepository = searchRepository,
                        context = activity.applicationContext
                    ) as T
                }
            }
        )[PlayerViewModel::class.java]
    }

    fun getListenTogetherViewModel(): ListenTogetherViewModel {
        return ViewModelProvider(
            activity,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ListenTogetherViewModel(
                        searchRepository = searchRepository,
                        syncManager = googleAccountSyncManager
                    ) as T
                }
            }
        )[ListenTogetherViewModel::class.java]
    }

    fun getAuthViewModel(): AuthViewModel {
        return ViewModelProvider(
            activity,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AuthViewModel(googleAccountSyncManager) as T
                }
            }
        )[AuthViewModel::class.java]
    }

    fun getStatsViewModel(): StatsViewModel {
        return ViewModelProvider(
            activity,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return StatsViewModel(statsRepository) as T
                }
            }
        )[StatsViewModel::class.java]
    }
}
