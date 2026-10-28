package com.auralis.music.windows

import com.auralis.music.ui.i18n.desktopText

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
    val stores: com.auralis.music.desktop.settings.DesktopDataStores,
    val homePlatform: com.auralis.music.desktop.home.DesktopHomePlatform,
    val together: com.auralis.music.windows.together.DesktopTogether,
    val discord: com.auralis.music.windows.discord.WindowsDiscordPresence,
    val listenRecorder: com.auralis.music.windows.history.DesktopListenRecorder?,
    val recorderScope: kotlinx.coroutines.CoroutineScope,
    val connect: com.auralis.music.windows.connect.DesktopConnect
)

fun main(args: Array<String>) {
    val dev = System.getProperty("jpackage.app-version") == null
    if (System.getProperty("auralis.dataDir").isNullOrBlank() && (dev || args.contains("--self-test"))) {
        System.setProperty("auralis.dataDir", java.io.File(desktopAppDataDir().parentFile, if (dev) "Auralis Dev" else "Auralis Self Test").absolutePath)
    }
    val dataDir = desktopAppDataDir()
    kotlinx.coroutines.runBlocking { com.auralis.music.windows.settings.InstallerDefaults.install(dataDir) }
    val windowTitle = if (dev) "Auralis Dev" else "Auralis"
    // The installed app has no console: its log goes to %APPDATA%\Auralis\auralis.log.
    Log.logToFile(java.io.File(dataDir, "auralis.log"), append = true)
    // The installer's step keeping gaming overlays off Auralis, and the elevated copy it starts.
    if (args.contains(com.auralis.music.windows.media.GameOverlayOptOut.SETUP_ARGUMENT))
        kotlin.system.exitProcess(com.auralis.music.windows.media.GameOverlayOptOut.setupFromInstaller())
    if (args.contains(com.auralis.music.windows.media.GameOverlayOptOut.ARGUMENT))
        kotlin.system.exitProcess(com.auralis.music.windows.media.GameOverlayOptOut.applyElevated())
    val allowMultiple = args.contains("--allow-multiple") || args.contains("--self-test")
    var singleInstanceLock = if (!allowMultiple) SingleInstanceApp.tryAcquire(dataDir) else null
    if (!allowMultiple && singleInstanceLock == null) {
        Log.i(TAG, "Another instance of Auralis may be running. Attempting to focus existing window...")
        val focused = SingleInstanceApp.focusExistingWindow(dataDir, windowTitle, maxWaitMs = 1500L)
        if (focused) {
            Log.i(TAG, "Existing window focused. Exiting secondary launcher.")
            kotlin.system.exitProcess(0)
        }
        val recovered = SingleInstanceApp.recoverZombieInstance(dataDir)
        if (recovered) {
            singleInstanceLock = SingleInstanceApp.acquireWithRetry(dataDir, maxWaitMs = 1200L)
        }
        if (singleInstanceLock == null) {
            Log.i(TAG, "Instance is already launching or running. Exiting secondary launcher.")
            kotlin.system.exitProcess(0)
        }
        Log.i(TAG, "Successfully acquired lock after zombie recovery. Launching Auralis window.")
    }

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        Log.e(TAG, "Fatal uncaught exception on thread '${thread.name}': ${throwable.message}", throwable)
        runCatching { singleInstanceLock?.close() }
        kotlin.system.exitProcess(1)
    }

    val launchStarted = System.currentTimeMillis()
    com.auralis.music.windows.dev.StartupSampler.startIfRequested(dataDir)
    com.auralis.music.windows.dev.ClassArchive.maintainIfPackaged()
    Log.i(TAG, "Starting Auralis for Windows ${System.getProperty("jpackage.app-version") ?: "(dev)"}, args=${args.toList()}")

    // Load icon immediately (<2ms) so window can display with icon without waiting for repositories
    val icon = runCatching {
        Thread.currentThread().contextClassLoader.getResourceAsStream("auralis_logo.png")
            ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
    }.getOrNull()

    // Launch heavy I/O concurrently on background thread pool
    val initScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    val componentsDeferred = initScope.async {
        val langJob = launch {
            com.auralis.music.windows.settings.DesktopAppLanguage.install()
        }
        val ytJob = launch {
            YouTubeSession.init(com.auralis.music.windows.profile.ProtectedYouTubeStore(java.io.File(dataDir, "youtube-session.properties")))
        }
        val spotifyJob = launch {
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

        val searchLookupsDeferred = async {
            val store = PropertiesFileKeyValueStore(java.io.File(dataDir, "search-lookups.properties"))
            AlbumMetadataResolver.persistTo(store)
            InnerTubeClient.persistReleaseTypesTo(store)
            store
        }

        val searchRepositoryDeferred = async {
            SearchRepositoryImpl(
                InnerTubeClient(),
                SearchSuggestionsClient(),
                KeyValueSearchHistoryStore(PropertiesFileKeyValueStore(java.io.File(dataDir, "search-history.properties")))
            )
        }

        val bundledVlc = System.getProperty("compose.application.resources.dir")?.let { java.io.File(it, "vlc") }
        val playbackDeferred = async {
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

        val lyricsDeferred = async {
            val playback = playbackDeferred.await()
            playback?.let {
                com.auralis.music.desktop.lyrics.DesktopLyricsController(
                    it,
                    com.auralis.music.desktop.lyrics.DesktopLyricsRepository(
                        com.auralis.music.desktop.lyrics.LyricsDiskCache(java.io.File(dataDir, "lyrics"))
                    ),
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
                    spicyMode = stores.appearance.settingsFlow.map {
                        com.auralis.music.domain.model.LyricsAnimationMode.fromDisplayName(it.lyricsAnimation) ==
                            com.auralis.music.domain.model.LyricsAnimationMode.SPICY_LYRICS
                    }
                )
            }
        }

        val libraryDeferred = async {
            com.auralis.music.desktop.library.DesktopLibraryRepository(java.io.File(dataDir, "library.json"))
        }

        val historyDeferred = async {
            val lib = libraryDeferred.await()
            com.auralis.music.desktop.library.DesktopHistoryRepository(java.io.File(dataDir, "history.json"), lib)
        }

        val listeningStatsDeferred = async {
            com.auralis.music.desktop.library.DesktopListeningStats(java.io.File(dataDir, "listening-stats.json"))
        }

        val recorderScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val privacyState = stores.privacy.settingsFlow.stateIn(
            recorderScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            com.auralis.music.domain.model.PrivacySettings()
        )
        val accountDeferred = async {
            com.auralis.music.windows.profile.DesktopAccount(java.io.File(dataDir, "account-session.bin"), recorderScope)
        }

        val libraryFlagsDeferred = async {
            PropertiesFileKeyValueStore(java.io.File(dataDir, "library-flags.properties"))
        }

        // Which startup step the window waited on, for the launch timing line below.
        val waits = StringBuilder()
        suspend fun <T> step(name: String, block: suspend () -> T): T = block().also {
            waits.append(" $name=${System.currentTimeMillis() - launchStarted}")
        }
        step("language") { langJob.join() }
        step("youtube") { ytJob.join() }
        step("spotify") { spotifyJob.join() }
        step("lookups") { searchLookupsDeferred.await() }
        val sRepo = step("search") { searchRepositoryDeferred.await() }
        val lib = step("library") { libraryDeferred.await() }
        val hist = step("history") { historyDeferred.await() }
        val stats = step("stats") { listeningStatsDeferred.await() }
        val pb = step("playback") { playbackDeferred.await() }
        val lyr = step("lyrics") { lyricsDeferred.await() }
        val acc = step("account") { accountDeferred.await() }
        val flags = step("flags") { libraryFlagsDeferred.await() }

        val radioRecommendations = com.auralis.music.windows.player.DesktopRadioRecommendations(hist)
        pb?.radioCandidates = radioRecommendations::candidates

        // Restore last played song, queue, and artwork theme palette from previous session
        com.auralis.music.windows.player.DesktopPlaybackSessionStore.init(dataDir)
        val restoredSession = com.auralis.music.windows.player.DesktopPlaybackSessionStore.currentSession()
        if (restoredSession != null && pb != null) {
            pb.restoreQueue(restoredSession.queue.ifEmpty { listOf(restoredSession.track) }, restoredSession.queueIndex)
        }

        if (pb != null) recorderScope.launch {
            stores.settings.settingsFlow.collect { settings ->
                com.auralis.music.desktop.playback.DesktopStreamResolver.quality = settings.audioQuality
                pb.setRate(settings.playbackRate)
                pb.preloadNext = settings.gaplessPlayback
                pb.autoLoadMore = settings.autoLoadMore
            }
        }
        val together = com.auralis.music.windows.together.DesktopTogether(pb, com.auralis.music.windows.together.FirestoreRest.fromResource(), recorderScope)
        together.searchSongs = sRepo::searchSongs
        val listenRecorder = pb?.let { com.auralis.music.windows.history.DesktopListenRecorder(it, stats, privacyState, recorderScope) }
        if (pb != null) {
            val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            appScope.launch {
                pb.queue.collect { q ->
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
                kotlinx.coroutines.flow.combine(pb.queue, pb.state, pb.session) { q, state, session ->
                    if (state == com.auralis.music.desktop.playback.VlcAudioPlayer.State.PLAYING) session to q.current else null
                }.filterNotNull().distinctUntilChangedBy { it.first }.collect { (_, track) ->
                    if (track != null && !stores.privacy.settingsFlow.first().pauseListenHistory) {
                        hist.addToHistory(track)
                        hist.recordPlay(track)
                    }
                }
            }
        }
        val discord = com.auralis.music.windows.discord.WindowsDiscordPresence(pb, stores.discord, sRepo)
        val homePlatform = com.auralis.music.desktop.home.DesktopHomePlatform(java.io.File(dataDir, "home"), stats)

        Log.i(TAG, "Startup components ready ${System.currentTimeMillis() - launchStarted}ms after launch;$waits")
        // Auralis Connect: this PC as one device of the signed-in account's listening session.
        val connect = com.auralis.music.windows.connect.DesktopConnect(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main),
            acc, pb, dataDir
        ) { com.auralis.music.windows.connect.DesktopConnect.windowShown }
        connect.start()
        ReadyComponents(sRepo, lib, hist, stats, pb, lyr, acc, flags, stores, homePlatform, together, discord, listenRecorder, recorderScope, connect)
    }

    val selfTest = SelfTest.fromArgs(args, dataDir)
    var shortcutHandler: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null

    CaptureFrameEcho.installForAllWindows()
    application {
        val systemSettings by com.auralis.music.windows.settings.DesktopSystemPreferences.store.settings.collectAsState()
        val nativeTitlebar = com.auralis.music.windows.settings.DesktopPreferences.store.bool("systemTitlebar")
        val launchMinimized = remember { "--autostart" in args && com.auralis.music.windows.settings.DesktopPreferences.store.bool("startMinimized") }
        var windowVisible by remember { mutableStateOf(true) }
        var systemTray by remember { mutableStateOf<com.auralis.music.windows.media.DesktopSystemTray?>(null) }
        val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        var playerFullscreen by remember { mutableStateOf(false) }
        var nativeFrame by remember { mutableStateOf<NativeWindowFrame?>(null) }
        var previousPlacement by remember { mutableStateOf(androidx.compose.ui.window.WindowPlacement.Floating) }
        fun setPlayerFullscreen(enabled: Boolean) {
            if (playerFullscreen == enabled) return
            if (enabled) previousPlacement = windowState.placement
            // Remove native work-area constraints before Compose requests the
            // monitor bounds; doing this in a later effect can leave a border.
            nativeFrame?.fullscreen = enabled
            playerFullscreen = enabled
            windowState.placement = if (enabled) androidx.compose.ui.window.WindowPlacement.Fullscreen else previousPlacement
        }
        LaunchedEffect(Unit) { if (launchMinimized) windowState.isMinimized = true }
        // Connect polls less while nobody is looking at the window.
        LaunchedEffect(windowVisible, windowState.isMinimized) {
            com.auralis.music.windows.connect.DesktopConnect.windowShown = windowVisible && !windowState.isMinimized
        }
        var composeWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        var components by remember { mutableStateOf<ReadyComponents?>(null) }
        // Shown again from the tray, the window stays cloaked until it has drawn (see setWindowCloaked).
        var revealPending by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            components = componentsDeferred.await()
        }

        val openWindow: () -> Unit = {
            windowState.isMinimized = false
            (composeWindow as? androidx.compose.ui.awt.ComposeWindow)?.takeIf { !it.isVisible }?.let { win ->
                val handle = win.windowHandle
                if (setWindowCloaked(handle, true)) {
                    revealPending = true
                    // Never left invisible, even if no frame comes.
                    javax.swing.Timer(600) { setWindowCloaked(handle, false) }.apply { isRepeats = false; start() }
                }
            }
            windowVisible = true
            composeWindow?.let { win ->
                win.isVisible = true
                win.toFront()
                win.requestFocus()
            }
        }

        DisposableEffect(components) {
            val comp = components
            val hotkeys = if (comp != null) com.auralis.music.windows.settings.DesktopGlobalHotkeys(comp.playback, openWindow) else null
            val scrobbler = if (comp != null) com.auralis.music.windows.settings.DesktopScrobbler(comp.playback, comp.recorderScope) else null
            val translator = comp?.lyrics?.let { com.auralis.music.windows.settings.DesktopAiLyrics(it, comp.stores.aiTranslation, comp.recorderScope) }
            onDispose { hotkeys?.close(); scrobbler?.close(); translator?.close() }
        }
        LaunchedEffect(components) {
            val pb = components?.playback ?: return@LaunchedEffect
            com.auralis.music.windows.settings.DesktopPreferences.store.changes.collect {
                val prefs = com.auralis.music.windows.settings.DesktopPreferences.store
                pb.setNormalizeVolume(prefs.bool("normalize"))
                pb.crossfadeSeconds = prefs.number("crossfadeSeconds",0.0).toFloat()
                com.auralis.music.desktop.playback.DesktopStreamResolver.enabledClients = prefs.text("streamClients","VISIONOS").split(',').filter {it in setOf("VISIONOS","IOS")}.toSet()
                com.auralis.music.desktop.lyrics.DesktopLyricsRepository.preferredProviders = prefs.text("lyricsPriority").split(',').mapNotNull {name->runCatching {com.auralis.music.domain.model.LyricsProvider.valueOf(name)}.getOrNull()}
                pb.preventDuplicates = prefs.bool("preventDuplicates",true)

                pb.shuffleWholeQueue = prefs.bool("shuffleWholeQueue")
            }
        }

        val closeApp: () -> Unit = {
            runCatching { systemTray?.close() }
            runCatching { composeWindow?.isVisible = false }
            runCatching { singleInstanceLock?.close() }
            val comp = components
            if (comp != null) {
                runCatching {
                    val q = comp.playback?.queue?.value
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
                comp.recorderScope.launch {
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(1100) {
                            // Give up the Connect output (paused) so other devices don't wait on this PC.
                            val leaving = launch { comp.connect.close() }
                            comp.together.close()
                            leaving.join()
                        }
                    }
                    finally {
                        runCatching { comp.listenRecorder?.close() }
                        runCatching { comp.playback?.release() }
                        runCatching { comp.discord.close() }
                        runCatching { exitApplication() }
                        kotlin.system.exitProcess(0)
                    }
                }
            } else {
                exitApplication()
                kotlin.system.exitProcess(0)
            }
        }
        val requestClose: () -> Unit = {
            if (com.auralis.music.windows.media.shouldCloseToTray(systemSettings.closeToTray, systemTray?.available == true)) {
                windowVisible = false
            } else closeApp()
        }

        // Install system tray at application scope so it stays present even when the window is hidden/minimized to tray
        DisposableEffect(systemSettings.closeToTray || systemSettings.songNotifications) {
            val tray = if (systemSettings.closeToTray || systemSettings.songNotifications)
                com.auralis.music.windows.media.DesktopSystemTray(openWindow, closeApp).also { it.install() } else null
            systemTray = tray
            onDispose { tray?.close(); if (systemTray === tray) systemTray = null }
        }

        // Continuously poll for restore requests at application scope so double-clicking the shortcut
        // or clicking the app icon restores the window even when minimized to the tray
        LaunchedEffect(Unit) {
            val restoreRequest = java.io.File(dataDir, "window-restore.request")
            while (true) {
                if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { restoreRequest.exists() && restoreRequest.delete() }) {
                    Log.i(TAG, "Restore request detected; restoring application window.")
                    openWindow()
                }
                kotlinx.coroutines.delay(100)
            }
        }

        val notificationSettings = rememberUpdatedState(systemSettings)
        LaunchedEffect(components?.playback) {
            val pb = components?.playback
            if (pb != null) {
                val tracker = com.auralis.music.windows.media.SongNotificationTracker(null)
                kotlinx.coroutines.flow.combine(pb.queue, pb.mediaState) { q, media ->
                    q.current to (media.state == com.auralis.music.desktop.playback.VlcAudioPlayer.State.PLAYING && media.trackId == q.current?.id)
                }.collect { (track, playing) ->
                    // Queue emissions can arrive before the old player's PLAYING event clears.
                    val actuallyPlaying = playing && pb.state.value == com.auralis.music.desktop.playback.VlcAudioPlayer.State.PLAYING &&
                        pb.queue.value.current?.id == track?.id
                    val isHidden = com.auralis.music.windows.media.isApplicationInBackground(composeWindow) || windowState.isMinimized || !windowVisible
                    tracker.started(track, actuallyPlaying, notificationSettings.value.songNotifications, isHidden)?.let {
                        com.auralis.music.windows.media.WindowsSongNotifications.show(it)
                    }
                }
            }
        }

        Window(
            onPreviewKeyEvent = {
                if (it.key == androidx.compose.ui.input.key.Key.F11 && it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                    setPlayerFullscreen(!playerFullscreen); true
                } else shortcutHandler?.invoke(it) ?: false
            },
            onCloseRequest = requestClose,
            visible = windowVisible,
            undecorated = true,
            title = if (dev) "Auralis Dev" else "Auralis",
            icon = icon,
            state = windowState
        ) {
            LaunchedEffect(playerFullscreen, nativeFrame) { nativeFrame?.fullscreen = playerFullscreen }
            var taskbarControls by remember { mutableStateOf<com.auralis.music.windows.media.TaskbarPlaybackControls?>(null) }
            var captionHover by remember { mutableStateOf(CaptionHover()) }
            DisposableEffect(window) {
                composeWindow = window
                // Every icon size Windows asks for (taskbar, Alt+Tab, Task Manager): one 256px
                // image shrunk by Windows was an unrecognisable smudge at 16-32px.
                appIconImages()?.let { window.iconImages = it }
                // Before Compose paints its first frame the window shows its native background:
                // make that the loading frame's colour rather than whatever was behind it.
                java.awt.Color(0x0F, 0x0E, 0x13).let { window.background = it; window.contentPane.background = it }
                window.minimumSize = java.awt.Dimension(900, 600)
                singleInstanceLock?.recordWindowHandle(window.windowHandle)
                val frame = runCatching { NativeWindowFrame(window, window.windowHandle) { captionHover = it }.also { it.onClose = requestClose } }
                    .onFailure { Log.w(TAG, "Native window frame unavailable: ${it.javaClass.simpleName}") }.getOrNull()
                nativeFrame = frame
                val inputRestore = installWindowInputRestore(window)
                java.awt.EventQueue.invokeLater {
                    Log.i(TAG, "Rendering with ${runCatching { window.renderApi }.getOrNull()}")
                    com.auralis.music.windows.dev.installRenderTiming(window, dataDir)
                }
                window.addComponentListener(object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent) {
                        com.auralis.music.windows.dev.StartupSampler.sampleResizeIfRequested(dataDir)
                        com.auralis.music.windows.dev.GpuExperiment.runIfRequested(dataDir, window)
                    }
                    override fun componentShown(e: java.awt.event.ComponentEvent) =
                        com.auralis.music.windows.dev.StartupSampler.windowEventIfRequested(dataDir, "shown")
                    override fun componentHidden(e: java.awt.event.ComponentEvent) =
                        com.auralis.music.windows.dev.StartupSampler.windowEventIfRequested(dataDir, "hidden")
                })
                window.addWindowStateListener { e ->
                    val iconified = java.awt.Frame.ICONIFIED
                    if (e.newState and iconified != 0 && e.oldState and iconified == 0)
                        com.auralis.music.windows.dev.StartupSampler.windowEventIfRequested(dataDir, "minimised")
                    else if (e.oldState and iconified != 0 && e.newState and iconified == 0)
                        com.auralis.music.windows.dev.StartupSampler.windowEventIfRequested(dataDir, "restored")
                }
                onDispose {
                    taskbarControls = null
                    composeWindow = null
                    inputRestore.close()
                    frame?.close()
                }
            }
            LaunchedEffect(revealPending) {
                if (!revealPending) return@LaunchedEffect
                // The first frame has been swapped onto the window by the time the second begins.
                kotlinx.coroutines.withTimeoutOrNull(400) { repeat(2) { withFrameNanos { } } }
                setWindowCloaked(window.windowHandle, false)
                revealPending = false
            }
            DisposableEffect(nativeFrame, nativeTitlebar) {
                nativeFrame?.systemDecorated = nativeTitlebar
                onDispose { }
            }
            LaunchedEffect(nativeFrame, components?.playback) {
                val frame = nativeFrame
                val pb = components?.playback
                if (frame != null && pb != null && taskbarControls == null) {
                    val taskbar = runCatching {
                        com.auralis.music.windows.media.TaskbarPlaybackControls(frame, window.windowHandle, pb::previous, pb::togglePlayPause, pb::next)
                    }.onFailure { Log.w(TAG, "Taskbar controls unavailable: ${it.message}") }.getOrNull()
                    taskbarControls = taskbar
                }
            }
            DisposableEffect(taskbarControls) {
                val controls = taskbarControls
                onDispose { controls?.close() }
            }
            LaunchedEffect(taskbarControls, components?.playback) {
                val controls = taskbarControls
                val pb = components?.playback
                if (controls != null && pb != null) {
                    kotlinx.coroutines.flow.combine(pb.queue, pb.state) { queue, state ->
                        Triple(queue.current != null, state == com.auralis.music.desktop.playback.VlcAudioPlayer.State.PLAYING, queue.hasNext)
                    }.collect { (hasTrack, playing, next) -> controls.update(hasTrack, playing, next) }
                }
            }
            LaunchedEffect(components?.stores) {
                val st = components?.stores
                if (st != null) {
                    st.privacy.settingsFlow.collect { privacy ->
                        runCatching {
                            val affinity = com.sun.jna.NativeLibrary.getInstance("user32").getFunction("SetWindowDisplayAffinity")
                            check(affinity.invokeInt(arrayOf(com.sun.jna.Pointer(window.windowHandle), if (privacy.disableScreenshot) 0x11 else 0)) != 0)
                        }.onFailure { Log.w(TAG, "Windows couldn't apply screenshot privacy: ${it.javaClass.simpleName}") }
                    }
                }
            }
            window.minimumSize = java.awt.Dimension(900, 600)
            LaunchedEffect(components?.playback) {
                val pb = components?.playback
                if (pb != null) {
                    com.auralis.music.windows.media.bindMediaControls(window.windowHandle, pb, this)
                }
            }
            val ready = components
            // The app's first frames are slow (cold code, images, shaders): a fade over them stuttered
            // as a half-transparent ghost. It draws them under an opaque cover below its title bar and
            // is revealed once frames come smoothly, so the switch is one clean step.
            var appSettled by remember { mutableStateOf(false) }
            LaunchedEffect(ready != null) {
                if (ready == null) return@LaunchedEffect
                val first = withFrameNanos { it }
                Log.i(TAG, "UI shown ${System.currentTimeMillis() - launchStarted}ms after launch")
                var last = first
                var smooth = 0
                while (smooth < 4 && last - first < 2_000_000_000L) {
                    val now = withFrameNanos { it }
                    smooth = if (now - last < 40_000_000L) smooth + 1 else 0
                    last = now
                }
                Log.i(TAG, "UI settled ${System.currentTimeMillis() - launchStarted}ms after launch")
                appSettled = true
            }
            val coverAlpha by androidx.compose.animation.core.animateFloatAsState(if (appSettled) 0f else 1f,
                androidx.compose.animation.core.tween(160), label = "startupCover")
            if (ready != null) Box(Modifier.fillMaxSize().background(Color(0xFF0F0E13))
                .layout { measurable, constraints ->
                    val started = System.nanoTime()
                    val placeable = measurable.measure(constraints)
                    com.auralis.music.windows.dev.FrameLog.laidOut(System.nanoTime() - started)
                    layout(placeable.width, placeable.height) {
                        val placing = System.nanoTime()
                        placeable.place(0, 0)
                        com.auralis.music.windows.dev.FrameLog.laidOut(System.nanoTime() - placing)
                    }
                }
                .drawWithContent {
                    com.auralis.music.windows.dev.FrameLog.drawn(size.width.toInt(), size.height.toInt())
                    drawContent()
                    com.auralis.music.windows.dev.FrameLog.drawFinished()
                }) {
                Box(Modifier.fillMaxSize()) {
                AuralisDesktopApp(
                    searchRepository = ready.searchRepository,
                    library = ready.library,
                    history = ready.history,
                    homePlatform = ready.homePlatform,
                    libraryFlags = ready.libraryFlags,
                    stores = ready.stores,
                    playback = ready.playback,
                    lyrics = ready.lyrics,
                    onShortcutHandler = { shortcutHandler = it },
                    selfTest = selfTest,
                    onSelfTestDone = { passed ->
                        singleInstanceLock?.close()
                        ready.listenRecorder?.close()
                        ready.playback?.release()
                        kotlin.system.exitProcess(if (passed) 0 else 1)
                    },
                    discordControl = ready.discord,
                    listeningStats = ready.listeningStats,
                    together = ready.together,
                    account = ready.account,
                    playerFullscreen = playerFullscreen,
                    onPlayerFullscreen = ::setPlayerFullscreen,
                    titleBar = { actions ->
                        if (!playerFullscreen) {
                        val chrome: @Composable () -> Unit = {
                            WindowTitleBar(actions, if (dev) "Auralis Dev" else "Auralis", icon,
                                systemDecorated = nativeTitlebar && nativeFrame != null,
                                maximized = windowState.placement == androidx.compose.ui.window.WindowPlacement.Maximized,
                                minimize = { nativeFrame?.minimize() ?: run { windowState.isMinimized = true } },
                                maximize = {
                                    nativeFrame?.toggleMaximize() ?: run {
                                        windowState.placement = if (windowState.placement == androidx.compose.ui.window.WindowPlacement.Maximized)
                                            androidx.compose.ui.window.WindowPlacement.Floating else androidx.compose.ui.window.WindowPlacement.Maximized
                                    }
                                }, close = requestClose,
                                captionBounds = { nativeFrame?.captionBounds = it },
                                brandBounds = { nativeFrame?.brandBounds = it },
                                maximizeBounds = { nativeFrame?.maximizeBounds = it },
                                minimizeBounds = { nativeFrame?.minimizeBounds = it }, closeBounds = { nativeFrame?.closeBounds = it },
                                searchBounds = { nativeFrame?.searchBounds = it },
                                captionHover = captionHover)
                        }
                        if (nativeFrame == null) WindowDraggableArea { chrome() } else chrome()
                        }
                    }
                )
                }
                if (coverAlpha > 0f) Box(Modifier.fillMaxSize().padding(top = TITLE_BAR_HEIGHT)
                    .graphicsLayer { alpha = coverAlpha }.background(Color(0xFF0F0E13))
                    // Clicks don't reach controls that aren't visible yet.
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
            } else {
                val placeholderChrome: @Composable () -> Unit = {
                    WindowTitleBar(
                        systemDecorated = nativeTitlebar && nativeFrame != null,
                        actions = TitleBarActions(),
                        title = if (dev) "Auralis Dev" else "Auralis",
                        icon = icon,
                        maximized = windowState.placement == androidx.compose.ui.window.WindowPlacement.Maximized,
                        minimize = { nativeFrame?.minimize() ?: run { windowState.isMinimized = true } },
                        maximize = {
                            nativeFrame?.toggleMaximize() ?: run {
                                windowState.placement = if (windowState.placement == androidx.compose.ui.window.WindowPlacement.Maximized)
                                    androidx.compose.ui.window.WindowPlacement.Floating else androidx.compose.ui.window.WindowPlacement.Maximized
                            }
                        },
                        close = requestClose,
                        captionBounds = { nativeFrame?.captionBounds = it },
                        brandBounds = { nativeFrame?.brandBounds = it },
                        maximizeBounds = { nativeFrame?.maximizeBounds = it },
                        minimizeBounds = { nativeFrame?.minimizeBounds = it }, closeBounds = { nativeFrame?.closeBounds = it },
                        searchBounds = { nativeFrame?.searchBounds = it },
                        captionHover = captionHover
                    )
                }
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Color(0xFF0F0E13))
                ) {
                    // Only the chrome while components load: no centred logo flash before the real UI.
                    if (nativeFrame == null) WindowDraggableArea { placeholderChrome() } else placeholderChrome()
                }
            }
        }
    }
}

/** The app tile drawn for each small size (bolder knot, less padding), then the full artwork. */
private fun appIconImages(): List<java.awt.Image>? = runCatching {
    val loader = Thread.currentThread().contextClassLoader
    (listOf(16, 20, 24, 32, 40, 48, 64).map { "app-icon/tile_$it.png" } + "auralis_logo.png")
        .map { name -> loader.getResourceAsStream(name)!!.use(ImageIO::read) }
}.onFailure { Log.w(TAG, "App icon sizes unavailable: ${it.message}") }.getOrNull()
