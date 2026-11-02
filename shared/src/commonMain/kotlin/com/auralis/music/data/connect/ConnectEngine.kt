package com.auralis.music.data.connect

import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.random.Random

/** The server side of Connect, already scoped to the signed-in account. */
interface ConnectBackend {
    /** The current session and queue, read from the server (never from a cache). */
    suspend fun fetch(): ConnectSnapshot

    /**
     * Runs [block] on the newest server state and writes what it returns atomically (a transaction,
     * or a write conditioned on the document's update time), retrying when someone else wrote first.
     * Returns what was written, or null when [block] returned null. Throws when the server is unreachable.
     */
    suspend fun update(block: (ConnectSnapshot) -> ConnectWrite?): ConnectSnapshot?

    /** Registers this client or refreshes its heartbeat. */
    suspend fun heartbeat(device: ConnectDevice)

    suspend fun removeDevice(id: String)
}

/** Estimates server time from this client's own writes, so positions compare across devices. */
class ServerClock(private val localNow: () -> Long) {
    @Volatile var offsetMs: Long = 0L; private set
    private var bestRoundTrip = Long.MAX_VALUE
    private var sampledAt = 0L

    /** [serverMs]: the server's time for a write sent at [sentLocalMs] and acknowledged at [ackLocalMs]. */
    fun sample(serverMs: Long, sentLocalMs: Long, ackLocalMs: Long) {
        val roundTrip = ackLocalMs - sentLocalMs
        if (roundTrip < 0 || serverMs <= 0L) return
        val now = localNow()
        // Keep the tightest round trip, but refresh it every ten minutes (clocks drift).
        if (roundTrip <= bestRoundTrip || now - sampledAt > 10 * 60_000L) {
            bestRoundTrip = roundTrip
            sampledAt = now
            offsetMs = serverMs - (sentLocalMs + roundTrip / 2)
        }
    }

    fun now(): Long = localNow() + offsetMs
    fun toLocal(serverMs: Long): Long = serverMs - offsetMs
}

/** The local player as Connect sees it. [playing] is the intent to play (true while starting). */
data class LocalPlayback(
    val track: Track? = null,
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val context: ConnectContext? = null,
    val error: String? = null,
    val lyricsSelection: ConnectLyricsSelection? = null
)

/**
 * The local audio player. Connect calls these on the engine's thread; they must act on this device
 * only (never be routed back to Connect as remote controls).
 */
interface ConnectPlayer {
    val canPlay: Boolean get() = true
    fun snapshot(): LocalPlayback
    /** Replaces the local queue; makes sound only when [play]. */
    fun load(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean, shuffle: Boolean, repeat: RepeatMode, context: ConnectContext?)
    /** Stops making sound now. */
    fun silence()
    /** Gets [track] ready to start quickly; false when this device can't play it at all. */
    suspend fun prepare(track: Track): Boolean
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun playIndex(index: Int)
    fun setShuffle(on: Boolean)
    fun setRepeat(mode: RepeatMode)
    fun add(track: Track)
    fun playNext(track: Track)
    fun move(from: Int, to: Int)
    fun remove(index: Int)
}

/** A control the user made on this client. Indices are positions in the queue on screen. */
sealed class ConnectIntent {
    data object Play : ConnectIntent()
    data object Pause : ConnectIntent()
    data class Seek(val positionMs: Long) : ConnectIntent()
    data object Next : ConnectIntent()
    data object Previous : ConnectIntent()
    data class PlayIndex(val index: Int, val trackId: String) : ConnectIntent()
    data class Load(val tracks: List<Track>, val index: Int, val context: ConnectContext? = null) : ConnectIntent()
    data class Shuffle(val on: Boolean) : ConnectIntent()
    data class Repeat(val mode: RepeatMode) : ConnectIntent()
    data class Add(val track: Track) : ConnectIntent()
    data class PlayNext(val track: Track) : ConnectIntent()
    data class Move(val from: Int, val to: Int) : ConnectIntent()
    data class Remove(val index: Int, val trackId: String) : ConnectIntent()
}

enum class ConnectRowState { IDLE, CONNECTING, FAILED, UNAVAILABLE }

data class ConnectDeviceRow(
    val device: ConnectDevice,
    val label: String,
    val isThis: Boolean,
    val isOutput: Boolean,
    val state: ConnectRowState
) {
    val selectable: Boolean get() = state == ConnectRowState.IDLE || state == ConnectRowState.FAILED
}

/** The device that has (or last had) the session's audio. */
data class ConnectOutput(
    val deviceId: String,
    val label: String,
    val type: ConnectDeviceType,
    val isThis: Boolean,
    /** False when that device's lease ran out (closed, offline): "last played on". */
    val active: Boolean,
    val playing: Boolean
)

/** The session as a silent client shows it. Progress continues from [anchorLocalMs]. */
data class ConnectRemoteView(
    val track: Track,
    val queue: List<Track>,
    val index: Int,
    val playing: Boolean,
    val buffering: Boolean,
    val positionMs: Long,
    val anchorLocalMs: Long,
    val durationMs: Long,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val context: ConnectContext?,
    val output: ConnectOutput,
    val lyricsSelection: ConnectLyricsSelection? = null
) {
    fun positionAt(localNowMs: Long): Long {
        if (!playing || buffering) return positionMs
        val p = positionMs + (localNowMs - anchorLocalMs).coerceAtLeast(0L)
        return if (durationMs > 0L) p.coerceAtMost(durationMs) else p
    }
}

data class ConnectTransferUi(val deviceId: String, val label: String, val failed: Boolean, val reason: String = "")

data class ConnectUi(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val me: ConnectDevice? = null,
    val devices: List<ConnectDeviceRow> = emptyList(),
    val output: ConnectOutput? = null,
    /** Non-null while another device is (or last was) the output: show this instead of local playback. */
    val remote: ConnectRemoteView? = null,
    val transfer: ConnectTransferUi? = null,
    val error: String? = null
) {
    val otherDevicesOnline: Boolean get() = devices.any { !it.isThis && it.state != ConnectRowState.UNAVAILABLE }
}

/**
 * One client's side of Connect. Feed it server-confirmed snapshots, device lists and local player
 * changes; it keeps the single-output rule, applies remote controls when this client is the
 * output, runs handoffs, and publishes [ui]. All work is serialised on one lock.
 *
 * Connect follows playback; playback never follows Connect. [intercept] decides a control's route
 * from what is already known, without waiting. A control for this device goes to the local player
 * at once and the session catches up afterwards, however long Firestore takes or if it never answers.
 * Only controls for another device and handoffs the user asked for need Firestore.
 */
class ConnectEngine(
    private val scope: CoroutineScope,
    private val backend: ConnectBackend,
    private val player: ConnectPlayer?,
    device: ConnectDevice,
    val clock: ServerClock,
    private val localNow: () -> Long,
    private val log: (String) -> Unit = {}
) {
    companion object {
        const val LEASE_MS = 90_000L
        const val RENEW_EVERY_MS = 30_000L
        // Every client writes this all day, against a 20k-writes-a-day project quota: keep it rare.
        const val HEARTBEAT_MS = 120_000L
        const val ONLINE_WINDOW_MS = 300_000L
        /** Position reports while another device is watching; between them it counts on by itself. */
        const val CHECKPOINT_MS = 15_000L
        /** With no other device online nobody reads the session: only keep the bookmark roughly current. */
        const val SOLO_CHECKPOINT_MS = 300_000L
        const val RECENT_WINDOW_MS = 14L * 86_400_000L
        const val FORGET_AFTER_MS = 30L * 86_400_000L
        const val COMMAND_TTL_MS = 30_000L
        const val PREPARE_TIMEOUT_MS = 6_000L
        const val REQUEST_TIMEOUT_MS = 15_000L
        const val FORCE_COMMIT_MS = 5_000L
        /** Lease on a direct handoff: the target renews it with its first write, or the audio comes back. */
        const val DIRECT_LEASE_MS = 12_000L
        /** The most transit time a position report is credited with (a poll and a round trip). */
        const val POSITION_SLACK_MS = 3_000L
        const val COMMIT_RETRY_MS = 2_000L
        /** A song skipped straight past isn't worth a stream lookup on the other devices. */
        const val REMOTE_WARM_DELAY_MS = 1_500L
        const val READY_TIMEOUT_MS = 12_000L
        const val TRANSFER_KEEP_MS = 8_000L
        const val DRIFT_MS = 1_500L
        const val SETTLE_MS = 4_000L
        const val OPTIMISTIC_MS = 6_000L
        const val START_WATCH_MS = 20_000L
        /** How long a song sent to another device waits for the write before it plays here instead. */
        const val WRITE_PATIENCE_MS = 4_000L
        /** No Firestore call may hold the engine's lock longer than this. */
        const val WRITE_TIMEOUT_MS = 10_000L
        /** Failed transactions still spend reads, so routine writes pause this long after a failure. */
        const val WRITE_RETRY_MS = 30_000L
        /** A control made here, before the player acts on it: it may not have a song yet. */
        const val LOCAL_CONTROL_SETTLE_MS = 2_000L
        /** The handoff reason of a device catching the session up with a control already made there. */
        const val LOCAL_CONTROL = "local-control"
    }

    var me: ConnectDevice = device
        private set
    private val lock = Mutex()
    @Volatile private var current: ConnectSnapshot? = null
    /** The epoch this client holds the output for; null when it is not the output. */
    @Volatile private var ownerEpoch: Long? = null
    private var devices: List<ConnectDevice> = emptyList()
    private val applied = LinkedHashSet<String>()
    private val realtimeSeeks = LinkedHashSet<String>()
    private val handledTransfers = LinkedHashSet<String>()
    private var startedTransfer: String? = null
    private var preparing: Job? = null
    private var preparedTransfer: String? = null

    private data class Published(val local: LocalPlayback, val atLocal: Long)
    private var lastPublished: Published? = null
    private var settleUntilLocal = 0L
    private var localSeekRevision = 0L
    private var publishedSeekRevision = 0L
    private var pendingSeek: Pair<ConnectIntent.Seek, Optimistic?>? = null
    private var sendingSeek = false

    private data class Optimistic(val playing: Boolean?, val positionMs: Long?, val atLocal: Long, var sentVersion: Long = Long.MAX_VALUE,
                                  var commandId: String? = null)
    @Volatile private var optimistic: Optimistic? = null

    private data class StartWatch(val from: String?, val atLocal: Long)
    private var startWatch: StartWatch? = null

    /**
     * A control made on this device that the session hasn't heard about. While it is set the local
     * player is in charge. [epoch] is the session's output epoch at the time, null if none was known.
     */
    private data class LocalControl(val epoch: Long?, val atLocal: Long)
    @Volatile private var localControl: LocalControl? = null

    /** The last session write failed; a control can't be expected to reach another device. */
    @Volatile private var writesFailing = false
    /** Routine writes wait until this local time after a failure. */
    private var writeRetryAtLocal = 0L

    private var lastHeartbeatLocal = Long.MIN_VALUE / 2
    @Volatile private var deviceAvailable = true
    private val presenceLock = kotlinx.coroutines.sync.Mutex()

    /** Hidden idle clients still mirror the session, but cannot receive playback. */
    fun setDeviceAvailable(available: Boolean) {
        if (deviceAvailable == available || stopped) return
        deviceAvailable = available
        scope.launch { heartbeatNow(); locked {} }
    }
    @Volatile private var stopped = false
    @Volatile private var connected = false
    private var error: String? = null

    private val _ui = MutableStateFlow(ConnectUi(enabled = true, me = device))
    val ui: StateFlow<ConnectUi> = _ui.asStateFlow()

    /** True once this client holds the output (it plays locally and publishes). */
    val isOutput: Boolean get() = ownerEpoch != null

    // ---- Entry points ------------------------------------------------------------------------

    /** Registers this client and reads the authoritative session before anything else happens. */
    fun start() {
        scope.launch {
            heartbeatNow()
            locked { refetch() }
        }
    }

    /** A server-confirmed snapshot (never one served from a local cache). */
    fun onSnapshot(snapshot: ConnectSnapshot) {
        scope.launch {
            // An output may be awaiting its own position write. An already committed
            // seek must reach the player without waiting for that network round trip.
            applyRealtimeSeeks(snapshot)
            locked { accept(snapshot); connected = true; error = null }
        }
    }

    fun onDevices(list: List<ConnectDevice>) {
        scope.launch {
            locked {
                val now = clock.now()
                val stale = list.filter { it.id != me.id && it.lastSeenMs in 1 until now - FORGET_AFTER_MS }
                devices = list.filter { it !in stale }
                stale.forEach { d -> scope.launch { runCatching { backend.removeDevice(d.id) } } }
            }
        }
    }

    /** The local player changed (song, play state, position, queue...). */
    fun onLocalChanged() {
        if (localPending) return
        localPending = true
        scope.launch { locked { localPending = false } }
    }
    @Volatile private var localPending = false

    /** Explicit seeks must bypass drift detection, even when only a few milliseconds apart. */
    fun onLocalSeek() {
        localSeekRevision++
        onLocalChanged()
    }

    /** Connection trouble reported by the platform (listener error, failed poll). */
    fun onConnectionLost(message: String?) {
        connected = false
        error = message
        scope.launch { lock.withLock { refreshUi() } }
    }

    /** Called about once a second: lease renewal, heartbeats, handoff timeouts. */
    fun tick() {
        if (stopped) return
        // Hiding already wrote "offline", so a hidden idle client has nothing new to say.
        // One heartbeat at a time: Android queues offline writes, and a pile would all land on reconnect.
        if (deviceAvailable && !presenceLock.isLocked && localNow() - lastHeartbeatLocal >= HEARTBEAT_MS) scope.launch { heartbeatNow() }
        if (lock.isLocked) return
        scope.launch {
            locked {
                if (current == null) refetch()
                optimistic?.let { if (localNow() - it.atLocal > OPTIMISTIC_MS) optimistic = null }
                watchStart()
            }
        }
    }

    /** Moves the output to [deviceId] (any device of the account, including this one). */
    fun transferTo(deviceId: String) {
        // Show "Connecting…" the moment it's picked. The request itself waits its turn on the
        // lock and a server round trip (2-3 s of a picker that seemed to ignore the click).
        val u = _ui.value
        val row = u.devices.firstOrNull { it.device.id == deviceId }
        if (row != null && !row.isOutput && row.state != ConnectRowState.UNAVAILABLE) {
            requested = deviceId to localNow()
            _ui.value = u.copy(transfer = ConnectTransferUi(deviceId, row.label, failed = false),
                devices = u.devices.map { if (it.device.id == deviceId) it.copy(state = ConnectRowState.CONNECTING) else it })
        }
        scope.launch {
            var sent = false
            try { locked { requestTransfer(deviceId); sent = true } }
            finally { if (requested?.first == deviceId) { requested = null; lock.withLock { refreshUi() } } }
            if (sent || stopped) return@launch
            // Asking for the audio here needs nothing from another device, so it still happens.
            if (deviceId == me.id) playHereInstead(ConnectIntent.Play)
            else {
                failedRequest = deviceId to localNow()
                lock.withLock { refreshUi() }
            }
        }
    }
    /** A device picked here whose handoff request hasn't reached the server yet. */
    @Volatile private var requested: Pair<String, Long>? = null
    /** A device picked here whose handoff request couldn't be written. */
    @Volatile private var failedRequest: Pair<String, Long>? = null

    /**
     * Called by the local player for a user control, on its way to acting. Never waits: true means
     * the control went to the output on another device; false means the local player acts on it now.
     */
    fun intercept(intent: ConnectIntent): Boolean {
        if (stopped || ownerEpoch != null || localControl != null) return false
        val p = player
        val s = current?.session
        val now = clock.now()
        val elsewhere = s != null && s.ownerId != me.id && s.ownerActive(now) &&
            (s.playing || s.buffering || p?.canPlay != true) && (s.track != null || intent is ConnectIntent.Load)
        if (elsewhere && p?.snapshot()?.playing != true &&
            ((connected && !writesFailing) || !intent.startsAudio || p?.canPlay != true)) {
            optimisticFor(intent, s!!, now)
            val feedback = optimistic
            if (intent is ConnectIntent.Seek) sendLatestSeek(intent) else sendToOutput(intent, feedback)
            return true
        }
        if (intent != ConnectIntent.Pause && p?.canPlay == true) {
            localControl = LocalControl(s?.epoch, localNow())
            optimistic = null
            // This device hasn't restored the session's song yet: the control acts on that song.
            if (s?.track != null && intent !is ConnectIntent.Load && p.snapshot().track == null) current?.let { startLocal(it, play = false, from = null) }
            refreshUi()
        }
        return false
    }

    private val ConnectIntent.startsAudio: Boolean get() = this == ConnectIntent.Play || this == ConnectIntent.Next ||
        this == ConnectIntent.Previous || this is ConnectIntent.PlayIndex || this is ConnectIntent.Load

    /** Sends a control to the output. A song that can't get there plays here, so the tap is never lost. */
    private fun sendToOutput(intent: ConnectIntent, feedback: Optimistic?) {
        scope.launch {
            var delivered = false
            val attempt = launch { locked { delivered = sendCommand(intent, feedback) } }
            withTimeoutOrNull(WRITE_PATIENCE_MS) { attempt.join() }
            if (delivered) return@launch
            attempt.cancel()
            if (optimistic === feedback) optimistic = null
            if (intent is ConnectIntent.Load || intent == ConnectIntent.Play) playHereInstead(intent)
        }
    }

    private fun playHereInstead(intent: ConnectIntent) {
        val p = player ?: return
        val snapshot = current ?: return
        val s = snapshot.session ?: return
        if (stopped || !p.canPlay || ownerEpoch != null) return
        log("Couldn't reach ${s.ownerName}; playing on this device")
        optimistic = null
        localControl = LocalControl(s.epoch, localNow())
        if (intent !is ConnectIntent.Load && s.track != null && p.snapshot().track?.id != s.track.id) startLocal(snapshot, play = false, from = null)
        executeLocalIntent(intent, s, p)
        refreshUi()
    }

    /**
     * Leaves the session: gives up the output (paused, so no other client thinks it still plays) and
     * marks this client offline, or removes its registration when [signOut].
     */
    suspend fun stop(signOut: Boolean) {
        if (stopped) return
        stopped = true
        preparing?.cancel()
        lock.withLock {
            val epoch = ownerEpoch
            val local = player?.snapshot()
            withTimeoutOrNull(4_000L) {
                if (epoch != null) runCatching {
                    backend.update { cur ->
                        val s = cur.session ?: return@update null
                        if (s.ownerId != me.id || s.epoch != epoch) return@update null
                        val now = clock.now()
                        // Alone, the session can be minutes behind: leave the real song as the bookmark.
                        val state = local?.takeIf { it.track != null }?.let { stateWrite(cur, s, it, now) }
                        ConnectWrite((state?.session ?: s.copy(version = s.version + 1)).copy(leaseUntilMs = 0L, playing = false, buffering = false,
                            positionMs = local?.positionMs ?: s.positionAt(now), positionAtMs = now,
                            transfer = s.transfer?.takeIf { !it.status.active }, updatedBy = me.id), state?.queue)
                    }
                }
                runCatching { if (signOut) backend.removeDevice(me.id) else backend.heartbeat(me.copy(online = false)) }
            }
            ownerEpoch = null
            _ui.value = ConnectUi()
        }
    }

    // ---- Core --------------------------------------------------------------------------------

    private suspend fun locked(block: suspend () -> Unit) {
        if (stopped) return
        lock.withLock {
            if (stopped) return
            try {
                block()
                process()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connected = false
                error = e.message?.take(160)
                log("Connect step failed: ${e.message}")
            }
            refreshUi()
        }
    }

    /** When this client first saw the session's current position report, by its own clock. */
    private var seenPosition = -1L
    private var seenStamp = -1L
    private var seenAtLocal = 0L
    private var seenAge = 0L

    /**
     * Where the session's song is now. It counts on from when this client first saw the report,
     * by its own clock; the writer's timestamp only adds the report's transit time (at most
     * [POSITION_SLACK_MS]). Trusting that timestamp outright let a writer whose clock estimate was
     * off push the position to the song's end: lyrics stuck on the last lines after a handoff.
     */
    private fun sessionPosition(s: ConnectSession): Long {
        if (!s.playing || s.buffering || s.track == null) return s.positionMs.coerceAtLeast(0L)
        // After an output disappears, restore its confirmed bookmark rather than inventing
        // up to an entire lease's worth of listening while no device was actually playing.
        if (!s.ownerActive(clock.now())) return s.positionMs.coerceAtLeast(0L)
        if (s.positionMs != seenPosition || s.positionAtMs != seenStamp) return s.positionAt(clock.now())
        // An output that went away (lease lapsed) stops where it was.
        val lapsedFor = if (s.leaseUntilMs > 0L) (clock.now() - s.leaseUntilMs).coerceAtLeast(0L) else 0L
        val position = s.positionMs + seenAge + (localNow() - seenAtLocal - lapsedFor).coerceAtLeast(0L)
        return if (s.durationMs > 0L) position.coerceIn(0L, s.durationMs) else position.coerceAtLeast(0L)
    }

    private fun accept(snapshot: ConnectSnapshot) {
        val known = current?.session?.version ?: -1L
        val incoming = snapshot.session?.version ?: 0L
        if (incoming < known) return // older than what this client already saw
        val s = snapshot.session
        // Keep the queue we have when the new snapshot's queue hasn't arrived yet but ours matches.
        val queue = snapshot.queue?.takeIf { s == null || it.rev == s.queueRev }
            ?: current?.queue?.takeIf { s != null && it.rev == s.queueRev }
        current = ConnectSnapshot(s, queue)
        if (s != null && (s.positionMs != seenPosition || s.positionAtMs != seenStamp)) {
            seenPosition = s.positionMs
            seenStamp = s.positionAtMs
            seenAtLocal = localNow()
            seenAge = (clock.now() - s.positionAtMs).coerceIn(0L, POSITION_SLACK_MS)
        }
        // The output's next write isn't proof it applied the control: a routine position update
        // written before it got there brought the old position back, so a tapped lyric line jumped
        // to another line and Play/Pause showed the wrong state. It removes a command once applied.
        optimistic?.let { o ->
            if (s != null && s.version > o.sentVersion && s.updatedBy == s.ownerId &&
                (o.commandId == null || s.commands.none { it.id == o.commandId })) optimistic = null
        }
    }

    private suspend fun refetch() {
        accept(bounded { backend.fetch() })
        connected = true
        error = null
    }

    /** Captures the newest server state seen inside [backend.update], whatever the block decided. */
    private suspend fun update(block: (ConnectSnapshot) -> ConnectWrite?): ConnectSnapshot? {
        var seen: ConnectSnapshot? = null
        val written = try {
            bounded { backend.update { cur -> seen = cur; block(cur) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            writesFailing = true
            writeRetryAtLocal = localNow() + WRITE_RETRY_MS
            throw e
        }
        writesFailing = false
        writeRetryAtLocal = 0L
        connected = true
        error = null
        if (written != null) accept(written) else seen?.let(::accept)
        return written
    }

    /** Runs a Firestore call that throws, rather than hangs, when no answer comes. */
    private suspend fun <T> bounded(call: suspend () -> T): T {
        var result: Result<T>? = null
        withTimeoutOrNull(WRITE_TIMEOUT_MS) { result = Result.success(call()) }
        val done = result ?: throw IllegalStateException("Firestore didn't answer")
        return done.getOrThrow()
    }

    private suspend fun process() {
        for (round in 0 until 3) {
            val before = current?.session?.version
            processOnce()
            if (current?.session?.version == before) return
        }
    }

    private suspend fun processOnce() {
        watchHandOver()
        val snap = current ?: return
        val s = snap.session
        val now = clock.now()

        if (s != null) {
            val owned = ownerEpoch
            if (owned != null && (s.ownerId != me.id || s.epoch != owned)) {
                log("Output moved to ${s.ownerName} (epoch ${s.epoch}); stopping audio here")
                ownerEpoch = null
                lastPublished = null
                startWatch = null
                player?.silence()
            }
            if (ownerEpoch == null && s.ownerId == me.id && (deviceAvailable || player?.snapshot()?.playing == true)) {
                val t = s.transfer
                ownerEpoch = s.epoch
                if (localControl != null) {
                    // What plays here is newer than the session, so it is published, never replaced.
                    localControl = null
                    lastPublished = null
                    t?.let { startedTransfer = it.id }
                } else if (t != null && t.status == TransferStatus.COMMITTED && t.to == me.id && t.epoch == s.epoch && startedTransfer != t.id) {
                    startedTransfer = t.id
                    log("Output handed to this device by ${t.from}")
                    startLocal(snap, s.playing, t.from)
                } else if (s.track != null && player?.snapshot()?.playing != true) {
                    // Reopening the previous output restores the account's bookmark silently.
                    // Do this before publishing, or an empty/stale local queue overwrites it.
                    startLocal(snap, play = false, from = null)
                }
                // Otherwise this client was the output before it restarted: it holds it again,
                // silently, and plays only when asked.
            }
        }

        val t = s?.transfer
        if (s != null && t != null && t.status.active) {
            val age = now - t.atMs
            val owner = ownerEpoch != null
            when {
                // The local player is in charge: the session must not start anything here.
                localControl != null && t.to == me.id -> Unit
                owner && t.epoch != s.epoch + 1 -> fail(t, "stale")
                t.to == me.id && t.status == TransferStatus.REQUESTED && t.id !in handledTransfers -> {
                    handledTransfers += t.id
                    if (player?.canPlay != true) fail(t, "unavailable") else beginPrepare(t)
                }
                // Preparing is local work; acknowledging it can fail transiently. Keep
                // retrying the acknowledgement instead of stranding a ready device.
                t.to == me.id && t.status == TransferStatus.REQUESTED && preparedTransfer == t.id && age <= REQUEST_TIMEOUT_MS -> {
                    if (s.ownerActive(now) && s.ownerId != me.id) markReady(t) else commitAsTarget(t, forced = false)
                }
                t.to == me.id && t.status == TransferStatus.READY && age > FORCE_COMMIT_MS -> commitAsTarget(t, forced = true)
                // A failed commit (offline) resumes the audio, which re-runs this at once: without a
                // pause between tries that was an endless silence/play loop. Retry every 2 s.
                owner && t.from == me.id && t.status == TransferStatus.READY &&
                    (commitTried?.first != t.id || localNow() - commitTried!!.second >= COMMIT_RETRY_MS) -> {
                    commitTried = t.id to localNow()
                    commitAsOwner(t)
                }
                t.status == TransferStatus.REQUESTED && age > REQUEST_TIMEOUT_MS && (owner || t.by == me.id) -> fail(t, "timeout")
                t.status == TransferStatus.READY && age > READY_TIMEOUT_MS && t.by == me.id -> fail(t, "timeout")
            }
        }

        val session = current?.session
        if (ownerEpoch != null && session != null) {
            val local = player?.snapshot()
            if (!deviceAvailable && local?.playing != true && local?.buffering != true && session.transfer?.status?.active != true) {
                releaseIdleOutput(session, local)
                return
            }
            applyCommands(session, clock.now())
            publish(force = false)
        } else if (ownerEpoch == null) {
            val p = player ?: return
            restoreLocalBookmark(current, p)
            warmRemoteSong(session, p)
            localControl?.let { syncLocalControl(it, session, p); return }
            if (preparing?.isActive == true || !p.snapshot().playing) return
            if (session != null && session.ownerActive(clock.now()) && session.ownerId != me.id) {
                // Never two outputs: something started audio here while another device has it.
                log("Local audio while ${session.ownerName} is the output; stopping it here")
                p.silence()
            } else if (localNow() >= writeRetryAtLocal) {
                claim(useSessionState = false)
            }
        }
    }

    /**
     * Tells the session about a control already carried out here. This is the only place local
     * playback and the session are reconciled: a control whose output is unchanged is the newest
     * thing the account did, so this device takes the output. It yields only to a device that
     * took the output since, or when it is silent and another device is playing.
     */
    private suspend fun syncLocalControl(control: LocalControl, session: ConnectSession?, p: ConnectPlayer) {
        val local = p.snapshot()
        if (local.track == null) {
            if (localNow() - control.atLocal > LOCAL_CONTROL_SETTLE_MS) localControl = null
            return
        }
        val elsewhere = session != null && session.ownerId != me.id && session.ownerActive(clock.now())
        val playingElsewhere = elsewhere && (session?.playing == true || session?.buffering == true)
        val moved = control.epoch != null && session != null && session.epoch != control.epoch
        if (playingElsewhere && (moved || !local.playing)) {
            log("${session?.ownerName} is the output; leaving the session to it")
            localControl = null
            if (local.playing) p.silence()
            return
        }
        if (localNow() < writeRetryAtLocal) return
        // Firestore's rules only let an output with a live lease be replaced through a handoff.
        if (elsewhere) {
            requestTransfer(me.id, reason = LOCAL_CONTROL)
            if (current?.session?.transfer?.let { it.to == me.id && it.status.active } != true) return
        }
        claim(useSessionState = false)
    }

    /** Paused hidden clients must not keep renewing a lease for an unavailable output. */
    private suspend fun releaseIdleOutput(session: ConnectSession, local: LocalPlayback?) {
        val epoch = ownerEpoch ?: return
        val now = clock.now()
        val written = update { snapshot ->
            val latest = snapshot.session ?: return@update null
            val latestLocal = player?.snapshot()
            if (deviceAvailable || latestLocal?.playing == true || latestLocal?.buffering == true) return@update null
            if (latest.ownerId != me.id || latest.epoch != epoch || latest.transfer?.status?.active == true) return@update null
            ConnectWrite(latest.copy(version = latest.version + 1, leaseUntilMs = 0L, playing = false,
                buffering = false, positionMs = local?.positionMs ?: session.positionMs,
                positionAtMs = now, updatedBy = me.id))
        }
        if (written != null) { ownerEpoch = null; lastPublished = null }
    }

    private data class RestoredBookmark(val owner: String, val epoch: Long, val track: ConnectTrack,
        val position: Long, val index: Int, val queue: ConnectQueue?, val shuffle: Boolean,
        val repeat: RepeatMode, val context: ConnectContext?)
    private var restoredBookmark: RestoredBookmark? = null

    /** Restore a resumable local player without claiming the account or starting audio. */
    private fun restoreLocalBookmark(snapshot: ConnectSnapshot?, p: ConnectPlayer) {
        val session = snapshot?.session ?: return
        val track = session.track ?: return
        if (!deviceAvailable || !p.canPlay || session.ownerId == me.id || localControl != null ||
            (session.ownerActive(clock.now()) && (session.playing || session.buffering)) ||
            session.transfer?.status?.active == true || preparing?.isActive == true || p.snapshot().playing) return
        val bookmark = RestoredBookmark(session.ownerId, session.epoch, track, session.positionMs,
            session.queueIndex, snapshot.queue, session.shuffle, session.repeat, session.context)
        if (restoredBookmark == bookmark) return
        restoredBookmark = bookmark
        startLocal(snapshot, play = false, from = null)
    }

    private fun window(local: LocalPlayback): Triple<List<ConnectTrack>, Int, Int> {
        val queue = local.queue.ifEmpty { listOfNotNull(local.track) }
        val index = if (local.queue.isEmpty()) (if (local.track != null) 0 else -1) else local.index
        if (queue.size <= MAX_QUEUE) return Triple(queue.map(ConnectTrack::from), 0, index)
        val start = (index - 20).coerceIn(0, queue.size - MAX_QUEUE)
        return Triple(queue.subList(start, start + MAX_QUEUE).map(ConnectTrack::from), start, index - start)
    }

    /** The session as this client's local playback describes it (version, lease and commands set). */
    private fun stateWrite(cur: ConnectSnapshot, s: ConnectSession, local: LocalPlayback, now: Long): ConnectWrite {
        val (tracks, offset, index) = window(local)
        val sameQueue = cur.queue != null && cur.queue.rev == s.queueRev && cur.queue.offset == offset && cur.queue.tracks == tracks
        val rev = if (sameQueue) s.queueRev else s.queueRev + 1
        val session = s.copy(
            version = s.version + 1,
            ownerName = myName(),
            ownerType = me.type,
            leaseUntilMs = now + LEASE_MS,
            track = local.track?.let { track ->
                val selected = local.lyricsSelection?.takeIf { it.trackId == track.id }
                ConnectTrack.from(track).copy(lyricsProvider = selected?.provider?.name, lyricsSpotifyId = selected?.spotifyId,
                    lyricsTitle = selected?.title, lyricsArtist = selected?.artist, lyricsDurationMs = selected?.durationMs)
            },
            playing = local.playing,
            buffering = local.buffering && local.playing,
            positionMs = local.positionMs.coerceAtLeast(0L),
            positionAtMs = now,
            durationMs = local.durationMs.coerceAtLeast(0L),
            queueRev = rev,
            queueIndex = index,
            shuffle = local.shuffle,
            repeat = local.repeat,
            context = local.context,
            commands = s.commands.filter { it.id !in applied },
            transfer = s.transfer?.takeUnless { !it.status.active && now - it.atMs > TRANSFER_KEEP_MS },
            updatedBy = me.id
        )
        return ConnectWrite(session, if (sameQueue) null else ConnectQueue(rev, tracks, offset))
    }

    private fun significant(a: LocalPlayback, b: LocalPlayback, elapsedMs: Long): Boolean {
        if (a.track?.id != b.track?.id || a.playing != b.playing || a.buffering != b.buffering) return true
        if (a.lyricsSelection != b.lyricsSelection) return true
        if (a.index != b.index || a.shuffle != b.shuffle || a.repeat != b.repeat || a.context != b.context) return true
        if (a.queue.size != b.queue.size || window(a).first.map { it.id } != window(b).first.map { it.id }) return true
        if (abs(a.durationMs - b.durationMs) > DRIFT_MS) return true
        val expected = if (a.playing && !a.buffering) a.positionMs + elapsedMs else a.positionMs
        return abs(b.positionMs - expected) > DRIFT_MS
    }

    private var publishedAlone = false

    private suspend fun publish(force: Boolean) {
        if (!force && localNow() < writeRetryAtLocal) return
        val p = player ?: return
        val epoch = ownerEpoch ?: return
        val local = p.snapshot()
        val localNowMs = localNow()
        val now = clock.now()
        val s = current?.session
        val last = lastPublished
        val seekRevision = localSeekRevision
        val explicitSeek = seekRevision != publishedSeekRevision
        val leaseDue = s == null || s.leaseUntilMs - now < LEASE_MS - RENEW_EVERY_MS
        val commandsDone = s?.commands?.any { it.id in applied } == true
        val transferOld = s?.transfer?.let { !it.status.active && now - it.atMs > TRANSFER_KEEP_MS } == true
        val changed = last == null || significant(last.local, local, localNowMs - last.atLocal)
        val checkpointDue = local.playing && last != null && localNowMs - last.atLocal >= CHECKPOINT_MS
        val alone = devices.none { it.id != me.id && available(it, now) }
        // A device that just appeared needs the real state at once, not the last bookmark.
        val arrived = publishedAlone && !alone
        publishedAlone = alone
        if (!arrived && alone && !force && !commandsDone && !transferOld && s != null && s.transfer?.status?.active != true && last != null) {
            // Most listeners have one device: these writes would be read by nobody.
            if (localNowMs - last.atLocal < SOLO_CHECKPOINT_MS || !(changed || local.playing)) return
        }
        if (!arrived && !force && !explicitSeek && !leaseDue && !commandsDone && !transferOld && !changed && !checkpointDue) return
        // Just after taking over, the song is still starting: don't publish its 0:00 as the position.
        if (!force && !commandsDone && localNowMs < settleUntilLocal && (local.buffering || local.track?.id != s?.track?.id)) return
        val written = update { cur ->
            val cs = cur.session ?: return@update null
            if (cs.ownerId != me.id || cs.epoch != epoch) null else stateWrite(cur, cs, local, now)
        }
        if (written != null) {
            lastPublished = Published(local, localNowMs)
            publishedSeekRevision = seekRevision
        }
    }

    private fun applyRealtimeSeeks(snapshot: ConnectSnapshot) {
        if (stopped) return
        val epoch = ownerEpoch ?: return
        val known = current?.session ?: return
        val s = snapshot.session ?: return
        val p = player ?: return
        if (s.version < known.version || s.ownerId != me.id || s.epoch != epoch ||
            !s.ownerActive(clock.now()) || s.transfer?.status?.active == true ||
            known.transfer?.status?.active == true || s.track?.id != p.snapshot().track?.id) return
        val pending = s.commands.filter { it.id !in applied && it.id !in realtimeSeeks }
        // Track/queue controls retain their strict ordering on the main state lock.
        if (pending.isEmpty() || pending.any { it.type != ConnectCommandType.SEEK }) return
        if (clock.now() - pending.last().atMs > COMMAND_TTL_MS) return
        pending.forEach { realtimeSeeks += it.id }
        while (realtimeSeeks.size > 200) realtimeSeeks.remove(realtimeSeeks.first())
        log("Applying realtime SEEK from ${pending.last().from}")
        p.seekTo(pending.last().positionMs.coerceAtLeast(0L))
    }

    private fun applyCommands(s: ConnectSession, now: Long) {
        val p = player ?: return
        val pending = s.commands.filter { it.id !in applied }
        if (pending.isEmpty()) return
        for (c in pending) {
            applied += c.id
            if (realtimeSeeks.remove(c.id)) continue
            if (now - c.atMs > COMMAND_TTL_MS) { log("Dropping a stale ${c.type} from ${c.from}"); continue }
            log("Applying ${c.type} from ${c.from}")
            execute(c, p)
        }
        while (applied.size > 200) applied.remove(applied.first())
    }

    private fun resolveIndex(c: ConnectCommand, local: LocalPlayback): Int {
        val q = current?.queue
        val guess = if (q != null && q.rev == c.queueRev) c.index + q.offset else c.index
        if (c.trackId.isEmpty()) return guess.takeIf { it in local.queue.indices } ?: -1
        if (local.queue.getOrNull(guess)?.id == c.trackId) return guess
        return local.queue.indices.filter { local.queue[it].id == c.trackId }.minByOrNull { abs(it - guess) } ?: -1
    }

    private fun execute(c: ConnectCommand, p: ConnectPlayer) {
        when (c.type) {
            ConnectCommandType.PLAY -> p.play()
            ConnectCommandType.PAUSE -> p.pause()
            ConnectCommandType.SEEK -> p.seekTo(c.positionMs.coerceAtLeast(0L))
            ConnectCommandType.NEXT -> p.next()
            ConnectCommandType.PREVIOUS -> p.previous()
            ConnectCommandType.PLAY_INDEX -> resolveIndex(c, p.snapshot()).takeIf { it >= 0 }?.let(p::playIndex)
            ConnectCommandType.LOAD -> if (c.tracks.isNotEmpty()) {
                val local = p.snapshot()
                p.load(c.tracks.map { it.toTrack() }, c.index.coerceIn(0, c.tracks.lastIndex), 0L, true, local.shuffle, local.repeat, c.context)
            }
            ConnectCommandType.SHUFFLE -> p.setShuffle(c.flag)
            ConnectCommandType.REPEAT -> p.setRepeat(c.repeat)
            ConnectCommandType.ADD -> c.tracks.firstOrNull()?.let { p.add(it.toTrack()) }
            ConnectCommandType.PLAY_NEXT -> c.tracks.firstOrNull()?.let { p.playNext(it.toTrack()) }
            ConnectCommandType.MOVE -> {
                val local = p.snapshot()
                val from = resolveIndex(c, local)
                val q = current?.queue
                val to = if (q != null && q.rev == c.queueRev) c.toIndex + q.offset else c.toIndex
                if (from >= 0 && to in local.queue.indices) p.move(from, to)
            }
            ConnectCommandType.REMOVE -> resolveIndex(c, p.snapshot()).takeIf { it >= 0 }?.let(p::remove)
        }
    }

    private fun newId(): String = me.id.take(6) + "-" + Random.nextLong().toULong().toString(36)

    private fun commandFor(intent: ConnectIntent, s: ConnectSession, now: Long): ConnectCommand {
        val base = ConnectCommand(id = newId(), type = ConnectCommandType.PLAY, from = me.id, atMs = now, queueRev = s.queueRev)
        return when (intent) {
            ConnectIntent.Play -> base
            ConnectIntent.Pause -> base.copy(type = ConnectCommandType.PAUSE)
            is ConnectIntent.Seek -> base.copy(type = ConnectCommandType.SEEK, positionMs = intent.positionMs)
            ConnectIntent.Next -> base.copy(type = ConnectCommandType.NEXT)
            ConnectIntent.Previous -> base.copy(type = ConnectCommandType.PREVIOUS)
            is ConnectIntent.PlayIndex -> base.copy(type = ConnectCommandType.PLAY_INDEX, index = intent.index, trackId = intent.trackId)
            is ConnectIntent.Load -> {
                // Only the queue window around the pick travels (the session holds at most MAX_QUEUE).
                val start = (intent.index - 20).coerceIn(0, (intent.tracks.size - MAX_QUEUE).coerceAtLeast(0))
                val tracks = intent.tracks.drop(start).take(MAX_QUEUE)
                base.copy(type = ConnectCommandType.LOAD, tracks = tracks.map(ConnectTrack::from), index = intent.index - start, context = intent.context)
            }
            is ConnectIntent.Shuffle -> base.copy(type = ConnectCommandType.SHUFFLE, flag = intent.on)
            is ConnectIntent.Repeat -> base.copy(type = ConnectCommandType.REPEAT, repeat = intent.mode)
            is ConnectIntent.Add -> base.copy(type = ConnectCommandType.ADD, tracks = listOf(ConnectTrack.from(intent.track)))
            is ConnectIntent.PlayNext -> base.copy(type = ConnectCommandType.PLAY_NEXT, tracks = listOf(ConnectTrack.from(intent.track)))
            is ConnectIntent.Move -> {
                val q = current?.queue
                base.copy(type = ConnectCommandType.MOVE, index = intent.from, toIndex = intent.to,
                    trackId = q?.tracks?.getOrNull(intent.from)?.id.orEmpty())
            }
            is ConnectIntent.Remove -> base.copy(type = ConnectCommandType.REMOVE, index = intent.index, trackId = intent.trackId)
        }
    }

    private fun optimisticFor(intent: ConnectIntent, s: ConnectSession, now: Long) {
        val at = localNow()
        optimistic = when (intent) {
            ConnectIntent.Play -> Optimistic(true, sessionPosition(s), at)
            ConnectIntent.Pause -> Optimistic(false, sessionPosition(s), at)
            is ConnectIntent.Seek -> Optimistic(null, intent.positionMs, at)
            else -> optimistic
        }
        refreshUi()
    }

    private fun sendLatestSeek(intent: ConnectIntent.Seek) {
        pendingSeek = intent to optimistic
        if (sendingSeek) return
        sendingSeek = true
        scope.launch {
            try {
                while (pendingSeek != null && !stopped) {
                    locked {
                        val latest = pendingSeek ?: return@locked
                        pendingSeek = null
                        sendCommand(latest.first, latest.second)
                    }
                }
            } finally { sendingSeek = false }
        }
    }

    /** False when the output changed before the control got there. */
    private suspend fun sendCommand(intent: ConnectIntent, feedback: Optimistic? = optimistic): Boolean {
        val now = clock.now()
        var sent: ConnectCommand? = null
        val written = update { cur ->
            val s = cur.session ?: return@update null
            if (!s.ownerActive(now) || s.ownerId == me.id) return@update null
            val cmd = commandFor(intent, s, now).also { sent = it }
            ConnectWrite(s.copy(version = s.version + 1, commands = (s.commands + cmd).takeLast(MAX_COMMANDS), updatedBy = me.id))
        }
        if (written != null) {
            // A later tap may already be on screen while this write is in flight.
            // Only the feedback for this command may be acknowledged by its result.
            if (optimistic === feedback) {
                feedback?.sentVersion = written.session?.version ?: Long.MAX_VALUE
                feedback?.commandId = sent?.id
            }
            log("Sent ${sent?.type} to ${written.session?.ownerName}")
        } else {
            if (optimistic === feedback) optimistic = null
            log("Control not sent: the output changed")
        }
        return written != null
    }

    /**
     * Makes this client the output. [useSessionState]: keep the session's song and position (taking
     * over a session whose output went away); otherwise publish what plays here now.
     */
    private suspend fun claim(useSessionState: Boolean): ConnectSnapshot? {
        val local = player?.snapshot() ?: return null
        val now = clock.now()
        val written = update { cur ->
            val s = cur.session
            val handoff = s?.transfer?.takeIf { it.to == me.id && it.status.active && it.epoch == s.epoch + 1 }
            if (s != null && s.ownerId != me.id && s.ownerActive(now) && handoff == null) return@update null
            val base = s ?: ConnectSession()
            val kept = base.ownerId == me.id && s != null
            val claimed = base.copy(
                ownerId = me.id, ownerName = myName(), ownerType = me.type,
                epoch = if (kept) base.epoch else base.epoch + 1,
                // Controls meant for the old output are not for this one.
                commands = if (kept) base.commands else emptyList(),
                transfer = handoff?.copy(status = TransferStatus.COMMITTED, atMs = now) ?: base.transfer?.takeIf { !it.status.active }
            )
            if (useSessionState) {
                ConnectWrite(claimed.copy(version = base.version + 1, leaseUntilMs = now + LEASE_MS, positionMs = sessionPosition(base),
                    positionAtMs = now, playing = false, buffering = false, updatedBy = me.id))
            } else stateWrite(cur.copy(session = claimed), claimed, local, now)
        }
        val session = written?.session ?: return null
        ownerEpoch = session.epoch
        localControl = null
        session.transfer?.takeIf { it.to == me.id && it.status == TransferStatus.COMMITTED }?.let { startedTransfer = it.id }
        lastPublished = if (useSessionState) null else Published(local, localNow())
        log("This device is now the output (epoch ${session.epoch})")
        return written
    }

    private fun executeLocalIntent(intent: ConnectIntent, session: ConnectSession, p: ConnectPlayer) {
        when (intent) {
            is ConnectIntent.Load -> p.load(intent.tracks, intent.index, 0L, true, session.shuffle, session.repeat, intent.context)
            else -> execute(commandFor(intent, session, clock.now()), p)
        }
    }

    private fun startLocal(snap: ConnectSnapshot, play: Boolean, from: String?) {
        val p = player ?: return
        val s = snap.session ?: return
        val track = s.track
        if (track == null) { p.silence(); return }
        val q = snap.queue?.takeIf { it.rev == s.queueRev && it.tracks.isNotEmpty() }
        var tracks = q?.tracks?.map { it.toTrack() } ?: listOf(track.toTrack())
        var index = if (q != null) s.queueIndex else 0
        if (tracks.getOrNull(index)?.id != track.id) {
            index = tracks.indexOfFirst { it.id == track.id }
            if (index < 0) { tracks = listOf(track.toTrack()); index = 0 }
        }
        val handoff = s.transfer?.let { it.status == TransferStatus.COMMITTED && it.to == me.id && it.epoch == s.epoch } == true
        p.load(tracks, index, if (handoff) s.positionMs else sessionPosition(s), play, s.shuffle, s.repeat, s.context)
        lastPublished = null
        settleUntilLocal = localNow() + SETTLE_MS
        startWatch = if (play) StartWatch(from, localNow()) else null
    }

    /** If the song never starts here after a handoff, hand the output back to where it came from. */
    private suspend fun watchStart() {
        val w = startWatch ?: return
        val p = player ?: return
        val elapsed = localNow() - w.atLocal
        val local = p.snapshot()
        if (elapsed > START_WATCH_MS || (local.playing && !local.buffering && local.error == null)) { startWatch = null; return }
        if (elapsed > 3_000L && local.error != null && ownerEpoch != null) {
            startWatch = null
            val back = w.from?.let { id -> devices.firstOrNull { it.id == id } }
            if (back != null && available(back, clock.now())) {
                log("Couldn't start here (${local.error}); handing back to ${back.name}")
                requestTransfer(back.id, reason = "restore")
            }
        }
    }

    private fun beginPrepare(t: ConnectTransfer) {
        val p = player ?: return
        val track = current?.session?.track?.toTrack()
        preparing?.cancel()
        preparedTransfer = null
        preparing = scope.launch {
            val ok = if (track == null) true else withTimeoutOrNull(PREPARE_TIMEOUT_MS) {
                try { p.prepare(track) } catch (e: CancellationException) { throw e } catch (_: Exception) { true }
            } ?: true
            locked {
                val s = current?.session ?: return@locked
                val tt = s.transfer
                if (tt?.id != t.id || tt.status != TransferStatus.REQUESTED) return@locked
                when {
                    !ok -> fail(t, "unplayable")
                    else -> {
                        preparedTransfer = t.id
                        if (s.ownerActive(clock.now()) && s.ownerId != me.id) markReady(t)
                        else commitAsTarget(t, forced = false)
                    }
                }
            }
        }
    }

    private suspend fun markReady(t: ConnectTransfer) {
        update { cur ->
            val s = cur.session ?: return@update null
            val tt = s.transfer
            if (tt?.id != t.id || tt.status != TransferStatus.REQUESTED) null
            else ConnectWrite(s.copy(version = s.version + 1, transfer = tt.copy(status = TransferStatus.READY, atMs = clock.now()), updatedBy = me.id))
        }
    }

    private suspend fun fail(t: ConnectTransfer, reason: String) {
        log("Handoff to ${t.toName} failed: $reason")
        update { cur ->
            val s = cur.session ?: return@update null
            val tt = s.transfer
            if (tt?.id != t.id || !tt.status.active) null
            else ConnectWrite(s.copy(version = s.version + 1, transfer = tt.copy(status = TransferStatus.FAILED, atMs = clock.now(), reason = reason), updatedBy = me.id))
        }
    }

    /** The output hands itself over: it stops first, so two devices never sound at once. */
    private suspend fun commitAsOwner(t: ConnectTransfer) {
        val p = player ?: return
        val epoch = ownerEpoch ?: return
        val local = p.snapshot()
        p.silence()
        val now = clock.now()
        val written = try {
            update { cur ->
                val s = cur.session ?: return@update null
                val tt = s.transfer
                if (s.ownerId != me.id || s.epoch != epoch || tt?.id != t.id || tt.status != TransferStatus.READY || tt.epoch != s.epoch + 1) return@update null
                // Handing back a song that wouldn't start here: it was meant to be playing.
                val w = stateWrite(cur, s, if (tt.reason == "restore") local.copy(playing = true, buffering = false) else local, now)
                w.copy(session = w.session.copy(ownerId = t.to, ownerName = t.toName, ownerType = t.toType, epoch = t.epoch,
                    leaseUntilMs = now + LEASE_MS, buffering = w.session.playing,
                    transfer = tt.copy(status = TransferStatus.COMMITTED, atMs = now)))
            }
        } catch (e: Exception) {
            if (local.playing) p.play()
            throw e
        }
        if (written != null) {
            ownerEpoch = null
            lastPublished = null
            log("Handed the output to ${t.toName}")
        } else if (ownerEpoch != null && current?.session?.ownerId == me.id && local.playing) {
            p.play() // the handoff didn't happen: keep playing here
        }
    }

    /** The target takes the output itself: no output is left, or the old one didn't answer. */
    private suspend fun commitAsTarget(t: ConnectTransfer, forced: Boolean) {
        val now = clock.now()
        val written = update { cur ->
            val s = cur.session ?: return@update null
            val tt = s.transfer
            if (tt?.id != t.id || !tt.status.active) return@update null
            val ownerActive = s.ownerActive(now) && s.ownerId != me.id
            if (ownerActive && !forced) return@update null
            // Ownership moved since the request: this handoff can no longer happen.
            if (tt.epoch != s.epoch + 1) return@update ConnectWrite(s.copy(version = s.version + 1,
                transfer = tt.copy(status = TransferStatus.FAILED, atMs = now, reason = "stale"), updatedBy = me.id))
            ConnectWrite(s.copy(
                version = s.version + 1, ownerId = me.id, ownerName = myName(), ownerType = me.type, epoch = tt.epoch,
                leaseUntilMs = now + LEASE_MS, positionMs = sessionPosition(s), positionAtMs = now,
                playing = s.playing && (ownerActive || s.ownerActive(now)), buffering = false,
                transfer = tt.copy(status = TransferStatus.COMMITTED, atMs = now), updatedBy = me.id
            ))
        } ?: return
        val session = written.session ?: return
        if (session.ownerId != me.id) return
        ownerEpoch = session.epoch
        startedTransfer = t.id
        log(if (forced) "Took the output after ${t.from} didn't answer" else "Took the output (no other output)")
        startLocal(written, session.playing, t.from.takeIf { it.isNotEmpty() })
    }

    private suspend fun requestTransfer(targetId: String, reason: String = "") {
        val now = clock.now()
        val target = if (targetId == me.id) me else devices.firstOrNull { it.id == targetId } ?: return
        if (target.id != me.id && !available(target, now)) return
        if (target.id == me.id && player?.canPlay != true) return
        val label = labels()[target.id] ?: ConnectDeviceNames.base(target)
        // A pull has an extra request/commit round trip. Resolve in parallel with that request,
        // rather than waiting for its acknowledgement before starting the returning output.
        if (target.id == me.id && reason != LOCAL_CONTROL) {
            val track = current?.session?.track?.toTrack()
            val p = player
            if (track != null && p != null) scope.launch { runCatching { p.prepare(track) } }
        }
        // The output moving its own audio hands over in one write. Waiting for the target's
        // "ready" and then committing took 3-4 s of a 7 s switch (seen PC -> phone).
        val owned = ownerEpoch
        if (owned != null && target.id != me.id) { handOver(target, label, owned, reason); return }
        val written = update { cur ->
            val s = cur.session ?: ConnectSession()
            if (s.ownerId == target.id && (s.ownerActive(now) || (target.id == me.id && ownerEpoch != null))) return@update null
            if (s.transfer?.status?.active == true && s.transfer.to == target.id) return@update null
            // Asking for this device: it already knows it can play, so the request is its "ready"
            // and the output can hand over the moment it sees it.
            val ready = target.id == me.id && s.ownerActive(now) && s.ownerId != me.id
            val t = ConnectTransfer(newId(), s.ownerId, target.id, label, target.type, s.epoch + 1,
                if (ready) TransferStatus.READY else TransferStatus.REQUESTED, now, me.id, reason)
            ConnectWrite(s.copy(version = s.version + 1, transfer = t, updatedBy = me.id))
        } ?: return
        written.session?.transfer?.takeIf { it.to == me.id }?.let { t ->
            handledTransfers += t.id
            preparedTransfer = t.id
        }
        log("Asked to move the output to $label")
    }

    /** This output hands its audio to [target] directly: it stops first, then gives it the output. */
    private suspend fun handOver(target: ConnectDevice, label: String, epoch: Long, reason: String) {
        val p = player ?: return
        val local = p.snapshot()
        p.silence()
        val now = clock.now()
        val written = try {
            update { cur ->
                val s = cur.session ?: return@update null
                if (s.ownerId != me.id || s.epoch != epoch) return@update null
                // Handing back a song that wouldn't start here: it was meant to be playing.
                val w = stateWrite(cur, s, if (reason == "restore") local.copy(playing = true, buffering = false) else local, now)
                val t = ConnectTransfer(newId(), me.id, target.id, label, target.type, epoch + 1, TransferStatus.COMMITTED, now, me.id, reason)
                // A short lease: the target renews it with its first write. If it never answers
                // (closed, asleep, offline) the lease lapses and this device takes the audio back.
                w.copy(session = w.session.copy(ownerId = target.id, ownerName = label, ownerType = target.type,
                    epoch = epoch + 1, leaseUntilMs = now + DIRECT_LEASE_MS,
                    buffering = w.session.playing, transfer = t))
            }
        } catch (e: Exception) {
            if (local.playing) p.play()
            throw e
        }
        if (written != null) {
            ownerEpoch = null
            lastPublished = null
            handedOver = HandedOver(target.id, label, epoch + 1, local.playing)
            log("Handed the output to $label")
        } else if (ownerEpoch != null && current?.session?.ownerId == me.id && local.playing) {
            p.play() // the handoff didn't happen: keep playing here
        }
    }

    /** The other device's song this one last warmed, so a handoff here can start at once. */
    private var warmedSong: String? = null

    /**
     * While another device plays, look up its current song's stream here in the background (once
     * per song, after it has been playing a moment). Without it a handoff to this device started
     * only after the handoff, by fetching the stream: PC -> phone was the slow direction.
     */
    private fun warmRemoteSong(session: ConnectSession?, p: ConnectPlayer) {
        val track = session?.track ?: return
        if (!p.canPlay || !session.ownerActive(clock.now()) || (!session.playing && !session.buffering) ||
            session.ownerId == me.id || track.id == warmedSong) return
        warmedSong = track.id
        scope.launch {
            kotlinx.coroutines.delay(REMOTE_WARM_DELAY_MS)
            if (current?.session?.track?.id == track.id && ownerEpoch == null)
                runCatching { p.prepare(track.toTrack()) }
        }
    }

    /** The handoff this output last tried to commit, and when. */
    private var commitTried: Pair<String, Long>? = null

    /** A direct handoff this device made, until the target answers or its short lease lapses. */
    private data class HandedOver(val to: String, val label: String, val epoch: Long, val wasPlaying: Boolean)
    private var handedOver: HandedOver? = null

    private suspend fun watchHandOver() {
        val h = handedOver ?: return
        val s = current?.session
        when {
            s == null || s.epoch != h.epoch || s.ownerId != h.to || s.updatedBy == h.to -> handedOver = null
            !s.ownerActive(clock.now()) -> {
                handedOver = null
                log("${h.label} never picked up the audio; playing here again")
                if (claim(useSessionState = true) != null && h.wasPlaying) player?.play()
            }
        }
    }

    private suspend fun heartbeatNow() = presenceLock.withLock {
        if (stopped) return@withLock
        lastHeartbeatLocal = localNow()
        try {
            backend.heartbeat(me.copy(canPlay = player?.canPlay == true, online = deviceAvailable))
            writesFailing = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            writesFailing = true
            lastHeartbeatLocal = localNow() - HEARTBEAT_MS + 60_000L // retry in a minute
            onConnectionLost(e.message)
        }
    }

    // ---- UI ------------------------------------------------------------------------------------

    private fun myName(): String = ConnectDeviceNames.base(me)

    private fun available(d: ConnectDevice, now: Long): Boolean =
        d.online && d.canPlay && (d.lastSeenMs == 0L || now - d.lastSeenMs < ONLINE_WINDOW_MS)

    private fun labels(): Map<String, String> = ConnectDeviceNames.labels(listOf(me) + devices.filter { it.id != me.id })

    private fun refreshUi() {
        if (stopped) return
        val now = clock.now()
        val localNowMs = localNow()
        val s = current?.session
        val labels = labels()
        val t = s?.transfer
        val asked = requested?.takeIf { localNowMs - it.second < REQUEST_TIMEOUT_MS }
        val unsent = failedRequest?.takeIf { localNowMs - it.second < TRANSFER_KEEP_MS }
        val transferUi = when {
            t != null && t.status.active -> ConnectTransferUi(t.to, labels[t.to] ?: t.toName, failed = false)
            asked != null -> ConnectTransferUi(asked.first, labels[asked.first] ?: "", failed = false)
            t != null && t.status == TransferStatus.FAILED && now - t.atMs < TRANSFER_KEEP_MS && (t.by == me.id || t.to == me.id) ->
                ConnectTransferUi(t.to, labels[t.to] ?: t.toName, failed = true, reason = t.reason)
            unsent != null -> ConnectTransferUi(unsent.first, labels[unsent.first] ?: "", failed = true, reason = "unreachable")
            else -> null
        }
        val localDefault = player?.canPlay == true && (localControl != null || (deviceAvailable &&
            (s == null || !s.ownerActive(now) || (!s.playing && !s.buffering)) && s?.transfer?.status?.active != true))
        val output = if (localDefault) ConnectOutput(me.id, myName(), me.type, true, true,
            player?.snapshot()?.playing == true) else s?.takeIf { it.ownerId.isNotEmpty() }?.let {
            val active = it.ownerActive(now) || (it.ownerId == me.id && ownerEpoch != null)
            ConnectOutput(it.ownerId, labels[it.ownerId] ?: it.ownerName, it.ownerType, it.ownerId == me.id, active,
                if (it.ownerId == me.id) player?.snapshot()?.playing == true else it.playing && active)
        }
        val visible = devices.filter { it.id != me.id && (it.lastSeenMs == 0L || now - it.lastSeenMs < RECENT_WINDOW_MS) }
        val rows = (listOf(me.copy(canPlay = player?.canPlay == true)) + visible).map { d ->
            val isThis = d.id == me.id
            val state = when {
                transferUi != null && transferUi.deviceId == d.id && !transferUi.failed -> ConnectRowState.CONNECTING
                isThis && d.canPlay -> if (transferUi?.deviceId == d.id && transferUi.failed) ConnectRowState.FAILED else ConnectRowState.IDLE
                !isThis && available(d, now) -> if (transferUi?.deviceId == d.id && transferUi.failed) ConnectRowState.FAILED else ConnectRowState.IDLE
                else -> ConnectRowState.UNAVAILABLE
            }
            ConnectDeviceRow(d, labels[d.id] ?: ConnectDeviceNames.base(d), isThis, output?.deviceId == d.id, state)
        }.sortedWith(compareByDescending<ConnectDeviceRow> { it.isThis }.thenByDescending { it.isOutput }
            .thenBy { it.state == ConnectRowState.UNAVAILABLE }.thenByDescending { it.device.lastSeenMs })

        val remote = if (s != null && ownerEpoch == null && s.ownerId != me.id && s.track != null && output != null && !output.isThis) {
            val q = current?.queue?.takeIf { it.rev == s.queueRev }
            val queue = q?.tracks?.map { it.toTrack() } ?: listOf(s.track.toTrack())
            val index = if (q != null) s.queueIndex.coerceIn(-1, queue.lastIndex) else 0
            val active = output.active
            val o = optimistic
            val playing = o?.playing ?: (s.playing && active)
            val position = o?.positionMs?.let { it + if (playing) (localNowMs - o.atLocal).coerceAtLeast(0L) else 0L } ?: sessionPosition(s)
            ConnectRemoteView(
                track = s.track.toTrack(),
                queue = queue,
                index = index,
                playing = playing,
                buffering = s.buffering && active,
                positionMs = position,
                anchorLocalMs = localNowMs,
                durationMs = s.durationMs.takeIf { it > 0L } ?: (s.track.durationSec * 1000L),
                shuffle = s.shuffle,
                repeat = s.repeat,
                context = s.context,
                output = output,
                lyricsSelection = s.track.lyricsProvider?.let { name ->
                    com.auralis.music.domain.model.LyricsProvider.entries.firstOrNull { it.name == name }
                }?.let { ConnectLyricsSelection(s.track.id, it, s.track.lyricsSpotifyId, s.track.lyricsTitle, s.track.lyricsArtist, s.track.lyricsDurationMs) }
            )
        } else null

        _ui.value = ConnectUi(
            enabled = true, connected = connected, me = me, devices = rows, output = output,
            remote = remote, transfer = transferUi, error = error
        )
    }
}
