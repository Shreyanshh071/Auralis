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

    private fun load(track: Track?) {
        job?.cancel()
        if (track == null) {
            _state.value = State()
            return
        }
        val videoId = RecordingMatches.getMatchedVideoId(track.id) ?: track.id
        repository.cached(videoId)?.let {
            _state.value = State(track.id, it, isLoading = false)
            return
        }
        _state.value = State(track.id, null, isLoading = true)
        job = scope.launch {
            // Align to the real stream length, as the phone does with the player's duration; the
            // stream is usually open within a second, so wait briefly for libVLC to report it.
            val lengthMs = withTimeoutOrNull(2500L) { playback.lengthMs.first { it > 0L } }
                ?: (RecordingMatches.getEffectiveDurationSec(videoId, track.duration) * 1000L)
            val final = repository.getLyrics(
                videoId = videoId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                channelTitle = track.channelTitle,
                durationSec = RecordingMatches.getEffectiveDurationSec(videoId, track.duration),
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
