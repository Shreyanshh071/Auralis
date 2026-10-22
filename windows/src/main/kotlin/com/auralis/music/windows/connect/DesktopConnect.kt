package com.auralis.music.windows.connect

import com.auralis.music.data.connect.ConnectBackend
import com.auralis.music.data.connect.ConnectCodec
import com.auralis.music.data.connect.ConnectContext
import com.auralis.music.data.connect.ConnectDevice
import com.auralis.music.data.connect.ConnectDeviceNames
import com.auralis.music.data.connect.ConnectDeviceType
import com.auralis.music.data.connect.ConnectEngine
import com.auralis.music.data.connect.ConnectIntent
import com.auralis.music.data.connect.ConnectPlatform
import com.auralis.music.data.connect.ConnectPlayer
import com.auralis.music.data.connect.ConnectQueue
import com.auralis.music.data.connect.ConnectServerTime
import com.auralis.music.data.connect.ConnectSnapshot
import com.auralis.music.data.connect.ConnectUi
import com.auralis.music.data.connect.ConnectWrite
import com.auralis.music.data.connect.LocalPlayback
import com.auralis.music.data.connect.ServerClock
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import com.auralis.music.util.Log
import com.auralis.music.windows.profile.DesktopAccount
import com.auralis.music.windows.together.FirestoreRest
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Connect over Firestore's REST API, as the signed-in account. Session writes are conditioned on
 * the document's update time (a compare-and-swap), so a write based on old state is refused and
 * retried on the new state; the security rules also refuse any version that isn't the next one.
 */
class DesktopConnectBackend(private val api: FirestoreRest, val uid: String, private val clock: ServerClock) : ConnectBackend {
    private val sessionPath = "users/$uid/connect/session"
    private val queuePath = "users/$uid/connect/queue"
    private val devicesPath = "users/$uid/connectDevices"
    @Volatile private var queueCache: ConnectQueue? = null

    private fun checkAccount() {
        if (api.uid != uid) throw FirestoreRest.ServiceException(401, "Signed in to another account")
    }

    private fun readQueue(rev: Long): ConnectQueue? {
        if (rev == 0L) return null
        queueCache?.takeIf { it.rev == rev }?.let { return it }
        val q = api.accountGet(queuePath)?.first?.let { ConnectCodec.decodeQueue(toMap(it)) }
        if (q != null) queueCache = q
        return q?.takeIf { it.rev == rev }
    }

    override suspend fun fetch(): ConnectSnapshot = withContext(Dispatchers.IO) {
        checkAccount()
        val session = api.accountGet(sessionPath)?.first?.let { ConnectCodec.decodeSession(toMap(it)) }
        ConnectSnapshot(session, session?.let { readQueue(it.queueRev) })
    }

    override suspend fun update(block: (ConnectSnapshot) -> ConnectWrite?): ConnectSnapshot? = withContext(Dispatchers.IO) {
        repeat(6) {
            checkAccount()
            val doc = api.accountGet(sessionPath)
            val session = doc?.first?.let { ConnectCodec.decodeSession(toMap(it)) }
            val current = ConnectSnapshot(session, session?.let { readQueue(it.queueRev) })
            val w = block(current) ?: return@withContext null
            val writes = JSONArray()
            val (fields, stamps) = toJson(ConnectCodec.encodeSession(w.session))
            writes.put(api.replaceWrite(sessionPath, fields, stamps, updateTime = doc?.second, mustNotExist = doc == null))
            w.queue?.let { q -> writes.put(api.replaceWrite(queuePath, toJson(ConnectCodec.encodeQueue(q)).first)) }
            val sent = System.currentTimeMillis()
            try {
                val commitTime = api.accountCommit(writes)
                clock.sample(commitTime, sent, System.currentTimeMillis())
                w.queue?.let { queueCache = it }
                return@withContext ConnectSnapshot(w.session, w.queue ?: current.queue)
            } catch (e: FirestoreRest.ServiceException) {
                // Someone else wrote first: read again and redo the step on the newer state.
                val conflict = e.status == 409 || e.status == 412 ||
                    (e.status == 400 && e.message.orEmpty().contains("precondition", ignoreCase = true))
                if (!conflict) throw e
            }
        }
        throw IllegalStateException("The session kept changing; try again")
    }

    override suspend fun heartbeat(device: ConnectDevice) = withContext(Dispatchers.IO) {
        checkAccount()
        val (fields, stamps) = toJson(ConnectCodec.encodeDevice(device))
        val sent = System.currentTimeMillis()
        val commitTime = api.accountCommit(JSONArray().put(api.replaceWrite("$devicesPath/${device.id}", fields, stamps)))
        clock.sample(commitTime, sent, System.currentTimeMillis())
    }

    override suspend fun removeDevice(id: String) = withContext(Dispatchers.IO) {
        checkAccount()
        api.accountDelete("$devicesPath/$id")
    }

    suspend fun devices(): List<ConnectDevice> = withContext(Dispatchers.IO) {
        checkAccount()
        api.accountList(devicesPath).map { (_, fields) -> ConnectCodec.decodeDevice(toMap(fields)) }.filter { it.id.isNotBlank() }
    }

    companion object {
        /** Fields for the REST encoder, plus the names of fields the server fills with its time. */
        fun toJson(map: Map<String, Any?>): Pair<JSONObject, List<String>> {
            val stamps = mutableListOf<String>()
            val obj = JSONObject()
            for ((k, v) in map) {
                if (v === ConnectServerTime) stamps += k else obj.put(k, jsonValue(v))
            }
            return obj to stamps
        }

        private fun jsonValue(v: Any?): Any = when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> put(k.toString(), jsonValue(x)) } }
            is List<*> -> JSONArray().apply { v.forEach { put(jsonValue(it)) } }
            else -> v
        }

        fun toMap(obj: JSONObject): Map<String, Any?> = obj.keys().asSequence().associateWith { kotlinValue(obj.get(it)) }

        private fun kotlinValue(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> toMap(v)
            is JSONArray -> (0 until v.length()).map { kotlinValue(v.get(it)) }
            else -> v
        }
    }
}

/** Connect's view of this PC's player; acts through [DesktopPlaybackController.fromConnect]. */
class DesktopConnectPlayer(private val controller: DesktopPlaybackController, scope: CoroutineScope) : ConnectPlayer {
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
            (state == VlcAudioPlayer.State.PLAYING || state == VlcAudioPlayer.State.BUFFERING || resolving)
        return LocalPlayback(
            track = track,
            queue = q.tracks,
            index = q.index,
            playing = playing,
            buffering = playing && (resolving || state == VlcAudioPlayer.State.BUFFERING),
            positionMs = controller.positionMs.value,
            durationMs = controller.lengthMs.value.takeIf { it > 0L } ?: ((track?.duration ?: 0L) * 1000L),
            shuffle = controller.shuffleActive,
            repeat = RepeatMode.valueOf(controller.repeatMode.value.name),
            context = null,
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
        val id = com.auralis.music.data.network.RecordingMatches.getMatchedVideoId(track.id)
            ?: track.id.takeIf(com.auralis.music.desktop.playback.DesktopPlaybackMatch::isVideoId)
            ?: com.auralis.music.desktop.playback.DesktopPlaybackMatch.videoId(track)
            ?: return@withContext false
        // Warm the stream so the song starts quickly once the handoff completes; a miss isn't fatal.
        runCatching {
            com.auralis.music.desktop.playback.DesktopStreamResolver.resolve(id)?.let { url ->
                com.auralis.music.desktop.playback.LocalStreamRelay.preload(url, refresh = {
                    com.auralis.music.desktop.playback.DesktopStreamResolver.refreshBlocking(track.id)
                })
            }
        }
        true
    }

    override fun play() = controller.fromConnect { controller.connectPlay() }
    override fun pause() = controller.fromConnect { controller.connectSilence() }
    override fun seekTo(positionMs: Long) = controller.fromConnect { controller.seekTo(positionMs) }
    override fun next() = controller.fromConnect { controller.next() }
    override fun previous() = controller.fromConnect { controller.previous() }
    override fun playIndex(index: Int) = controller.fromConnect { controller.playAt(index) }
    // Windows shuffles the upcoming songs once; there is no unshuffle to apply.
    override fun setShuffle(on: Boolean) { if (on) controller.fromConnect { controller.shuffleUpcoming() } }
    override fun setRepeat(mode: RepeatMode) = controller.fromConnect { controller.setRepeatMode(DesktopPlaybackController.RepeatMode.valueOf(mode.name)) }
    override fun add(track: Track) = controller.fromConnect { controller.addToQueue(track) }
    override fun playNext(track: Track) = controller.fromConnect { controller.playNext(track) }
    override fun move(from: Int, to: Int) = controller.fromConnect { controller.moveAt(from, to) }
    override fun remove(index: Int) = controller.fromConnect { controller.removeAt(index) }
}

/**
 * Auralis Connect on Windows. Follows the signed-in account; while signed in, this PC is one
 * device of the account's listening session. Desktop has no realtime Firestore listener, so it
 * polls the small session document: fast during a handoff, every few seconds while it is the
 * output or showing another device's playback, rarely when idle or minimised.
 */
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
    private var backend: DesktopConnectBackend? = null
    private var uid: String? = null
    private var jobs: List<Job> = emptyList()
    /** Set while the device picker is open: the device list refreshes more often. */
    @Volatile var pickerOpen = false
        set(value) { field = value; if (value) devicesNow() }

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
        return e.intercept(intent)
    }

    private suspend fun follow(newUid: String?) = lock.withLock {
        if (newUid == uid) return@withLock
        // Another account, or none: the old account's session and controls go away at once.
        stopEngine(signOut = false)
        uid = newUid
        if (newUid != null) startEngine(newUid)
    }

    private fun startEngine(accountUid: String) {
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
        val clock = ServerClock(System::currentTimeMillis)
        val b = DesktopConnectBackend(account.firestore, accountUid, clock)
        val player = controller?.let { DesktopConnectPlayer(it, scope) }
        val e = ConnectEngine(scope, b, player, device, clock, System::currentTimeMillis) { Log.i(TAG, it) }
        backend = b
        engine = e
        Log.i(TAG, "Joined the account session as ${device.name}")
        jobs = buildList {
            add(scope.launch { e.ui.collect { u -> _ui.value = u; controller?.showRemote(u.remote?.takeIf { !e.isOutput }) } })
            add(scope.launch { while (isActive) { delay(1_000L); e.tick() } })
            // The session: read from the server, never assumed (a waking PC reads it first).
            add(scope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        e.onSnapshot(b.fetch())
                    } catch (ex: Exception) {
                        if (ex is kotlinx.coroutines.CancellationException) throw ex
                        e.onConnectionLost(ex.message)
                    }
                    delay(pollInterval(e))
                }
            })
            add(scope.launch(Dispatchers.IO) {
                while (isActive) {
                    refreshDevices(e, b)
                    var waited = 0L
                    while (isActive && waited < 30_000L && !(pickerOpen && waited >= 5_000L) && !devicesWanted) { delay(500L); waited += 500L }
                    devicesWanted = false
                }
            })
            controller?.let { c ->
                add(scope.launch {
                    merge(
                        combine(c.queue, c.state, c.resolving, c.repeatMode) { _, _, _, _ -> Unit },
                        c.positionMs.sample(1_000L)
                    ).collect { e.onLocalChanged() }
                })
            }
        }
        e.start()
    }

    @Volatile private var devicesWanted = false
    private fun devicesNow() { devicesWanted = true }

    private suspend fun refreshDevices(e: ConnectEngine, b: DesktopConnectBackend) {
        try { e.onDevices(b.devices()) } catch (ex: Exception) {
            if (ex is kotlinx.coroutines.CancellationException) throw ex
        }
    }

    /**
     * Reads cost against the free Firestore quota, so polling follows what's happening:
     * 0.4 s during a handoff, 2 s while this PC is the output (remote controls), 3 s while it
     * shows another device's playback, 6 s otherwise; four times slower while minimised.
     */
    private fun pollInterval(e: ConnectEngine): Long {
        val u = e.ui.value
        if (u.transfer?.failed == false) return 400L
        val base = when {
            e.isOutput -> 2_000L
            u.remote?.playing == true -> 3_000L
            else -> 6_000L
        }
        return if (windowVisible() || e.isOutput) base else base * 4
    }

    private suspend fun stopEngine(signOut: Boolean) {
        val e = engine ?: return
        engine = null
        backend = null
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _ui.value = ConnectUi()
        controller?.showRemote(null)
        e.stop(signOut)
    }

    companion object {
        private const val TAG = "AuralisConnect"
        /** The app's Connect (one per process), for the player bar and full player. */
        @Volatile var current: DesktopConnect? = null
        /** Whether the window is on screen (not minimised or hidden to the tray). */
        @Volatile var windowShown: Boolean = true

        /** The PC's own name (Windows computer name, through JNA); blank falls back to "Windows computer". */
        fun computerName(): String {
            val native = runCatching { com.sun.jna.platform.win32.Kernel32Util.getComputerName() }.getOrNull()
            return ConnectDeviceNames.clean(native) ?: ConnectDeviceNames.clean(System.getenv("COMPUTERNAME")).orEmpty()
        }
    }
}
