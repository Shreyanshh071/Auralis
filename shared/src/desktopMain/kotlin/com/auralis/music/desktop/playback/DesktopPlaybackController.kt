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

    init {
        player.onFinished = { scope.launch { if (_queue.value.hasNext) next() } }
        player.onError = {
            scope.launch {
                val track = _queue.value.current ?: return@launch
                // A stream URL can expire or be refused; look it up once more before giving up.
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
                player.play(url, resumeAtMs)
                consecutiveFailures = 0
            }
        }
    }

    private fun failed(track: Track) {
        consecutiveFailures++
        _errors.tryEmit("Couldn't play “${track.title}”")
        if (consecutiveFailures < 3 && _queue.value.current?.id == track.id && _queue.value.hasNext) next()
    }
}
