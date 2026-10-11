package com.auralis.music.windows.dev

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.unit.dp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.repository.KeyValueSearchHistoryStore
import com.auralis.music.data.repository.SearchRepositoryImpl
import com.auralis.music.desktop.library.DesktopHistoryRepository
import com.auralis.music.desktop.library.DesktopLibraryPlatform
import com.auralis.music.desktop.library.DesktopLibraryRepository
import com.auralis.music.desktop.settings.DesktopDataStores
import com.auralis.music.ui.components.DesktopPlatformActions
import com.auralis.music.ui.screens.AboutScreen
import com.auralis.music.ui.screens.AiLyricsTranslationScreen
import com.auralis.music.ui.screens.AppearanceScreen
import com.auralis.music.ui.screens.ContentSettingsScreen
import com.auralis.music.ui.screens.DiscordIntegrationScreen
import com.auralis.music.ui.screens.PrivacySettingsScreen
import com.auralis.music.ui.screens.StorageSettingsScreen
import com.auralis.music.ui.screens.UpdaterScreen
import com.auralis.music.ui.screens.YouTubeAccountScreen
import com.auralis.music.ui.viewmodel.LibraryViewModel
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.windows.profile.DesktopProfile
import com.auralis.music.windows.profile.DesktopSettings
import com.auralis.music.windows.settings.DesktopSettingsPlatform
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Development check: draws Windows screens to PNG files without opening a window (Compose's
 * ImageComposeScene), so pages can be checked without touching the desktop or taking focus.
 * Uses a throwaway data folder; never the user's %APPDATA%.
 *
 * usage: renderScreens <outDir> [screen names...]
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screens").apply { mkdirs() }
    val only = args.drop(1).toSet()
    com.auralis.music.ui.i18n.DesktopStrings.install()
    val data = kotlin.io.path.createTempDirectory("auralis-render").toFile()
    val stores = DesktopDataStores(File(data, "settings"))
    val library = DesktopLibraryRepository(File(data, "library.json"))
    val history = DesktopHistoryRepository(File(data, "history.json"), library)
    val stats = com.auralis.music.desktop.library.DesktopListeningStats(File(data, "stats.json"))
    val sampleTrack = com.auralis.music.domain.model.Track(id = "preview", title = "Auralis preview song", artist = "Preview artist", duration = 180)
    kotlinx.coroutines.runBlocking { history.addToHistory(sampleTrack) }
    stats.record("preview", sampleTrack, System.currentTimeMillis(), 75_000)
    val search = SearchRepositoryImpl(
        InnerTubeClient(), SearchSuggestionsClient(),
        KeyValueSearchHistoryStore(PropertiesFileKeyValueStore(File(data, "search-history.properties")))
    )
    val messages = mutableListOf<String>()
    val actions = DesktopPlatformActions { messages += it }
    val libraryVm = LibraryViewModel(libraryRepository = library, historyRepository = history,
        platform = DesktopLibraryPlatform(PropertiesFileKeyValueStore(File(data, "flags.properties"))) { messages += it })
    val platform = DesktopSettingsPlatform(null, history, search, discord = null)

    // Representative fixtures only; rendering Home never fetches recommendations or reads user data.
    val starterHome = com.auralis.music.ui.viewmodel.HomeUiState()
    val previewSongs = starterHome.speedDialPages.flatten().mapNotNull { it.track }.distinctBy { it.id }
    val personalHome = starterHome.copy(
        recentTracks = previewSongs.take(8).map { com.auralis.music.domain.model.HistoryEntry(it) },
        quickPicks = previewSongs.drop(8).take(9),
        dailyDiscover = previewSongs.drop(17).map { com.auralis.music.domain.model.DailyDiscoverItem(previewSongs.first(), it) }
    )
    val homePreview: @Composable (Boolean) -> Unit = { personal ->
        com.auralis.music.windows.home.DesktopHomeScreen(
            if (personal) personalHome else starterHome.copy(quickPicks = previewSongs.drop(8).take(9)),
            onPlay = { _, _ -> }, onShortcut = {}, onAlbum = {}, onMenu = {}, onSearch = {}, onSurprise = {}, onChip = {}
        )
    }

    val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
        "song-context-menu" to {
            Box(androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp)) {
                com.auralis.music.windows.library.DesktopSongMenuContent(
                    listOf("Play next", "Add to queue", "Add to playlist", "Add to library", "Pin to Speed dial", "Download", "View artist", "View album", "Share").map {
                        com.auralis.music.ui.components.GlassMenuItem(it, androidx.compose.material.icons.Icons.Default.Add) {}
                    }, {})
            }
        },
        "search-glass" to {
            Box(androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp)) {
                com.auralis.music.windows.search.SearchDropdownSurface(true, previewSongs.firstOrNull()?.thumbnail,
                    androidx.compose.ui.Modifier.width(480.dp).height(460.dp)) {
                    Column(androidx.compose.ui.Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text("Search Auralis", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Text("Mockingbird", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                        Text("Mockingbird Eminem", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                        Text("Heat Waves", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        "now-playing-sidebar" to {
            com.auralis.music.windows.player.ArtworkAppBackground(null, com.auralis.music.ui.theme.ArtworkPalette(
                androidx.compose.ui.graphics.Color(0xFF7A3D92), androidx.compose.ui.graphics.Color(0xFF49315B), androidx.compose.ui.graphics.Color(0xFFAC5271))) {
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) { homePreview(true) }
                    com.auralis.music.windows.player.NowPlayingSidebarContent(sampleTrack, {}, androidx.compose.ui.Modifier.width(300.dp).fillMaxHeight(),
                        com.auralis.music.ui.theme.ArtworkPalette(androidx.compose.ui.graphics.Color(0xFF7A3D92), androidx.compose.ui.graphics.Color(0xFF49315B), androidx.compose.ui.graphics.Color(0xFFAC5271))) {
                        androidx.compose.material3.Text("The current lyric line\n\nThe next lyric line", modifier = androidx.compose.ui.Modifier.padding(16.dp),
                            color = androidx.compose.ui.graphics.Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                    }
                }
            }
        },
        "home" to { homePreview(true) },
        "home-wide" to { homePreview(true) },
        "home-narrow" to { homePreview(true) },
        "home-new" to { homePreview(false) },
        "titlebar" to { com.auralis.music.windows.WindowTitleBar(
            com.auralis.music.windows.TitleBarActions(true, false, {}, {}, {}, "Sign in", {}, {}, {}, {}),
            "Auralis", null, false, {}, {}, {}) },
        "history" to { com.auralis.music.windows.history.HistorySheet(
            listOf(com.auralis.music.domain.model.HistoryEntry(sampleTrack, System.currentTimeMillis())), isPlaying = false,
            onTrackClick = { _, _ -> }, onRemoveFromHistory = {}, onClearHistory = {}, onDismiss = {}) },
        "stats" to { com.auralis.music.windows.history.ListeningStatsPage(stats, null, false, {}) },
        "wrapped" to { com.auralis.music.windows.history.ListeningStatsPage(stats, null, true, {}) },
        "together" to { com.auralis.music.windows.together.ListenTogetherPage(
            androidx.compose.runtime.remember { com.auralis.music.windows.together.DesktopTogether(null,
                com.auralis.music.windows.together.FirestoreRest.fromResource(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())) }, {}) },
        "profile" to { DesktopProfile(stores, libraryVm, history, search, hasActiveTrack = false, onDismiss = {}) },
        "settings" to { DesktopSettings(stores, history, search, hasActiveTrack = false, onOpenProfile = {}, onDismiss = {}) },
        "appearance" to { com.auralis.music.windows.settings.DesktopAppearanceSettings(onDismiss = {}) },
        "audio" to { com.auralis.music.windows.settings.DesktopAudioSettings(onDismiss = {}) },
        "account" to { com.auralis.music.windows.profile.AccountPage(androidx.compose.runtime.remember { com.auralis.music.windows.profile.DesktopAccount(File(data, "account.bin"), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())) }, {}) },
        "content" to { ContentSettingsScreen(onDismiss = {}) },
        "privacy" to { PrivacySettingsScreen(onDismiss = {}) },
        "storage" to { com.auralis.music.windows.settings.DesktopStorageSettings(onDismiss = {}) },
        "ai-translation" to { AiLyricsTranslationScreen(onDismiss = {}) },
        "updater" to { UpdaterScreen(onDismiss = {}) },
        "about" to { AboutScreen(onDismiss = {}) },
        "discord" to { DiscordIntegrationScreen(onDismiss = {}) },
        "youtube-account" to { YouTubeAccountScreen(onDismiss = {}) },
    )
    for ((name, content) in screens) {
        if (only.isNotEmpty() && name !in only) continue
        val result = runCatching {
            val scene = ImageComposeScene(width = when (name) { "titlebar" -> 1280; "home-wide" -> 1440; "home-narrow" -> 660; else -> 1040 },
                height = when (name) { "titlebar" -> 36; "home-wide" -> 1000; else -> 760 }, density = Density(1f)) {
                com.auralis.music.ui.theme.AuralisDesktopTheme(platformActions = actions) {
                    CompositionLocalProvider(
                        com.auralis.music.data.datastore.LocalSettingsStores provides stores,
                        com.auralis.music.ui.settings.LocalSettingsPlatform provides platform,
                        com.auralis.music.ui.theme.LocalAppearanceSettingsStore provides stores.appearance
                    ) {
                        androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                            content()
                        }
                    }
                }
            }
            // Let effects, flows and enter animations settle over ~1.5 s of frames.
            var image = scene.render(0)
            for (frame in 1..90) {
                if (frame % 10 == 0) Thread.sleep(30)
                image = scene.render(frame * 16_666_667L)
            }
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name.png").writeBytes(png)
            scene.close()
        }
        println(if (result.isSuccess) "rendered $name" else "FAILED $name: ${result.exceptionOrNull()}")
        result.exceptionOrNull()?.printStackTrace()
    }
    if (messages.isNotEmpty()) println("messages: $messages")
    kotlin.system.exitProcess(0)
}
