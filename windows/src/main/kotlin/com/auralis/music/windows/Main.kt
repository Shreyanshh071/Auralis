package com.auralis.music.windows

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val TAG = "AuralisWindows"

fun main(args: Array<String>) {
    val dev = System.getProperty("jpackage.app-version") == null
    if (System.getProperty("auralis.dataDir").isNullOrBlank() && (dev || args.contains("--self-test"))) {
        System.setProperty("auralis.dataDir", java.io.File(desktopAppDataDir().parentFile, if (dev) "Auralis Dev" else "Auralis Self Test").absolutePath)
    }
    // The installed app has no console: its log goes to %APPDATA%\Auralis\auralis.log.
    Log.logToFile(java.io.File(desktopAppDataDir(), "auralis.log"))
    // UI text: the phone's strings.xml files, in the system language.
    com.auralis.music.windows.settings.DesktopAppLanguage.install()
    Log.i(TAG, "Starting Auralis for Windows ${System.getProperty("jpackage.app-version") ?: "(dev)"}, args=${args.toList()}")
    // Same startup as the phone's: the YouTube session and the search lookup caches load from their
    // own files under %APPDATA%\Auralis (separate, so signing out never clears the lookup cache).
    val dataDir = desktopAppDataDir()
    YouTubeSession.init(com.auralis.music.windows.profile.ProtectedYouTubeStore(java.io.File(dataDir, "youtube-session.properties")))
    // Spotify's session (sp_dc cookie, protected by the Windows user like YouTube's).
    val spotifyFile = com.auralis.music.windows.profile.ProtectedYouTubeStore(java.io.File(dataDir, "spotify-session.properties"))
    com.auralis.music.data.network.SpotifySession.init(object : com.auralis.music.data.network.SpotifySession.Store {
        override fun getString(key: String) = spotifyFile.getString(key, "") ?: ""
        override fun getLong(key: String) = spotifyFile.getString(key, "0")?.toLongOrNull() ?: 0L
        override fun put(values: Map<String, Any>) = spotifyFile.putStrings(values.mapValues { it.value.toString() })
        override fun clear() = spotifyFile.clear()
    })
    // The phone's settings files (DataStore), and content settings (language, region, proxy,
    // lyrics providers) applied to every request as on the phone.
    val stores = com.auralis.music.desktop.settings.DesktopDataStores(java.io.File(dataDir, "settings"))
    com.auralis.music.windows.settings.DesktopCaches.install(kotlinx.coroutines.runBlocking { stores.storage.settingsFlow.first().maxImageCacheSizeMb })
    com.auralis.music.data.datastore.ContentSettingsStore.init(stores.content)
    com.auralis.music.data.network.ContentSettingsSource.current = { com.auralis.music.data.datastore.ContentSettingsStore.value }
    com.auralis.music.data.network.ContentSettingsSource.updates = com.auralis.music.data.datastore.ContentSettingsStore.current
    com.auralis.music.data.network.ContentProxy.install()
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
    // once audio starts, excluding failed stream lookups.
    val library = com.auralis.music.desktop.library.DesktopLibraryRepository(java.io.File(dataDir, "library.json"))
    val history = com.auralis.music.desktop.library.DesktopHistoryRepository(java.io.File(dataDir, "history.json"), library)
    val listeningStats = com.auralis.music.desktop.library.DesktopListeningStats(java.io.File(dataDir, "listening-stats.json"))
    val recorderScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val privacyState = stores.privacy.settingsFlow.stateIn(recorderScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, kotlinx.coroutines.runBlocking { stores.privacy.settingsFlow.first() })
    val account = com.auralis.music.windows.profile.DesktopAccount(java.io.File(dataDir, "account-session.bin"), recorderScope)
    if (playback != null) recorderScope.launch {
        stores.settings.settingsFlow.collect { settings ->
            com.auralis.music.desktop.playback.DesktopStreamResolver.quality = settings.audioQuality
            playback.setRate(settings.playbackRate)
            playback.preloadNext = settings.gaplessPlayback
        }
    }
    val together = com.auralis.music.windows.together.DesktopTogether(playback, com.auralis.music.windows.together.FirestoreRest.fromResource(), recorderScope)
    val listenRecorder = playback?.let { com.auralis.music.windows.history.DesktopListenRecorder(it, listeningStats, privacyState, recorderScope) }
    if (playback != null) {
        val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        appScope.launch {
            kotlinx.coroutines.flow.combine(playback.queue, playback.state, playback.session) { q, state, session ->
                if (state == com.auralis.music.desktop.playback.VlcAudioPlayer.State.PLAYING) session to q.current else null
            }.filterNotNull().distinctUntilChangedBy { it.first }.collect { (_, track) ->
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
    val homePlatform = com.auralis.music.desktop.home.DesktopHomePlatform(java.io.File(dataDir, "home"), listeningStats)
    val libraryFlags = PropertiesFileKeyValueStore(java.io.File(dataDir, "library-flags.properties"))

    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("auralis_logo.png")
        ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }

    val selfTest = SelfTest.fromArgs(args, dataDir)
    var shortcutHandler: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null
    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        val closeApp: () -> Unit = {
            recorderScope.launch {
                try { kotlinx.coroutines.withTimeoutOrNull(15_000) { together.close() } }
                finally { listenRecorder?.close(); playback?.release(); exitApplication() }
            }
        }
        Window(
            onPreviewKeyEvent = { shortcutHandler?.invoke(it) ?: false },
            onCloseRequest = closeApp,
            undecorated = true,
            title = if (dev) "Auralis Dev" else "Auralis",
            icon = icon,
            state = windowState
        ) {
            var nativeFrame by remember { mutableStateOf<NativeWindowFrame?>(null) }
            var taskbarControls by remember { mutableStateOf<com.auralis.music.windows.media.TaskbarPlaybackControls?>(null) }
            var maximizeHovered by remember { mutableStateOf(false) }
            DisposableEffect(window) {
                window.minimumSize = java.awt.Dimension(900, 600)
                val frame = runCatching { NativeWindowFrame(window, window.windowHandle) { maximizeHovered = it } }
                    .onFailure { Log.w(TAG, "Native window frame unavailable: ${it.javaClass.simpleName}") }.getOrNull()
                nativeFrame = frame
                val inputRestore = installWindowInputRestore(window)
                val taskbar = if (frame != null && playback != null) runCatching {
                    com.auralis.music.windows.media.TaskbarPlaybackControls(frame, window.windowHandle, playback::previous, playback::togglePlayPause, playback::next)
                }.onFailure { Log.w(TAG, "Taskbar controls unavailable: ${it.message}") }.getOrNull() else null
                taskbarControls = taskbar
                onDispose { taskbarControls = null; taskbar?.close(); inputRestore.close(); frame?.close() }
            }
            LaunchedEffect(taskbarControls) {
                val controls = taskbarControls
                if (controls != null && playback != null) {
                    kotlinx.coroutines.flow.combine(playback.queue, playback.state) { queue, state ->
                        Triple(queue.current != null, state == com.auralis.music.desktop.playback.VlcAudioPlayer.State.PLAYING, queue.hasNext)
                    }.collect { (hasTrack, playing, next) -> controls.update(hasTrack, playing, next) }
                }
            }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                stores.privacy.settingsFlow.collect { privacy ->
                    runCatching {
                        val affinity = com.sun.jna.NativeLibrary.getInstance("user32").getFunction("SetWindowDisplayAffinity")
                        check(affinity.invokeInt(arrayOf(com.sun.jna.Pointer(window.windowHandle), if (privacy.disableScreenshot) 0x11 else 0)) != 0)
                    }.onFailure { Log.w(TAG, "Windows couldn't apply screenshot privacy: ${it.javaClass.simpleName}") }
                }
            }
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
                    listenRecorder?.close()
                    playback?.release()
                    kotlin.system.exitProcess(if (passed) 0 else 1)
                },
                discordControl = discord,
                listeningStats = listeningStats,
                together = together,
                account = account,
                titleBar = { actions ->
                    val chrome: @Composable () -> Unit = {
                        WindowTitleBar(actions, if (dev) "Auralis Dev" else "Auralis", icon,
                            maximized = windowState.placement == androidx.compose.ui.window.WindowPlacement.Maximized,
                            minimize = { nativeFrame?.minimize() ?: run { windowState.isMinimized = true } },
                            maximize = {
                                nativeFrame?.toggleMaximize() ?: run {
                                    windowState.placement = if (windowState.placement == androidx.compose.ui.window.WindowPlacement.Maximized)
                                        androidx.compose.ui.window.WindowPlacement.Floating else androidx.compose.ui.window.WindowPlacement.Maximized
                                }
                            }, close = closeApp,
                            captionBounds = { nativeFrame?.captionBounds = it },
                            maximizeBounds = { nativeFrame?.maximizeBounds = it },
                            searchBounds = { nativeFrame?.searchBounds = it },
                            maximizeHovered = maximizeHovered)
                    }
                    if (nativeFrame == null) WindowDraggableArea { chrome() } else chrome()
                }
            )
        }
    }
}
