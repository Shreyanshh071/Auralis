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
import kotlinx.coroutines.launch

/**
 * The desktop queue: what is playing, what comes next, and the controls. Like the phone, tapping
 * a song in a list plays it and queues the rest of that list after it.
 */
class DesktopPlaybackController(
    private val player: VlcAudioPlayer,
    private val scope: CoroutineScope,
    private val offlineFile: (String) -> java.io.File? = { com.auralis.music.desktop.library.DesktopDownloads.instance.localFile(it) },
    private val resolveStream: suspend (String) -> String? = { DesktopStreamResolver.resolve(it) }
) {
    enum class RepeatMode { OFF, ALL, ONE }
    data class Queue(val tracks: List<Track> = emptyList(), val index: Int = -1) {
        val current: Track? get() = tracks.getOrNull(index)
        val hasNext: Boolean get() = index in 0 until tracks.lastIndex
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
        if (roomControl != null) return
        _repeatMode.value = RepeatMode.entries[(_repeatMode.value.ordinal + 1) % RepeatMode.entries.size]
    }

    private val _queue = MutableStateFlow(Queue())
    val queue: StateFlow<Queue> = _queue.asStateFlow()
    private val _session = MutableStateFlow(0L)
    val session: StateFlow<Long> = _session.asStateFlow()
    /** Active rooms route guest controls to the host instead of changing local playback. */
    @Volatile var roomControl: ((String, Track?, Long) -> Boolean)? = null
    private val applyingRoom = ThreadLocal.withInitial { false }
    fun <T> fromRoom(block: () -> T): T {
        val before = applyingRoom.get()
        applyingRoom.set(true)
        return try { block() } finally { applyingRoom.set(before) }
    }
    private fun intercepted(action: String, track: Track? = null, position: Long = 0): Boolean =
        !applyingRoom.get() && roomControl?.invoke(action, track, position) == true

    fun syncRoomQueue(tracks: List<Track>, index: Int) {
        if (index in tracks.indices && tracks[index].id == _queue.value.current?.id) _queue.value = Queue(tracks, index)
    }

    /** Rearrange only upcoming songs; keep the playing track, history and stream untouched. */
    fun shuffleUpcoming() {
        if (roomControl != null) return // The room host owns the shared queue order.
        val queue = _queue.value
        if (!queue.hasNext) return
        _queue.value = queue.copy(tracks = queue.tracks.take(queue.index + 1) + queue.tracks.drop(queue.index + 1).shuffled())
    }

    /** True while the stream URL for the current song is being looked up. */
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    val state: StateFlow<VlcAudioPlayer.State> get() = player.state
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
    val clock: com.auralis.music.data.service.PlaybackClockSource get() = player.clock
    @Volatile var preloadNext: Boolean = true
    fun setRate(rate: Float) = player.setRate(rate)
    private var preloadJob: Job? = null

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<String> = _errors

    private var loadJob: Job? = null
    /** Songs that failed in a row; stops auto-skipping when a whole list fails (e.g. offline). */
    private var consecutiveFailures = 0
    /** The song whose stream was already looked up again after a playback error. */
    private var retriedTrackId: String? = null

    /** The stream URL handed to libVLC for the current song. */
    @Volatile private var currentUrl: String? = null

    init {
        // Only sound counts as success: a failure streak ends when audio actually plays, not when
        // a URL is handed to libVLC (it reports a refused stream a moment later).
        scope.launch {
            player.state.collect { if (it == VlcAudioPlayer.State.PLAYING) consecutiveFailures = 0 }
        }
        player.onFinished = { scope.launch {
            if (roomControl != null) { if (_queue.value.hasNext) next() }
            else _queue.value.afterFinished(_repeatMode.value)?.let { load(it) }
        } }
        player.onError = {
            scope.launch {
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

    fun playFrom(track: Track, list: List<Track>) {
        if (intercepted("play_track", track)) return
        val tracks = list.ifEmpty { listOf(track) }
        val index = tracks.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: 0
        consecutiveFailures = 0
        load(Queue(tracks, index))
    }

    fun togglePlayPause() {
        if (intercepted(if (player.state.value == VlcAudioPlayer.State.PLAYING) "pause" else "play")) return
        when (player.state.value) {
            VlcAudioPlayer.State.PLAYING, VlcAudioPlayer.State.BUFFERING -> player.pause()
            VlcAudioPlayer.State.PAUSED -> player.resume()
            VlcAudioPlayer.State.ENDED, VlcAudioPlayer.State.IDLE, VlcAudioPlayer.State.ERROR ->
                _queue.value.takeIf { it.current != null }?.let { load(it) }
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
        val q = _queue.value
        if (q.hasNext) load(q.copy(index = q.index + 1))
        else if (roomControl == null && q.current != null && _repeatMode.value == RepeatMode.ALL) load(q.copy(index = 0))
    }

    /** Like the phone: restarts the song when more than 3 seconds in, otherwise goes back one. */
    fun previous() {
        if (intercepted("previous")) return
        val q = _queue.value
        if (player.positionMs.value > 3000L || q.index <= 0) seekTo(0L)
        else load(q.copy(index = q.index - 1))
    }

    fun seekTo(ms: Long) { if (!intercepted("seek", position = ms)) player.seekTo(ms) }

    /** Seeks [deltaMs] forward (or back when negative), within the song. */
    fun seekBy(deltaMs: Long) {
        if (_queue.value.current == null) return
        val length = player.lengthMs.value
        val target = (player.positionMs.value + deltaMs).coerceAtLeast(0L)
        seekTo(if (length > 0L) target.coerceAtMost(length - 1000L) else target)
    }

    fun volumeBy(delta: Float) = setVolume(_volume.value + delta)

    private var volumeBeforeMute = 1f

    /** Mutes, or brings back the volume from before muting. */
    fun toggleMute() {
        if (_volume.value > 0f) {
            volumeBeforeMute = _volume.value
            setVolume(0f)
        } else {
            setVolume(volumeBeforeMute.takeIf { it > 0f } ?: 1f)
        }
    }

    /** Puts [track] right after the playing song (or plays it when nothing is queued). */
    fun playNext(track: Track) {
        if (intercepted("recommend", track)) return
        val q = _queue.value
        if (q.current == null) return playFrom(track, listOf(track))
        val tracks = q.tracks.toMutableList().apply { add(q.index + 1, track) }
        _queue.value = q.copy(tracks = tracks)
    }

    /** Adds [track] to the end of the queue (or plays it when nothing is queued). */
    fun addToQueue(track: Track) {
        if (intercepted("recommend", track)) return
        val q = _queue.value
        if (q.current == null) return playFrom(track, listOf(track))
        _queue.value = q.copy(tracks = q.tracks + track)
    }

    /** Plays the song at [index] in the queue (the queue itself stays as it is). */
    fun playAt(index: Int) {
        val q = _queue.value
        if (index !in q.tracks.indices) return
        if (intercepted("play_track", q.tracks[index])) return
        consecutiveFailures = 0
        load(q.copy(index = index))
    }

    /** Removes a song that isn't the one playing. */
    fun removeAt(index: Int) {
        if (intercepted("edit_queue")) return
        val q = _queue.value
        if (index !in q.tracks.indices || index == q.index) return
        val tracks = q.tracks.toMutableList().apply { removeAt(index) }
        _queue.value = Queue(tracks, if (index < q.index) q.index - 1 else q.index)
    }

    /** Audio outputs for the player bar's device picker (id to name), and the chosen one. */
    fun outputDevices(): List<Pair<String, String>> = player.outputDevices()
    val outputDevice: String? get() = player.preferredDevice
    fun setOutputDevice(id: String?) = player.setOutputDevice(id)

    /** Clears History: every song before the one playing. */
    fun clearHistory() {
        if (intercepted("edit_queue")) return
        val q = _queue.value
        if (q.index <= 0) return
        _queue.value = Queue(q.tracks.drop(q.index), 0)
    }

    fun setVolume(volume: Float) {
        _volume.value = volume.coerceIn(0f, 1f)
        player.setVolume(_volume.value)
    }

    fun release() {
        preloadJob?.cancel()
        loadJob?.cancel()
        player.release()
    }

    private fun load(q: Queue, resumeAtMs: Long = 0L, isRetry: Boolean = false) {
        val track = q.current ?: return
        if (!isRetry) _session.value++
        if (!isRetry) retriedTrackId = null
        lengthTrackId.value = null
        _queue.value = q
        loadJob?.cancel()
        preloadJob?.cancel()
        player.stop()
        loadJob = scope.launch {
            _resolving.value = true
            val localAudio = offlineFile(track.id)
            val url = try {
                localAudio?.absolutePath
                    ?: run {
                        val videoId = DesktopPlaybackMatch.videoId(track)
                        if (videoId == null) null else resolveStream(videoId)
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
                player.setVolume(_volume.value)
                currentUrl = url
                // Through the local relay, which fetches the stream in pieces YouTube accepts.
                player.play(if (localAudio != null) url else LocalStreamRelay.register(url, refresh = { DesktopStreamResolver.refreshBlocking(track.id) }), resumeAtMs)
                lengthTrackId.value = track.id
                if (preloadNext && q.hasNext) {
                    preloadJob?.cancel()
                    preloadJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val next = q.tracks[q.index + 1]
                        // Do not start extra searches for unmatched imports in the background.
                        val id = com.auralis.music.data.network.RecordingMatches.getMatchedVideoId(next.id) ?: next.id
                        if (DesktopPlaybackMatch.isVideoId(id)) resolveStream(id)
                    }
                }
            }
        }
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
