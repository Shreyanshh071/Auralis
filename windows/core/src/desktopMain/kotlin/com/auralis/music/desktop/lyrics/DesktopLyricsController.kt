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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ensureActive
import com.auralis.music.data.network.provider.LyricsSearchQuery

/** Lyrics for whatever is playing: looked up on every song change, like the phone's player. */
class DesktopLyricsController(
    private val playback: DesktopPlaybackController,
    private val repository: DesktopLyricsRepository,
    private val scope: CoroutineScope,
    private val spicyMode: Flow<Boolean> = flowOf(true)
) {
    data class State(val trackId: String? = null, val lyrics: LyricsData? = null, val isLoading: Boolean = false, val originalLyrics: LyricsData? = null)

    private val _state = MutableStateFlow(State())
    fun applyTranslation(trackId: String?, source: LyricsData, translated: LyricsData): Boolean {
        while (true) {
            val current = _state.value
            if (current.trackId != trackId || (current.originalLyrics ?: current.lyrics) != source) return false
            if (_state.compareAndSet(current, current.copy(lyrics=translated, originalLyrics=source))) return true
        }
    }
    fun clearTranslation() {
        while (true) {
            val current = _state.value
            val source = current.originalLyrics ?: return
            if (_state.compareAndSet(current, current.copy(lyrics=source, originalLyrics=null))) return
        }
    }
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            val shownTrack = kotlinx.coroutines.flow.combine(playback.queue, playback.connectRemote) { queue, remote ->
                remote?.track?.let { track ->
                    track.copy(duration = remote.durationMs.takeIf { it > 0 }?.div(1000L) ?: track.duration)
                } ?: queue.current
            }.distinctUntilChanged { a, b ->
                a?.id == b?.id && a?.title == b?.title && a?.artist == b?.artist && a?.duration == b?.duration
            }
            kotlinx.coroutines.flow.combine(shownTrack, spicyMode.distinctUntilChanged()) { track, spicy -> track to spicy }
                .collect { (track, spicy) -> load(track, spicy) }
        }
    }

    /** Songs whose cached lyrics already had their one network upgrade attempt this session. */
    private val upgradeAttempted = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun load(track: Track?, preferSpicy: Boolean) {
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
        val previous = _state.value
        _state.value = if (previous.trackId == track.id && previous.originalLyrics != null) previous
            else State(track.id, cached, isLoading = cached == null)
        job = scope.launch {
            fun applyResult(candidate: LyricsData?, loading: Boolean = false) {
                while (true) {
                    val current = _state.value
                    if (current.trackId != track.id || current.originalLyrics != null) return
                    val selected = DesktopLyricsRepository.choose(current.lyrics, candidate, preferSpicy)
                    if (_state.compareAndSet(current, current.copy(lyrics = selected,
                            isLoading = selected == null && loading))) return
                }
            }
            fun startSpicyUpgrade(durationMs: Long) = launch {
                val result = repository.upgradeSpicy(LyricsSearchQuery(track.title, track.artist,
                    durationSec = durationMs / 1000, videoId = videoId, album = track.album,
                    channelTitle = track.channelTitle, durationMs = durationMs,
                    spotifyId = com.auralis.music.data.network.SpicyRecordingMatcher.spotifyId(track.id)))
                ensureActive()
                if (result != null) applyResult(result)
            }
            // Cached music remains visible. A word-cache hit and a fast fallback
            // race must not cancel this separate, bounded Spicy enrichment request.
            val earlySpicy = if (preferSpicy && durationSec > 0L) startSpicyUpgrade(durationSec * 1000L) else null
            // Align to the real stream length, as the phone does with the player's duration. The
            // stream is usually open within a second or two; songs with no listed length (common
            // in imported playlists) wait longer, since matching needs a length to trust.
            val waitMs = if (durationSec > 0L) 2500L else 8000L
            val remote = playback.connectRemote.value?.takeIf { it.track.id == track.id }
            val lengthMs = if (remote != null) remote.durationMs.takeIf { it > 0 } ?: durationSec * 1000L
                else withTimeoutOrNull(waitMs) { playback.lengthMs.first { it > 0L } } ?: (durationSec * 1000L)
            if (preferSpicy && earlySpicy == null && lengthMs > 0L) startSpicyUpgrade(lengthMs)
            if (cached != null) {
                repository.cached(videoId, lengthMs)?.let { realigned ->
                    applyResult(realigned)
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
                    applyResult(interim, loading = true)
                },
                preferSpicy = preferSpicy
            )
            applyResult(final)
        }
    }
}
