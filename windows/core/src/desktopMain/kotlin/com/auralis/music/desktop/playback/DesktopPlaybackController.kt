package com.auralis.music.desktop.playback

import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The desktop queue: what is playing, what comes next, and the controls. Like the phone, tapping
 * a song in a list plays it and queues the rest of that list after it.
 */
class DesktopPlaybackController(
    private val player: VlcAudioPlayer,
    private val scope: CoroutineScope,
    private val offlineFile: (String) -> java.io.File? = { com.auralis.music.desktop.library.DesktopDownloads.instance.localFile(it) },
    volumeStore: com.auralis.music.util.KeyValueStore? = null,
    private val resolveStream: suspend (String) -> String? = { DesktopStreamResolver.resolve(it) }
) {
    enum class RepeatMode { OFF, ALL, ONE }
    data class Queue(val tracks: List<Track> = emptyList(), val index: Int = -1, val listenedIndices: Set<Int> = emptySet()) {
        val history: List<IndexedValue<Track>> get() = tracks.withIndex().take(index.coerceAtLeast(0)).filter { it.index in listenedIndices }
        val current: Track? get() = tracks.getOrNull(index)
        val hasNext: Boolean get() = index in 0 until tracks.lastIndex
        /** Move unheard songs skipped over to after the selection, preserving heard-song order. */
        fun retainUnplayed(): Queue {
            if (current == null) return this
            val heard = (0 until index).filter { it in listenedIndices }
            val pending = (0 until index).filter { it !in listenedIndices }
            val order = heard + index + pending + (index + 1 until tracks.size)
            val heardPositions = order.withIndex().filter { it.value in listenedIndices }.map { it.index }.toSet()
            return copy(tracks = order.map { tracks[it] }, index = heard.size, listenedIndices = heardPositions)
        }

        fun withoutHistory(): Queue {
            if (current == null) return this
            val keep = tracks.indices.filter { it == index || it !in listenedIndices }
            val nextIndex = keep.indexOf(index)
            return copy(tracks = keep.map { tracks[it] }, index = nextIndex,
                listenedIndices = if (index in listenedIndices) setOf(nextIndex) else emptySet())
        }
        fun afterFinished(repeat: RepeatMode): Queue? = when {
            current == null -> null
            repeat == RepeatMode.ONE -> this
            hasNext -> copy(index = index + 1)
            repeat == RepeatMode.ALL -> copy(index = 0)
            else -> null
        }
    }

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode = _repeatMode.asStateFlow()
    fun cycleRepeatMode() {
        if (connect(com.auralis.music.data.connect.ConnectIntent.Repeat(com.auralis.music.domain.model.RepeatMode.entries[(_repeatMode.value.ordinal + 1) % RepeatMode.entries.size]))) return
        if (roomControl != null) return
        _repeatMode.value = RepeatMode.entries[(_repeatMode.value.ordinal + 1) % RepeatMode.entries.size]
        if (_repeatMode.value == RepeatMode.OFF) autoRadio.queueChanged() else autoRadio.cancelPending()
    }

    private val _queue = MutableStateFlow(Queue())
    val queue: StateFlow<Queue> = _queue.asStateFlow()
    @Volatile var radioCandidates: suspend (Track) -> List<Track> = { emptyList() }
    @Volatile var autoLoadMore: Boolean = true
        set(value) {
            field = value
            if (value) autoRadio.queueChanged() else autoRadio.cancelPending()
        }
    private val autoRadio = DesktopAutoRadio(scope, { _queue.value },
        { autoLoadMore && roomControl == null && _repeatMode.value == RepeatMode.OFF }, { radioCandidates(it) },
        { batch -> _queue.update { q -> q.copy(tracks = q.tracks + batch.filter { song -> q.tracks.none { it.id == song.id } }) } },
        { if (_queue.value.hasNext) next() })
    private val _session = MutableStateFlow(0L)
    val session: StateFlow<Long> = _session.asStateFlow()
    private val _roomActive = MutableStateFlow(false)
    val roomActive: StateFlow<Boolean> = _roomActive.asStateFlow()
    /** Active rooms route guest controls to the host instead of changing local playback. */
    @Volatile var roomControl: ((String, Track?, Long) -> Boolean)? = null
        set(value) {
            field = value
            _roomActive.value = value != null
            if (value != null) autoRadio.cancelPending()
            else autoRadio.queueChanged()
        }
    private val applyingRoom = ThreadLocal.withInitial { false }
    var roomQueueEdit: ((Int, Int?) -> Boolean)? = null
    var roomTrackWillLoad: ((Track) -> Unit)? = null
    fun holdStartForRoom() = player.holdStartForRoom()
    fun releaseRoomStart() = player.releaseRoomStart()
    fun <T> fromRoom(block: () -> T): T {
        val before = applyingRoom.get()
        applyingRoom.set(true)
        return try { block() } finally { applyingRoom.set(before) }
    }
    private fun intercepted(action: String, track: Track? = null, position: Long = 0): Boolean =
        !applyingRoom.get() && roomControl?.invoke(action, track, position) == true

    // ---- Auralis Connect ----------------------------------------------------------------------
    /**
     * Set while signed in to an account: when another device of the account is the output, this
     * PC's controls go there (returns true) and this PC stays silent. Rooms take precedence.
     */
    @Volatile var connectControl: ((com.auralis.music.data.connect.ConnectIntent) -> Boolean)? = null
    private val applyingConnect = ThreadLocal.withInitial { false }
    /** Runs [block] as Connect acting on this PC itself, so it isn't routed back out. */
    fun <T> fromConnect(block: () -> T): T {
        val before = applyingConnect.get()
        applyingConnect.set(true)
        return try { block() } finally { applyingConnect.set(before) }
    }
    private fun connect(intent: com.auralis.music.data.connect.ConnectIntent): Boolean =
        !applyingConnect.get() && !applyingRoom.get() && roomControl == null && connectControl?.invoke(intent) == true

    private val _connectRemote = MutableStateFlow<com.auralis.music.data.connect.ConnectRemoteView?>(null)
    /** The session another device is playing, shown here silently; null when this PC is the output. */
    val connectRemote: StateFlow<com.auralis.music.data.connect.ConnectRemoteView?> = _connectRemote.asStateFlow()

    /** Shows [view] (or stops showing it): the queue on screen mirrors the other device's queue. */
    fun showRemote(view: com.auralis.music.data.connect.ConnectRemoteView?) {
        // In a Listen Together room the room's playback is shown, never the account's session.
        _connectRemote.value = view?.takeIf { roomControl == null }
        if (view == null || roomControl != null) return
        val current = _queue.value
        if (current.tracks.map { it.id } != view.queue.map { it.id } || current.index != view.index) {
            _queue.value = Queue(view.queue, view.index.coerceIn(-1, view.queue.lastIndex))
        }
        _repeatMode.value = RepeatMode.valueOf(view.repeat.name)
    }

    /** Where a song loaded silently by Connect starts when play is pressed. */
    @Volatile private var pendingResumeMs: Long? = null
    private data class ResumeAnchor(val trackId: String, val positionMs: Long, val atNanos: Long)
    @Volatile private var resumeAnchor: ResumeAnchor? = null

    private fun resumePosition(): Long? {
        val anchor = resumeAnchor ?: return pendingResumeMs
        if (anchor.trackId != _queue.value.current?.id) { resumeAnchor = null; return pendingResumeMs }
        val reached = lengthTrackId.value == anchor.trackId &&
            player.state.value == VlcAudioPlayer.State.PLAYING &&
            kotlin.math.abs(player.clock.rawPositionMs() - anchor.positionMs) < 1_500L
        if (reached || System.nanoTime() - anchor.atNanos > 20_000_000_000L) {
            resumeAnchor = null
            return pendingResumeMs
        }
        return anchor.positionMs
    }

    /** Connect: replaces the queue; starts at [positionMs] when [play], otherwise waits silently there. */
    fun connectLoad(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean) {
        if (index !in tracks.indices) return
        autoRadio.reset(false)
        consecutiveFailures = 0
        if (play) {
            pendingResumeMs = null
            load(Queue(tracks, index), resumeAtMs = positionMs.coerceAtLeast(0L))
        } else {
            loadJob?.cancel(); preloadJob?.cancel(); stallJob?.cancel()
            _resolving.value = false
            player.stop()
            lengthTrackId.value = null
            _queue.value = Queue(tracks, index)
            pendingResumeMs = positionMs.coerceAtLeast(0L)
            _session.value++
        }
    }

    /** Connect: stop making sound now (also stops a song that is still being fetched from starting). */
    fun connectSilence() {
        if (loadJob?.isActive == true) {
            val position = resumePosition() ?: player.clock.rawPositionMs()
            loadJob?.cancel()
            _resolving.value = false
            pendingResumeMs = position.coerceAtLeast(0L)
            resumeAnchor = null
        }
        if (player.state.value == VlcAudioPlayer.State.PLAYING || player.state.value == VlcAudioPlayer.State.BUFFERING) player.pause()
    }

    /** Connect: play from where this PC is (or where a silently loaded song waits). */
    fun connectPlay() {
        when (player.state.value) {
            VlcAudioPlayer.State.PAUSED -> player.resume()
            VlcAudioPlayer.State.PLAYING, VlcAudioPlayer.State.BUFFERING -> Unit
            else -> if (loadJob?.isActive != true) _queue.value.takeIf { it.current != null }?.let { load(it, resumeAtMs = pendingResumeMs ?: 0L) }
        }
        pendingResumeMs = null
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
        if (mode == RepeatMode.OFF) autoRadio.queueChanged() else autoRadio.cancelPending()
    }

    /** Whether the upcoming songs were shuffled (what Connect reports as shuffle). */
    val shuffleActive: Boolean get() = shuffleEngaged

    fun syncRoomQueue(tracks: List<Track>, index: Int) {
        if (index in tracks.indices && tracks[index].id == _queue.value.current?.id) _queue.value = _queue.value.copy(tracks = tracks, index = index)
    }

    /** Restores the previous playback queue on app launch in idle state without auto-starting playback. */
    fun restoreQueue(tracks: List<Track>, index: Int = 0) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        if (_queue.value.current == null && player.state.value == VlcAudioPlayer.State.IDLE) {
            _queue.value = Queue(tracks = tracks, index = index)
            // The first lookup after launch is the slowest (~2.5s, NewPipe warming up); do it for the
            // restored song shortly after launch, so pressing play starts it almost at once.
            val track = tracks[index]
            if (offlineFile(track.id) == null) preloadJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.delay(RESTORED_PRELOAD_DELAY_MS)
                if (_queue.value.current?.id != track.id || player.state.value != VlcAudioPlayer.State.IDLE) return@launch
                val id = com.auralis.music.data.network.RecordingMatches.getMatchedVideoId(track.id)
                    ?: track.id.takeIf(DesktopPlaybackMatch::isVideoId) ?: return@launch
                resolveStream(id)?.let { LocalStreamRelay.preload(it, refresh = { DesktopStreamResolver.refreshBlocking(track.id) }) }
            }
        }
    }

    /** Rearrange only upcoming songs; keep the playing track, history and stream untouched. */
    fun shuffleUpcoming() {
        if (connect(com.auralis.music.data.connect.ConnectIntent.Shuffle(true))) return
        if (roomControl != null) return // The room host owns the shared queue order.
        val queue = _queue.value
        if (queue.current == null) return
        shuffleEngaged = true
        _queue.value = if (shuffleWholeQueue) Queue(listOf(queue.current!!) + queue.tracks.filterIndexed { i, _ -> i != queue.index }.shuffled(), 0)
        else queue.copy(tracks = queue.tracks.take(queue.index + 1) + queue.tracks.drop(queue.index + 1).shuffled(), listenedIndices = queue.listenedIndices.filter { it <= queue.index }.toSet())
    }

    /** True while the stream URL for the current song is being looked up. */
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val volumeState = DesktopVolumeState(volumeStore, player.currentVolume(), player::setVolume)
    private val _volume = volumeState.volume
    val volume: StateFlow<Float> = _volume

    val state: StateFlow<VlcAudioPlayer.State> get() = player.state
    /** The native stream identity and state in one emission; the queue may already show the next song. */
    val mediaState: StateFlow<VlcAudioPlayer.MediaState> get() = player.mediaState
    val positionMs: StateFlow<Long> get() = player.positionMs
    /** Which song libVLC's length belongs to; null while the next stream is still resolving. */
    private val lengthTrackId = MutableStateFlow<String?>(null)
    /**
     * The playing song's stream length, 0 until libVLC reports it for the current song. libVLC
     * keeps the previous song's length while the next stream resolves; lyrics matched against
     * that stale length rejected every synced source as a different recording.
     */
    val lengthMs: StateFlow<Long> = kotlinx.coroutines.flow.combine(lengthTrackId, player.lengthMs) { id, length ->
        if (id != null && id == _queue.value.current?.id) length else 0L
    }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, 0L)
    /** Smooth playback clock for word-synced lyrics. */
    val clock: com.auralis.music.data.service.PlaybackClockSource = object : com.auralis.music.data.service.PlaybackClockSource {
        override fun rawPositionMs(): Long = _connectRemote.value?.positionAt(System.currentTimeMillis()) ?: resumePosition() ?: player.clock.rawPositionMs()
        override fun isPlaying(): Boolean = _connectRemote.value?.let { it.playing && !it.buffering } ?: (resumePosition() == null && player.clock.isPlaying())
        override fun speed(): Float = if (_connectRemote.value != null) 1f else player.clock.speed()
        override fun isBuffering(): Boolean = _connectRemote.value?.buffering ?: ((resumePosition() != null && pendingResumeMs == null) || player.clock.isBuffering())
    }
    @Volatile var preloadNext: Boolean = true
    fun setRate(rate: Float) = player.setRate(rate)
    fun setNormalizeVolume(enabled: Boolean) { player.normalizeVolume = enabled }
    var crossfadeSeconds: Float
        get() = player.crossfadeSeconds
        set(value) { player.crossfadeSeconds = value.coerceIn(0f,12f) }
    @Volatile var preventDuplicates = true
    @Volatile var stickyShuffle = false
    @Volatile var shuffleWholeQueue = false
    private var shuffleEngaged = false
    private var preloadJob: Job? = null

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<String> = _errors

    private var loadJob: Job? = null
    /** Songs that failed in a row; stops auto-skipping when a whole list fails (e.g. offline). */
    private var consecutiveFailures = 0
    /** The song whose stream was already looked up again after a playback error. */
    private var retriedTrackId: String? = null

    /** The song being started and when it was asked for, until it sounds (for the log). */
    @Volatile private var startRequested: Pair<String, Long>? = null

    /** The stream URL handed to libVLC for the current song. */
    @Volatile private var currentUrl: String? = null

    init {
        // Only sound counts as success: a failure streak ends when audio actually plays, not when
        // a URL is handed to libVLC (it reports a refused stream a moment later).
        scope.launch {
            player.state.collect { if (it == VlcAudioPlayer.State.PLAYING) {
                startRequested?.let { (id, nanos) ->
                    startRequested = null
                    com.auralis.music.util.Log.i("DesktopPlayback", "Started $id in ${(System.nanoTime() - nanos) / 1_000_000}ms")
                }
                consecutiveFailures = 0
                _queue.update { q -> q.current?.let { q.copy(listenedIndices = q.listenedIndices + q.index) } ?: q }
            } }
        }
        player.onFinished = { scope.launch {
            if (_resolving.value && crossfadeSeconds > 0) return@launch
            if (roomControl != null) { if (_queue.value.hasNext) next() }
            else {
                val q = _queue.value
                val withCurrentHeard = if (q.current != null) q.copy(listenedIndices = q.listenedIndices + q.index) else q
                val next = withCurrentHeard.afterFinished(_repeatMode.value)
                if (next != null) load(next) else autoRadio.request(advanceWhenLoaded = true)
            }
        } }
        scope.launch {
            player.positionMs.collect { position ->
                val remaining = player.lengthMs.value - position
                if (crossfadeSeconds > 0 && roomControl == null && _repeatMode.value != RepeatMode.ONE &&
                    player.state.value == VlcAudioPlayer.State.PLAYING && !_resolving.value && _queue.value.hasNext &&
                    remaining > 0 && remaining <= crossfadeSeconds * 1000 && position > 1000) next()
            }
        }
        player.onError = {
            scope.launch {
                if (_resolving.value && crossfadeSeconds > 0) return@launch
                val track = _queue.value.current ?: return@launch
                // A stream URL can expire or be refused (YouTube answers 403). Like the phone,
                // avoid that server for a while and look the song up once more before giving up.
                currentUrl?.let(DesktopStreamResolver::markHostFailed)
                DesktopStreamResolver.invalidate(track.id)
                if (retriedTrackId != track.id) {
                    retriedTrackId = track.id
                    load(_queue.value, resumeAtMs = player.positionMs.value, isRetry = true)
                } else {
                    failed(track)
                }
            }
        }
    }

    fun playSingle(track: Track) = playFrom(track, listOf(track), automatic = true)

    fun playFrom(track: Track, list: List<Track>, automatic: Boolean = list.size <= 1) {
        if (intercepted("play_track", track)) return
        if (connect(com.auralis.music.data.connect.ConnectIntent.Load(list.ifEmpty { listOf(track) }, list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)))) return
        autoRadio.reset(automatic)
        pendingResumeMs = null
        val supplied = list.ifEmpty { listOf(track) }
        val unique = if (preventDuplicates) supplied.distinctBy { it.id } else supplied
        val tracks = if (stickyShuffle && shuffleEngaged) listOf(track) + unique.filter { it.id != track.id }.shuffled() else unique
        val index = tracks.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: 0
        consecutiveFailures = 0
        load(Queue(tracks, index))
    }

    fun togglePlayPause() {
        if (intercepted(if (player.state.value == VlcAudioPlayer.State.PLAYING) "pause" else "play")) return
        _connectRemote.value?.let { remote -> if (connect(if (remote.playing) com.auralis.music.data.connect.ConnectIntent.Pause else com.auralis.music.data.connect.ConnectIntent.Play)) return }
        if (connect(if (player.state.value == VlcAudioPlayer.State.PLAYING) com.auralis.music.data.connect.ConnectIntent.Pause else com.auralis.music.data.connect.ConnectIntent.Play)) return
        when (player.state.value) {
            VlcAudioPlayer.State.PLAYING, VlcAudioPlayer.State.BUFFERING -> player.pause()
            VlcAudioPlayer.State.PAUSED -> player.resume()
            VlcAudioPlayer.State.ENDED, VlcAudioPlayer.State.IDLE, VlcAudioPlayer.State.ERROR ->
                _queue.value.takeIf { it.current != null }?.let { load(it, resumeAtMs = pendingResumeMs ?: 0L); pendingResumeMs = null }
        }
    }

    /** Set a requested state without flipping it when a room repeats the same update. */
    fun setPaused(paused: Boolean) {
        when {
            paused && player.state.value == VlcAudioPlayer.State.PLAYING -> player.pause()
            !paused && player.state.value == VlcAudioPlayer.State.PAUSED -> player.resume()
        }
    }

    fun next() {
        if (intercepted("next")) return
        if (connect(com.auralis.music.data.connect.ConnectIntent.Next)) return
        val q = _queue.value
        val withCurrentHeard = if (q.current != null) q.copy(listenedIndices = q.listenedIndices + q.index) else q
        if (withCurrentHeard.hasNext) load(withCurrentHeard.copy(index = withCurrentHeard.index + 1))
        else if (roomControl == null && withCurrentHeard.current != null && _repeatMode.value == RepeatMode.ALL) load(withCurrentHeard.copy(index = 0))
        else autoRadio.request(advanceWhenLoaded = true)
    }

    /** Like the phone: restarts the song when more than 3 seconds in, otherwise goes back one. */
    fun previous() {
        if (intercepted("previous")) return
        if (connect(com.auralis.music.data.connect.ConnectIntent.Previous)) return
        val q = _queue.value
        if (player.positionMs.value > 3000L || q.index <= 0) seekTo(0L)
        else {
            val withCurrentHeard = if (q.current != null) q.copy(listenedIndices = q.listenedIndices + q.index) else q
            load(withCurrentHeard.copy(index = q.index - 1))
        }
    }

    fun seekTo(ms: Long) {
        if (intercepted("seek", position = ms) || connect(com.auralis.music.data.connect.ConnectIntent.Seek(ms.coerceAtLeast(0L)))) return
        resumeAnchor?.let { resumeAnchor = it.copy(positionMs = ms.coerceAtLeast(0L), atNanos = System.nanoTime()) }
        if (pendingResumeMs != null && player.state.value == VlcAudioPlayer.State.IDLE) pendingResumeMs = ms.coerceAtLeast(0L) else player.seekTo(ms)
    }

    /** Seeks [deltaMs] forward (or back when negative), within the song. */
    fun seekBy(deltaMs: Long) {
        if (_queue.value.current == null) return
        _connectRemote.value?.let { remote ->
            val target = (remote.positionAt(System.currentTimeMillis()) + deltaMs).coerceAtLeast(0L)
            seekTo(if (remote.durationMs > 0L) target.coerceAtMost(remote.durationMs - 1000L) else target)
            return
        }
        val length = player.lengthMs.value
        val target = (player.positionMs.value + deltaMs).coerceAtLeast(0L)
        seekTo(if (length > 0L) target.coerceAtMost(length - 1000L) else target)
    }

    fun volumeBy(delta: Float) = setVolume(_volume.value + delta)

    /** Restores the same pre-mute volume, including after restarting while muted. */
    fun toggleMute() = volumeState.toggleMute()

    /** Puts [track] right after the playing song (or plays it when nothing is queued). */
    fun playNext(track: Track) {
        if (intercepted("recommend", track)) return
        if (connect(com.auralis.music.data.connect.ConnectIntent.PlayNext(track))) return
        val q = _queue.value
        if (q.current == null) return playFrom(track, listOf(track))
        if (preventDuplicates && q.tracks.drop(q.index + 1).any { it.id == track.id }) return
        val tracks = q.tracks.toMutableList().apply { add(q.index + 1, track) }
        _queue.value = q.copy(tracks = tracks, listenedIndices = q.listenedIndices.map { if (it > q.index) it + 1 else it }.toSet())
    }

    /** Adds [track] to the end of the queue (or plays it when nothing is queued). */
    fun addToQueue(track: Track) {
        if (intercepted("recommend", track)) return
        if (connect(com.auralis.music.data.connect.ConnectIntent.Add(track))) return
        val q = _queue.value
        if (q.current == null) return playFrom(track, listOf(track))
        if (preventDuplicates && q.tracks.drop(q.index + 1).any { it.id == track.id }) return
        _queue.value = q.copy(tracks = q.tracks + track)
    }

    /** Plays the song at [index] in the queue (the queue itself stays as it is). */
    fun playAt(index: Int) {
        val q = _queue.value
        if (index !in q.tracks.indices) return
        if (intercepted("play_track", q.tracks[index])) return
        if (connect(com.auralis.music.data.connect.ConnectIntent.PlayIndex(index, q.tracks[index].id))) return
        consecutiveFailures = 0
        pendingResumeMs = null
        val withCurrentHeard = if (q.current != null) q.copy(listenedIndices = q.listenedIndices + q.index) else q
        load(withCurrentHeard.copy(index = index), jumpWithinQueue = true)
    }

    /** Removes a song that isn't the one playing. */
    fun removeAt(index: Int) {
        if (!applyingRoom.get() && roomQueueEdit?.invoke(index, null) == true) return
        if (intercepted("edit_queue")) return
        _queue.value.tracks.getOrNull(index)?.let { if (connect(com.auralis.music.data.connect.ConnectIntent.Remove(index, it.id))) return }
        val q = _queue.value
        if (index !in q.tracks.indices || index == q.index) return
        val tracks = q.tracks.toMutableList().apply { removeAt(index) }
        _queue.value = q.copy(tracks = tracks, index = if (index < q.index) q.index - 1 else q.index,
            listenedIndices = q.listenedIndices.filter { it != index }.map { if (it > index) it - 1 else it }.toSet())
        autoRadio.queueChanged()
    }

    fun moveAt(from: Int, to: Int) {
        if (!applyingRoom.get() && roomQueueEdit?.invoke(from, to) == true) return
        if (intercepted("edit_queue")) return
        if (connect(com.auralis.music.data.connect.ConnectIntent.Move(from, to))) return
        val q = _queue.value
        if (from !in q.tracks.indices || to !in q.tracks.indices || from == to) return
        val order = q.tracks.indices.toMutableList().apply { add(to, removeAt(from)) }
        _queue.value = q.copy(tracks = order.map { q.tracks[it] }, index = order.indexOf(q.index),
            listenedIndices = order.indices.filter { order[it] in q.listenedIndices }.toSet())
    }

    /** Audio outputs for the player bar's device picker (id to name), and the chosen one. */
    fun outputDevices(): List<Pair<String, String>> = player.outputDevices()
    val outputDevice: String? get() = player.preferredDevice
    fun setOutputDevice(id: String?) = player.setOutputDevice(id)

    /** Clears History: every song before the one playing. */
    fun clearHistory() {
        if (intercepted("edit_queue")) return
        val q = _queue.value
        _queue.value = q.withoutHistory()
    }

    fun setVolume(volume: Float) {
        volumeState.set(volume)
    }

    fun release() {
        autoRadio.reset(false)
        preloadJob?.cancel()
        loadJob?.cancel()
        stallJob?.cancel()
        player.release()
    }

    private fun shouldRetainUnplayed(current: Queue, requested: Queue): Boolean {
        if (current.tracks.size != requested.tracks.size) return false
        if (current.current == null || requested.current == null) return false
        return requested.index > current.index + 1
    }

    private fun load(requested: Queue, resumeAtMs: Long = 0L, isRetry: Boolean = false, jumpWithinQueue: Boolean = false) {
        val current = _queue.value
        // Retain unplayed songs only when jumping ahead by 2 or more tracks over unheard songs in
        // the queue itself. A song started from a list is a new queue in that list's order: judged
        // by length alone, picking the 27th song of the 100-song playlist already playing moved the
        // first 26 after it, so "next" became the playlist's first song.
        // Sequential advancement (next, finished) and stepping back (previous) never reorder tracks.
        val q = if (jumpWithinQueue && roomControl == null && shouldRetainUnplayed(current, requested)) requested.retainUnplayed() else requested
        val track = q.current ?: return
        if (!isRetry) startRequested = track.id to System.nanoTime()
        if (!isRetry) _session.value++
        if (!isRetry) retriedTrackId = null
        lengthTrackId.value = null
        _queue.value = q
        resumeAnchor = resumeAtMs.takeIf { it > 0L }?.let { ResumeAnchor(track.id, it, System.nanoTime()) }
        autoRadio.queueChanged()
        loadJob?.cancel()
        preloadJob?.cancel()
        stallJob?.cancel()
        val fade = crossfadeSeconds > 0 && roomControl == null && !isRetry && resumeAtMs == 0L &&
            current.current != null && current.current?.id != track.id && player.state.value == VlcAudioPlayer.State.PLAYING
        if (!fade) player.stop()
        if (!isRetry) roomTrackWillLoad?.invoke(track)
        player.warmOutput()
        loadJob = scope.launch {
            _resolving.value = true
            val localAudio = offlineFile(track.id)
            val url = try {
                localAudio?.absolutePath
                    ?: run {
                        val videoId = DesktopPlaybackMatch.videoId(track)
                        // An age-restricted or unplayable upload: play another upload of the same recording.
                        if (videoId == null) null else resolveStream(videoId)
                            ?: DesktopPlaybackMatch.alternative(track, videoId, resolveStream)?.second
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.auralis.music.util.Log.w("DesktopPlayback", "Playback lookup failed for ${track.id}: ${e.javaClass.simpleName}")
                null
            } finally {
                _resolving.value = false
            }
            if (_queue.value.current?.id != track.id) return@launch
            if (url == null) {
                failed(track)
            } else {
                // A sleeping device (Bluetooth earbuds) misses the first second or two of whatever
                // plays as it wakes; give a cold one that long on silence first.
                val awake = player.warmOutput()
                if (awake < OUTPUT_WAKE_MS) {
                    kotlinx.coroutines.delay(OUTPUT_WAKE_MS - awake)
                    if (_queue.value.current?.id != track.id) return@launch
                }
                player.setVolume(_volume.value)
                currentUrl = url
                // Through the local relay, which fetches the stream in pieces YouTube accepts.
                val startAt = resumeAnchor?.takeIf { it.trackId == track.id }?.positionMs ?: resumeAtMs
                player.play(if (localAudio != null) url else LocalStreamRelay.register(url, refresh = { DesktopStreamResolver.refreshBlocking(track.id) }), startAt, track.id)
                lengthTrackId.value = track.id
                if (localAudio == null) watchForStall(track)
                if (preloadNext && q.hasNext) {
                    preloadJob?.cancel()
                    preloadJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val next = q.tracks[q.index + 1]
                        if (offlineFile(next.id) != null) return@launch
                        val id = com.auralis.music.data.network.RecordingMatches.getMatchedVideoId(next.id)
                            ?: (if (DesktopPlaybackMatch.isVideoId(next.id)) next.id else DesktopPlaybackMatch.videoId(next))
                        // The next song's address and its first seconds of audio, so skipping starts at once.
                        if (id != null && DesktopPlaybackMatch.isVideoId(id)) resolveStream(id)?.let { nextUrl ->
                            LocalStreamRelay.preload(nextUrl, refresh = { DesktopStreamResolver.refreshBlocking(next.id) })
                        }
                    }
                }
            }
        }
    }

    private var stallJob: Job? = null
    /** Fresh streams fetched for a song that stalled; reset when another song is picked. */
    private var stallRetries = 0
    private var stallTrackId: String? = null

    /**
     * A stream can open but never deliver (or stop mid-song) without libVLC reporting an error, so
     * the song sat buffering until it was picked again, which fetched a fresh stream. Do that on
     * its own: no sound within [START_STALL_MS] of starting, or no progress for [PLAY_STALL_MS] while
     * playing, gets a fresh stream at the same position (twice, then the song counts as failed).
     */
    private fun watchForStall(track: Track) {
        if (stallTrackId != track.id) { stallTrackId = track.id; stallRetries = 0 }
        stallJob?.cancel()
        stallJob = scope.launch {
            var lastPosition = -1L
            var since = System.nanoTime()
            while (_queue.value.current?.id == track.id) {
                kotlinx.coroutines.delay(500)
                val state = player.state.value
                val position = player.positionMs.value
                val waiting = state == VlcAudioPlayer.State.BUFFERING || state == VlcAudioPlayer.State.PLAYING
                if (!waiting || roomControl != null || (state == VlcAudioPlayer.State.PLAYING && position != lastPosition)) {
                    if (state == VlcAudioPlayer.State.PLAYING) lastPosition = position
                    since = System.nanoTime()
                    if (state == VlcAudioPlayer.State.ENDED || state == VlcAudioPlayer.State.ERROR) return@launch
                    continue
                }
                val limit = if (lastPosition < 0) START_STALL_MS else PLAY_STALL_MS
                if ((System.nanoTime() - since) / 1_000_000 < limit) continue
                if (stallRetries >= 2) {
                    com.auralis.music.util.Log.w("DesktopPlayback", "${track.id} still stalled after fresh streams")
                    failed(track)
                    return@launch
                }
                stallRetries++
                com.auralis.music.util.Log.w("DesktopPlayback", "${track.id} stalled ${if (lastPosition < 0) "before starting" else "at ${position}ms"}; fetching a fresh stream ($stallRetries/2)")
                currentUrl?.let(DesktopStreamResolver::markHostFailed)
                DesktopStreamResolver.invalidate(track.id)
                load(_queue.value, resumeAtMs = if (lastPosition < 0) 0L else position, isRetry = true)
                return@launch
            }
        }
    }

    private companion object {
        const val OUTPUT_WAKE_MS = 1000L
        const val START_STALL_MS = 6000L
        const val RESTORED_PRELOAD_DELAY_MS = 4000L
        const val PLAY_STALL_MS = 10_000L
    }

    private fun failed(track: Track) {
        consecutiveFailures++
        _errors.tryEmit("Couldn't play “${track.title}”")
        if (consecutiveFailures < 3 && _queue.value.current?.id == track.id && _queue.value.hasNext) {
            next()
        } else if (consecutiveFailures >= 3) {
            com.auralis.music.util.Log.w("DesktopPlayback", "Stopped skipping after $consecutiveFailures songs in a row failed")
            _errors.tryEmit(if (DesktopPlaybackMatch.isVideoId(track.id) && com.auralis.music.data.network.YouTubeBackoff.active)
                "YouTube is temporarily blocking requests from this network. Try again in a few minutes or switch networks."
                else "Playback isn't working right now. Check your connection and try again.")
        }
    }
}
