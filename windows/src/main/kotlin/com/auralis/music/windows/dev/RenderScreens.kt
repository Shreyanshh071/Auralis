package com.auralis.music.windows.dev

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

    val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
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
            val scene = ImageComposeScene(width = 1040, height = 760, density = Density(1f)) {
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
