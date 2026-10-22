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
    val error: String? = null
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
    val output: ConnectOutput
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
        const val HEARTBEAT_MS = 60_000L
        const val ONLINE_WINDOW_MS = 150_000L
        const val RECENT_WINDOW_MS = 14L * 86_400_000L
        const val FORGET_AFTER_MS = 30L * 86_400_000L
        const val COMMAND_TTL_MS = 30_000L
        const val PREPARE_TIMEOUT_MS = 6_000L
        const val REQUEST_TIMEOUT_MS = 15_000L
        const val FORCE_COMMIT_MS = 5_000L
        const val READY_TIMEOUT_MS = 12_000L
        const val TRANSFER_KEEP_MS = 8_000L
        const val DRIFT_MS = 1_500L
        const val SETTLE_MS = 4_000L
        const val OPTIMISTIC_MS = 6_000L
        const val START_WATCH_MS = 20_000L
    }

    var me: ConnectDevice = device
        private set
    private val lock = Mutex()
    @Volatile private var current: ConnectSnapshot? = null
    /** The epoch this client holds the output for; null when it is not the output. */
    @Volatile private var ownerEpoch: Long? = null
    private var devices: List<ConnectDevice> = emptyList()
    private val applied = LinkedHashSet<String>()
    private val handledTransfers = LinkedHashSet<String>()
    private var startedTransfer: String? = null
    private var preparing: Job? = null

    private data class Published(val local: LocalPlayback, val atLocal: Long)
    private var lastPublished: Published? = null
    private var settleUntilLocal = 0L

    private data class Optimistic(val playing: Boolean?, val positionMs: Long?, val atLocal: Long, var sentVersion: Long = Long.MAX_VALUE)
    @Volatile private var optimistic: Optimistic? = null

    private data class StartWatch(val from: String?, val atLocal: Long)
    private var startWatch: StartWatch? = null

    private var lastHeartbeatLocal = Long.MIN_VALUE / 2
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
        scope.launch { locked { accept(snapshot); connected = true; error = null } }
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

    /** Connection trouble reported by the platform (listener error, failed poll). */
    fun onConnectionLost(message: String?) {
        connected = false
        error = message
        scope.launch { lock.withLock { refreshUi() } }
    }

    /** Called about once a second: lease renewal, heartbeats, handoff timeouts. */
    fun tick() {
        if (stopped) return
        if (localNow() - lastHeartbeatLocal >= HEARTBEAT_MS) scope.launch { heartbeatNow() }
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
        scope.launch { locked { requestTransfer(deviceId) } }
    }

    /**
     * Called by the local player for a user control. Returns true when Connect handled it (sent it
     * to the output on another device, or took the output over first); false lets the local player act.
     */
    fun intercept(intent: ConnectIntent): Boolean {
        if (stopped || ownerEpoch != null) return false
        val s = current?.session ?: return false
        if (s.ownerId == me.id) return false
        val now = clock.now()
        if (s.ownerActive(now)) {
            if (s.track == null && intent !is ConnectIntent.Load) return false
            optimisticFor(intent, s, now)
            scope.launch { locked { sendCommand(intent) } }
            return true
        }
        if (s.track == null) return false
        return when (intent) {
            // A new pick plays here; this client becomes the output as its audio starts.
            is ConnectIntent.Load -> false
            ConnectIntent.Pause -> true
            else -> {
                scope.launch { locked { takeOver(intent) } }
                true
            }
        }
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
                        ConnectWrite(s.copy(version = s.version + 1, leaseUntilMs = 0L, playing = false, buffering = false,
                            positionMs = local?.positionMs ?: s.positionAt(now), positionAtMs = now,
                            transfer = s.transfer?.takeIf { !it.status.active }, updatedBy = me.id))
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

    private fun accept(snapshot: ConnectSnapshot) {
        val known = current?.session?.version ?: -1L
        val incoming = snapshot.session?.version ?: 0L
        if (incoming < known) return // older than what this client already saw
        val s = snapshot.session
        // Keep the queue we have when the new snapshot's queue hasn't arrived yet but ours matches.
        val queue = snapshot.queue?.takeIf { s == null || it.rev == s.queueRev }
            ?: current?.queue?.takeIf { s != null && it.rev == s.queueRev }
        current = ConnectSnapshot(s, queue)
        optimistic?.let { o -> if (s != null && s.version > o.sentVersion && s.updatedBy == s.ownerId) optimistic = null }
    }

    private suspend fun refetch() {
        accept(backend.fetch())
        connected = true
        error = null
    }

    /** Captures the newest server state seen inside [backend.update], whatever the block decided. */
    private suspend fun update(block: (ConnectSnapshot) -> ConnectWrite?): ConnectSnapshot? {
        var seen: ConnectSnapshot? = null
        val written = backend.update { cur -> seen = cur; block(cur) }
        connected = true
        error = null
        if (written != null) accept(written) else seen?.let(::accept)
        return written
    }

    private suspend fun process() {
        for (round in 0 until 3) {
            val before = current?.session?.version
            processOnce()
            if (current?.session?.version == before) return
        }
    }

    private suspend fun processOnce() {
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
            if (ownerEpoch == null && s.ownerId == me.id) {
                val t = s.transfer
                ownerEpoch = s.epoch
                if (t != null && t.status == TransferStatus.COMMITTED && t.to == me.id && t.epoch == s.epoch && startedTransfer != t.id) {
                    startedTransfer = t.id
                    log("Output handed to this device by ${t.from}")
                    startLocal(snap, s.playing, t.from)
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
                owner && t.epoch != s.epoch + 1 -> fail(t, "stale")
                t.to == me.id && t.status == TransferStatus.REQUESTED && t.id !in handledTransfers -> {
                    handledTransfers += t.id
                    if (player?.canPlay != true) fail(t, "unavailable") else beginPrepare(t)
                }
                t.to == me.id && t.status == TransferStatus.READY && age > FORCE_COMMIT_MS -> commitAsTarget(t, forced = true)
                owner && t.from == me.id && t.status == TransferStatus.READY -> commitAsOwner(t)
                t.status == TransferStatus.REQUESTED && age > REQUEST_TIMEOUT_MS && (owner || t.by == me.id) -> fail(t, "timeout")
                t.status == TransferStatus.READY && age > READY_TIMEOUT_MS && t.by == me.id -> fail(t, "timeout")
            }
        }

        val session = current?.session
        if (ownerEpoch != null && session != null) {
            applyCommands(session, clock.now())
            publish(force = false)
        } else if (ownerEpoch == null) {
            val p = player ?: return
            if (preparing?.isActive == true || !p.snapshot().playing) return
            if (session != null && session.ownerActive(clock.now()) && session.ownerId != me.id) {
                // Never two outputs: something started audio here while another device has it.
                log("Local audio while ${session.ownerName} is the output; stopping it here")
                p.silence()
            } else {
                claim(useSessionState = false)
            }
        }
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
            track = local.track?.let(ConnectTrack::from),
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
        if (a.index != b.index || a.shuffle != b.shuffle || a.repeat != b.repeat || a.context != b.context) return true
        if (a.queue.size != b.queue.size || window(a).first.map { it.id } != window(b).first.map { it.id }) return true
        if (abs(a.durationMs - b.durationMs) > DRIFT_MS) return true
        val expected = if (a.playing && !a.buffering) a.positionMs + elapsedMs else a.positionMs
        return abs(b.positionMs - expected) > DRIFT_MS
    }

    private suspend fun publish(force: Boolean) {
        val p = player ?: return
        val epoch = ownerEpoch ?: return
        val local = p.snapshot()
        val localNowMs = localNow()
        val now = clock.now()
        val s = current?.session
        val last = lastPublished
        val leaseDue = s == null || s.leaseUntilMs - now < LEASE_MS - RENEW_EVERY_MS
        val commandsDone = s?.commands?.any { it.id in applied } == true
        val transferOld = s?.transfer?.let { !it.status.active && now - it.atMs > TRANSFER_KEEP_MS } == true
        val changed = last == null || significant(last.local, local, localNowMs - last.atLocal)
        if (!force && !leaseDue && !commandsDone && !transferOld && !changed) return
        // Just after taking over, the song is still starting: don't publish its 0:00 as the position.
        if (!force && !commandsDone && localNowMs < settleUntilLocal && (local.buffering || local.track?.id != s?.track?.id) && !leaseDue) return
        val written = update { cur ->
            val cs = cur.session ?: return@update null
            if (cs.ownerId != me.id || cs.epoch != epoch) null else stateWrite(cur, cs, local, now)
        }
        if (written != null) lastPublished = Published(local, localNowMs)
    }

    private fun applyCommands(s: ConnectSession, now: Long) {
        val p = player ?: return
        val pending = s.commands.filter { it.id !in applied }
        if (pending.isEmpty()) return
        for (c in pending) {
            applied += c.id
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
            ConnectIntent.Play -> Optimistic(true, s.positionAt(now), at)
            ConnectIntent.Pause -> Optimistic(false, s.positionAt(now), at)
            is ConnectIntent.Seek -> Optimistic(null, intent.positionMs, at)
            else -> optimistic
        }
        refreshUi()
    }

    private suspend fun sendCommand(intent: ConnectIntent) {
        val now = clock.now()
        var sent: ConnectCommand? = null
        val written = update { cur ->
            val s = cur.session ?: return@update null
            if (!s.ownerActive(now) || s.ownerId == me.id) return@update null
            val cmd = commandFor(intent, s, now).also { sent = it }
            ConnectWrite(s.copy(version = s.version + 1, commands = (s.commands + cmd).takeLast(MAX_COMMANDS), updatedBy = me.id))
        }
        if (written != null) {
            optimistic?.sentVersion = written.session?.version ?: Long.MAX_VALUE
            log("Sent ${sent?.type} to ${written.session?.ownerName}")
        } else {
            optimistic = null
            log("Control not sent: the output changed")
        }
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
            if (s != null && s.ownerId != me.id && s.ownerActive(now)) return@update null
            val base = s ?: ConnectSession()
            val claimed = base.copy(
                ownerId = me.id, ownerName = myName(), ownerType = me.type,
                epoch = if (base.ownerId == me.id && s != null) base.epoch else base.epoch + 1,
                transfer = base.transfer?.takeIf { !it.status.active }
            )
            if (useSessionState) {
                ConnectWrite(claimed.copy(version = base.version + 1, leaseUntilMs = now + LEASE_MS, positionMs = base.positionAt(now),
                    positionAtMs = now, playing = false, buffering = false, updatedBy = me.id))
            } else stateWrite(cur.copy(session = claimed), claimed, local, now)
        }
        val session = written?.session ?: return null
        ownerEpoch = session.epoch
        lastPublished = if (useSessionState) null else Published(local, localNow())
        log("This device is now the output (epoch ${session.epoch})")
        return written
    }

    private suspend fun takeOver(intent: ConnectIntent) {
        val s = current?.session ?: return
        if (s.ownerActive(clock.now()) && s.ownerId != me.id) { sendCommand(intent); return }
        val written = claim(useSessionState = true) ?: run { sendCommand(intent); return }
        startLocal(written, play = intent == ConnectIntent.Play, from = null)
        val p = player ?: return
        when (intent) {
            ConnectIntent.Play, ConnectIntent.Pause -> Unit
            is ConnectIntent.Load -> p.load(intent.tracks, intent.index, 0L, true, s.shuffle, s.repeat, intent.context)
            else -> execute(commandFor(intent, written.session ?: s, clock.now()), p)
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
        p.load(tracks, index, s.positionAt(clock.now()), play, s.shuffle, s.repeat, s.context)
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
                    s.ownerActive(clock.now()) && s.ownerId != me.id -> markReady(t)
                    else -> commitAsTarget(t, forced = false)
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
                    leaseUntilMs = now + LEASE_MS, transfer = tt.copy(status = TransferStatus.COMMITTED, atMs = now)))
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
                leaseUntilMs = now + LEASE_MS, positionMs = s.positionAt(now), positionAtMs = now,
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
        update { cur ->
            val s = cur.session ?: ConnectSession()
            if (s.ownerId == target.id && (s.ownerActive(now) || (target.id == me.id && ownerEpoch != null))) return@update null
            if (s.transfer?.status?.active == true && s.transfer.to == target.id) return@update null
            val t = ConnectTransfer(newId(), s.ownerId, target.id, label, target.type, s.epoch + 1, TransferStatus.REQUESTED, now, me.id, reason)
            ConnectWrite(s.copy(version = s.version + 1, transfer = t, updatedBy = me.id))
        }?.let { log("Asked to move the output to $label") }
    }

    private suspend fun heartbeatNow() {
        lastHeartbeatLocal = localNow()
        try {
            backend.heartbeat(me.copy(canPlay = player?.canPlay == true, online = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastHeartbeatLocal = localNow() - HEARTBEAT_MS + 10_000L // retry in ten seconds
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
        val transferUi = when {
            t != null && t.status.active -> ConnectTransferUi(t.to, labels[t.to] ?: t.toName, failed = false)
            t != null && t.status == TransferStatus.FAILED && now - t.atMs < TRANSFER_KEEP_MS && (t.by == me.id || t.to == me.id) ->
                ConnectTransferUi(t.to, labels[t.to] ?: t.toName, failed = true, reason = t.reason)
            else -> null
        }
        val output = s?.takeIf { it.ownerId.isNotEmpty() }?.let {
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

        val remote = if (s != null && ownerEpoch == null && s.ownerId != me.id && s.track != null && output != null) {
            val q = current?.queue?.takeIf { it.rev == s.queueRev }
            val queue = q?.tracks?.map { it.toTrack() } ?: listOf(s.track.toTrack())
            val index = if (q != null) s.queueIndex.coerceIn(-1, queue.lastIndex) else 0
            val active = output.active
            val o = optimistic
            val playing = o?.playing ?: (s.playing && active)
            val position = o?.positionMs?.let { it + if (playing) (localNowMs - o.atLocal).coerceAtLeast(0L) else 0L } ?: s.positionAt(now)
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
                output = output
            )
        } else null

        _ui.value = ConnectUi(
            enabled = true, connected = connected, me = me, devices = rows, output = output,
            remote = remote, transfer = transferUi, error = error
        )
    }
}
