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
import kotlinx.coroutines.launch

/**
 * The desktop queue: what is playing, what comes next, and the controls. Like the phone, tapping
 * a song in a list plays it and queues the rest of that list after it.
 */
class DesktopPlaybackController(
    private val player: VlcAudioPlayer,
    private val scope: CoroutineScope
) {
    data class Queue(val tracks: List<Track> = emptyList(), val index: Int = -1) {
        val current: Track? get() = tracks.getOrNull(index)
        val hasNext: Boolean get() = index in 0 until tracks.lastIndex
    }

    private val _queue = MutableStateFlow(Queue())
    val queue: StateFlow<Queue> = _queue.asStateFlow()

    /** True while the stream URL for the current song is being looked up. */
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    val state: StateFlow<VlcAudioPlayer.State> get() = player.state
    val positionMs: StateFlow<Long> get() = player.positionMs
    val lengthMs: StateFlow<Long> get() = player.lengthMs
    /** Smooth playback clock for word-synced lyrics. */
    val clock: com.auralis.music.data.service.PlaybackClockSource get() = player.clock

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
        player.onFinished = { scope.launch { if (_queue.value.hasNext) next() } }
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
        val tracks = list.ifEmpty { listOf(track) }
        val index = tracks.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: 0
        consecutiveFailures = 0
        load(Queue(tracks, index))
    }

    fun togglePlayPause() {
        when (player.state.value) {
            VlcAudioPlayer.State.PLAYING, VlcAudioPlayer.State.BUFFERING -> player.pause()
            VlcAudioPlayer.State.PAUSED -> player.resume()
            VlcAudioPlayer.State.ENDED, VlcAudioPlayer.State.IDLE, VlcAudioPlayer.State.ERROR ->
                _queue.value.takeIf { it.current != null }?.let { load(it) }
        }
    }

    fun next() {
        val q = _queue.value
        if (q.hasNext) load(q.copy(index = q.index + 1))
    }

    /** Like the phone: restarts the song when more than 3 seconds in, otherwise goes back one. */
    fun previous() {
        val q = _queue.value
        if (player.positionMs.value > 3000L || q.index <= 0) seekTo(0L)
        else load(q.copy(index = q.index - 1))
    }

    fun seekTo(ms: Long) = player.seekTo(ms)

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
        val q = _queue.value
        if (q.current == null) return playFrom(track, listOf(track))
        val tracks = q.tracks.toMutableList().apply { add(q.index + 1, track) }
        _queue.value = q.copy(tracks = tracks)
    }

    /** Adds [track] to the end of the queue (or plays it when nothing is queued). */
    fun addToQueue(track: Track) {
        val q = _queue.value
        if (q.current == null) return playFrom(track, listOf(track))
        _queue.value = q.copy(tracks = q.tracks + track)
    }

    /** Plays the song at [index] in the queue (the queue itself stays as it is). */
    fun playAt(index: Int) {
        val q = _queue.value
        if (index !in q.tracks.indices) return
        consecutiveFailures = 0
        load(q.copy(index = index))
    }

    /** Removes a song that isn't the one playing. */
    fun removeAt(index: Int) {
        val q = _queue.value
        if (index !in q.tracks.indices || index == q.index) return
        val tracks = q.tracks.toMutableList().apply { removeAt(index) }
        _queue.value = Queue(tracks, if (index < q.index) q.index - 1 else q.index)
    }

    /** Clears History: every song before the one playing. */
    fun clearHistory() {
        val q = _queue.value
        if (q.index <= 0) return
        _queue.value = Queue(q.tracks.drop(q.index), 0)
    }

    fun setVolume(volume: Float) {
        _volume.value = volume.coerceIn(0f, 1f)
        player.setVolume(_volume.value)
    }

    fun release() {
        loadJob?.cancel()
        player.release()
    }

    private fun load(q: Queue, resumeAtMs: Long = 0L, isRetry: Boolean = false) {
        val track = q.current ?: return
        if (!isRetry) retriedTrackId = null
        _queue.value = q
        loadJob?.cancel()
        player.stop()
        loadJob = scope.launch {
            _resolving.value = true
            val url = try {
                DesktopStreamResolver.resolve(track.id)
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
                player.play(LocalStreamRelay.register(url, refresh = { DesktopStreamResolver.refreshBlocking(track.id) }), resumeAtMs)
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
            _errors.tryEmit("Playback isn't working right now. Check your connection and try again.")
        }
    }
}
