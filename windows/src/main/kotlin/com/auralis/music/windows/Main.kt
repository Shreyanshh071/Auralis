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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private const val TAG = "AuralisWindows"

private data class ReadyComponents(
    val searchRepository: SearchRepositoryImpl,
    val library: com.auralis.music.desktop.library.DesktopLibraryRepository,
    val history: com.auralis.music.desktop.library.DesktopHistoryRepository,
    val listeningStats: com.auralis.music.desktop.library.DesktopListeningStats,
    val playback: com.auralis.music.desktop.playback.DesktopPlaybackController?,
    val lyrics: com.auralis.music.desktop.lyrics.DesktopLyricsController?,
    val account: com.auralis.music.windows.profile.DesktopAccount,
    val libraryFlags: PropertiesFileKeyValueStore,
    val icon: BitmapPainter?
)

fun main(args: Array<String>) {
    val dev = System.getProperty("jpackage.app-version") == null
    if (System.getProperty("auralis.dataDir").isNullOrBlank() && (dev || args.contains("--self-test"))) {
        System.setProperty("auralis.dataDir", java.io.File(desktopAppDataDir().parentFile, if (dev) "Auralis Dev" else "Auralis Self Test").absolutePath)
    }
    val dataDir = desktopAppDataDir()
    val windowTitle = if (dev) "Auralis Dev" else "Auralis"
    // The installed app has no console: its log goes to %APPDATA%\Auralis\auralis.log.
    Log.logToFile(java.io.File(dataDir, "auralis.log"))
    val allowMultiple = args.contains("--allow-multiple") || args.contains("--self-test")
    var singleInstanceLock = if (!allowMultiple) SingleInstanceApp.tryAcquire(dataDir) else null
    if (!allowMultiple && singleInstanceLock == null) {
        Log.i(TAG, "Another instance of Auralis may be running. Attempting to focus existing window...")
        val focused = SingleInstanceApp.focusExistingWindow(dataDir, windowTitle)
        if (focused) {
            Log.i(TAG, "Existing window focused. Exiting secondary launcher.")
            kotlin.system.exitProcess(0)
        }
        // focusExistingWindow returned false: no window exists on screen!
        Log.w(TAG, "No active window found for running instance. Recovering from zombie process...")
        SingleInstanceApp.recoverZombieInstance(dataDir)
        singleInstanceLock = SingleInstanceApp.acquireWithRetry(dataDir, maxWaitMs = 1500L)
        if (singleInstanceLock == null) {
            Log.e(TAG, "Could not acquire single instance lock even after zombie recovery. Exiting.")
            kotlin.system.exitProcess(0)
        }
        Log.i(TAG, "Successfully acquired lock after zombie recovery. Launching Auralis window.")
    }

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        Log.e(TAG, "Fatal uncaught exception on thread '${thread.name}': ${throwable.message}", throwable)
        runCatching { singleInstanceLock?.close() }
        kotlin.system.exitProcess(1)
    }

    Log.i(TAG, "Starting Auralis for Windows ${System.getProperty("jpackage.app-version") ?: "(dev)"}, args=${args.toList()}")

    val initScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    val langJob = initScope.launch {
        com.auralis.music.windows.settings.DesktopAppLanguage.install()
    }
    val ytJob = initScope.launch {
        YouTubeSession.init(com.auralis.music.windows.profile.ProtectedYouTubeStore(java.io.File(dataDir, "youtube-session.properties")))
    }
    val spotifyJob = initScope.launch {
        val spotifyFile = com.auralis.music.windows.profile.ProtectedYouTubeStore(java.io.File(dataDir, "spotify-session.properties"))
        com.auralis.music.data.network.SpotifySession.init(object : com.auralis.music.data.network.SpotifySession.Store {
            override fun getString(key: String) = spotifyFile.getString(key, "") ?: ""
            override fun getLong(key: String) = spotifyFile.getString(key, "0")?.toLongOrNull() ?: 0L
            override fun put(values: Map<String, Any>) = spotifyFile.putStrings(values.mapValues { it.value.toString() })
            override fun clear() = spotifyFile.clear()
        })
    }

    val stores = com.auralis.music.desktop.settings.DesktopDataStores(java.io.File(dataDir, "settings"))
    com.auralis.music.windows.settings.DesktopCaches.install(512)
    com.auralis.music.data.datastore.ContentSettingsStore.init(stores.content)
    com.auralis.music.data.network.ContentSettingsSource.current = { com.auralis.music.data.datastore.ContentSettingsStore.value }
    com.auralis.music.data.network.ContentSettingsSource.updates = com.auralis.music.data.datastore.ContentSettingsStore.current
    com.auralis.music.data.network.ContentProxy.install()

    val searchLookupsDeferred = initScope.async {
        val store = PropertiesFileKeyValueStore(java.io.File(dataDir, "search-lookups.properties"))
        AlbumMetadataResolver.persistTo(store)
        InnerTubeClient.persistReleaseTypesTo(store)
        store
    }

    val searchRepositoryDeferred = initScope.async {
        SearchRepositoryImpl(
            InnerTubeClient(),
            SearchSuggestionsClient(),
            KeyValueSearchHistoryStore(PropertiesFileKeyValueStore(java.io.File(dataDir, "search-history.properties")))
        )
    }

    val bundledVlc = System.getProperty("compose.application.resources.dir")?.let { java.io.File(it, "vlc") }
    val playbackDeferred = initScope.async {
        val player = com.auralis.music.desktop.playback.VlcAudioPlayer.createOrNull(bundledVlc)
        player?.let {
            com.auralis.music.desktop.playback.DesktopPlaybackController(
                it,
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
                volumeStore = PropertiesFileKeyValueStore(java.io.File(dataDir, "playback-volume.properties"))
            )
        }
    }

    com.auralis.music.desktop.playback.DesktopStreamResolver.warmUp()
    com.auralis.music.data.network.provider.AmllLyricsSource.indexCacheDir = java.io.File(dataDir, "cache").apply { mkdirs() }

    val lyricsDeferred = initScope.async {
        val playback = playbackDeferred.await()
        playback?.let {
            com.auralis.music.desktop.lyrics.DesktopLyricsController(
                it,
                com.auralis.music.desktop.lyrics.DesktopLyricsRepository(
                    com.auralis.music.desktop.lyrics.LyricsDiskCache(java.io.File(dataDir, "lyrics"))
                ),
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            )
        }
    }

    val libraryDeferred = initScope.async {
        com.auralis.music.desktop.library.DesktopLibraryRepository(java.io.File(dataDir, "library.json"))
    }

    val historyDeferred = initScope.async {
        val lib = libraryDeferred.await()
        com.auralis.music.desktop.library.DesktopHistoryRepository(java.io.File(dataDir, "history.json"), lib)
    }

    val listeningStatsDeferred = initScope.async {
        com.auralis.music.desktop.library.DesktopListeningStats(java.io.File(dataDir, "listening-stats.json"))
    }

    val recorderScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val privacyState = stores.privacy.settingsFlow.stateIn(
        recorderScope,
        kotlinx.coroutines.flow.SharingStarted.Eagerly,
        com.auralis.music.domain.model.PrivacySettings()
    )
    val accountDeferred = initScope.async {
        com.auralis.music.windows.profile.DesktopAccount(java.io.File(dataDir, "account-session.bin"), recorderScope)
    }

    val libraryFlagsDeferred = initScope.async {
        PropertiesFileKeyValueStore(java.io.File(dataDir, "library-flags.properties"))
    }

    val iconDeferred = initScope.async {
        Thread.currentThread().contextClassLoader.getResourceAsStream("auralis_logo.png")
            ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
    }

    val (searchRepository, library, history, listeningStats, playback, lyrics, account, libraryFlags, icon) =
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            langJob.join()
            ytJob.join()
            spotifyJob.join()
            searchLookupsDeferred.await()
            val sRepo = searchRepositoryDeferred.await()
            val lib = libraryDeferred.await()
            val hist = historyDeferred.await()
            val stats = listeningStatsDeferred.await()
            val pb = playbackDeferred.await()
            val lyr = lyricsDeferred.await()
            val acc = accountDeferred.await()
            val flags = libraryFlagsDeferred.await()
            val ic = iconDeferred.await()
            ReadyComponents(sRepo, lib, hist, stats, pb, lyr, acc, flags, ic)
        }

    val radioRecommendations = com.auralis.music.windows.player.DesktopRadioRecommendations(history)
    playback?.radioCandidates = radioRecommendations::candidates

    // Restore last played song, queue, and artwork theme palette from previous session
    com.auralis.music.windows.player.DesktopPlaybackSessionStore.init(dataDir)
    val restoredSession = com.auralis.music.windows.player.DesktopPlaybackSessionStore.currentSession()
    if (restoredSession != null && playback != null) {
        playback.restoreQueue(restoredSession.queue.ifEmpty { listOf(restoredSession.track) }, restoredSession.queueIndex)
    }

    if (playback != null) recorderScope.launch {
        stores.settings.settingsFlow.collect { settings ->
            com.auralis.music.desktop.playback.DesktopStreamResolver.quality = settings.audioQuality
            playback.setRate(settings.playbackRate)
            playback.preloadNext = settings.gaplessPlayback
            playback.autoLoadMore = settings.autoLoadMore
        }
    }
    val together = com.auralis.music.windows.together.DesktopTogether(playback, com.auralis.music.windows.together.FirestoreRest.fromResource(), recorderScope)
    together.searchSongs = searchRepository::searchSongs
    val listenRecorder = playback?.let { com.auralis.music.windows.history.DesktopListenRecorder(it, listeningStats, privacyState, recorderScope) }
    if (playback != null) {
        val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        appScope.launch {
            playback.queue.collect { q ->
                val track = q.current
                if (track != null) {
                    com.auralis.music.windows.player.DesktopPlaybackSessionStore.save(
                        track = track,
                        queue = q.tracks,
                        queueIndex = q.index
                    )
                }
            }
        }
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
    val discord = com.auralis.music.desktop.discord.DesktopDiscordPresence(stores.discord, playback)
    val homePlatform = com.auralis.music.desktop.home.DesktopHomePlatform(java.io.File(dataDir, "home"), listeningStats)

    val selfTest = SelfTest.fromArgs(args, dataDir)
    var shortcutHandler: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null
    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        var composeWindow: java.awt.Window? = null
        val closeApp: () -> Unit = {
            runCatching { composeWindow?.isVisible = false }
            runCatching { singleInstanceLock?.close() }
            runCatching {
                val q = playback?.queue?.value
                if (q?.current != null) {
                    com.auralis.music.windows.player.DesktopPlaybackSessionStore.save(
                        track = q.current!!,
                        queue = q.tracks,
                        queueIndex = q.index
                    )
                }
            }
            Thread {
                try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                kotlin.system.exitProcess(0)
            }.apply {
                isDaemon = true
                name = "auralis-shutdown-watchdog"
            }.start()
            recorderScope.launch {
                try { kotlinx.coroutines.withTimeoutOrNull(1000) { together.close() } }
                finally {
                    runCatching { listenRecorder?.close() }
                    runCatching { playback?.release() }
                    runCatching { exitApplication() }
                    kotlin.system.exitProcess(0)
                }
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
                composeWindow = window
                window.minimumSize = java.awt.Dimension(900, 600)
                singleInstanceLock?.recordWindowHandle(window.windowHandle)
                val frame = runCatching { NativeWindowFrame(window, window.windowHandle) { maximizeHovered = it } }
                    .onFailure { Log.w(TAG, "Native window frame unavailable: ${it.javaClass.simpleName}") }.getOrNull()
                nativeFrame = frame
                val inputRestore = installWindowInputRestore(window)
                val taskbar = if (frame != null && playback != null) runCatching {
                    com.auralis.music.windows.media.TaskbarPlaybackControls(frame, window.windowHandle, playback::previous, playback::togglePlayPause, playback::next)
                }.onFailure { Log.w(TAG, "Taskbar controls unavailable: ${it.message}") }.getOrNull() else null
                taskbarControls = taskbar
                onDispose {
                    taskbarControls = null
                    composeWindow = null
                    taskbar?.close()
                    inputRestore.close()
                    frame?.close()
                }
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
                    singleInstanceLock?.close()
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
