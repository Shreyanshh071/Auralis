package com.auralis.music.desktop.playback

import com.auralis.music.domain.model.AudioQuality
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
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
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import com.auralis.music.data.network.YouTubeBackoff
import com.auralis.music.data.network.YouTubeCooldown
import com.auralis.music.data.network.RecordingMatches

/**
 * The desktop queue: what is playing, what comes next, and the controls. Like the phone, tapping
 * a song in a list plays it and queues the rest of that list after it.
 */
class DesktopPlaybackController(
    private val player: VlcAudioPlayer,
    private val scope: CoroutineScope,
    private val offlineFile: (String) -> java.io.File? = { com.auralis.music.desktop.library.DesktopDownloads.instance.localFile(it) },
    volumeStore: com.auralis.music.util.KeyValueStore? = null,
    resolveJioSaavn: (suspend (Track) -> String?)? = null,
    resolveSoundCloud: (suspend (Track) -> String?)? = null,
    private val filterTracks: suspend (List<Track>) -> List<Track> = com.auralis.music.data.network.YouTubeShortsFilter::filter,
    // Last, so callers can pass it as a trailing lambda (RoomPlaybackCheck does).
    private val resolveStream: suspend (String) -> String? = DEFAULT_RESOLVE,
) {
    private var admissionJob: Job? = null
    private var selectionJob: Job? = null
    private var connectAdmissionJob: Job? = null
    @Volatile private var connectAdmissionPlay = false
    @Volatile private var connectAdmissionPending = false
    private var roomAdmissionJob: Job? = null
    private val queueEdits = Mutex()
    private fun admissionContext() = applyingConnect.asContextElement(applyingConnect.get()) + applyingRoom.asContextElement(applyingRoom.get())
    private fun admit(tracks: List<Track>, action: (List<Track>) -> Unit): Job = scope.launch(
        admissionContext(),
        start = CoroutineStart.UNDISPATCHED
    ) {
        val allowed = filterTracks(tracks)
        ensureActive()
        action(allowed)
    }

    /** Keep occurrence order, current position and listened markers after removing blocked videos. */
    private fun Queue.playable(allowed: List<Track>): Queue {
        val ids = allowed.map { it.id }.toSet()
        val indices = tracks.indices.filter { tracks[it].id in ids }
        val selected = indices.firstOrNull { it >= index }
        return Queue(indices.map { tracks[it] }, selected?.let { indices.indexOf(it) } ?: -1,
            indices.indices.filter { indices[it] in listenedIndices }.toSet())
    }
    private val resolveJioSaavn: (suspend (Track) -> String?)? =
        resolveJioSaavn ?: if (resolveStream === DEFAULT_RESOLVE) DesktopJioSaavnAudio::find320 else null
    private val resolveSoundCloud: (suspend (Track) -> String?)? =
        resolveSoundCloud ?: if (resolveStream === DEFAULT_RESOLVE) DesktopSoundCloudAudio::find else null
    /** Stream lookups with their reasons; tests' plain resolvers are wrapped (null = unknown failure). */
    private val resolveDetailed: suspend (String, RequestPriority) -> ResolutionResult =
        if (resolveStream === DEFAULT_RESOLVE) { id, priority -> DesktopStreamResolver.resolveResult(id, priority) }
        else { id, _ ->
            resolveStream(id)?.let { ResolutionResult.Resolved(PlaybackResource.fromUrl(id, it)) }
                ?: ResolutionResult.Failed(com.auralis.music.data.network.PlaybackFailure(com.auralis.music.data.network.FailureCategory.UNKNOWN, "resolve"))
        }
    enum class RepeatMode { OFF, ALL, ONE }
    data class Queue(val tracks: List<Track> = emptyList(), val index: Int = -1, val listenedIndices: Set<Int> = emptySet()) {
        val history: List<IndexedValue<Track>> get() = tracks.withIndex().take(index.coerceAtLeast(0)).filter { it.index in listenedIndices }
        val current: Track? get() = tracks.getOrNull(index)
        val hasNext: Boolean get() = index in 0 until tracks.lastIndex
        /** Restore source order without reviving removed songs or losing added occurrences. */
        fun inSourceOrder(source: List<Track>): Queue {
            val remaining = tracks.indices.toMutableList()
            val order = source.mapNotNull { item ->
                remaining.firstOrNull { tracks[it].id == item.id }?.also { remaining.remove(it) }
            } + remaining
            return copy(tracks = order.map { tracks[it] }, index = order.indexOf(index),
                listenedIndices = order.indices.filter { order[it] in listenedIndices }.toSet())
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
        { autoLoadMore && roomControl == null && _repeatMode.value == RepeatMode.OFF }, { filterTracks(radioCandidates(it)) },
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
    private var remoteAdmissionJob: Job? = null
    private var remoteIndices = emptyList<Int>()
    private fun remoteIndex(index: Int) = if (_connectRemote.value != null) remoteIndices.getOrElse(index) { index } else index

    /** Shows [view] (or stops showing it): the queue on screen mirrors the other device's queue. */
    fun showRemote(view: com.auralis.music.data.connect.ConnectRemoteView?) {
        remoteAdmissionJob?.cancel()
        if (view == null || roomControl != null) {
            _connectRemote.value = null
            remoteIndices = emptyList()
            return
        }
        remoteAdmissionJob = admit(view.queue + view.track) { allowed ->
            val ids = allowed.map { it.id }.toSet()
            remoteIndices = view.queue.indices.filter { view.queue[it].id in ids }
            if (view.track.id !in ids) {
                _connectRemote.value = null
                _queue.value = Queue()
            } else showAllowedRemote(view.copy(queue = remoteIndices.map { view.queue[it] }, index = remoteIndices.indexOf(view.index)))
        }
    }

    private fun showAllowedRemote(view: com.auralis.music.data.connect.ConnectRemoteView) {
        // In a Listen Together room the room's playback is shown, never the account's session.
        _connectRemote.value = view.takeIf { roomControl == null }
        if (roomControl != null) return
        val current = _queue.value
        if (current.tracks != view.queue || current.index != view.index) {
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
        selectionJob?.cancel()
        admissionJob?.cancel()
        loadJob?.cancel()
        connectAdmissionJob?.cancel()
        connectAdmissionPlay = play
        connectAdmissionPending = true
        // Hold the transfer bookmark while metadata is checked, without displaying
        // an unchecked video or reporting zero back to the sending device.
        pendingResumeMs = positionMs.coerceAtLeast(0L)
        resumeAnchor = null
        _resolving.value = play
        connectAdmissionJob = admit(tracks) { allowed ->
            connectAdmissionPending = false
            val queue = Queue(tracks, index).playable(allowed)
            if (queue.current == null) {
                player.stop(); _queue.value = Queue(); pendingResumeMs = null; _resolving.value = false
            } else connectLoadAllowed(queue.tracks, queue.index,
                if (queue.current?.id == tracks[index].id) pendingResumeMs ?: positionMs else 0L, connectAdmissionPlay)
        }
    }

    private fun connectLoadAllowed(tracks: List<Track>, index: Int, positionMs: Long, play: Boolean) {
        autoRadio.reset(false)
        consecutiveFailures = 0
        if (play) {
            pendingResumeMs = null
            loadAllowed(Queue(tracks, index), resumeAtMs = positionMs.coerceAtLeast(0L), isRetry = false)
        } else {
            loadJob?.cancel(); preloadJob?.cancel(); stallJob?.cancel(); progressJob?.cancel()
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
        connectAdmissionPlay = false
        admissionJob?.cancel()
        selectionJob?.cancel()
        _resolving.value = false
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
        if (connectAdmissionPending) {
            connectAdmissionPlay = true
            _resolving.value = true
            return
        }
        when (player.state.value) {
            VlcAudioPlayer.State.PAUSED -> resumePaused()
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
    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()
    private var unshuffledTracks: List<Track> = emptyList()

    fun toggleShuffle() = setShuffle(!shuffleEngaged)

    fun setShuffle(on: Boolean) {
        if (connect(com.auralis.music.data.connect.ConnectIntent.Shuffle(on))) return
        if (roomControl != null || on == shuffleEngaged) return
        if (on) shuffleUpcoming() else {
            shuffleEngaged = false
            _queue.value = _queue.value.inSourceOrder(unshuffledTracks)
        }
    }

    fun syncRoomQueue(tracks: List<Track>, index: Int) {
        roomAdmissionJob?.cancel()
        roomAdmissionJob = admit(tracks) { allowed ->
            val queue = Queue(tracks, index).playable(allowed)
            if (queue.current?.id == _queue.value.current?.id) _queue.value = queue
        }
    }

    /** Restores the previous playback queue on app launch in idle state without auto-starting playback. */
    fun restoreQueue(tracks: List<Track>, index: Int = 0) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        admissionJob?.cancel()
        admissionJob = admit(tracks) { allowed -> restorePlayableQueue(Queue(tracks, index).playable(allowed)) }
    }

    private fun restorePlayableQueue(queue: Queue) {
        if (_queue.value.current == null && player.state.value == VlcAudioPlayer.State.IDLE) {
            // Metadata admission never resolves or starts an audio stream.
            _queue.value = queue
        }
    }

    /** Rearrange only upcoming songs; keep the playing track, history and stream untouched. */
    fun shuffleUpcoming() {
        if (connect(com.auralis.music.data.connect.ConnectIntent.Shuffle(true))) return
        if (roomControl != null) return // The room host owns the shared queue order.
        val queue = _queue.value
        if (queue.current == null) return
        if (!shuffleEngaged) unshuffledTracks = queue.tracks.toList()
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
        override fun isBuffering(): Boolean = _connectRemote.value?.buffering ?: (_resolving.value || (resumePosition() != null && pendingResumeMs == null) || player.clock.isBuffering())
    }
    @Volatile var preloadNext: Boolean = true
    fun setRate(rate: Float) = player.setRate(rate)
    fun setNormalizeVolume(enabled: Boolean) { player.normalizeVolume = enabled }
    var crossfadeSeconds: Float
        get() = player.crossfadeSeconds
        set(value) { player.crossfadeSeconds = value.coerceIn(0f,12f) }
    @Volatile var preventDuplicates = true
    @Volatile var shuffleWholeQueue = false
    private var shuffleEngaged: Boolean
        get() = _shuffleEnabled.value
        set(value) { _shuffleEnabled.value = value }
    private var preloadJob: Job? = null

    data class YouTubeFallback(val trackId: String, val videoId: String, val startSeconds: Long) {
        val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId&t=${startSeconds}s"
    }

    data class PlaybackError(val message: String, val youtubeFallback: YouTubeFallback? = null)

    private val _errors = MutableSharedFlow<PlaybackError>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<PlaybackError> = _errors

    private val _seekEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val seekEvents: SharedFlow<Long> = _seekEvents

    private var loadJob: Job? = null
    /** Songs that failed in a row; stops auto-skipping when a whole list fails (e.g. offline). */
    private var consecutiveFailures = 0
    /** The song whose stream was already looked up again after a playback error. */
    private var retriedTrackId: String? = null

    /** The song being started and when it was asked for, until it sounds (for the log). */
    @Volatile private var startRequested: Pair<String, Long>? = null

    /** The address handed to libVLC for the current song. */
    @Volatile private var currentLocalUrl: String? = null
    /** Where a song's audio comes from, in the order they are tried. */
    enum class AudioSource { OFFLINE, YOUTUBE, JIOSAAVN, SOUNDCLOUD }

    /**
     * The current song's source, what YouTube said about it, and every source this intent already
     * tried. [failureHandled] lets only one of the error and stall paths move the song on.
     */
    private class ActiveSource(val trackId: String, val source: AudioSource, val youtubeResult: ResolutionResult?,
        val tried: Set<AudioSource>) {
        val failureHandled = java.util.concurrent.atomic.AtomicBoolean()
    }
    @Volatile private var activeSource: ActiveSource? = null
    /** Downloads that failed to play this run: the song streams instead. */
    private val brokenDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The current play intent's allowance, shared by its lookup, relay refreshes and recoveries. */
    @Volatile private var budget: com.auralis.music.data.network.RequestBudget? = null
    /** "trackId|source" already reopened once after a failure; cleared by new intents and steady progress. */
    @Volatile private var repairedSource: String? = null
    private var progressJob: Job? = null

    private val _establishedTrackId = MutableStateFlow<String?>(null)
    /**
     * The song whose audio is actually sounding on this PC, or null. Optional work (the sidebar's
     * companion video) waits for this: never for a restored idle song, a song still resolving, or
     * a song another device plays through Connect.
     */
    val establishedTrackId: StateFlow<String?> = _establishedTrackId.asStateFlow()

    init {
        // Only sound counts as success: a failure streak ends when audio actually plays, not when
        // a URL is handed to libVLC (it reports a refused stream a moment later).
        scope.launch {
            player.state.collect { if (it == VlcAudioPlayer.State.PLAYING) {
                startRequested?.let { (id, nanos) ->
                    startRequested = null
                    com.auralis.music.util.Log.i("DesktopPlayback", "Started $id in ${(System.nanoTime() - nanos) / 1_000_000}ms ${budget ?: ""}")
                }
                consecutiveFailures = 0
                _queue.update { q -> q.current?.let { q.copy(listenedIndices = q.listenedIndices + q.index) } ?: q }
            } }
        }
        // Crossfades keep the state PLAYING across songs, so the song's own stream identity decides.
        scope.launch {
            kotlinx.coroutines.flow.combine(player.state, lengthTrackId, _connectRemote) { s, id, remote -> Triple(s, id, remote) }
                .collect { (s, id, remote) ->
                    if (remote != null) _establishedTrackId.value = null
                    else if (s == VlcAudioPlayer.State.PLAYING && id != null && id == _queue.value.current?.id) _establishedTrackId.value = id
                }
        }
        player.onFinished = { scope.launch {
            if (_resolving.value && crossfadeSeconds > 0) return@launch
            // The relay ends a stream it couldn't repair, and libVLC may read that as the song's
            // end. A song cut short is a failed source, not a finished song.
            _queue.value.current?.let { track ->
                val upstream = currentLocalUrl?.let(LocalStreamRelay::failureFor)
                val position = player.positionMs.value
                val length = player.lengthMs.value.takeIf { it > 0L } ?: (track.duration * 1000L)
                if (upstream != null && lengthTrackId.value == track.id && length - position > EARLY_END_MS) {
                    com.auralis.music.util.Log.w("DesktopPlayback", "${track.id} ended at ${position}ms of ${length}ms after $upstream")
                    recover(track, position, upstream)
                    return@launch
                }
            }
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
                recover(track, player.positionMs.value, currentLocalUrl?.let(LocalStreamRelay::failureFor))
            }
        }
    }

    /**
     * The current source failed at [position] (a libVLC error, or a stream cut short). [upstream]
     * is what the relay saw from the source, null when the source looked fine.
     *  - A player problem, a download or another catalog's stream: reopen the same source once.
     *  - YouTube refusing one link: one fresh lookup, within the intent's recovery allowance.
     *  - Otherwise (and YouTube refusing this network): the song goes on from the next source.
     */
    private fun recover(track: Track, position: Long, upstream: com.auralis.music.data.network.PlaybackFailure?) {
        val local = currentLocalUrl
        val source = activeSource?.takeIf { it.trackId == track.id }?.source ?: AudioSource.YOUTUBE
        if (source != AudioSource.YOUTUBE || upstream == null) {
            if (local != null && reopenOnce(track, source, local, position)) return
            switchSource(track, position, upstream ?: com.auralis.music.data.network.PlaybackFailure(
                com.auralis.music.data.network.FailureCategory.PLAYER, "playback"))
            return
        }
        if (!upstream.category.networkWide && retriedTrackId != track.id &&
            budget?.tryTransition("re-resolve ${track.id} after ${upstream.category}") != false) {
            retriedTrackId = track.id
            DesktopStreamResolver.invalidate(track.id)
            load(_queue.value, resumeAtMs = position, isRetry = true)
            return
        }
        switchSource(track, position, upstream)
    }

    /**
     * Reopens [local] at [position] unless this song's [source] was already reopened since its last
     * stretch of steady playback. Asking for a new link wouldn't fix a decoder or network hiccup.
     */
    private fun reopenOnce(track: Track, source: AudioSource, local: String, position: Long, watch: Boolean = true): Boolean {
        val key = "${track.id}|$source"
        if (repairedSource == key) return false
        repairedSource = key
        com.auralis.music.util.Log.w("DesktopPlayback", "${source.name.lowercase()} source for ${track.id} failed; reopening it at ${position}ms")
        player.stop()
        player.play(local, position, track.id)
        lengthTrackId.value = track.id
        if (watch && source != AudioSource.OFFLINE) watchForStall(track)
        return true
    }

    /**
     * The current source can't go on: continue the song from [positionMs] on the next source this
     * intent hasn't tried (a broken download streams; YouTube's refusal goes to JioSaavn, then
     * SoundCloud), or hold it with the reason when none is left. Every source matches the exact
     * recording, so the position carries over.
     */
    private fun switchSource(track: Track, positionMs: Long, failure: com.auralis.music.data.network.PlaybackFailure) {
        if (_queue.value.current?.id != track.id) return
        val current = activeSource?.takeIf { it.trackId == track.id }
        if (current != null && !current.failureHandled.compareAndSet(false, true)) return
        val position = (resumeAnchor?.takeIf { it.trackId == track.id }?.positionMs ?: positionMs).coerceAtLeast(0L)
        val from = current?.source ?: AudioSource.YOUTUBE
        val tried = current?.tried.orEmpty() + from
        val youtubeResult = current?.youtubeResult?.takeIf { it !is ResolutionResult.Resolved } ?: ResolutionResult.Failed(failure)
        player.stop()
        if (from == AudioSource.OFFLINE) {
            brokenDownloads += track.id
            com.auralis.music.util.Log.w("DesktopPlayback", "Switching ${track.id} from its download to streaming at ${position}ms: $failure")
            load(_queue.value, resumeAtMs = position, isRetry = true)
            return
        }
        val intentBudget = budget
        // Holds the shown time while the next source is found.
        resumeAnchor = position.takeIf { it > 0L }?.let { ResumeAnchor(track.id, it, System.nanoTime()) }
        stallJob?.cancel()
        loadJob?.cancel()
        loadJob = scope.launch {
            _resolving.value = true
            val lookup = try { lookUpAlternate(track, tried) } finally { _resolving.value = false }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (_queue.value.current?.id != track.id) return@launch
            val alternate = lookup.alternate
            if (alternate == null) {
                com.auralis.music.util.Log.w("DesktopPlayback", "No source left for ${track.id} after ${from.name.lowercase()} failed: $failure")
                unavailable(track, youtubeResult, position, tried + lookup.attempted)
                return@launch
            }
            com.auralis.music.util.Log.w("DesktopPlayback", "Switching ${track.id} from ${from.name.lowercase()} to ${alternate.source.name.lowercase()} at ${position}ms: $failure")
            startSource(track, ActiveSource(track.id, alternate.source, youtubeResult, tried + lookup.attempted), alternate.url, position, intentBudget)
        }
    }

    fun playSingle(track: Track) = playFrom(track, listOf(track), automatic = true)

    fun playFrom(track: Track, list: List<Track>, automatic: Boolean = list.size <= 1) {
        connectAdmissionJob?.cancel()
        connectAdmissionPending = false
        selectionJob?.cancel()
        selectionJob = admit(list.ifEmpty { listOf(track) }) { allowed ->
            if (allowed.any { it.id == track.id }) playAllowed(track, allowed, automatic)
        }
    }

    private fun playAllowed(track: Track, list: List<Track>, automatic: Boolean) {
        if (intercepted("play_track", track)) return
        if (connect(com.auralis.music.data.connect.ConnectIntent.Load(list.ifEmpty { listOf(track) }, list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)))) return
        autoRadio.reset(automatic)
        pendingResumeMs = null
        val supplied = list.ifEmpty { listOf(track) }
        val unique = if (preventDuplicates) supplied.distinctBy { it.id } else supplied
        unshuffledTracks = unique.toList()
        val tracks = if (shuffleEngaged) listOf(track) + unique.filter { it.id != track.id }.shuffled() else unique
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
            VlcAudioPlayer.State.PAUSED -> resumePaused()
            VlcAudioPlayer.State.ENDED, VlcAudioPlayer.State.IDLE, VlcAudioPlayer.State.ERROR ->
                _queue.value.takeIf { it.current != null }?.let { load(it, resumeAtMs = pendingResumeMs ?: 0L); pendingResumeMs = null }
        }
    }

    /** Set a requested state without flipping it when a room repeats the same update. */
    fun setPaused(paused: Boolean) {
        when {
            paused && player.state.value == VlcAudioPlayer.State.PLAYING -> player.pause()
            !paused && player.state.value == VlcAudioPlayer.State.PAUSED -> resumePaused()
        }
    }

    /**
     * Resuming is a new request to play: the intent's allowance (whose deadline ran out during a
     * long pause) is renewed, so the relay can still fetch a fresh link if the old one expired.
     */
    private fun resumePaused() {
        budget?.renewAfterProgress()
        player.resume()
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
        _seekEvents.tryEmit(ms.coerceAtLeast(0L))
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

    /** True when [track] is already the song lined up right after the one playing. */
    fun isNext(track: Track): Boolean {
        val q = _queue.value
        return q.current != null && q.tracks.getOrNull(q.index + 1)?.id == track.id
    }

    /** Puts [track] right after the playing song (or plays it when nothing is queued). */
    fun playNext(track: Track) {
        scope.launch(admissionContext(), start = CoroutineStart.UNDISPATCHED) { queueEdits.withLock {
            if (filterTracks(listOf(track)).isNotEmpty()) enqueueAllowedNext(track)
        } }
    }

    private fun enqueueAllowedNext(track: Track) {
        if (intercepted("recommend", track)) return
        if (connect(com.auralis.music.data.connect.ConnectIntent.PlayNext(track))) return
        val q = _queue.value
        if (q.current == null) return playFrom(track, listOf(track))
        // Asking again for the song already lined up next must not stack another copy behind it.
        if (isNext(track)) return
        if (preventDuplicates && q.tracks.drop(q.index + 1).any { it.id == track.id }) return
        val tracks = q.tracks.toMutableList().apply { add(q.index + 1, track) }
        _queue.value = q.copy(tracks = tracks, listenedIndices = q.listenedIndices.map { if (it > q.index) it + 1 else it }.toSet())
    }

    /** Adds [track] to the end of the queue (or plays it when nothing is queued). */
    fun addToQueue(track: Track) {
        scope.launch(admissionContext(), start = CoroutineStart.UNDISPATCHED) { queueEdits.withLock {
            if (filterTracks(listOf(track)).isNotEmpty()) enqueueAllowed(track)
        } }
    }

    private fun enqueueAllowed(track: Track) {
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
        if (connect(com.auralis.music.data.connect.ConnectIntent.PlayIndex(remoteIndex(index), q.tracks[index].id))) return
        consecutiveFailures = 0
        pendingResumeMs = null
        val withCurrentHeard = if (q.current != null) q.copy(listenedIndices = q.listenedIndices + q.index) else q
        // Songs jumped over stay behind the selection, out of Up next; History lists only heard songs.
        load(withCurrentHeard.copy(index = index))
    }

    /** Removes a song that isn't the one playing. */
    fun removeAt(index: Int) {
        if (!applyingRoom.get() && roomQueueEdit?.invoke(index, null) == true) return
        if (intercepted("edit_queue")) return
        _queue.value.tracks.getOrNull(index)?.let { if (connect(com.auralis.music.data.connect.ConnectIntent.Remove(remoteIndex(index), it.id))) return }
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
        if (connect(com.auralis.music.data.connect.ConnectIntent.Move(remoteIndex(from), remoteIndex(to)))) return
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
        connectAdmissionJob?.cancel()
        connectAdmissionPending = false
        admissionJob?.cancel()
        selectionJob?.cancel()
        remoteAdmissionJob?.cancel()
        roomAdmissionJob?.cancel()
        autoRadio.reset(false)
        preloadJob?.cancel()
        loadJob?.cancel()
        stallJob?.cancel()
        progressJob?.cancel()
        player.release()
    }

    private fun load(requested: Queue, resumeAtMs: Long = 0L, isRetry: Boolean = false) {
        connectAdmissionJob?.cancel()
        connectAdmissionPending = false
        admissionJob?.cancel()
        loadJob?.cancel()
        admissionJob = admit(requested.tracks) { allowed ->
            val playable = requested.playable(allowed)
            if (playable.current != null) loadAllowed(playable,
                if (playable.current?.id == requested.current?.id) resumeAtMs else 0L, isRetry)
            else { player.stop(); _queue.value = Queue() }
        }
    }

    private fun loadAllowed(requested: Queue, resumeAtMs: Long, isRetry: Boolean) {
        val current = _queue.value
        // The queue plays in its own order: next, previous and jumps never move songs.
        val q = requested
        val track = q.current ?: return
        if (!isRetry) startRequested = track.id to System.nanoTime()
        if (!isRetry) _session.value++
        if (!isRetry) retriedTrackId = null
        if (!isRetry) repairedSource = null
        // A new intent gets its own allowance; a retry spends the one it already has.
        val intentBudget = budget.takeIf { isRetry } ?: com.auralis.music.data.network.RequestBudget(
            com.auralis.music.data.network.RequestBudget.newId(), "play").also { budget = it }
        if (_establishedTrackId.value != track.id) _establishedTrackId.value = null
        lengthTrackId.value = null
        _queue.value = q
        activeSource = null
        // The start point is resumeAtMs now. A held position left by an earlier failed lookup
        // (unavailable) or a silent Connect load would otherwise pin the shown time and lyrics
        // there while the song plays on, since resumePosition() returns it whenever it is set.
        pendingResumeMs = null
        resumeAnchor = resumeAtMs.takeIf { it > 0L }?.let { ResumeAnchor(track.id, it, System.nanoTime()) }
        autoRadio.queueChanged()
        loadJob?.cancel()
        preloadJob?.cancel()
        stallJob?.cancel()
        progressJob?.cancel()
        val fade = crossfadeSeconds > 0 && roomControl == null && !isRetry && resumeAtMs == 0L &&
            current.current != null && current.current?.id != track.id && player.state.value == VlcAudioPlayer.State.PLAYING
        if (!fade) player.stop()
        if (!isRetry) roomTrackWillLoad?.invoke(track)
        player.warmOutput()
        loadJob = scope.launch {
            _resolving.value = true
            val download = offlineFile(track.id)?.takeIf { track.id !in brokenDownloads }
            val choice = try {
                if (download != null) Choice(AudioSource.OFFLINE, download.absolutePath, null, setOf(AudioSource.OFFLINE))
                else chooseStream(track, intentBudget)
            } finally {
                _resolving.value = false
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (_queue.value.current?.id != track.id) return@launch
            val startAt = resumeAnchor?.takeIf { it.trackId == track.id }?.positionMs ?: resumeAtMs
            val url = choice.url
            if (url == null) unavailable(track, choice.youtube ?: SLOW_YOUTUBE, startAt, choice.tried)
            else startSource(track, ActiveSource(track.id, choice.source, choice.youtube, choice.tried), url, startAt, intentBudget)
        }
    }

    /** Where a song will play from; [url] is null when no source could play it ([youtube] says why). */
    private class Choice(val source: AudioSource, val url: String?, val youtube: ResolutionResult?, val tried: Set<AudioSource>)
    private class Alternate(val source: AudioSource, val url: String)
    private class AlternateLookup(val alternate: Alternate?, val attempted: Set<AudioSource>)

    private val hasAlternates: Boolean get() = resolveJioSaavn != null || resolveSoundCloud != null

    /**
     * YouTube first; when it refuses or fails, the same recording on the other catalogs. A YouTube
     * lookup still running after [HEDGE_AFTER_MS] gets the other catalogs asked alongside it and the
     * first playable answer wins: a slow extraction (or a Spotify import's search) would otherwise
     * leave silence for the intent's whole allowance.
     */
    private suspend fun chooseStream(track: Track, intentBudget: com.auralis.music.data.network.RequestBudget): Choice = kotlinx.coroutines.coroutineScope {
        val youtube = async { lookUpYouTube(track, intentBudget) }
        val quick = if (hasAlternates) kotlinx.coroutines.withTimeoutOrNull(HEDGE_AFTER_MS) { youtube.await() } else youtube.await()
        if (quick != null) {
            if (quick.url != null) return@coroutineScope Choice(AudioSource.YOUTUBE, quick.url, quick, setOf(AudioSource.YOUTUBE))
            return@coroutineScope afterYouTube(quick, lookUpAlternate(track, setOf(AudioSource.YOUTUBE)))
        }
        val others = async { lookUpAlternate(track, setOf(AudioSource.YOUTUBE)) }
        kotlinx.coroutines.selects.select<Choice> {
            youtube.onAwait { result ->
                if (result.url != null) {
                    others.cancel()
                    Choice(AudioSource.YOUTUBE, result.url, result, setOf(AudioSource.YOUTUBE))
                } else afterYouTube(result, others.await())
            }
            others.onAwait { lookup ->
                val alternate = lookup.alternate
                if (alternate != null) {
                    youtube.cancel()
                    com.auralis.music.util.Log.w("DesktopPlayback", "YouTube is slow for ${track.id}; playing ${alternate.source.name.lowercase()} instead")
                    Choice(alternate.source, alternate.url, SLOW_YOUTUBE, setOf(AudioSource.YOUTUBE) + lookup.attempted)
                } else {
                    val result = youtube.await()
                    if (result.url != null) Choice(AudioSource.YOUTUBE, result.url, result, setOf(AudioSource.YOUTUBE))
                    else afterYouTube(result, lookup)
                }
            }
        }
    }

    private fun afterYouTube(youtube: ResolutionResult, lookup: AlternateLookup): Choice {
        val tried = setOf(AudioSource.YOUTUBE) + lookup.attempted
        return lookup.alternate?.let { Choice(it.source, it.url, youtube, tried) } ?: Choice(AudioSource.YOUTUBE, null, youtube, tried)
    }

    /**
     * Starts [url] from [source] at [startAt] and watches it. YouTube links go through the relay
     * with a refresh; other catalogs' files through the relay without one; HLS playlists (some
     * SoundCloud uploads) straight to libVLC, which reads them itself.
     */
    private suspend fun startSource(track: Track, source: ActiveSource, url: String, startAt: Long,
        intentBudget: com.auralis.music.data.network.RequestBudget?) {
        // A sleeping device (Bluetooth earbuds) misses the first second or two of whatever
        // plays as it wakes; give a cold one that long on silence first.
        val awake = player.warmOutput()
        if (awake < OUTPUT_WAKE_MS) {
            kotlinx.coroutines.delay(OUTPUT_WAKE_MS - awake)
            if (_queue.value.current?.id != track.id) return
        }
        player.setVolume(_volume.value)
        val local = when {
            source.source == AudioSource.OFFLINE -> url
            source.source == AudioSource.YOUTUBE && intentBudget != null -> LocalStreamRelay.register(url, refresh = relayRefresh(track, intentBudget),
                tag = com.auralis.music.data.network.YouTubeTraffic.Tag(intentBudget.correlationId, "play", intentBudget))
            isPlaylist(url) -> url
            else -> LocalStreamRelay.register(url)
        }
        currentLocalUrl = local
        activeSource = source
        if (source.source == AudioSource.JIOSAAVN || source.source == AudioSource.SOUNDCLOUD)
            com.auralis.music.util.Log.i("DesktopPlayback", "Selected ${source.source.name.lowercase()} fallback for ${track.id} at ${startAt}ms")
        player.play(local, startAt, track.id)
        lengthTrackId.value = track.id
        if (source.source != AudioSource.OFFLINE) watchForStall(track)
        watchProgress(track)
    }

    private fun isPlaylist(url: String): Boolean =
        runCatching { java.net.URI(url).path.orEmpty().lowercase().contains(".m3u8") }.getOrDefault(false)

    private suspend fun lookUpYouTube(track: Track, intentBudget: com.auralis.music.data.network.RequestBudget): ResolutionResult = try {
        kotlinx.coroutines.withContext(com.auralis.music.data.network.PlaybackIntentContext(intentBudget)) { lookUp(track, intentBudget) }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        com.auralis.music.util.Log.w("DesktopPlayback", "Playback lookup failed for ${track.id}: ${error.javaClass.simpleName}")
        ResolutionResult.Failed(com.auralis.music.data.network.FailureClassifier.classify(error, "resolve"))
    }

    /**
     * JioSaavn, then SoundCloud, skipping any in [tried]: the first with the exact recording. Any
     * quality setting qualifies: a fallback stream beats not playing the song at all. A failing or
     * slow catalog is a miss; only canceling the caller propagates.
     */
    private suspend fun lookUpAlternate(track: Track, tried: Set<AudioSource>): AlternateLookup {
        val attempted = mutableSetOf<AudioSource>()
        for ((source, resolver) in listOf(AudioSource.JIOSAAVN to resolveJioSaavn, AudioSource.SOUNDCLOUD to resolveSoundCloud)) {
            if (source in tried || resolver == null) continue
            attempted += source
            val url = try {
                resolver(track)
            } catch (error: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                null
            } catch (error: Throwable) {
                com.auralis.music.util.Log.w("DesktopPlayback", "${source.name.lowercase()} lookup failed for ${track.id}: ${error.javaClass.simpleName}")
                null
            }
            if (url != null) return AlternateLookup(Alternate(source, url), attempted)
        }
        return AlternateLookup(null, attempted)
    }

    /** The song's stream: its matched upload, or another upload of the recording when that one can't play. */
    private suspend fun lookUp(track: Track, intentBudget: com.auralis.music.data.network.RequestBudget): ResolutionResult {
        val videoId = DesktopPlaybackMatch.videoId(track)
            ?: return YouTubeBackoff.retryInMs().takeIf { it > 0L }?.let {
                ResolutionResult.Deferred(it, com.auralis.music.data.network.PlaybackFailure(
                    YouTubeBackoff.cooldown.snapshot()?.cause.toCategory(), "match", detail = "cooldown"))
            } ?: ResolutionResult.Failed(com.auralis.music.data.network.PlaybackFailure(
                com.auralis.music.data.network.FailureCategory.CONTENT_UNAVAILABLE, "match", detail = "no matching upload"))
        val primary = resolveDetailed(videoId, RequestPriority.FOREGROUND)
        // Another upload only helps when this one is restricted or missing, not when YouTube
        // is refusing this network (every upload would fail alike), and only within the allowance.
        if (primary !is ResolutionResult.Failed || !primary.failure.category.alternateMayHelp ||
            !intentBudget.tryTransition("alternate upload for ${track.id} after ${primary.failure.category}")) return primary
        val alternate = DesktopPlaybackMatch.alternative(track, videoId) { resolveDetailed(it, RequestPriority.FOREGROUND).url }
        return alternate?.let { ResolutionResult.Resolved(PlaybackResource.fromUrl(it.first, it.second)) } ?: primary
    }

    private fun YouTubeCooldown.Cause?.toCategory() = when (this) {
        YouTubeCooldown.Cause.RATE_LIMITED -> com.auralis.music.data.network.FailureCategory.RATE_LIMITED
        YouTubeCooldown.Cause.VERIFICATION -> com.auralis.music.data.network.FailureCategory.VERIFICATION_CHALLENGE
        else -> com.auralis.music.data.network.FailureCategory.TRANSIENT
    }

    /**
     * How the relay gets a new link for [track] when YouTube stops serving the current one: only
     * while [track] is still the song playing, and only with the intent's recovery allowance.
     */
    private fun relayRefresh(track: Track, intentBudget: com.auralis.music.data.network.RequestBudget): (LocalStreamRelay.Refresh) -> String? = { refresh ->
        if (_queue.value.current?.id != track.id) null
        else if (!intentBudget.tryTransition("fresh link for ${track.id} after ${refresh.failure.category}")) null
        else DesktopStreamResolver.refreshBlocking(track.id, refresh.failedUrl, refresh.failure, intentBudget)
    }

    /**
     * Meaningful progress (time actually played, not a URL handed over) renews the intent's
     * allowance and lets the next song's stream be prepared: never before, so preloading can't
     * compete with a song that is still starting or recovering.
     */
    private fun watchProgress(track: Track) {
        progressJob?.cancel()
        progressJob = scope.launch {
            var prefetched = false
            var played = 0L
            var last = -1L
            while (_queue.value.current?.id == track.id) {
                kotlinx.coroutines.delay(1000)
                if (player.state.value == VlcAudioPlayer.State.PLAYING && lengthTrackId.value == track.id) {
                    val position = player.positionMs.value
                    if (last >= 0 && position > last) played += minOf(position - last, 2_000L)
                    last = position
                } else last = -1L
                if (played >= PROGRESS_RENEW_MS) {
                    played = 0L
                    retriedTrackId = null
                    stallRetries = 0
                    repairedSource = null
                    budget?.renewAfterProgress()
                }
                if (!prefetched && played >= PREFETCH_AFTER_MS) {
                    prefetched = true
                    // A song on another catalog means YouTube is refusing it; asking ahead would be refused too.
                    val source = activeSource?.takeIf { it.trackId == track.id }?.source
                    if (source != AudioSource.JIOSAAVN && source != AudioSource.SOUNDCLOUD) prefetchNext(track)
                }
            }
        }
    }

    private fun nextIdOf(q: Queue): String? = q.tracks.getOrNull(q.index + 1)?.takeIf { q.hasNext }?.id

    /** One song ahead, at background priority, abandoned the moment it stops being next. */
    private fun prefetchNext(track: Track) {
        val q = _queue.value
        if (!preloadNext || q.current?.id != track.id || !q.hasNext || roomControl != null || _connectRemote.value != null) return
        val next = q.tracks[q.index + 1]
        if (offlineFile(next.id) != null || YouTubeBackoff.active) return
        preloadJob?.cancel()
        preloadJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val prefetchBudget = com.auralis.music.data.network.RequestBudget(com.auralis.music.data.network.RequestBudget.newId("n"), "prefetch",
                deadlineMs = com.auralis.music.data.network.RequestBudget.BACKGROUND_DEADLINE_MS, recoveryTransitions = 0)
            val work = async {
                kotlinx.coroutines.withContext(com.auralis.music.data.network.PlaybackIntentContext(prefetchBudget)) {
                    val id = com.auralis.music.data.network.RecordingMatches.getMatchedVideoId(next.id)
                        ?: (if (DesktopPlaybackMatch.isVideoId(next.id)) next.id else DesktopPlaybackMatch.videoId(next))
                    if (id == null || !DesktopPlaybackMatch.isVideoId(id)) null else resolveDetailed(id, RequestPriority.BACKGROUND).url
                }
            }
            val watcher = launch { _queue.first { nextIdOf(it) != next.id }; work.cancel() }
            try {
                val nextUrl = runCatching { work.await() }.getOrNull() ?: return@launch
                if (nextIdOf(_queue.value) != next.id) return@launch
                // The next song's first seconds of audio, so skipping starts at once.
                LocalStreamRelay.preload(nextUrl, refresh = null,
                    tag = com.auralis.music.data.network.YouTubeTraffic.Tag(prefetchBudget.correlationId, "prefetch", prefetchBudget))
            } finally {
                watcher.cancel()
            }
        }
    }

    private var stallJob: Job? = null
    /** Recoveries for a song that stalled; reset when another song is picked or it plays on. */
    private var stallRetries = 0
    private var stallTrackId: String? = null

    /**
     * A stream can open but never deliver (or stop mid-song) without libVLC reporting an error.
     * No sound within [START_STALL_MS] of starting, or no progress for [PLAY_STALL_MS] while
     * playing: first the same source is reopened (no YouTube request) unless the relay saw its
     * upstream fail, then a fresh link is fetched if the intent has recovery allowance left.
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
                val resumeAt = if (lastPosition < 0) (resumeAnchor?.takeIf { it.trackId == track.id }?.positionMs ?: 0L) else position
                val local = currentLocalUrl
                val upstream = local?.let(LocalStreamRelay::failureFor)
                val stalled = upstream ?: com.auralis.music.data.network.PlaybackFailure(
                    com.auralis.music.data.network.FailureCategory.TRANSIENT, "playback", detail = "stalled")
                com.auralis.music.util.Log.w("DesktopPlayback", "${track.id} stalled ${if (lastPosition < 0) "before starting" else "at ${position}ms"} ${upstream ?: ""}")
                // Another catalog's stream: reopen it once, then the next source.
                val source = activeSource?.takeIf { it.trackId == track.id }?.source ?: AudioSource.YOUTUBE
                if (source != AudioSource.YOUTUBE) {
                    if (local != null && reopenOnce(track, source, local, resumeAt, watch = false)) {
                        lastPosition = -1L
                        since = System.nanoTime()
                        continue
                    }
                    switchSource(track, resumeAt, stalled)
                    return@launch
                }
                // YouTube refusing this network (or two recoveries already spent): other catalogs.
                if ((upstream != null && upstream.category.networkWide) || stallRetries >= 2) {
                    switchSource(track, resumeAt, stalled)
                    return@launch
                }
                stallRetries++
                if (upstream == null && local != null && stallRetries == 1) {
                    com.auralis.music.util.Log.w("DesktopPlayback", "Reopening the same source for ${track.id}")
                    player.stop()
                    player.play(local, resumeAt, track.id)
                    lengthTrackId.value = track.id
                    lastPosition = -1L
                    since = System.nanoTime()
                    continue
                }
                if (budget?.tryTransition("fresh link for stalled ${track.id}${upstream?.let { " after ${it.category}" } ?: ""}") == false) {
                    switchSource(track, resumeAt, stalled)
                    return@launch
                }
                com.auralis.music.util.Log.w("DesktopPlayback", "${track.id} stalled; fetching a fresh stream ($stallRetries/2)")
                DesktopStreamResolver.invalidate(track.id)
                load(_queue.value, resumeAtMs = resumeAt, isRetry = true)
                return@launch
            }
        }
    }

    private companion object {
        const val OUTPUT_WAKE_MS = 1000L
        const val START_STALL_MS = 6000L
        const val PLAY_STALL_MS = 10_000L
        /** Played time that counts as meaningful progress (renews the recovery allowance). */
        const val PROGRESS_RENEW_MS = 20_000L
        /** Played time before the next song's stream is prepared. */
        const val PREFETCH_AFTER_MS = 15_000L
        /** How long YouTube may take before the other catalogs are asked alongside it. */
        const val HEDGE_AFTER_MS = 8_000L
        /** Audio left when a stream "finishes" that means it was cut short, not that it ended. */
        const val EARLY_END_MS = 5_000L
        /** What a YouTube lookup canceled for being slow counts as, should every other source fail. */
        val SLOW_YOUTUBE: ResolutionResult = ResolutionResult.Failed(com.auralis.music.data.network.PlaybackFailure(
            com.auralis.music.data.network.FailureCategory.TRANSIENT, "resolve", detail = "slow"))
        val DEFAULT_RESOLVE: suspend (String) -> String? = { DesktopStreamResolver.resolve(it) }
    }

    /**
     * The song couldn't start. When YouTube is refusing this network (rate limit, verification,
     * offline) or the attempt ran out of time or allowance, every song would fail alike: keep the
     * queue, the song and its position, say why, and send nothing more until play is pressed.
     * A failure specific to this song moves on as before (at most three in a row). [tried] names
     * the sources that were asked, so the message can say the other catalogs couldn't help either.
     */
    private fun unavailable(track: Track, result: ResolutionResult, positionMs: Long, tried: Set<AudioSource> = emptySet()) {
        val failure = when (result) {
            is ResolutionResult.Failed -> result.failure
            is ResolutionResult.Deferred -> result.failure
            else -> return
        }
        val category = failure.category
        val keep = result is ResolutionResult.Deferred || category.networkWide ||
            category == com.auralis.music.data.network.FailureCategory.TRANSIENT ||
            category == com.auralis.music.data.network.FailureCategory.BUDGET_EXHAUSTED
        if (!keep) return failed(track, failure, tried)
        // A failed crossfade lookup can leave the outgoing song alive. Stop it so its
        // eventual end event cannot advance the queue past the song we are holding.
        player.stop()
        val wait = (result as? ResolutionResult.Deferred)?.retryInMs ?: YouTubeBackoff.retryInMs()
        com.auralis.music.util.Log.w("DesktopPlayback", "Holding ${track.id} at ${positionMs}ms: $failure ${budget ?: ""}")
        pendingResumeMs = positionMs.coerceAtLeast(0L).takeIf { it > 0L }
        resumeAnchor = null
        val fallback = if (category == com.auralis.music.data.network.FailureCategory.VERIFICATION_CHALLENGE) {
            val videoId = RecordingMatches.getMatchedVideoId(track.id) ?: track.id
            videoId.takeIf(DesktopPlaybackMatch::isVideoId)?.let {
                YouTubeFallback(track.id, it, positionMs.coerceAtLeast(0L) / 1000L)
            }
        } else null
        _errors.tryEmit(PlaybackError(messageFor(track, failure, wait, tried), fallback))
    }

    private fun failed(track: Track, failure: com.auralis.music.data.network.PlaybackFailure? = null, tried: Set<AudioSource> = emptySet()) {
        consecutiveFailures++
        // A refusal of this network fails every song alike: skipping on would only burn through the queue.
        val blocked = failure?.category?.networkWide == true || (DesktopPlaybackMatch.isVideoId(track.id) && YouTubeBackoff.active)
        if (blocked) {
            com.auralis.music.util.Log.w("DesktopPlayback", "Not skipping past ${track.id}: YouTube is refusing this network")
            unavailable(track, ResolutionResult.Failed(failure?.takeIf { it.category.networkWide }
                ?: com.auralis.music.data.network.PlaybackFailure(YouTubeBackoff.cooldown.snapshot()?.cause.toCategory())),
                resumeAnchor?.positionMs ?: 0L, tried)
            return
        }
        _errors.tryEmit(PlaybackError(failure?.let { messageFor(track, it, 0L, tried) } ?: "Couldn't play “${track.title}”"))
        if (consecutiveFailures < 3 && _queue.value.current?.id == track.id && _queue.value.hasNext) {
            next()
        } else if (consecutiveFailures >= 3) {
            com.auralis.music.util.Log.w("DesktopPlayback", "Stopped skipping after $consecutiveFailures songs in a row failed")
            _errors.tryEmit(PlaybackError("Playback isn't working right now. Check your connection and try again."))
        }
    }

    /** What the listener is told; any time given is when Auralis may try again, not when YouTube relents. */
    private fun messageFor(track: Track, failure: com.auralis.music.data.network.PlaybackFailure, waitMs: Long,
        tried: Set<AudioSource> = emptySet()): String {
        val retry = when {
            waitMs <= 0L -> ""
            waitMs < 90_000L -> " Auralis can try again in about ${(waitMs + 999) / 1000}s."
            else -> " Auralis can try again in about ${(waitMs + 59_999) / 60_000} min."
        }
        val catalogs = listOfNotNull("JioSaavn".takeIf { AudioSource.JIOSAAVN in tried }, "SoundCloud".takeIf { AudioSource.SOUNDCLOUD in tried })
        val elsewhere = if (catalogs.isEmpty()) "" else " ${catalogs.joinToString(" and ")} couldn't play it either."
        return when (failure.category) {
            com.auralis.music.data.network.FailureCategory.RATE_LIMITED ->
                "YouTube is rate limiting requests from this network, so streaming is paused.$elsewhere$retry Your queue and position are kept."
            com.auralis.music.data.network.FailureCategory.VERIFICATION_CHALLENGE ->
                "YouTube requires verification to stream this track.$elsewhere$retry Downloaded songs still play."
            com.auralis.music.data.network.FailureCategory.NETWORK_UNAVAILABLE ->
                "Can't reach YouTube.$elsewhere Check your connection; your queue and position are kept."
            com.auralis.music.data.network.FailureCategory.TRANSIENT ->
                "YouTube didn't answer in time for “${track.title}”.$elsewhere Press play to try again; your place is kept."
            com.auralis.music.data.network.FailureCategory.BUDGET_EXHAUSTED ->
                "Couldn't start “${track.title}” after a few tries.$elsewhere Press play to try again; your place is kept."
            com.auralis.music.data.network.FailureCategory.RESTRICTED ->
                "“${track.title}” is restricted (age, account or region) and can't be played here.$elsewhere"
            com.auralis.music.data.network.FailureCategory.CONTENT_UNAVAILABLE -> "“${track.title}” isn't available to stream.$elsewhere"
            com.auralis.music.data.network.FailureCategory.LOGIN_REQUIRED -> "YouTube wants a sign-in to play “${track.title}”.$elsewhere"
            else -> if (elsewhere.isEmpty()) "Couldn't play “${track.title}”" else "Couldn't play “${track.title}”.$elsewhere"
        }
    }
}
