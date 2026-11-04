package com.auralis.music.windows.connect

import com.auralis.music.data.connect.ConnectContext
import com.auralis.music.data.connect.ConnectControlStore
import com.auralis.music.data.connect.ConnectDevice
import com.auralis.music.data.connect.ConnectDeviceNames
import com.auralis.music.data.connect.ConnectDeviceType
import com.auralis.music.data.connect.ConnectEngine
import com.auralis.music.data.connect.ConnectIntent
import com.auralis.music.data.connect.ConnectPlatform
import com.auralis.music.data.connect.ConnectPlayer
import com.auralis.music.data.connect.ConnectRealtimeBackend
import com.auralis.music.data.connect.ConnectRealtimeSink
import com.auralis.music.data.connect.ConnectSavedControl
import com.auralis.music.data.connect.ConnectSnapshot
import com.auralis.music.data.connect.ConnectUi
import com.auralis.music.data.connect.LocalPlayback
import com.auralis.music.data.connect.ServerClock
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import com.auralis.music.util.Log
import com.auralis.music.windows.profile.DesktopAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Connect's view of this PC's player; acts through [DesktopPlaybackController.fromConnect]. */
class DesktopConnectPlayer(private val controller: DesktopPlaybackController, private val scope: CoroutineScope) : ConnectPlayer {
    @Volatile private var lastError: Pair<String, Long>? = null

    init {
        scope.launch { controller.errors.collect { lastError = it to System.currentTimeMillis() } }
    }

    override fun snapshot(): LocalPlayback {
        val q = controller.queue.value
        val track = q.current
        val state = controller.state.value
        val resolving = controller.resolving.value
        // A Listen Together room drives this player: Connect stands aside (never silences or claims it).
        val playing = track != null && !controller.roomActive.value &&
            (state == VlcAudioPlayer.State.PLAYING || state == VlcAudioPlayer.State.BUFFERING || resolving || controller.clock.isBuffering())
        return LocalPlayback(
            track = track,
            queue = q.tracks,
            index = q.index,
            playing = playing,
            buffering = playing && (resolving || controller.clock.isBuffering()),
            positionMs = controller.clock.rawPositionMs(),
            durationMs = controller.lengthMs.value.takeIf { it > 0L } ?: ((track?.duration ?: 0L) * 1000L),
            shuffle = controller.shuffleActive,
            repeat = RepeatMode.valueOf(controller.repeatMode.value.name),
            context = null,
            lyricsSelection = com.auralis.music.data.connect.ConnectLyricsState.current(track?.id),
            error = lastError?.takeIf { System.currentTimeMillis() - it.second < 15_000L }?.first
        )
    }

    override fun load(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean, shuffle: Boolean, repeat: RepeatMode, context: ConnectContext?) {
        if (controller.roomActive.value) return
        controller.fromConnect {
            controller.showRemote(null)
            lastError = null
            controller.connectLoad(tracks, index, positionMs, play)
            controller.setRepeatMode(DesktopPlaybackController.RepeatMode.valueOf(repeat.name))
        }
    }

    override fun silence() { if (!controller.roomActive.value) controller.fromConnect { controller.connectSilence() } }

    override suspend fun prepare(track: Track): Boolean = withContext(Dispatchers.IO) {
        if (com.auralis.music.desktop.library.DesktopDownloads.instance.localFile(track.id) != null) return@withContext true
        // A song from another device's own files can't be played here.
        if (track.source == TrackSource.LOCAL) return@withContext false
        if (track.id.startsWith("unavailable:")) return@withContext false
        // Warm the stream while the handoff completes rather than before acknowledging it: the
        // old output stops and commits meanwhile, and the load joins this same in-flight lookup
        // (DesktopStreamResolver shares it). A miss isn't fatal; a song that won't start is handed back.
        scope.launch(Dispatchers.IO) {
            runCatching {
                val id = com.auralis.music.data.network.RecordingMatches.getMatchedVideoId(track.id)
                    ?: track.id.takeIf(com.auralis.music.desktop.playback.DesktopPlaybackMatch::isVideoId)
                    ?: com.auralis.music.desktop.playback.DesktopPlaybackMatch.videoId(track)
                    ?: return@runCatching
                com.auralis.music.desktop.playback.DesktopStreamResolver.resolve(id)?.let { url ->
                    com.auralis.music.desktop.playback.LocalStreamRelay.preload(url, refresh = {
                        com.auralis.music.desktop.playback.DesktopStreamResolver.refreshBlocking(track.id)
                    })
                }
            }
        }
        true
    }

    override fun play() { controller.fromConnect { controller.connectPlay() } }
    override fun pause() { controller.fromConnect { controller.connectSilence() } }
    override fun seekTo(positionMs: Long) { controller.fromConnect { controller.seekTo(positionMs) } }
    override fun next() { controller.fromConnect { controller.next() } }
    override fun previous() { controller.fromConnect { controller.previous() } }
    override fun playIndex(index: Int) { controller.fromConnect { controller.playAt(index) } }
    // Apply the requested mode without toggling it on duplicate Connect commands.
    override fun setShuffle(on: Boolean) { controller.fromConnect { controller.setShuffle(on) } }
    override fun setRepeat(mode: RepeatMode) = controller.fromConnect { controller.setRepeatMode(DesktopPlaybackController.RepeatMode.valueOf(mode.name)) }
    override fun add(track: Track) = controller.fromConnect { controller.addToQueue(track) }
    override fun playNext(track: Track) = controller.fromConnect { controller.playNext(track) }
    override fun move(from: Int, to: Int) = controller.fromConnect { controller.moveAt(from, to) }
    override fun remove(index: Int) = controller.fromConnect { controller.removeAt(index) }
}

/** Connect lifecycle and playback observation for the signed-in desktop account. */
class DesktopConnect(
    private val scope: CoroutineScope,
    private val account: DesktopAccount,
    private val controller: DesktopPlaybackController?,
    dataDir: File,
    private val windowVisible: () -> Boolean = { true }
) {
    private val _ui = MutableStateFlow(ConnectUi())
    val ui: StateFlow<ConnectUi> = _ui.asStateFlow()
    private val store = com.auralis.music.util.PropertiesFileKeyValueStore(File(dataDir, "connect.properties"))
    private val lock = Mutex()
    @Volatile private var engine: ConnectEngine? = null
    private var realtime: ConnectRealtimeBackend? = null

    val deviceSyncAvailable: Boolean get() = !System.getProperty(REALTIME_URL).isNullOrBlank()
    private val _deviceSyncEnabled = MutableStateFlow(store.getString("realtime_transport", "") == "on")
    val deviceSyncEnabled: StateFlow<Boolean> = _deviceSyncEnabled.asStateFlow()

    fun setDeviceSyncEnabled(enabled: Boolean) {
        if (enabled && !deviceSyncAvailable) return
        scope.launch {
            lock.withLock {
                if (_deviceSyncEnabled.value == enabled) return@withLock
                store.putStrings(mapOf("realtime_transport" to if (enabled) "on" else ""))
                _deviceSyncEnabled.value = enabled
                if (enabled) uid?.let(::startEngine)
                else stopEngine(signOut = false)
            }
        }
    }

    private var uid: String? = null
    private var jobs: List<Job> = emptyList()
    fun start() {
        current = this
        account.beforeSignOut = { leave(signOut = true) }
        controller?.connectControl = { intent -> intercept(intent) }
        scope.launch {
            account.state.map { it.profile?.uid?.takeIf(String::isNotBlank) }.distinctUntilChanged().collect { follow(it) }
        }
    }

    fun transferTo(deviceId: String) { engine?.transferTo(deviceId) }

    /** On close: give up the output (paused) and show this PC as offline to the other devices. */
    suspend fun close() { withTimeoutOrNull(3_000L) { lock.withLock { stopEngine(signOut = false) } } }

    suspend fun leave(signOut: Boolean) {
        lock.withLock {
            stopEngine(signOut)
            uid = null
        }
    }

    private fun intercept(intent: ConnectIntent): Boolean {
        val e = engine ?: return false
        // Picking from the queue on screen (the other device's queue) plays that item there.
        if (intent is ConnectIntent.Load) {
            val remote = controller?.connectRemote?.value
            if (remote != null && remote.output.active && intent.tracks.map { it.id } == remote.queue.map { it.id }) {
                intent.tracks.getOrNull(intent.index)?.let { return e.intercept(ConnectIntent.PlayIndex(intent.index, it.id)) }
            }
        }
        val intercepted = e.intercept(intent)
        if (intercepted) controller?.showRemote(e.ui.value.remote?.takeIf { !e.isOutput })
        return intercepted
    }

    private suspend fun follow(newUid: String?) = lock.withLock {
        if (newUid == uid) return@withLock
        // Another account, or none: the old account's session and controls go away at once.
        stopEngine(signOut = false)
        uid = newUid
        if (newUid != null) startEngine(newUid)
    }

    private fun startEngine(accountUid: String) {
        if (!_deviceSyncEnabled.value || !deviceSyncAvailable) return
        val deviceId = store.getString("device_$accountUid", "")?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString().also { store.putStrings(mapOf("device_$accountUid" to it)) }
        val created = store.getString("created_$accountUid", "")?.toLongOrNull()
            ?: System.currentTimeMillis().also { store.putStrings(mapOf("created_$accountUid" to it.toString())) }
        val device = ConnectDevice(
            id = deviceId,
            name = computerName(),
            nickname = store.getString("nickname_$accountUid", "")?.takeIf { it.isNotBlank() },
            platform = ConnectPlatform.WINDOWS,
            type = ConnectDeviceType.COMPUTER,
            appVersion = System.getProperty("jpackage.app-version") ?: "dev",
            canPlay = controller != null,
            createdMs = created
        )
        val elapsed = { System.nanoTime() / 1_000_000L }
        val clock = ServerClock(System::currentTimeMillis, elapsed)
        val sink = object : ConnectRealtimeSink {
            @Volatile var engine: ConnectEngine? = null
            override fun onSnapshot(snapshot: ConnectSnapshot) { engine?.onSnapshot(snapshot) }
            override fun onDevices(devices: List<ConnectDevice>) { engine?.onDevices(devices) }
            override fun onConnectionLost(message: String?) { engine?.onConnectionLost(message) }
        }
        val realtimeUrl = System.getProperty(REALTIME_URL)?.takeIf { it.isNotBlank() } ?: return
        val realtime = realtimeUrl.let { url ->
            ConnectRealtimeBackend(url, deviceId, clock, scope, sink, okhttp3.OkHttpClient(), log = { Log.i(TAG, it) }) { refresh ->
                withContext(Dispatchers.IO) { runCatching { account.firestore.accountIdToken(refresh) }.getOrNull() }
            }
        }
        val player = controller?.let { DesktopConnectPlayer(it, scope) }
        val savedControls = object : ConnectControlStore {
            override fun load() = ConnectSavedControl.decode(store.getString("ws_control_$accountUid", ""))
            override fun save(control: ConnectSavedControl?) = store.putStrings(mapOf("ws_control_$accountUid" to control?.encode().orEmpty()))
        }
        val e = ConnectEngine(scope, realtime, player, device, clock, System::currentTimeMillis, elapsed, savedControls) { Log.i(TAG, it) }
        sink.engine = e
        this.realtime = realtime
        engine = e
        Log.i(TAG, "Joined the account session as ${device.name} over WebSocket")
        jobs = buildList {
            add(scope.launch {
                e.ui.collect { u ->
                    _ui.value = u
                    controller?.showRemote(u.remote?.takeIf { !e.isOutput })
                }
            })
            add(scope.launch { while (isActive) {
                delay(1_000L)
                // A hidden idle app is not an available output. Actual background
                // playback stays reachable, matching the phone's media lifetime.
                val local = player?.snapshot()
                e.setDeviceAvailable(windowVisible() || local?.playing == true || local?.buffering == true)
                e.tick()
            } })
            controller?.let { c ->
                add(scope.launch { c.seekEvents.collect { e.onLocalSeek() } })
                add(scope.launch {
                    merge(
                        combine(c.queue, c.state, c.resolving, c.repeatMode) { _, _, _, _ -> Unit },
                        c.positionMs.sample(1_000L)
                    ).collect { e.onLocalChanged() }
                })
            }
        }
        e.start()
        realtime.start()
    }

    private suspend fun stopEngine(signOut: Boolean) {
        val e = engine ?: return
        val oldRealtime = realtime
        engine = null
        realtime = null
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _ui.value = ConnectUi()
        controller?.showRemote(null)
        // The old transport gives up the output first, then closes.
        try { e.stop(signOut) } finally { oldRealtime?.stop() }
    }

    companion object {
        private const val TAG = "AuralisConnect"
        private const val REALTIME_URL = "auralis.connectRealtimeUrl"
        /** The app's Connect (one per process), for the player bar and full player. */
        @Volatile var current: DesktopConnect? = null
        /** Whether the window is on screen (not minimised or hidden to the tray). */
        @Volatile var windowShown: Boolean = true

        /** The PC's own name (Windows computer name, through JNA); blank falls back to "Windows computer". */
        fun computerName(): String {
            val native = runCatching { com.sun.jna.platform.win32.Kernel32Util.getComputerName() }.getOrNull()
            val host = ConnectDeviceNames.clean(native) ?: ConnectDeviceNames.clean(System.getenv("COMPUTERNAME"))
            fun bios(key: String) = runCatching {
                com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
                    com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE, "HARDWARE\\DESCRIPTION\\System\\BIOS", key)
            }.getOrNull()
            return desktopDeviceName(host, bios("SystemManufacturer"), bios("SystemFamily"), bios("SystemProductName"))
        }
    }
}
