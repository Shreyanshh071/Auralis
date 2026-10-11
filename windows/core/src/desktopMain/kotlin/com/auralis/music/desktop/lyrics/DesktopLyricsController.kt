package com.auralis.music.desktop.lyrics

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.RecordingMatches
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Lyrics for whatever is playing: looked up on every song change, like the phone's player. */
class DesktopLyricsController(
    private val playback: DesktopPlaybackController,
    private val repository: DesktopLyricsRepository,
    private val scope: CoroutineScope
) {
    data class State(val trackId: String? = null, val lyrics: LyricsData? = null, val isLoading: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            playback.queue.map { it.current }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { track ->
                load(track)
            }
        }
    }

    /** Songs whose cached lyrics already had their one network upgrade attempt this session. */
    private val upgradeAttempted = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun load(track: Track?) {
        job?.cancel()
        if (track == null) {
            _state.value = State()
            return
        }
        val videoId = RecordingMatches.getMatchedVideoId(track.id) ?: track.id
        val durationSec = RecordingMatches.getEffectiveDurationSec(videoId, track.duration)
        // Cached lyrics show at once (aligned to the song's listed length until libVLC reports
        // the stream's), as the phone shows its Room copy before searching.
        val cached = repository.cached(videoId, durationSec * 1000L)
        _state.value = State(track.id, cached, isLoading = cached == null)
        job = scope.launch {
            // Align to the real stream length, as the phone does with the player's duration; the
            // stream is usually open within a second, so wait briefly for libVLC to report it.
            val lengthMs = withTimeoutOrNull(2500L) { playback.lengthMs.first { it > 0L } } ?: (durationSec * 1000L)
            if (cached != null) {
                repository.cached(videoId, lengthMs)?.let { realigned ->
                    if (_state.value.trackId == track.id) _state.value = _state.value.copy(lyrics = realigned)
                }
                // Word sync is final. Line sync gets one look for better per session; plain is
                // looked up on every play, since a slow or failed race can leave synced songs plain.
                val tier = LyricsClient.tierOf(cached)
                if (tier >= LyricsClient.TIER_WORD) return@launch
                if (tier == LyricsClient.TIER_LINE && !upgradeAttempted.add(videoId.lowercase())) return@launch
            }
            val final = repository.fetch(
                videoId = videoId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                channelTitle = track.channelTitle,
                durationSec = durationSec,
                durationMs = lengthMs,
                onInterim = { interim ->
                    // Shown only if it beats what is on screen, and never after the final answer.
                    val current = _state.value
                    if (current.trackId == track.id && current.isLoading &&
                        LyricsClient.tierOf(interim) > (current.lyrics?.let(LyricsClient::tierOf) ?: LyricsClient.TIER_NONE)
                    ) {
                        _state.value = current.copy(lyrics = interim)
                    }
                }
            )
            if (_state.value.trackId == track.id) {
                _state.value = State(track.id, final ?: _state.value.lyrics, isLoading = false)
            }
        }
    }
}
