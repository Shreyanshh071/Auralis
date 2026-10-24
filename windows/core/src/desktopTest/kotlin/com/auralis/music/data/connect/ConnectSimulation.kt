package com.auralis.music.data.connect

import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

/**
 * An in-memory stand-in for one account's Firestore documents. Every write goes through the same
 * checks firestore.rules makes (version + 1, fenced owner changes), so a test fails if the engine
 * ever writes something the real rules would refuse.
 */
class FakeServer(private val now: () -> Long) {
    private var session: Map<String, Any?>? = null
    private var queue: Map<String, Any?>? = null
    val devices = LinkedHashMap<String, ConnectDevice>()
    val violations = mutableListOf<String>()
    val listeners = mutableListOf<SimDevice>()
    var writes = 0

    val version: Long get() = (session?.get("version") as? Number)?.toLong() ?: 0L

    fun snapshot(): ConnectSnapshot = ConnectSnapshot(
        session?.let(ConnectCodec::decodeSession), queue?.let(ConnectCodec::decodeQueue)
    )

    private fun stamp(m: Map<String, Any?>): Map<String, Any?> = m.mapValues { (_, v) -> if (v === ConnectServerTime) now() else v }

    fun write(w: ConnectWrite): Boolean {
        val before = session?.let(ConnectCodec::decodeSession)
        val after = w.session
        val problem = when {
            before == null && after.version != 1L -> "create with version ${after.version}"
            before != null && after.version != before.version + 1 -> "version ${before.version} -> ${after.version}"
            before != null && (after.ownerId != before.ownerId || after.epoch != before.epoch) && !ownerChangeAllowed(before, after) ->
                "owner ${before.ownerId}@${before.epoch} -> ${after.ownerId}@${after.epoch} by ${after.updatedBy}"
            before == null && after.ownerId.isNotEmpty() && after.epoch != 1L -> "create with epoch ${after.epoch}"
            w.queue != null && w.queue.rev != after.queueRev -> "queue rev ${w.queue.rev} != ${after.queueRev}"
            after.commands.size > MAX_COMMANDS -> "too many commands"
            else -> null
        }
        if (problem != null) {
            violations += problem
            throw IllegalStateException("PERMISSION_DENIED: $problem")
        }
        session = stamp(ConnectCodec.encodeSession(after))
        w.queue?.let { queue = ConnectCodec.encodeQueue(it) }
        writes++
        return true
    }

    private fun ownerChangeAllowed(before: ConnectSession, after: ConnectSession): Boolean {
        if (after.epoch != before.epoch + 1) return false
        val t = before.transfer
        return before.ownerId.isEmpty() || before.leaseUntilMs < now() ||
            (t != null && t.status.active && t.to == after.ownerId && t.epoch == after.epoch) ||
            after.updatedBy == before.ownerId
    }

    fun heartbeat(d: ConnectDevice) {
        devices[d.id] = d.copy(lastSeenMs = now(), createdMs = devices[d.id]?.createdMs ?: d.createdMs)
    }
}

/** A fake player: plays songs of [Track.duration] seconds, advances its queue at the end. */
class FakePlayer(private val clock: () -> Long) : ConnectPlayer {
    var queue: List<Track> = emptyList()
    var index = -1
    var playing = false
    var shuffled = false
    var repeatMode = RepeatMode.OFF
    var context: ConnectContext? = null
    private var position = 0L
    private var anchor = 0L
    var playable = true
    var failStart = false
    var onChange: () -> Unit = {}
    override var canPlay: Boolean = true

    val track: Track? get() = queue.getOrNull(index)
    val positionMs: Long get() = if (playing) position + (clock() - anchor) else position

    private fun changed() { onChange() }
    private fun setPosition(ms: Long) { position = ms; anchor = clock() }

    override fun snapshot() = LocalPlayback(
        track = track, queue = queue, index = index, playing = playing, buffering = false,
        positionMs = positionMs, durationMs = (track?.duration ?: 0L) * 1000L, shuffle = shuffled, repeat = repeatMode,
        context = context, error = if (failStart && !playing && track != null) "stream failed" else null
    )

    var lastLoadedPositionMs: Long? = null
    override fun load(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean, shuffle: Boolean, repeat: RepeatMode, context: ConnectContext?) {
        lastLoadedPositionMs = positionMs
        queue = tracks; this.index = index; this.shuffled = shuffle; this.repeatMode = repeat; this.context = context
        setPosition(positionMs)
        playing = play && !failStart
        changed()
    }

    override fun silence() { if (playing) { setPosition(positionMs); playing = false; changed() } }
    override suspend fun prepare(track: Track): Boolean { delay(300); return playable }
    override fun play() { if (track != null && !failStart) { setPosition(positionMs); playing = true; changed() } }
    override fun pause() { setPosition(positionMs); playing = false; changed() }
    override fun seekTo(positionMs: Long) { setPosition(positionMs); changed() }
    override fun next() {
        if (index < queue.lastIndex) { index++; setPosition(0) } else if (repeatMode == RepeatMode.ALL && queue.isNotEmpty()) { index = 0; setPosition(0) } else playing = false
        changed()
    }
    override fun previous() { if (positionMs > 3000 || index <= 0) setPosition(0) else { index--; setPosition(0) }; changed() }
    override fun playIndex(index: Int) { this.index = index; setPosition(0); playing = true; changed() }
    override fun setShuffle(on: Boolean) { shuffled = on; changed() }
    override fun setRepeat(mode: RepeatMode) { repeatMode = mode; changed() }
    override fun add(track: Track) { queue = queue + track; changed() }
    override fun playNext(track: Track) { queue = queue.toMutableList().apply { add(index + 1, track) }; changed() }
    override fun move(from: Int, to: Int) {
        val order = queue.indices.toMutableList().apply { add(to, removeAt(from)) }
        val playingId = track?.id
        queue = order.map { queue[it] }; index = queue.indexOfFirst { it.id == playingId }; changed()
    }
    override fun remove(index: Int) { if (index != this.index) { queue = queue.filterIndexed { i, _ -> i != index }; if (index < this.index) this.index--; changed() } }

    /** Called every second: a song that reached its end moves on, like the real players. */
    fun advance() {
        val t = track ?: return
        if (playing && positionMs >= t.duration * 1000L) {
            if (repeatMode == RepeatMode.ONE) setPosition(0) else next()
            changed()
        }
    }
}

/** One client: its engine, player and the backend connection to [server]. */
class SimDevice(
    val server: FakeServer,
    val scope: CoroutineScope,
    val testScope: TestScope,
    id: String,
    name: String,
    platform: ConnectPlatform,
    type: ConnectDeviceType,
    private val skewMs: Long = 0L,
    /** How long server changes take to reach this client (a listener, or polling on Windows). */
    val latencyMs: Long = 100L,
    private val roundTripMs: Long = 80L
) {
    val localNow: () -> Long = { testScope.testScheduler.currentTime + skewMs }
    val player = FakePlayer(localNow)
    var offline = false
    var rejectNextReady = false
    val log = mutableListOf<String>()
    val clock = ServerClock(localNow)
    private val serverNow: () -> Long = { testScope.testScheduler.currentTime }

    val backend = object : ConnectBackend {
        private fun check() { if (offline) throw IllegalStateException("offline") }
        override suspend fun fetch(): ConnectSnapshot { check(); delay(roundTripMs); check(); return server.snapshot() }
        override suspend fun update(block: (ConnectSnapshot) -> ConnectWrite?): ConnectSnapshot? {
            while (true) {
                check()
                val sent = localNow()
                val cur = server.snapshot()
                val base = server.version
                delay(roundTripMs / 2)
                check()
                val w = block(cur) ?: return null
                if (rejectNextReady && w.session.transfer?.status == TransferStatus.READY) {
                    rejectNextReady = false
                    throw IllegalStateException("Transient acknowledgement failure")
                }
                if (server.version != base) continue // someone else wrote first: retry on fresh state
                server.write(w)
                val written = server.snapshot()
                val at = serverNow()
                server.listeners.forEach { it.deliverLater() }
                delay(roundTripMs / 2)
                clock.sample(at, sent, localNow())
                return written
            }
        }
        override suspend fun heartbeat(device: ConnectDevice) {
            check(); val sent = localNow(); delay(roundTripMs / 2); server.heartbeat(device); val at = serverNow()
            server.listeners.forEach { it.deliverDevicesLater() }; delay(roundTripMs / 2); clock.sample(at, sent, localNow())
        }
        override suspend fun removeDevice(id: String) { check(); server.devices.remove(id); server.listeners.forEach { it.deliverDevicesLater() } }
    }

    val engine = ConnectEngine(scope, backend, player,
        ConnectDevice(id, name, platform = platform, type = type, createdMs = testScope.testScheduler.currentTime),
        clock, localNow) { log += it }

    init {
        player.onChange = { engine.onLocalChanged() }
        server.listeners += this
    }

    fun start() {
        engine.start()
        scope.launch {
            while (true) {
                delay(1000)
                player.advance()
                engine.tick()
            }
        }
    }

    fun deliverLater() {
        if (offline || !server.listeners.contains(this)) return
        scope.launch { delay(latencyMs); if (!offline) engine.onSnapshot(server.snapshot()) }
    }

    fun deliverDevicesLater() {
        if (offline || !server.listeners.contains(this)) return
        scope.launch { delay(latencyMs); if (!offline) engine.onDevices(server.devices.values.toList()) }
    }

    /** What the platform does for a user control: Connect first, the local player otherwise. */
    fun user(intent: ConnectIntent) {
        if (engine.intercept(intent)) return
        when (intent) {
            ConnectIntent.Play -> player.play()
            ConnectIntent.Pause -> player.pause()
            is ConnectIntent.Seek -> player.seekTo(intent.positionMs)
            ConnectIntent.Next -> player.next()
            ConnectIntent.Previous -> player.previous()
            is ConnectIntent.PlayIndex -> player.playIndex(intent.index)
            is ConnectIntent.Load -> player.load(intent.tracks, intent.index, 0L, true, player.shuffled, player.repeatMode, intent.context)
            is ConnectIntent.Shuffle -> player.setShuffle(intent.on)
            is ConnectIntent.Repeat -> player.setRepeat(intent.mode)
            is ConnectIntent.Add -> player.add(intent.track)
            is ConnectIntent.PlayNext -> player.playNext(intent.track)
            is ConnectIntent.Move -> player.move(intent.from, intent.to)
            is ConnectIntent.Remove -> player.remove(intent.index)
        }
    }

    /** Back online: like a listener reattaching, it reads the server first. */
    fun reconnect() {
        offline = false
        deliverLater()
        deliverDevicesLater()
    }
}

fun songs(count: Int, prefix: String = "song") = (1..count).map {
    Track(id = "$prefix$it", title = "Song $it", artist = "Artist", duration = 180, thumbnail = "https://example.com/$it.jpg")
}
