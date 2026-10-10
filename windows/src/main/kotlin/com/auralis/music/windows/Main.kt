package com.auralis.music.windows

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.auralis.music.data.network.AlbumMetadataResolver
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.data.repository.KeyValueSearchHistoryStore
import com.auralis.music.data.repository.SearchRepositoryImpl
import com.auralis.music.util.Log
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "AuralisWindows"

fun main(args: Array<String>) {
    // The installed app has no console: its log goes to %APPDATA%\Auralisuralis.log.
    Log.logToFile(java.io.File(desktopAppDataDir(), "auralis.log"))
    // UI text: the phone's strings.xml files, in the system language.
    com.auralis.music.ui.i18n.DesktopStrings.install()
    Log.i(TAG, "Starting Auralis for Windows ${System.getProperty("jpackage.app-version") ?: "(dev)"}, args=${args.toList()}")
    // Same startup as the phone's: the YouTube session and the search lookup caches load from their
    // own files under %APPDATA%\Auralis (separate, so signing out never clears the lookup cache).
    val dataDir = desktopAppDataDir()
    YouTubeSession.init(PropertiesFileKeyValueStore(java.io.File(dataDir, "youtube-session.properties")))
    // The phone's settings files (DataStore), and content settings (language, region, proxy,
    // lyrics providers) applied to every request as on the phone.
    val stores = com.auralis.music.desktop.settings.DesktopDataStores(java.io.File(dataDir, "settings"))
    com.auralis.music.data.datastore.ContentSettingsStore.init(stores.content)
    com.auralis.music.data.network.ContentSettingsSource.current = { com.auralis.music.data.datastore.ContentSettingsStore.value }
    com.auralis.music.data.network.ContentSettingsSource.updates = com.auralis.music.data.datastore.ContentSettingsStore.current
    val searchLookups = PropertiesFileKeyValueStore(java.io.File(dataDir, "search-lookups.properties"))
    AlbumMetadataResolver.persistTo(searchLookups)
    InnerTubeClient.persistReleaseTypesTo(searchLookups)
    val searchRepository = SearchRepositoryImpl(
        InnerTubeClient(),
        SearchSuggestionsClient(),
        KeyValueSearchHistoryStore(PropertiesFileKeyValueStore(java.io.File(dataDir, "search-history.properties")))
    )

    // Audio through libVLC: the copy the installer bundles (resources "vlc" folder), else an
    // installed VLC. Without either, the app still runs and the bar explains what is missing.
    val bundledVlc = System.getProperty("compose.application.resources.dir")?.let { java.io.File(it, "vlc") }
    val playback = com.auralis.music.desktop.playback.VlcAudioPlayer.createOrNull(bundledVlc)?.let {
        com.auralis.music.desktop.playback.DesktopPlaybackController(it, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default))
    }
    com.auralis.music.desktop.playback.DesktopStreamResolver.warmUp()
    // Lyrics: the phone's provider race. AMLL keeps its song index in the cache folder, as on the phone.
    com.auralis.music.data.network.provider.AmllLyricsSource.indexCacheDir = java.io.File(dataDir, "cache").apply { mkdirs() }
    val lyrics = playback?.let {
        com.auralis.music.desktop.lyrics.DesktopLyricsController(
            it,
            com.auralis.music.desktop.lyrics.DesktopLyricsRepository(
                com.auralis.music.desktop.lyrics.LyricsDiskCache(java.io.File(dataDir, "lyrics"))
            ),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        )
    }

    // Library and listening history: JSON files with the phone's rules. A song counts as played
    // when it becomes the current song, as on the phone.
    val library = com.auralis.music.desktop.library.DesktopLibraryRepository(java.io.File(dataDir, "library.json"))
    val history = com.auralis.music.desktop.library.DesktopHistoryRepository(java.io.File(dataDir, "history.json"), library)
    if (playback != null) {
        val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        appScope.launch {
            playback.queue.map { it.current }.distinctUntilChangedBy { it?.id }.collect { track ->
                // Settings > Privacy > Pause listen history, as on the phone.
                if (track != null && !stores.privacy.settingsFlow.first().pauseListenHistory) {
                    history.addToHistory(track)
                    history.recordPlay(track)
                }
            }
        }
    }
    // Discord status through the Discord desktop app, with the phone's Discord settings. Nothing is
    // sent until the user connects on the Discord page and Rich Presence is on.
    val discord = com.auralis.music.desktop.discord.DesktopDiscordPresence(stores.discord, playback)
    val homePlatform = com.auralis.music.desktop.home.DesktopHomePlatform(java.io.File(dataDir, "home"))
    val libraryFlags = PropertiesFileKeyValueStore(java.io.File(dataDir, "library-flags.properties"))

    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("auralis_logo.png")
        ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }

    val selfTest = SelfTest.fromArgs(args, dataDir)
    var shortcutHandler: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null
    application {
        Window(
            onPreviewKeyEvent = { shortcutHandler?.invoke(it) ?: false },
            onCloseRequest = { playback?.release(); exitApplication() },
            title = "Auralis",
            icon = icon,
            state = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        ) {
            window.minimumSize = java.awt.Dimension(900, 600)
            // The song in Windows' media overlay, and the keyboard's media keys.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (playback != null) {
                    com.auralis.music.windows.media.bindMediaControls(window.windowHandle, playback, this)
                }
            }
            AuralisDesktopApp(
                searchRepository,
                library,
                history,
                homePlatform,
                libraryFlags,
                stores,
                playback,
                lyrics,
                onShortcutHandler = { shortcutHandler = it },
                selfTest = selfTest,
                onSelfTestDone = { passed ->
                    playback?.release()
                    kotlin.system.exitProcess(if (passed) 0 else 1)
                },
                discordControl = discord
            )
        }
    }
}
