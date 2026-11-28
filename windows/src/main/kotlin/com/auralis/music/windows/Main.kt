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
import androidx.compose.ui.window.WindowPlacement
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private const val TAG = "AuralisWindows"
private val isMacPlatform = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

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
    awaitRestartParent(args)
    val syncPilot = System.getProperty("auralis.syncPilot") == "true"
    if (syncPilot) {
        require(System.getProperty("auralis.accountSyncUrl") == "https://auralis-connect-staging.auralis018.workers.dev") {
            "The internal sync pilot must use the staging account service."
        }
        val pilotLibraryUrl = System.getProperty("auralis.librarySyncUrl").orEmpty()
        require(pilotLibraryUrl.isBlank() || pilotLibraryUrl == "https://auralis-connect-staging.auralis018.workers.dev") {
            "The internal library pilot must use the same staging service."
        }
        val profile = System.getProperty("auralis.dataDir")?.let { java.io.File(it).canonicalFile }
        require(profile != null && profile.parentFile?.name == "sync-pilot") {
            "The sync pilot requires an isolated profile inside a sync-pilot directory."
        }
        System.setProperty("auralis.connectRealtimeUrl", "wss://auralis-connect-staging.auralis018.workers.dev/v1/connect")
        System.setProperty("auralis.togetherSyncUrl", "https://auralis-connect-staging.auralis018.workers.dev")
    }
    if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
        if (System.getProperty("skiko.renderApi").isNullOrBlank()) {
            System.setProperty("skiko.renderApi", "METAL")
        }
        System.setProperty("apple.awt.enableTemplateImages", "true")
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("io.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl", "true")
        System.setProperty("io.netty.handler.ssl.noOpenSsl", "true")
        System.setProperty("io.grpc.netty.shaded.io.netty.transport.noNative", "true")
        System.setProperty("io.netty.transport.noNative", "true")
    }
    if ("--verify-bundled-runtime" in args) {
        kotlin.system.exitProcess(com.auralis.music.windows.dev.verifyBundledRuntime())
    }
    val dev = System.getProperty("jpackage.app-version") == null
    if (System.getProperty("auralis.dataDir").isNullOrBlank() && (dev || args.contains("--self-test"))) {
        System.setProperty("auralis.dataDir", java.io.File(desktopAppDataDir().parentFile, if (dev) "Auralis Dev" else "Auralis Self Test").absolutePath)
    }
    val dataDir = desktopAppDataDir()
    val newProfile = dataDir.listFiles()?.isNotEmpty() != true
    kotlinx.coroutines.runBlocking { com.auralis.music.windows.settings.InstallerDefaults.install(dataDir) }
    val windowTitle = if (syncPilot) "Auralis Sync Pilot" else if (dev) "Auralis Dev" else "Auralis"
    // The installed app has no console: its log goes to %APPDATA%\Auralis\auralis.log.
    Log.logToFile(java.io.File(dataDir, "auralis.log"), append = true)
    com.auralis.music.data.network.YouTubeBackoff.stateFile = java.io.File(dataDir, "youtube-cooldown.properties")
    com.auralis.music.data.network.YouTubeTraffic.diagnosticsDir = java.io.File(dataDir, "diagnostics")
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

    com.auralis.music.windows.player.CanvasSettings.initializeIntro(newProfile)

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        Log.e(TAG, "Fatal uncaught exception on thread '${thread.name}': ${throwable.message}", throwable)
        runCatching { singleInstanceLock?.close() }
        ExitGuard.arm()
        kotlin.system.exitProcess(1)
    }

    val launchStarted = System.currentTimeMillis()
    val traceLayout = java.io.File(dataDir, "trace-frames").isFile || java.io.File(dataDir, "trace-resize").isFile
    com.auralis.music.windows.dev.FrameLog.enabled = traceLayout
    com.auralis.music.windows.dev.StartupSampler.startIfRequested(dataDir)
    com.auralis.music.windows.dev.ClassArchive.maintainIfPackaged()
    Log.i(TAG, "Starting Auralis for ${if (isMacPlatform) "macOS" else "Windows"} ${System.getProperty("jpackage.app-version") ?: "(dev)"}, args=${args.toList()}")

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

        val artistCredits = PropertiesFileKeyValueStore(java.io.File(dataDir, "artist-credits.properties"))
        com.auralis.music.domain.stats.ArtistCredits.persistTo(artistCredits)
        com.auralis.music.data.network.OfficialArtistResolver.persistTo(artistCredits)

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
        com.auralis.music.windows.profile.installSignedInPlayback(java.io.File(dataDir, "cache/zemer-cipher"))
        com.auralis.music.data.network.provider.AmllLyricsSource.indexCacheDir = java.io.File(dataDir, "cache").apply { mkdirs() }

        val libraryDeferred = async {
            com.auralis.music.data.network.YouTubeShortsFilter.classificationStore =
                PropertiesFileKeyValueStore(java.io.File(dataDir, "youtube-video-classifications.properties"))
            com.auralis.music.desktop.library.DesktopLibraryRepository(java.io.File(dataDir, "library.json"))
        }

        val lyricsDeferred = async {
            com.auralis.music.data.local.LyricsProviderChoices.init(java.io.File(dataDir, "lyrics-provider-choices"))
            val playback = playbackDeferred.await()
            val lyricsLibrary = libraryDeferred.await()
            playback?.let {
                com.auralis.music.desktop.lyrics.DesktopLyricsController(
                    it,
                    com.auralis.music.desktop.lyrics.DesktopLyricsRepository(
                        com.auralis.music.desktop.lyrics.LyricsDiskCache(java.io.File(dataDir, "lyrics")),
                        knownTracks = { lyricsLibrary.tracksForLyrics() }
                    ),
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
                    spicyMode = stores.appearance.settingsFlow.map {
                        com.auralis.music.domain.model.LyricsAnimationMode.fromDisplayName(it.lyricsAnimation) ==
                            com.auralis.music.domain.model.LyricsAnimationMode.SPICY_LYRICS
                    }
                )
            }
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
        // Shuffle is one device-wide switch (like Spotify): it comes back on after a restart,
        // with the order turning it off returns to.
        val desktopPrefs = com.auralis.music.windows.settings.DesktopPreferences.store
        if (pb != null && desktopPrefs.bool("shuffleMode")) {
            val pool = restoredSession?.queue.orEmpty().toMutableList()
            val sourceOrder = desktopPrefs.text("shuffleSourceOrder").split(',').filter { it.isNotBlank() }
                .mapNotNull { id -> pool.firstOrNull { it.id == id }?.also { pool.remove(it) } }
            pb.restoreShuffleMode(true, if (sourceOrder.isEmpty()) emptyList() else sourceOrder + pool)
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
                kotlinx.coroutines.flow.combine(pb.shuffleEnabled, pb.queue) { on, _ ->
                    on to if (on) pb.shuffleSourceOrder.joinToString(",") { it.id } else ""
                }.distinctUntilChanged().collect { (on, order) ->
                    if (desktopPrefs.bool("shuffleMode") != on) desktopPrefs.set("shuffleMode", on)
                    if (desktopPrefs.text("shuffleSourceOrder") != order) desktopPrefs.set("shuffleSourceOrder", order)
                }
            }
            appScope.launch {
                com.auralis.music.windows.history.establishedHistoryPlays(
                    pb.queue, pb.state, pb.session, pb.establishedTrackId
                ).collect { (_, track) ->
                    if (!stores.privacy.settingsFlow.first().pauseListenHistory) {
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
        val isMac = remember { System.getProperty("os.name").orEmpty().lowercase().contains("mac") }
        val macChromeInstalled = remember(isMac) { if (isMac) MacWindowChrome.installIfMac() else false }
        val systemSettings by com.auralis.music.windows.settings.DesktopSystemPreferences.store.settings.collectAsState()
        val nativeTitlebar = com.auralis.music.windows.settings.DesktopPreferences.store.bool("systemTitlebar")
        val launchMinimized = remember { "--autostart" in args && com.auralis.music.windows.settings.DesktopPreferences.store.bool("startMinimized") }
        var windowVisible by remember { mutableStateOf(true) }
        var settingsRequestTrigger by remember { mutableStateOf(0) }
        var systemTray by remember { mutableStateOf<com.auralis.music.windows.media.DesktopSystemTray?>(null) }
        val initialRawWindowState = remember { WindowStatePreferences.loadRaw() }
        val savedWindowState = remember {
            resolveRestoredWindowState(
                initialRawWindowState,
                currentUsableScreenBounds(),
                minWidth = minWindowWidth(isMac),
                minHeight = minWindowHeight(isMac),
            )
        }
        // Windows: a window saved maximized is created at the full work-area size instead of its restored
        // size. It used to be created at the restored size and maximized only when shown, so the whole
        // app was composed and laid out at 1307x726 first (~1.9s, trace-frames C1230 L685, 2026-11-27)
        // and then again at full size. Its restored bounds are put back after it maximizes.
        val startMaximizedOn = remember {
            if (isMac || savedWindowState.placement != WindowPlacement.Maximized) null
            else currentUsableScreenBounds().let { screens ->
                val pos = savedWindowState.position as? androidx.compose.ui.window.WindowPosition.Absolute
                val cx = pos?.let { it.x.value + savedWindowState.size.width.value / 2f }
                val cy = pos?.let { it.y.value + savedWindowState.size.height.value / 2f }
                screens.firstOrNull { s -> cx != null && cy != null && cx in s.x..(s.x + s.width) && cy in s.y..(s.y + s.height) }
                    ?: screens.firstOrNull()
            }
        }
        val windowState = rememberWindowState(
            placement = if (isMac && savedWindowState.placement == WindowPlacement.Fullscreen) {
                WindowPlacement.Floating
            } else {
                savedWindowState.placement
            },
            size = startMaximizedOn?.let { androidx.compose.ui.unit.DpSize(it.width.dp, it.height.dp) } ?: savedWindowState.size,
            position = startMaximizedOn?.let { androidx.compose.ui.window.WindowPosition.Absolute(it.x.dp, it.y.dp) } ?: savedWindowState.position
        )
        var persistedWindowState by remember {
            mutableStateOf(
                initialRawWindowState.copy(
                    width = savedWindowState.size.width.value,
                    height = savedWindowState.size.height.value,
                    x = (savedWindowState.position as? androidx.compose.ui.window.WindowPosition.Absolute)?.x?.value
                        ?: initialRawWindowState.x,
                    y = (savedWindowState.position as? androidx.compose.ui.window.WindowPosition.Absolute)?.y?.value
                        ?: initialRawWindowState.y
                )
            )
        }
        var restoringInitialFullscreen by remember {
            mutableStateOf(isMac && savedWindowState.placement == WindowPlacement.Fullscreen && !launchMinimized)
        }
        var playerFullscreen by remember { mutableStateOf(false) }
        var nativeFrame by remember { mutableStateOf<NativeWindowFrame?>(null) }
        var previousGeometry by remember { mutableStateOf<WindowGeometry?>(null) }
        var macPreviousPlacement by remember { mutableStateOf<WindowPlacement?>(null) }
        var macImmersivePreviousPlacement by remember { mutableStateOf<WindowPlacement?>(null) }
        var composeWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        val immersive = remember { com.auralis.music.windows.immersive.ImmersiveSession() }
        var lockScreenPreview by remember {
            mutableStateOf(isMac && com.auralis.music.windows.immersive.isLockScreenPreviewRequested(args))
        }
        val embeddedDisplay = remember(immersive.trigger) {
            com.auralis.music.windows.immersive.connectedDisplays(null).singleOrNull()
        }
        val embeddedImmersive = immersive.trigger != null && embeddedDisplay != null
        // The window's key filter can outlive a recomposition; read the live value at key time.
        val embeddedImmersiveNow by rememberUpdatedState(embeddedImmersive)
        var immersiveGeometry by remember { mutableStateOf<WindowGeometry?>(null) }
        val embeddedCoverActive = embeddedImmersive || immersiveGeometry != null
        val retainedAppConstraints = remember { java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.unit.Constraints>() }
        val mainWindowShown = windowVisible && immersive.trigger == null
        var playerRequests by remember { mutableStateOf(0) }
        val computeLiveWindowState: (Boolean) -> PersistedWindowState = { commitFloatingBounds ->
            val awtWindow = composeWindow
            val livePlacement = (awtWindow as? androidx.compose.ui.awt.ComposeWindow)?.placement ?: windowState.placement
            val liveFloating = awtWindow != null && awtWindow.isVisible && livePlacement == WindowPlacement.Floating
            nextPersistedWindowState(
                current = persistedWindowState,
                placement = livePlacement,
                size = if (liveFloating) DpSize(awtWindow.width.dp, awtWindow.height.dp) else windowState.size,
                position = if (liveFloating) {
                    androidx.compose.ui.window.WindowPosition.Absolute(awtWindow.x.dp, awtWindow.y.dp)
                } else {
                    windowState.position
                },
                isMinimized = windowState.isMinimized,
                visible = windowVisible,
                playerFullscreen = playerFullscreen,
                embeddedImmersive = embeddedImmersive,
                isMac = isMac,
                macPrePlayerPlacement = macPreviousPlacement ?: macImmersivePreviousPlacement,
                restoringInitialFullscreen = restoringInitialFullscreen,
                commitFloatingBounds = commitFloatingBounds
            )
        }
        val flushWindowState: () -> Unit = {
            val latest = computeLiveWindowState(true)
            persistedWindowState = latest
            runCatching { WindowStatePreferences.save(latest) }
        }
        LaunchedEffect(
            windowState.placement,
            windowState.size,
            windowState.position,
            windowState.isMinimized,
            windowVisible,
            playerFullscreen,
            embeddedImmersive,
            restoringInitialFullscreen
        ) {
            if (!windowVisible || windowState.isMinimized || restoringInitialFullscreen) return@LaunchedEffect
            val placementChanged = windowState.placement != persistedWindowState.placement
            if (windowState.placement != WindowPlacement.Floating) {
                val immediate = computeLiveWindowState(false)
                if (immediate != persistedWindowState) {
                    persistedWindowState = immediate
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { WindowStatePreferences.save(immediate) }
                    }
                }
                return@LaunchedEffect
            }
            if (placementChanged) {
                val quick = computeLiveWindowState(false)
                if (quick != persistedWindowState) {
                    persistedWindowState = quick
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { WindowStatePreferences.save(quick) }
                    }
                }
            }
            kotlinx.coroutines.delay(450)
            val settled = computeLiveWindowState(true)
            if (settled != persistedWindowState) {
                persistedWindowState = settled
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { WindowStatePreferences.save(settled) }
                }
            }
        }
        fun setPlayerFullscreen(enabled: Boolean) {
            if (playerFullscreen == enabled) return
            nativeFrame?.fullscreen = enabled
            playerFullscreen = enabled
            if (isMac) {
                macPreviousPlacement = applyMacPlayerFullscreen(
                    enabled = enabled,
                    currentPlacement = windowState.placement,
                    savedPlacement = macPreviousPlacement,
                    onSetPlacement = { windowState.placement = it }
                )
                return
            }
            if (enabled) {
                val bounds = composeWindow?.graphicsConfiguration?.bounds
                    ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                previousGeometry = windowState.enterBorderlessFullscreen(bounds)
            } else {
                previousGeometry?.restore(windowState)
                previousGeometry = null
            }
        }
        LaunchedEffect(Unit) { if (launchMinimized) windowState.isMinimized = true }
        // Connect polls less while nobody is looking at the window.
        LaunchedEffect(mainWindowShown, windowState.isMinimized) {
            com.auralis.music.windows.connect.DesktopConnect.windowShown = mainWindowShown && !windowState.isMinimized
        }
        // In the tray or minimized for a while (not immersive mode, which draws), memory goes back to Windows.
        LaunchedEffect(windowVisible, windowState.isMinimized, immersive.trigger == null) {
            if ((windowVisible && !windowState.isMinimized) || immersive.trigger != null) return@LaunchedEffect
            kotlinx.coroutines.delay(15_000)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.auralis.music.windows.media.WorkingSetTrim.trim() }
        }
        // Open but behind other windows for a minute (the usual way music plays), memory goes back too:
        // 2026-11-28 the visible, unfocused app sat at 1071 MB with a 148 MB heap. Once per stretch
        // in the background; what it still uses comes straight back from memory.
        LaunchedEffect(Unit) {
            var inactiveSince = 0L
            var trimmedThisStretch = false
            while (true) {
                kotlinx.coroutines.delay(10_000)
                val active = composeWindow?.isActive == true
                val now = System.currentTimeMillis()
                if (active) { inactiveSince = 0L; trimmedThisStretch = false; continue }
                if (inactiveSince == 0L) inactiveSince = now
                if (!trimmedThisStretch && windowVisible && !windowState.isMinimized && immersive.trigger == null &&
                    now - inactiveSince >= 60_000L) {
                    trimmedThisStretch = true
                    // No full GC here: the window is still on screen (lyrics may be animating).
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.auralis.music.windows.media.WorkingSetTrim.trim(collect = false) }
                }
            }
        }
        var components by remember { mutableStateOf<ReadyComponents?>(null) }
        // Shown again from the tray, the window stays cloaked until it has drawn (see setWindowCloaked).
        var revealPending by remember { mutableStateOf(false) }
        // The window opens cloaked and appears once the app draws smoothly at its final size. Shown at
        // once, it laid out at the restored width inside the maximized window for ~1.5s, then jumped,
        // and its title bar (above the startup cover) filled in piece by piece: logo, search, account
        // name, avatar (screen recording 2026-11-27).
        var startupCloaked by remember { mutableStateOf(!isMac && !launchMinimized) }

        LaunchedEffect(Unit) {
            components = componentsDeferred.await()
        }

        val openWindow: () -> Unit = {
            immersive.close()
            if (isMac && java.awt.Desktop.isDesktopSupported()) {
                val desktop = java.awt.Desktop.getDesktop()
                if (desktop.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) {
                    runCatching { desktop.requestForeground(true) }
                }
            }
            windowState.isMinimized = false
            (composeWindow as? java.awt.Frame)?.let { frame ->
                if (frame.extendedState and java.awt.Frame.ICONIFIED != 0) {
                    frame.extendedState = frame.extendedState and java.awt.Frame.ICONIFIED.inv()
                }
            }
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
        // A link in immersive mode (album or artist) opens its page in the main window.
        LaunchedEffect(immersive.browseRequest) {
            if (immersive.browseRequest != null) openWindow()
        }

        DisposableEffect(components) {
            val comp = components
            val hotkeys = if (comp != null) com.auralis.music.windows.settings.DesktopGlobalHotkeys(comp.playback, openWindow) else null
            onDispose { hotkeys?.close() }
        }
        LaunchedEffect(components) {
            val pb = components?.playback ?: return@LaunchedEffect
            com.auralis.music.windows.settings.DesktopPreferences.store.changes.collect {
                val prefs = com.auralis.music.windows.settings.DesktopPreferences.store
                pb.setNormalizeVolume(prefs.bool("normalize"))
                pb.crossfadeSeconds = prefs.number("crossfadeSeconds",0.0).toFloat()
                // Stream clients are no longer a setting: always the extractor's default (VISIONOS).
                com.auralis.music.desktop.playback.DesktopStreamResolver.enabledClients = setOf("VISIONOS")
                com.auralis.music.desktop.lyrics.DesktopLyricsRepository.preferredProviders = prefs.text("lyricsPriority").split(',').mapNotNull {name->runCatching {com.auralis.music.domain.model.LyricsProvider.valueOf(name)}.getOrNull()}
                pb.preventDuplicates = prefs.bool("preventDuplicates",true)

                pb.shuffleWholeQueue = prefs.bool("shuffleWholeQueue")
            }
        }

        val closeApp: () -> Unit = {
            ExitGuard.arm()
            runCatching { flushWindowState() }
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
                        runCatching { if (isMac) com.auralis.music.windows.media.MacPlaybackActivity.stopPlaybackActivity() }
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
            if (isMac || com.auralis.music.windows.media.shouldCloseToTray(systemSettings.closeToTray, systemTray?.available == true)) {
                runCatching { flushWindowState() }
                windowVisible = false
            } else closeApp()
        }
        val restartApp: () -> Unit = {
            runCatching { launchDesktopRestart(args) }
                .onSuccess { closeApp() }
                .onFailure {
                    Log.e(TAG, "Could not restart Auralis", it)
                    javax.swing.JOptionPane.showMessageDialog(composeWindow,
                        "Auralis could not restart. Please try again or reopen the app.",
                        "Restart Auralis", javax.swing.JOptionPane.ERROR_MESSAGE)
                }
        }
        // "Restart to update" closes through the same orderly shutdown; its helper reopens Auralis.
        SideEffect { com.auralis.music.windows.update.DesktopUpdater.closeApp = closeApp }
        // Update checks start once the app is up (off the UI thread, after a short delay of their own).
        LaunchedEffect(components != null) {
            if (components != null && selfTest == null && !syncPilot) com.auralis.music.windows.update.DesktopUpdater.start()
        }

        // macOS Dock click (reopen) and Application Menu (Preferences, About, Quit) handling
        DisposableEffect(Unit) {
            val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
            val reopenListener = if (isMac && desktop != null && desktop.isSupported(java.awt.Desktop.Action.APP_EVENT_REOPENED)) {
                java.awt.desktop.AppReopenedListener {
                    java.awt.EventQueue.invokeLater {
                        openWindow()
                    }
                }.also { desktop.addAppEventListener(it) }
            } else null

            val prefHandler = if (isMac && desktop != null && desktop.isSupported(java.awt.Desktop.Action.APP_PREFERENCES)) {
                java.awt.desktop.PreferencesHandler {
                    java.awt.EventQueue.invokeLater {
                        openWindow()
                        settingsRequestTrigger++
                    }
                }.also { desktop.setPreferencesHandler(it) }
            } else null

            val aboutHandler = if (isMac && desktop != null && desktop.isSupported(java.awt.Desktop.Action.APP_ABOUT)) {
                java.awt.desktop.AboutHandler {
                    java.awt.EventQueue.invokeLater {
                        openWindow()
                        settingsRequestTrigger++
                    }
                }.also { desktop.setAboutHandler(it) }
            } else null

            val quitHandler = if (isMac && desktop != null && desktop.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) {
                java.awt.desktop.QuitHandler { _, _ ->
                    java.awt.EventQueue.invokeLater {
                        closeApp()
                    }
                }.also { desktop.setQuitHandler(it) }
            } else null

            onDispose {
                reopenListener?.let { runCatching { desktop?.removeAppEventListener(it) } }
                prefHandler?.let { runCatching { desktop?.setPreferencesHandler(null) } }
                aboutHandler?.let { runCatching { desktop?.setAboutHandler(null) } }
                quitHandler?.let { runCatching { desktop?.setQuitHandler(null) } }
            }
        }

        // Install system tray at application scope so it stays present even when the window is hidden/minimized to tray
        DisposableEffect(systemSettings.closeToTray || systemSettings.songNotifications) {
            val tray = if (systemSettings.closeToTray || systemSettings.songNotifications)
                com.auralis.music.windows.media.DesktopSystemTray(openWindow, closeApp, onRestart = restartApp).also { it.install() } else null
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
                        com.auralis.music.windows.media.DesktopSongNotifications.show(it)
                    }
                }
            }
        }

        Window(
            onPreviewKeyEvent = {
                if (embeddedImmersiveNow) false
                else if (it.key == androidx.compose.ui.input.key.Key.F11 && it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                    setPlayerFullscreen(!playerFullscreen); true
                } else shortcutHandler?.invoke(it) ?: false
            },
            onCloseRequest = requestClose,
            visible = windowVisible,
            undecorated = !isMac,
            title = windowTitle,
            icon = icon,
            state = windowState
        ) {
            // Cloaking preserves the Compose scene while the screensaver owns the display.
            LaunchedEffect(immersive.trigger, windowVisible, embeddedImmersive, startupCloaked) {
                val cloak = startupCloaked || (immersive.trigger != null && !embeddedImmersive)
                val ok = setWindowCloaked(window.windowHandle, cloak)
                if (immersive.trigger == null) Log.i(TAG, "Window cloak=$cloak ok=$ok showing=${window.isShowing} ${System.currentTimeMillis() - launchStarted}ms after launch")
            }
            // Never left invisible: a very slow cold start still shows the window after 8s.
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(8_000)
                startupCloaked = false
            }
            // Created at full size (see startMaximizedOn): once Windows has maximized it, its restore
            // bounds go back to the saved restored size and position.
            LaunchedEffect(window) {
                val screen = startMaximizedOn ?: return@LaunchedEffect
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    while (!(window.isShowing && window.extendedState and java.awt.Frame.MAXIMIZED_BOTH == java.awt.Frame.MAXIMIZED_BOTH)) withFrameNanos { }
                    val size = savedWindowState.size
                    val pos = savedWindowState.position as? androidx.compose.ui.window.WindowPosition.Absolute
                    val x = pos?.x?.value ?: (screen.x + (screen.width - size.width.value) / 2f)
                    val y = pos?.y?.value ?: (screen.y + (screen.height - size.height.value) / 2f)
                    setRestoreBounds(window, screen.x, screen.y, x, y, size.width.value, size.height.value)
                }
            }
            // Revealed, the whole app (title bar too) eases in from slightly small and transparent.
            // It starts only once frames come smoothly (see appSettled), so the motion itself is smooth.
            val openReveal = remember { androidx.compose.animation.core.Animatable(if (startupCloaked) 0f else 1f) }
            LaunchedEffect(startupCloaked) {
                if (startupCloaked || openReveal.value >= 1f) return@LaunchedEffect
                withFrameNanos { } // the uncloaked window's first frame shows the start of the motion
                openReveal.animateTo(1f, androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
            LaunchedEffect(embeddedImmersive) {
                if (embeddedImmersive) {
                    nativeFrame?.fullscreen = true
                    if (isMac) {
                        macImmersivePreviousPlacement = applyMacPlayerFullscreen(
                            enabled = true,
                            currentPlacement = windowState.placement,
                            savedPlacement = macImmersivePreviousPlacement,
                            onSetPlacement = { windowState.placement = it }
                        )
                    } else {
                        immersiveGeometry = windowState.enterBorderlessFullscreen(window.graphicsConfiguration.bounds)
                    }
                } else {
                    nativeFrame?.fullscreen = playerFullscreen
                    if (isMac) {
                        if (!playerFullscreen) {
                            macImmersivePreviousPlacement = applyMacPlayerFullscreen(
                                enabled = false,
                                currentPlacement = windowState.placement,
                                savedPlacement = macImmersivePreviousPlacement,
                                onSetPlacement = { windowState.placement = it }
                            )
                        } else {
                            macImmersivePreviousPlacement = null
                        }
                    } else immersiveGeometry?.let {
                        it.restore(windowState)
                        // Keep the screensaver over the resize until the restored viewport is presented.
                        repeat(2) { withFrameNanos { } }
                        immersiveGeometry = null
                        window.toFront()
                        window.requestFocus()
                    }
                }
            }
            LaunchedEffect(playerFullscreen, nativeFrame, embeddedImmersive, windowState.placement) {
                nativeFrame?.fullscreen = playerFullscreen ||
                    embeddedImmersive ||
                    windowState.placement == WindowPlacement.Fullscreen
            }
            LaunchedEffect(window) {
                if (restoringInitialFullscreen) {
                    androidx.compose.runtime.withFrameNanos { }
                    androidx.compose.runtime.withFrameNanos { }
                    if (restoringInitialFullscreen) {
                        windowState.placement = WindowPlacement.Fullscreen
                    }
                    kotlinx.coroutines.withTimeoutOrNull(3000L) {
                        androidx.compose.runtime.snapshotFlow { windowState.placement }
                            .first { it == WindowPlacement.Fullscreen }
                    }
                    restoringInitialFullscreen = false
                }
            }
            var taskbarControls by remember { mutableStateOf<com.auralis.music.windows.media.TaskbarPlaybackControls?>(null) }
            var captionHover by remember { mutableStateOf(CaptionHover()) }
            val macWindowFrame = remember(window, isMac, macChromeInstalled) {
                if (isMac && macChromeInstalled) MacWindowChrome.attachWindow(window) else null
            }
            DisposableEffect(macWindowFrame) {
                onDispose { macWindowFrame?.close() }
            }
            LaunchedEffect(macWindowFrame, playerFullscreen, embeddedCoverActive) {
                val active = !playerFullscreen && !embeddedCoverActive
                macWindowFrame?.titleBarEnabled = active
                if (!active) macWindowFrame?.clearBounds()
            }
            @Composable
            fun ChromeTitleBar(actions: TitleBarActions) {
                val density = androidx.compose.ui.platform.LocalDensity.current.density
                WindowTitleBar(
                    actions = actions,
                    title = windowTitle,
                    icon = icon,
                    systemDecorated = nativeTitlebar && nativeFrame != null,
                    maximized = windowState.placement == androidx.compose.ui.window.WindowPlacement.Maximized,
                    minimize = { nativeFrame?.minimize() ?: run { windowState.isMinimized = true } },
                    maximize = {
                        nativeFrame?.toggleMaximize() ?: run {
                            windowState.placement = if (windowState.placement == androidx.compose.ui.window.WindowPlacement.Maximized)
                                androidx.compose.ui.window.WindowPlacement.Floating else androidx.compose.ui.window.WindowPlacement.Maximized
                        }
                    },
                    close = requestClose,
                    captionBounds = {
                        nativeFrame?.captionBounds = it
                        macWindowFrame?.setCaptionBounds(it, density)
                    },
                    brandBounds = {
                        nativeFrame?.brandBounds = it
                        macWindowFrame?.setBrandBounds(it, density)
                    },
                    trailingBounds = {
                        macWindowFrame?.setTrailingBounds(it, density)
                    },
                    maximizeBounds = { nativeFrame?.maximizeBounds = it },
                    minimizeBounds = { nativeFrame?.minimizeBounds = it },
                    closeBounds = { nativeFrame?.closeBounds = it },
                    searchBounds = {
                        nativeFrame?.searchBounds = it
                        macWindowFrame?.setSearchBounds(it, density)
                    },
                    captionHover = captionHover,
                    dragArea = { modifier, content ->
                        if (nativeFrame == null && macWindowFrame == null) {
                            WindowDraggableArea(modifier) { content() }
                        } else {
                            Box(modifier) { content() }
                        }
                    },
                )
            }
            DisposableEffect(window) {
                composeWindow = window
                if (isMac) {
                    val rootPane = (window as? androidx.compose.ui.awt.ComposeWindow)?.rootPane
                    rootPane?.putClientProperty("apple.awt.fullWindowContent", true)
                    rootPane?.putClientProperty("apple.awt.transparentTitleBar", true)
                    rootPane?.putClientProperty("apple.awt.windowTitleVisible", false)
                    rootPane?.putClientProperty("apple.awt.fullscreenable", true)
                    runCatching {
                        if (java.awt.Taskbar.isTaskbarSupported()) {
                            val taskbar = java.awt.Taskbar.getTaskbar()
                            if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                                (Thread.currentThread().contextClassLoader.getResourceAsStream("app-icon/auralis_squircle.png")
                                    ?: Thread.currentThread().contextClassLoader.getResourceAsStream("auralis_logo.png"))?.use {
                                    taskbar.iconImage = ImageIO.read(it)
                                }
                            }
                        }
                    }
                }
                // Every icon size Windows asks for (taskbar, Alt+Tab, Task Manager): one 256px
                // image shrunk by Windows was an unrecognisable smudge at 16-32px.
                appIconImages()?.let { window.iconImages = it }
                // Before Compose paints its first frame the window shows its native background:
                // make that the loading frame's colour rather than whatever was behind it.
                java.awt.Color(0x0F, 0x0E, 0x13).let { window.background = it; window.contentPane.background = it }
                window.minimumSize = java.awt.Dimension(minWindowWidth(isMac).toInt(), minWindowHeight(isMac).toInt())
                singleInstanceLock?.recordWindowHandle(window.windowHandle)
                val frame = if (!isMac) {
                    runCatching { NativeWindowFrame(window, window.windowHandle) { captionHover = it }.also {
                        it.onClose = requestClose
                        it.onCaptionPress = { com.auralis.music.windows.search.TitleBarPresses.count++ }
                    } }
                        .onFailure { Log.w(TAG, "Native window frame unavailable: ${it.javaClass.simpleName}") }.getOrNull()
                } else null
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
                    override fun componentShown(e: java.awt.event.ComponentEvent) {
                        Log.i(TAG, "Window shown (cloaked=${isWindowCloaked(window.windowHandle)}) ${System.currentTimeMillis() - launchStarted}ms after launch")
                        com.auralis.music.windows.dev.StartupSampler.windowEventIfRequested(dataDir, "shown")
                    }
                    override fun componentHidden(e: java.awt.event.ComponentEvent) =
                        com.auralis.music.windows.dev.StartupSampler.windowEventIfRequested(dataDir, "hidden")
                })
                // Coming back to Auralis looks for a release published since the last check.
                window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent) =
                        com.auralis.music.windows.update.DesktopUpdater.onWindowFocused()
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
                if (st != null && !isMac) {
                    st.privacy.settingsFlow.collect { privacy ->
                        runCatching {
                            val affinity = com.sun.jna.NativeLibrary.getInstance("user32").getFunction("SetWindowDisplayAffinity")
                            check(affinity.invokeInt(arrayOf(com.sun.jna.Pointer(window.windowHandle), if (privacy.disableScreenshot) 0x11 else 0)) != 0)
                        }.onFailure { Log.w(TAG, "Windows couldn't apply screenshot privacy: ${it.javaClass.simpleName}") }
                    }
                }
            }
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
                // Settled = every frame under 25ms for 350ms straight (3s ceiling). Four quick frames
                // weren't enough: late covers and data kept making 60-76ms frames right after, and the
                // reveal animation stuttered through them (recording + trace-frames, 2026-11-27).
                var last = first
                var smoothSince = first
                while (last - smoothSince < 350_000_000L && last - first < 3_000_000_000L) {
                    val now = withFrameNanos { it }
                    if (now - last >= 25_000_000L) smoothSince = now
                    last = now
                }
                Log.i(TAG, "UI settled ${System.currentTimeMillis() - launchStarted}ms after launch")
                appSettled = true
                startupCloaked = false
            }
            val coverAlpha = androidx.compose.animation.core.animateFloatAsState(if (appSettled) 0f else 1f,
                androidx.compose.animation.core.tween(160), label = "startupCover")
            val showStartupCover by remember(coverAlpha) {
                androidx.compose.runtime.derivedStateOf { coverAlpha.value > 0f }
            }
            if (ready != null) Box(Modifier.fillMaxSize().background(Color(0xFF0F0E13))
                .then(if (traceLayout) Modifier.layout { measurable, constraints ->
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
                } else Modifier)) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    // Read here (draw phase), so the reveal never recomposes the app.
                    // Scale only: a fading alpha made Skia composite the whole window offscreen each
                    // frame. The fade is a scrim drawn over the app instead (see below).
                    val t = openReveal.value
                    scaleX = 0.965f + 0.035f * t
                    scaleY = scaleX
                }.layout { measurable, constraints ->
                    val appConstraints = if (embeddedCoverActive) retainedAppConstraints.get() ?: constraints
                        else constraints.also { retainedAppConstraints.set(it) }
                    val content = measurable.measure(appConstraints)
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        if (!embeddedCoverActive) content.place(0, 0)
                    }
                }) {
                CompositionLocalProvider(
                    com.auralis.music.ui.player.LocalPlaybackVisualsActive provides
                        (windowVisible && !windowState.isMinimized && !embeddedCoverActive && immersive.trigger == null)
                ) {
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
                    isTextInputActive = { isWindowTextInputActive(composeWindow) },
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
                    onCloseWindow = requestClose,
                    onMinimizeWindow = { nativeFrame?.minimize() ?: run { windowState.isMinimized = true } },
                    openSettingsTrigger = settingsRequestTrigger,
                    // I toggles: if the key reaches the main window while immersive shows, it closes it.
                    onOpenImmersive = {
                        if (immersive.trigger != null) immersive.close()
                        else immersive.open(com.auralis.music.windows.immersive.ImmersiveTrigger.MANUAL)
                    },
                    onToggleLockScreenPreview = {
                        if (isMac) {
                            lockScreenPreview = if (lockScreenPreview) {
                                false
                            } else {
                                ready.playback?.queue?.value?.current != null
                            }
                        }
                    },
                    playerRequests = playerRequests,
                    immersiveBrowse = immersive.browseRequest,
                    onImmersiveBrowseHandled = { immersive.browseRequest = null },
                    titleBar = { actions ->
                        if (!playerFullscreen) {
                            ChromeTitleBar(actions)
                        }
                    }
                )
                }
                }
                // The opening fade: a scrim over the whole window (title bar too), read in the draw phase.
                androidx.compose.foundation.layout.Spacer(Modifier.fillMaxSize().drawBehind {
                    val a = 1f - openReveal.value
                    if (a > 0f) drawRect(Color(0xFF0F0E13), alpha = a)
                })
                if (showStartupCover) Box(Modifier.fillMaxSize().padding(top = TITLE_BAR_HEIGHT)
                    .graphicsLayer { alpha = coverAlpha.value }.background(Color(0xFF0F0E13))
                    // Clicks don't reach controls that aren't visible yet.
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
                androidx.compose.animation.AnimatedVisibility(
                    visible = embeddedCoverActive,
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
                ) {
                    if (embeddedDisplay != null && ready.playback != null && ready.lyrics != null) {
                        com.auralis.music.windows.immersive.EmbeddedImmersive(
                            immersive, ready.playback, ready.lyrics, ready.stores, embeddedDisplay
                        )
                    }
                }
            } else {
                val placeholderChrome: @Composable () -> Unit = {
                    ChromeTitleBar(TitleBarActions())
                }
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Color(0xFF0F0E13))
                ) {
                    // Only the chrome while components load: no centred logo flash before the real UI.
                    placeholderChrome()
                }
            }
        }

        val immersivePlayback = components?.playback
        val immersiveLyrics = components?.lyrics
        val immersiveStores = components?.stores
        if (immersivePlayback != null && immersiveLyrics != null && immersiveStores != null) {
            com.auralis.music.windows.immersive.ImmersiveIdleWatcher(immersive, immersivePlayback)
            if (isMac) {
                com.auralis.music.windows.notch.AuralisNotch(
                    playback = immersivePlayback,
                    lyrics = immersiveLyrics,
                    appearanceStore = immersiveStores.appearance,
                    lockScreenPreview = lockScreenPreview,
                    onOpenPlayer = { openWindow(); playerRequests++ },
                    onOpenSettings = { openWindow(); settingsRequestTrigger++ }
                )
                com.auralis.music.windows.immersive.MacLockScreenOverlay(
                    playback = immersivePlayback,
                    lyrics = immersiveLyrics,
                    stores = immersiveStores,
                    previewActive = lockScreenPreview,
                    onDismissPreview = { lockScreenPreview = false }
                )
            }
            if (!embeddedImmersive) com.auralis.music.windows.immersive.ImmersiveWindows(immersive, immersivePlayback, immersiveLyrics, immersiveStores,
                appBounds = { composeWindow?.takeIf { windowVisible && !windowState.isMinimized }?.bounds }, icon = icon,
                onLeaveToPlayer = {
                    immersive.close()
                    openWindow()
                })
        }
    }
}

/** The app tile drawn for each small size (bolder knot, less padding), then the full artwork. */
private fun appIconImages(): List<java.awt.Image>? = runCatching {
    val loader = Thread.currentThread().contextClassLoader
    (listOf(16, 20, 24, 32, 40, 48, 64).map { "app-icon/tile_$it.png" } + "auralis_logo.png")
        .map { name -> loader.getResourceAsStream(name)!!.use(ImageIO::read) }
}.onFailure { Log.w(TAG, "App icon sizes unavailable: ${it.message}") }.getOrNull()
