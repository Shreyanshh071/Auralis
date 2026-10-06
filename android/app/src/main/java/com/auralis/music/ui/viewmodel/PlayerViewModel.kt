package com.auralis.music.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.datastore.PlaylistListeningStore
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.domain.repository.LyricsRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.compose.runtime.Immutable
data class PlayerUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val playbackPositionMs: Long = 0,
    val durationMs: Long = 0,
    val isBuffering: Boolean = false,
    val isFavorite: Boolean = false,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val queueSourceTitle: String? = null,
    val isShuffled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val lyrics: LyricsData? = null,
    val isLoadingLyrics: Boolean = false,
    val lyricsOffsetMs: Long = 0,
    val sleepTimerSeconds: Long = 0,
    val isSleepTimerEndOfSong: Boolean = false,
    val showLyricsView: Boolean = false,
    val showInlineLyrics: Boolean = false,
    val lyricsViewRequestId: Int = 0,
    val errorMessage: String? = null,
    val audioLeadingSilenceMs: Long? = null
)

enum class LyricsProviderStatus { FETCHING, NOT_FOUND, FOUND }

/** What the lyrics provider sheet knows for one song; [trackId] scopes it to that song. */
data class LyricsProviderPicks(
    val trackId: String? = null,
    val status: Map<LyricsProvider, LyricsProviderStatus> = emptyMap(),
    /** Timing of what each source returned, so the sheet can say word / line / unsynced. */
    val syncTypes: Map<LyricsProvider, SyncType> = emptyMap()
)

/** Keep the best timed lyrics already displayed when the search settles. */
internal fun selectSettledLyrics(cached: LyricsData?, displayed: LyricsData?, result: LyricsData?): LyricsData? {
    val cachedTier = lyricsTier(cached)
    val displayedTier = lyricsTier(displayed)
    val resultTier = lyricsTier(result)
    val winner = when {
        result != null && resultTier > cachedTier && resultTier >= displayedTier -> result
        displayedTier > cachedTier -> displayed
        else -> cached ?: result ?: displayed
    }
    // Automatic playback never exposes plain text as a temporary lyric screen.
    return winner?.takeIf { lyricsTier(it) >= 2 }
}

/** Genuine word starts beat line timestamps beat untimed text. */
internal fun lyricsTier(data: LyricsData?): Int = when {
    data == null || data.lines.isEmpty() -> 0
    com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(data.lines) -> 3
    data.syncType != SyncType.PLAIN && data.lines.any { it.time > 0L } -> 2
    else -> 1
}

internal fun needsResolvedLyricsRefresh(
    lookupTrackId: String?,
    playingTrackId: String,
    lookupVideoId: String?,
    playingVideoId: String,
    lookupDurationMs: Long,
    playingDurationMs: Long
): Boolean = lookupTrackId == playingTrackId && playingDurationMs > 0L &&
    (lookupVideoId != playingVideoId || kotlin.math.abs(playingDurationMs - lookupDurationMs) >= 1500L)

internal fun retainLyricsForResolvedPlayback(
    lyrics: LyricsData?,
    track: Track,
    playbackVideoId: String,
    playbackDurationMs: Long
): LyricsData? = lyrics?.takeIf { candidate ->
    lyricsTier(candidate) >= 2 &&
        com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
            lyrics = candidate,
            playbackDurationMs = playbackDurationMs,
            playbackTitle = track.title,
            candidateTitle = candidate.trackName ?: track.title,
            playbackVideoId = playbackVideoId,
            playbackArtist = track.artist,
            candidateArtist = candidate.artistName ?: track.artist
        )
}

/** A plain or empty first lookup can precede a timed result while providers warm up. */
internal suspend fun searchForTimedLyrics(
    maxAttempts: Int = 3,
    retryDelayMs: Long = 500L,
    timedLyricsVisible: () -> Boolean,
    search: suspend (attempt: Int) -> LyricsData?
): LyricsData? {
    require(maxAttempts > 0)
    var best: LyricsData? = null
    repeat(maxAttempts) { attempt ->
        val result = try {
            search(attempt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (lyricsTier(result) > lyricsTier(best)) best = result
        if (lyricsTier(best) >= 2 || timedLyricsVisible()) return best
        if (attempt < maxAttempts - 1) delay(retryDelayMs)
    }
    return best
}

internal fun resolveQueueSourceTitle(
    queueSize: Int,
    sourcePlaylistTitle: String?,
    previousTitle: String?,
    preservePrevious: Boolean
): String? = when {
    queueSize <= 1 -> null
    preservePrevious -> previousTitle
    else -> sourcePlaylistTitle?.trim()?.takeIf { it.isNotEmpty() }
}

@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val libraryRepository: LibraryRepository,
    private val historyRepository: HistoryRepository,
    private val lyricsRepository: LyricsRepository,
    private val settingsRepository: SettingsRepository,
    private val audioPlayer: AuralisAudioPlayer? = null,
    private val innerTubeClient: InnerTubeClient = InnerTubeClient(),
    private val searchRepository: SearchRepository? = null,
    private val context: android.content.Context? = null
) : ViewModel() {

    private val queueManager = audioPlayer?.queueManager ?: AudioQueueManager()
    private val sleepTimerManager = SleepTimerManager()
    private val currentPlaybackRequestId = java.util.concurrent.atomic.AtomicLong(0L)

    private val initialPlayerPosition: Long = audioPlayer?.let { player ->
        val raw = player.rawPositionMs()
        if (raw > 0L) raw else player.playbackPositionMs.value
    } ?: 0L

    private val _playbackPositionMs = MutableStateFlow(initialPlayerPosition)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _uiState = MutableStateFlow(
        audioPlayer?.let { player ->
            val track = player.currentTrack.value
            val qState = player.queueState.value
            val pos = initialPlayerPosition
            PlayerUiState(
                currentTrack = track,
                isPlaying = player.isPlaying.value,
                isBuffering = player.isBuffering.value,
                playbackPositionMs = pos,
                durationMs = player.durationMs.value.takeIf { it > 0L } ?: ((track?.duration ?: 0L) * 1000L),
                queue = qState.queue,
                currentIndex = qState.currentIndex,
                isShuffled = qState.isShuffled,
                repeatMode = qState.repeatMode,
                sleepTimerSeconds = player.sleepTimerSeconds.value,
                isSleepTimerEndOfSong = player.isSleepTimerEndOfSong.value
            )
        } ?: PlayerUiState()
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _playerSettings = MutableStateFlow(com.auralis.music.domain.model.PlayerSettings())
    val playerSettings: StateFlow<com.auralis.music.domain.model.PlayerSettings> = _playerSettings.asStateFlow()

    fun getPlaybackPosition(): Long = _playbackPositionMs.value

    /**
     * The player's own fine-grained clock, exposed as the narrow
     * [com.auralis.music.data.service.PlaybackClockSource] interface so the
     * lyrics UI can sample it per frame without pulling `@UnstableApi` Media3
     * types — or the whole audio player — into the composable layer.
     */
    val playbackClockSource: com.auralis.music.data.service.PlaybackClockSource? = audioPlayer

    private var sleepTimerJob: Job? = null
    private var playJob: Job? = null
    private var seekJob: Job? = null
    private var lyricsJob: Job? = null
    private var lyricsLookupTrackId: String? = null
    private var lyricsLookupVideoId: String? = null
    private var lyricsLookupDurationMs: Long = 0L
    private val lyricsLookupGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private var translationJob: Job? = null

    /**
     * Tracks whose cached line-synced lyrics have already been given one chance to
     * be upgraded to word sync this session, so replaying a track that genuinely
     * has no word timing does not re-run the provider cascade every time.
     */
    private val lyricsUpgradeAttempted =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private var radioJob: Job? = null
    private var radioAdvanceJob: Job? = null
    private var palettePreloadJob: Job? = null
    private var isAutoRadioMode: Boolean = true
    @Volatile private var sourcePlaylistId: String? = null
    @Volatile private var sourcePlaylistTrackIds: Set<String> = emptySet()
    @Volatile private var listeningHistoryPaused = true

    init {
        context?.let { ctx ->
            viewModelScope.launch {
                com.auralis.music.data.datastore.PrivacyDataStore(ctx).settingsFlow.collect {
                    listeningHistoryPaused = it.pauseListenHistory
                }
            }
            // Count only playback time that actually advances. Seeks and repeated taps do not
            // manufacture listening time; the small batch keeps disk writes inexpensive.
            viewModelScope.launch(Dispatchers.IO) {
                var previousTrackId: String? = null
                var previousPosition = -1L
                var pendingMs = 0L
                var pendingPlaylistId: String? = null
                _playbackPositionMs.collect { position ->
                    val playlistId = sourcePlaylistId
                    val trackId = audioPlayer?.currentTrack?.value?.id ?: _uiState.value.currentTrack?.id
                    if (playlistId != pendingPlaylistId || trackId != previousTrackId) {
                        pendingPlaylistId?.let { if (pendingMs > 0) PlaylistListeningStore.recordListening(ctx, it, pendingMs) }
                        pendingMs = 0L
                        pendingPlaylistId = playlistId
                        previousTrackId = trackId
                        previousPosition = position
                    } else {
                        val delta = position - previousPosition
                        val playing = audioPlayer?.isPlaying?.value ?: _uiState.value.isPlaying
                        if (playlistId != null && trackId in sourcePlaylistTrackIds && playing && !listeningHistoryPaused && delta in 1..5000) {
                            pendingMs += delta
                            if (pendingMs >= 5000) {
                                PlaylistListeningStore.recordListening(ctx, playlistId, pendingMs)
                                pendingMs = 0L
                            }
                        }
                        previousPosition = position
                    }
                }
            }
        }
        // Collect current track favorite state reactively
        viewModelScope.launch {
            _uiState.map { it.currentTrack?.id }
                .distinctUntilChanged()
                .collectLatest { trackId ->
                    if (trackId != null) {
                        libraryRepository.isFavorite(trackId).collect { isFav ->
                            _uiState.update { it.copy(isFavorite = isFav) }
                            audioPlayer?.setIsFavorite(isFav)
                        }
                    } else {
                        _uiState.update { it.copy(isFavorite = false) }
                        audioPlayer?.setIsFavorite(false)
                    }
                }
        }

        // Bind AudioPlayer reactive flows if available
        audioPlayer?.let { player ->
            player.setNavigationCallbacks(
                onNext = {
                    val rId = currentPlaybackRequestId.incrementAndGet()
                    if (isAutoRadioMode && _playerSettings.value.autoLoadMore) {
                        handleInfiniteRadioAdvance(rId)
                    } else {
                        player.pause()
                    }
                },
                onPrevious = {
                    previous()
                }
            )

            val initialTrack = player.currentTrack.value
            val initialQState = player.queueState.value
            val currentPos = if (player.rawPositionMs() > 0L) player.rawPositionMs() else player.playbackPositionMs.value
            _playbackPositionMs.value = currentPos
            if (initialTrack != null) {
                _uiState.update {
                    it.copy(
                        currentTrack = initialTrack,
                        queue = initialQState.queue,
                        currentIndex = initialQState.currentIndex,
                        isShuffled = initialQState.isShuffled,
                        repeatMode = initialQState.repeatMode,
                        isPlaying = player.isPlaying.value,
                        isBuffering = player.isBuffering.value,
                        playbackPositionMs = currentPos,
                        durationMs = player.durationMs.value.takeIf { d -> d > 0 } ?: (initialTrack.duration * 1000L)
                    )
                }
                loadLyrics(initialTrack)
            }

            viewModelScope.launch {
                player.queueState.collect { qState ->
                    isAutoRadioMode = !qState.isUserQueue
                    _uiState.update {
                        it.copy(
                            queue = qState.queue,
                            currentIndex = qState.currentIndex,
                            isShuffled = qState.isShuffled,
                            repeatMode = qState.repeatMode
                        )
                    }
                }
            }

            viewModelScope.launch {
                player.currentTrack.collectLatest { activeTrack ->
                    if (activeTrack != null) {
                        palettePreloadJob?.cancel()
                        context?.let { ctx ->
                            palettePreloadJob = viewModelScope.launch(Dispatchers.Default) {
                                com.auralis.music.ui.theme.ArtworkPaletteCache.updateForTrack(ctx, activeTrack)
                            }
                        }
                        val isNewTrack = _uiState.value.currentTrack?.id != activeTrack.id
                        if (isNewTrack) {
                            val reqId = currentPlaybackRequestId.incrementAndGet()
                            _uiState.update {
                                it.copy(
                                    currentTrack = activeTrack,
                                    lyrics = null,
                                    isLoadingLyrics = true,
                                    durationMs = player.durationMs.value.takeIf { d -> d > 0 } ?: (activeTrack.duration * 1000L)
                                )
                            }
                            loadLyrics(activeTrack, reqId)
                            viewModelScope.launch(Dispatchers.IO) {
                                val isPaused = context?.let { ctx ->
                                    com.auralis.music.data.datastore.PrivacyDataStore(ctx).settingsFlow.first().pauseListenHistory
                                } ?: false
                                if (!isPaused) {
                                    historyRepository.addToHistory(activeTrack)
                                    historyRepository.recordPlay(activeTrack)
                                }
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    currentTrack = activeTrack,
                                    durationMs = player.durationMs.value.takeIf { d -> d > 0 } ?: (activeTrack.duration * 1000L)
                                )
                            }
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                currentTrack = null,
                                lyrics = null,
                                isLoadingLyrics = false
                            )
                        }
                    }
                }
            }

            viewModelScope.launch {
                player.isPlaying.collect { playing ->
                    _uiState.update { it.copy(isPlaying = playing) }
                    if (playing) refreshLyricsForResolvedPlayback()
                }
            }

            viewModelScope.launch {
                player.playbackPositionMs.collect { pos ->
                    _playbackPositionMs.value = pos
                }
            }

            viewModelScope.launch {
                player.durationMs
                    .collect { dur ->
                        if (dur > 0 && dur != _uiState.value.durationMs) {
                            _uiState.update { it.copy(durationMs = dur) }
                        }
                        if (dur > 0 && player.isPlaying.value) refreshLyricsForResolvedPlayback()
                    }
            }

            viewModelScope.launch {
                player.isBuffering
                    .collect { buffering ->
                        if (buffering != _uiState.value.isBuffering) {
                            _uiState.update { it.copy(isBuffering = buffering) }
                        }
                    }
            }

            viewModelScope.launch {
                player.playbackError.collect { error ->
                    _uiState.update { it.copy(errorMessage = error) }
                }
            }

            viewModelScope.launch {
                player.sleepTimerSeconds.collect { sec ->
                    _uiState.update { it.copy(sleepTimerSeconds = sec) }
                }
            }

            viewModelScope.launch {
                player.isSleepTimerEndOfSong.collect { endOfSong ->
                    _uiState.update { it.copy(isSleepTimerEndOfSong = endOfSong) }
                }
            }

            viewModelScope.launch {
                player.audioLeadingSilenceMs.collect { silenceMs ->
                    if (silenceMs != null) {
                        _uiState.update { current ->
                            val currentLyrics = current.lyrics ?: return@update current
                            val playbackMs = current.durationMs.takeIf { it > 0L }
                                ?: ((current.currentTrack?.duration ?: 0L) * 1000L)
                            val aligned = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.alignToPlayback(
                                currentLyrics,
                                playbackMs,
                                silenceMs
                            )
                            current.copy(lyrics = aligned, audioLeadingSilenceMs = silenceMs)
                        }
                    }
                }
            }

            player.setOnGaplessTransitionCallback { nextTrack ->
                val effectiveTrack = nextTrack
                val reqId = currentPlaybackRequestId.incrementAndGet()
                val qState = player.queueState.value
                _uiState.update {
                    it.copy(
                        currentTrack = effectiveTrack,
                        lyrics = null,
                        isLoadingLyrics = true,
                        queue = qState.queue,
                        currentIndex = qState.currentIndex,
                        isPlaying = true,
                        playbackPositionMs = 0L,
                        durationMs = effectiveTrack.duration * 1000L,
                        errorMessage = null
                    )
                }
                viewModelScope.launch {
                    val isPaused = context?.let { ctx ->
                        com.auralis.music.data.datastore.PrivacyDataStore(ctx).settingsFlow.first().pauseListenHistory
                    } ?: false
                    if (!isPaused) {
                        historyRepository.addToHistory(effectiveTrack)
                        historyRepository.recordPlay(effectiveTrack)
                    }
                }
                loadLyrics(effectiveTrack, reqId)

                viewModelScope.launch(Dispatchers.IO) {
                    if (com.auralis.music.data.network.AlbumMetadataResolver.needsResolving(effectiveTrack.album, effectiveTrack.title)) {
                        try {
                            val resolved = com.auralis.music.data.network.AlbumMetadataResolver.resolveAlbum(
                                trackTitle = effectiveTrack.title,
                                artistName = effectiveTrack.artist,
                                knownAlbum = effectiveTrack.album
                            )
                            if (resolved != null && !resolved.isSingle && resolved.albumTitle.isNotBlank() && reqId == currentPlaybackRequestId.get()) {
                                val updatedTrack = (_uiState.value.currentTrack ?: effectiveTrack).copy(
                                    album = resolved.albumTitle,
                                    albumId = resolved.albumId ?: effectiveTrack.albumId
                                )
                                _uiState.update { state ->
                                    if (state.currentTrack?.id == effectiveTrack.id) {
                                        state.copy(currentTrack = updatedTrack)
                                    } else state
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Pre-enqueue next song in queue for continuous gapless playback
                val nextInQueue = qState.queue.getOrNull(qState.currentIndex + 1)
                audioPlayer?.prefetchTrack(nextInQueue)

                // Background pre-fetch lyrics for next song in queue for 0ms instant display upon transition
                if (nextInQueue != null) {
                    val nextEffectiveId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(nextInQueue.id) ?: nextInQueue.id
                    val nextEffectiveDuration = com.auralis.music.data.network.AudioStreamResolver.getEffectiveDurationSec(nextInQueue.id, nextInQueue.duration)
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            lyricsRepository.getLyrics(
                                title = nextInQueue.title,
                                artist = nextInQueue.artist,
                                durationSec = nextEffectiveDuration,
                                videoId = nextEffectiveId,
                                album = nextInQueue.album,
                                channelTitle = nextInQueue.channelTitle,
                                durationMs = nextEffectiveDuration * 1000L,
                                forceRefresh = false
                            )
                        } catch (_: Exception) {}
                    }
                }

                val isNear = audioPlayer?.queueManager?.isNearEnd(threshold = 4) ?: queueManager.isNearEnd(threshold = 4)
                if (isAutoRadioMode && _playerSettings.value.autoLoadMore && isNear) {
                    fetchAndAppendRadioTracks(effectiveTrack)
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                val autoLoadChanged = _playerSettings.value.autoLoadMore != settings.autoLoadMore
                _playerSettings.value = settings
                val isNear = audioPlayer?.queueManager?.isNearEnd(threshold = 4) ?: queueManager.isNearEnd(threshold = 4)
                if (!settings.autoLoadMore) {
                    radioJob?.cancel()
                    radioAdvanceJob?.cancel()
                } else if (autoLoadChanged && isAutoRadioMode && isNear) {
                    (audioPlayer?.currentTrack?.value ?: queueManager.state.currentTrack)?.let { fetchAndAppendRadioTracks(it) }
                }
                audioPlayer?.setAudioQuality(settings.audioQuality)
                audioPlayer?.setGaplessEnabled(settings.gaplessPlayback)
                audioPlayer?.setSkipSilenceEnabled(settings.skipSilence)
                audioPlayer?.setSpatialAudioEnabled(settings.spatialAudio)
            }
        }
    }

    fun updateAudioQuality(quality: com.auralis.music.domain.model.AudioQuality) {
        viewModelScope.launch {
            settingsRepository.setAudioQuality(quality)
        }
    }

    fun toggleGaplessPlayback(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setGaplessPlayback(enabled)
        }
    }

    fun toggleSkipSilence(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSkipSilence(enabled)
        }
    }

    fun toggleSpatialAudio(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSpatialAudio(enabled)
        }
    }

    fun updateThemeMode(mode: com.auralis.music.domain.model.ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    private fun triggerPlayback(
        track: Track,
        debounceMs: Long = 0L,
        requestId: Long = currentPlaybackRequestId.get(),
        initialPositionMs: Long = 0L
    ) {
        playJob?.cancel()
        lyricsJob?.cancel()

        // 1. Instantly trigger audio playback at 0ms latency
        audioPlayer?.play(track, initialPositionMs, requestId)

        playJob = viewModelScope.launch(Dispatchers.IO) {
            if (requestId != currentPlaybackRequestId.get()) return@launch

            // Asynchronously resolve thumbnail in background without delaying playback startup
            if (track.thumbnail.isNullOrBlank()) {
                try {
                    val resolvedThumb = com.auralis.music.data.network.ArtworkResolver.resolveArtwork(track)
                    if (!resolvedThumb.isNullOrBlank() && requestId == currentPlaybackRequestId.get()) {
                        audioPlayer?.updateCurrentTrackArtwork(resolvedThumb)
                        _uiState.update { state ->
                            val updatedQueue = state.queue.map { q ->
                                if (q.id == track.id) q.copy(thumbnail = resolvedThumb) else q
                            }
                            val updatedTrack = if (state.currentTrack?.id == track.id) {
                                state.currentTrack.copy(thumbnail = resolvedThumb)
                            } else state.currentTrack
                            state.copy(currentTrack = updatedTrack, queue = updatedQueue)
                        }
                    }
                } catch (_: Exception) {}
            }

            // Asynchronously resolve authentic album if missing or redundant with title
            if (com.auralis.music.data.network.AlbumMetadataResolver.needsResolving(track.album, track.title)) {
                try {
                    val resolved = com.auralis.music.data.network.AlbumMetadataResolver.resolveAlbum(
                        trackTitle = track.title,
                        artistName = track.artist,
                        knownAlbum = track.album
                    )
                    if (resolved != null && !resolved.isSingle && resolved.albumTitle.isNotBlank() && requestId == currentPlaybackRequestId.get()) {
                        val updatedTrack = (_uiState.value.currentTrack ?: track).copy(
                            album = resolved.albumTitle,
                            albumId = resolved.albumId ?: track.albumId
                        )
                        _uiState.update { state ->
                            if (state.currentTrack?.id == track.id) {
                                state.copy(currentTrack = updatedTrack)
                            } else state
                        }
                    }
                } catch (_: Exception) {}
            }

            val isPaused = context?.let { ctx ->
                com.auralis.music.data.datastore.PrivacyDataStore(ctx).settingsFlow.first().pauseListenHistory
            } ?: false
            if (!isPaused) {
                val finalTrack = _uiState.value.currentTrack ?: track
                historyRepository.addToHistory(finalTrack)
                historyRepository.recordPlay(finalTrack)
            }
        }

        loadLyrics(track, requestId)
    }

    fun playTrack(
        track: Track,
        newQueue: List<Track> = emptyList(),
        startIndex: Int = 0,
        isUserQueue: Boolean = false,
        initialPositionMs: Long = 0L,
        sourcePlaylistTitle: String? = null,
        preserveQueueSource: Boolean = false,
        sourcePlaylistId: String? = null
    ) {
        // A Listen Together guest's pick goes to the room; this phone changes song only when the
        // host's does. Updating the screen first showed a new cover and lyrics over the old audio.
        if (audioPlayer?.isGuestListenTogether?.value == true) {
            audioPlayer.playTrack(track, newQueue, startIndex, isUserQueue, initialPositionMs, preserveQueueSource)
            return
        }
        this.sourcePlaylistId = if (preserveQueueSource) this.sourcePlaylistId else sourcePlaylistId
        if (!preserveQueueSource) sourcePlaylistTrackIds = if (sourcePlaylistId != null) {
            (newQueue.ifEmpty { listOf(track) }).mapTo(mutableSetOf()) { it.id }
        } else emptySet()
        if (sourcePlaylistId != null) {
            context?.let { ctx ->
                viewModelScope.launch(Dispatchers.IO) {
                    val paused = com.auralis.music.data.datastore.PrivacyDataStore(ctx).settingsFlow.first().pauseListenHistory
                    if (!paused) PlaylistListeningStore.recordSession(ctx, sourcePlaylistId)
                }
            }
        }
        val effectiveIsUserQueue = isUserQueue || (sourcePlaylistTitle != null)
        val reqId = currentPlaybackRequestId.incrementAndGet()
        val isAutoQueue = !effectiveIsUserQueue
        isAutoRadioMode = isAutoQueue

        Log.d("AuralisPlayback", "[UI Tap] playTrack #$reqId: id=${track.id}, title='${track.title}', queueSize=${newQueue.size}, isAutoRadio=$isAutoRadioMode, initialPos=${initialPositionMs}ms")
        
        val isSingleSongSelection = !preserveQueueSource && !effectiveIsUserQueue && (newQueue.isEmpty() || newQueue.size == 1)
        val targetQueue = if (isSingleSongSelection) {
            listOf(track)
        } else if (newQueue.isNotEmpty()) {
            newQueue
        } else {
            _uiState.value.queue
        }
        val queueSourceTitle = resolveQueueSourceTitle(
            queueSize = targetQueue.size,
            sourcePlaylistTitle = sourcePlaylistTitle,
            previousTitle = _uiState.value.queueSourceTitle,
            preservePrevious = preserveQueueSource
        )
        val targetIndex = if (isSingleSongSelection) {
            0
        } else if (startIndex in targetQueue.indices && targetQueue[startIndex].id == track.id) {
            startIndex
        } else {
            targetQueue.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: startIndex.coerceIn(0, (targetQueue.size - 1).coerceAtLeast(0))
        }

        context?.let { ctx ->
            // Dispatch palette extraction off the main thread — toHct() + Coil mem-cache lookup
            // must not block the UI thread during the song-skip tap.
            palettePreloadJob?.cancel()
            palettePreloadJob = viewModelScope.launch(Dispatchers.Default) {
                com.auralis.music.ui.theme.ArtworkPaletteCache.updateForTrack(ctx, track)
            }
            // Pre-extract palette for neighboring tracks in background
            viewModelScope.launch(Dispatchers.IO) {
                listOfNotNull(
                    targetQueue.getOrNull(targetIndex + 1),
                    targetQueue.getOrNull(targetIndex - 1),
                    targetQueue.getOrNull(targetIndex + 2)
                ).forEach { neighbor ->
                    if (com.auralis.music.ui.theme.ArtworkPaletteCache.getCached(neighbor.id) == null) {
                        com.auralis.music.ui.theme.ArtworkPaletteCache.extractPalette(ctx, neighbor.id, neighbor.thumbnail)
                    }
                }
            }
        }

        _uiState.update {
            it.copy(
                currentTrack = track,
                queue = targetQueue,
                currentIndex = targetIndex,
                queueSourceTitle = queueSourceTitle,
                isPlaying = true,
                lyrics = null,
                isLoadingLyrics = true,
                playbackPositionMs = initialPositionMs,
                durationMs = track.duration * 1000L,
                errorMessage = null
            )
        }

        if (audioPlayer != null) {
            audioPlayer.playTrack(
                track = track,
                newQueue = targetQueue,
                startIndex = targetIndex,
                isUserQueue = effectiveIsUserQueue,
                initialPositionMs = initialPositionMs,
                preserveQueueSource = preserveQueueSource
            )
        } else {
            val isSameQueue = queueManager.state.queue.isNotEmpty() &&
                              targetQueue.map { it.id } == queueManager.state.queue.map { it.id }
            val qState = queueManager.setQueue(targetQueue, targetIndex, preserveOrderIfSame = isSameQueue, isUserQueue = effectiveIsUserQueue)
            _uiState.update {
                it.copy(
                    currentTrack = qState.currentTrack,
                    queue = qState.queue,
                    currentIndex = qState.currentIndex,
                    isShuffled = qState.isShuffled,
                    isPlaying = true,
                    playbackPositionMs = initialPositionMs,
                    durationMs = track.duration * 1000,
                    errorMessage = null
                )
            }
            triggerPlayback(track, debounceMs = 0L, requestId = reqId, initialPositionMs = initialPositionMs)
        }

        playJob?.cancel()
        lyricsJob?.cancel()
        playJob = viewModelScope.launch(Dispatchers.IO) {
            val isPaused = context?.let { ctx ->
                com.auralis.music.data.datastore.PrivacyDataStore(ctx).settingsFlow.first().pauseListenHistory
            } ?: false
            if (!isPaused) {
                historyRepository.addToHistory(track)
                historyRepository.recordPlay(track)
            }
        }
        loadLyrics(track, reqId)

        if (isSingleSongSelection && isAutoRadioMode && _playerSettings.value.autoLoadMore) {
            fetchAndAppendRadioTracks(track)
        }
    }

    private fun fetchAndAppendRadioTracks(seedTrack: Track) {
        if (audioPlayer != null) {
            audioPlayer.requestAutoQueueExtension()
            return
        }
        if (!_playerSettings.value.autoLoadMore || !isAutoRadioMode) return
        val requestId = currentPlaybackRequestId.get()
        radioJob?.cancel()
        radioJob = viewModelScope.launch {
            try {
                val radioTracks = withContext(Dispatchers.IO) {
                    com.auralis.music.data.network.LocalizedContent.run { innerTubeClient.getRadioTracks(seedTrack.id, seedTrack.artist, seedTrack.title) }
                }
                if (!_playerSettings.value.autoLoadMore || !isAutoRadioMode || requestId != currentPlaybackRequestId.get()) return@launch
                if (radioTracks.isNotEmpty()) {
                    val existingIds = queueManager.state.queue.map { it.id }.toSet()
                    val uniqueTracks = radioTracks.filter { it.id !in existingIds && it.id != seedTrack.id }
                    val batch = uniqueTracks.take(14)
                    if (batch.isNotEmpty()) {
                        val qState = queueManager.appendTracks(batch)
                        _uiState.update {
                            it.copy(queue = qState.queue)
                        }
                        Log.d("AuralisPlayback", "[AutoRadio] Appended ${batch.size} radio tracks for '${seedTrack.title}'")
                    }
                }
            } catch (e: Exception) {
                Log.w("AuralisPlayback", "[AutoRadio] Error fetching radio tracks: ${e.message}")
            }
        }
    }

    fun resume() {
        Log.d("AuralisPlayback", "[Action] resume (currently isPlaying=${_uiState.value.isPlaying})")
        if (audioPlayer != null) {
            audioPlayer.resume()
        } else {
            _uiState.update { it.copy(isPlaying = true) }
        }
    }

    fun pause() {
        Log.d("AuralisPlayback", "[Action] pause (currently isPlaying=${_uiState.value.isPlaying})")
        if (audioPlayer != null) {
            audioPlayer.pause()
        } else {
            _uiState.update { it.copy(isPlaying = false) }
        }
    }

    fun togglePlayPause() {
        Log.d("AuralisPlayback", "[UI Tap] togglePlayPause (currently isPlaying=${_uiState.value.isPlaying})")
        if (audioPlayer != null) {
            audioPlayer.togglePlayPause()
        } else {
            _uiState.update { it.copy(isPlaying = !it.isPlaying) }
        }
    }

    fun closePlayer() {
        Log.d("AuralisPlayback", "[Action] closePlayer / stop and clear current song")
        currentPlaybackRequestId.incrementAndGet()
        playJob?.cancel()
        radioJob?.cancel()
        lyricsJob?.cancel()
        if (audioPlayer != null) {
            audioPlayer.stop()
            audioPlayer.clearQueue()
            audioPlayer.clearCurrentTrack()
        } else {
            queueManager.setQueue(emptyList())
        }
        _uiState.update {
            it.copy(
                currentTrack = null,
                queue = emptyList(),
                currentIndex = 0,
                isPlaying = false,
                playbackPositionMs = 0L,
                durationMs = 0L,
                lyrics = null
            )
        }
        com.auralis.music.ui.theme.ArtworkPaletteCache.resetToDefault()
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0, _uiState.value.durationMs.coerceAtLeast(0))
        _playbackPositionMs.value = clamped
        _uiState.update { it.copy(playbackPositionMs = clamped) }
        if (audioPlayer != null) {
            val trackId = _uiState.value.currentTrack?.id
            seekJob?.cancel()
            // Rapid lyric taps should send only the latest seek to the audio engine.
            seekJob = viewModelScope.launch {
                delay(40L)
                if (_uiState.value.currentTrack?.id == trackId) audioPlayer.seekTo(clamped)
            }
        }
    }

    fun next() {
        Log.d("AuralisPlayback", "[PlayerViewModel] next() triggered")
        seekJob?.cancel()
        palettePreloadJob?.cancel()
        val reqId = currentPlaybackRequestId.incrementAndGet()

        context?.let { ctx ->
            val nextIdx = _uiState.value.currentIndex + 1
            _uiState.value.queue.getOrNull(nextIdx)?.let { nextTrack ->
                palettePreloadJob = viewModelScope.launch(Dispatchers.Default) {
                    com.auralis.music.ui.theme.ArtworkPaletteCache.updateForTrack(ctx, nextTrack)
                }
            }
        }

        if (audioPlayer != null) {
            val advanced = audioPlayer.next()
            if (advanced) {
                val cur = audioPlayer.currentTrack.value
                val isNear = audioPlayer.queueManager.isNearEnd(threshold = 4)
                if (cur != null && isAutoRadioMode && _playerSettings.value.autoLoadMore && isNear) {
                    fetchAndAppendRadioTracks(cur)
                }
                return
            }
            Log.d("AuralisPlayback", "[PlayerViewModel] audioPlayer.next() returned false (end of queue or single track). Handled by onNextCallback.")
            return
        }

        val nextTrack = queueManager.advanceNext(isUserSkip = true)
        if (nextTrack != null) {
            val qState = queueManager.state
            _uiState.update {
                it.copy(
                    currentTrack = nextTrack,
                    lyrics = null,
                    isLoadingLyrics = true,
                    currentIndex = qState.currentIndex,
                    isPlaying = true,
                    playbackPositionMs = 0,
                    durationMs = nextTrack.duration * 1000,
                    errorMessage = null
                )
            }
            triggerPlayback(nextTrack, debounceMs = 0L, requestId = reqId)
            loadLyrics(nextTrack, reqId)

            if (isAutoRadioMode && _playerSettings.value.autoLoadMore && queueManager.isNearEnd(threshold = 4)) {
                fetchAndAppendRadioTracks(nextTrack)
            }
        } else {
            if (isAutoRadioMode && _playerSettings.value.autoLoadMore) {
                Log.d("AuralisPlayback", "[AutoRadio] End of queue reached in auto-radio mode -> advancing infinitely")
                handleInfiniteRadioAdvance(reqId)
            } else {
                _uiState.update { it.copy(isPlaying = false) }
            }
        }
    }

    private fun handleInfiniteRadioAdvance(reqId: Long) {
        if (audioPlayer != null) {
            audioPlayer.requestAutoQueueExtension(advanceWhenLoaded = true)
            return
        }
        if (!_playerSettings.value.autoLoadMore) {
            _uiState.update { it.copy(isPlaying = false) }
            return
        }
        radioAdvanceJob?.cancel()
        val curTrack = _uiState.value.currentTrack ?: audioPlayer?.currentTrack?.value
        Log.d("AuralisPlayback", "[AutoRadio] handleInfiniteRadioAdvance for '${curTrack?.title}' (reqId=$reqId)")
        radioAdvanceJob = viewModelScope.launch {
            var nextCandidate: Track? = null

            // 1. Fetch radio tracks immediately for current track (3s timeout to prevent UI stall)
            if (curTrack != null) {
                try {
                    val fetched = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                        com.auralis.music.data.network.LocalizedContent.run { innerTubeClient.getRadioTracks(curTrack.id, curTrack.artist, curTrack.title) }
                    } ?: emptyList()
                    if (!_playerSettings.value.autoLoadMore || reqId != currentPlaybackRequestId.get()) return@launch
                    val existingIds = queueManager.state.queue.map { it.id }.toSet()
                    val uniqueTracks = fetched.filter { it.id !in existingIds && it.id != curTrack.id }
                    nextCandidate = uniqueTracks.firstOrNull() ?: fetched.firstOrNull { it.id != curTrack.id }
                    val batch = uniqueTracks.take(14)

                    if (batch.isNotEmpty()) {
                        if (audioPlayer != null) {
                            audioPlayer.appendTracks(batch)
                        } else {
                            val qState = queueManager.appendTracks(batch)
                            _uiState.update { it.copy(queue = qState.queue) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AuralisPlayback", "[AutoRadio] Error in InnerTube radio fetch: ${e.message}")
                }
            }

            // 2. Fallback to history / local recommendations
            if (nextCandidate == null) {
                try {
                    val heavyRotation = historyRepository.getRecentHeavyRotation()
                    val history = historyRepository.getHistory().firstOrNull()?.map { it.track } ?: emptyList()
                    val liked = historyRepository.getLikedSeeds()
                    val allCandidates = (heavyRotation + history + liked).distinctBy { it.id }
                    if (!_playerSettings.value.autoLoadMore || reqId != currentPlaybackRequestId.get()) return@launch
                    val existingIds = queueManager.state.queue.map { it.id }.toSet()
                    nextCandidate = allCandidates.filter { it.id !in existingIds && it.id != curTrack?.id }.shuffled().firstOrNull()
                        ?: allCandidates.filter { it.id != curTrack?.id }.shuffled().firstOrNull()
                } catch (e: Exception) {
                    Log.w("AuralisPlayback", "[AutoRadio] Error in local recommendations fallback: ${e.message}")
                }
            }

            // 3. Fallback to searching songs by the same artist
            if (nextCandidate == null && curTrack != null && curTrack.artist.isNotBlank()) {
                try {
                    val artistSongs = kotlinx.coroutines.withTimeoutOrNull(2500L) {
                        com.auralis.music.data.network.LocalizedContent.run { innerTubeClient.search("${curTrack.artist} songs", com.auralis.music.data.network.InnerTubeClient.FILTER_SONGS).songs }
                    } ?: emptyList()
                    if (!_playerSettings.value.autoLoadMore || reqId != currentPlaybackRequestId.get()) return@launch
                    val existingIds = queueManager.state.queue.map { it.id }.toSet()
                    val uniqueTracks = artistSongs.filter { it.id !in existingIds && it.id != curTrack.id }
                    nextCandidate = uniqueTracks.firstOrNull() ?: artistSongs.firstOrNull { it.id != curTrack.id }
                    val batch = uniqueTracks.take(14)
                    if (batch.isNotEmpty()) {
                        if (audioPlayer != null) {
                            audioPlayer.appendTracks(batch)
                        } else {
                            val qState = queueManager.appendTracks(batch)
                            _uiState.update { it.copy(queue = qState.queue) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AuralisPlayback", "[AutoRadio] Error in artist search fallback: ${e.message}")
                }
            }

            if (!_playerSettings.value.autoLoadMore || reqId != currentPlaybackRequestId.get()) return@launch
            // 4. Play found candidate seamlessly
            if (nextCandidate != null) {
                Log.d("AuralisPlayback", "[AutoRadio] Advancing to: '${nextCandidate.title}' by ${nextCandidate.artist}")
                if (audioPlayer != null) {
                    audioPlayer.appendTracks(listOf(nextCandidate))
                    val advanced = queueManager.advanceNext() ?: nextCandidate
                    _uiState.update {
                        it.copy(
                            currentTrack = advanced,
                            queue = queueManager.state.queue,
                            currentIndex = queueManager.state.currentIndex,
                            isPlaying = true,
                            playbackPositionMs = 0,
                            durationMs = advanced.duration * 1000,
                            errorMessage = null
                        )
                    }
                    audioPlayer.play(advanced, initialSeekMs = 0L)
                    audioPlayer.syncUpcomingGaplessTrack()
                    audioPlayer.persistQueue()
                } else {
                    queueManager.appendTracks(listOf(nextCandidate))
                    val advanced = queueManager.advanceNext() ?: nextCandidate
                    _uiState.update {
                        it.copy(
                            currentTrack = advanced,
                            queue = queueManager.state.queue,
                            currentIndex = queueManager.state.currentIndex,
                            isPlaying = true,
                            playbackPositionMs = 0,
                            durationMs = advanced.duration * 1000,
                            errorMessage = null
                        )
                    }
                    triggerPlayback(advanced, debounceMs = 0L, requestId = reqId)
                }
                loadLyrics(nextCandidate, reqId)

                if (isAutoRadioMode && _playerSettings.value.autoLoadMore && queueManager.isNearEnd(threshold = 4)) {
                    fetchAndAppendRadioTracks(nextCandidate)
                }
            } else {
                Log.w("AuralisPlayback", "[AutoRadio] No candidate found, looping back to first track or replaying")
                val fallbackTrack = queueManager.state.queue.firstOrNull() ?: curTrack
                if (fallbackTrack != null) {
                    if (audioPlayer != null) {
                        audioPlayer.playTrack(fallbackTrack, queueManager.state.queue, 0)
                    } else {
                        triggerPlayback(fallbackTrack, debounceMs = 0L, requestId = reqId)
                    }
                } else {
                    audioPlayer?.pause()
                    _uiState.update { it.copy(isPlaying = false) }
                }
            }
        }
    }

    private var lastPreviousTapMs = 0L

    fun previous() {
        Log.d("AuralisPlayback", "[PlayerViewModel] previous() triggered")
        seekJob?.cancel()
        palettePreloadJob?.cancel()
        val now = System.currentTimeMillis()
        val isDoubleTap = (now - lastPreviousTapMs) <= 2000L
        lastPreviousTapMs = now

        if (_playbackPositionMs.value <= 3000 || isDoubleTap) {
            context?.let { ctx ->
                val prevIdx = _uiState.value.currentIndex - 1
                _uiState.value.queue.getOrNull(prevIdx)?.let { prevTrack ->
                    palettePreloadJob = viewModelScope.launch(Dispatchers.Default) {
                        com.auralis.music.ui.theme.ArtworkPaletteCache.updateForTrack(ctx, prevTrack)
                    }
                }
            }
        }
        if (audioPlayer != null) {
            audioPlayer.previous()
        } else {
            if (_playbackPositionMs.value > 3000 && !isDoubleTap) {
                seekTo(0)
                return
            }
            val reqId = currentPlaybackRequestId.incrementAndGet()
            val prevTrack = queueManager.advancePrevious(isUserSkip = true)
            if (prevTrack != null) {
                val qState = queueManager.state
                _uiState.update {
                    it.copy(
                        currentTrack = prevTrack,
                        lyrics = null,
                        isLoadingLyrics = true,
                        currentIndex = qState.currentIndex,
                        isPlaying = true,
                        playbackPositionMs = 0,
                        durationMs = prevTrack.duration * 1000,
                        errorMessage = null
                    )
                }
                triggerPlayback(prevTrack, debounceMs = 0L, requestId = reqId)
                loadLyrics(prevTrack, reqId)
            } else {
                seekTo(0)
            }
        }
    }

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (audioPlayer != null) {
            audioPlayer.addToQueue(tracks)
        } else {
            val qState = queueManager.addToQueue(tracks)
            _uiState.update { it.copy(queue = qState.queue) }
            if (_uiState.value.currentTrack == null && tracks.isNotEmpty()) {
                playTrack(tracks.first(), qState.queue, 0, isUserQueue = true)
            }
        }
    }

    fun toggleShuffle() {
        if (audioPlayer != null) {
            audioPlayer.toggleShuffle()
        } else {
            val qState = queueManager.toggleShuffle()
            _uiState.update {
                it.copy(
                    queue = qState.queue,
                    currentIndex = qState.currentIndex,
                    isShuffled = qState.isShuffled
                )
            }
        }
    }

    fun toggleRepeat() {
        if (audioPlayer != null) {
            val nextMode = audioPlayer.toggleRepeat()
            _uiState.update { it.copy(repeatMode = nextMode) }
        } else {
            val nextMode = when (_uiState.value.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            queueManager.setRepeatMode(nextMode)
            _uiState.update { it.copy(repeatMode = nextMode) }
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (audioPlayer != null) {
            audioPlayer.moveQueueItem(fromIndex, toIndex)
        } else {
            val qState = queueManager.moveItem(fromIndex, toIndex)
            _uiState.update { it.copy(queue = qState.queue, currentIndex = qState.currentIndex) }
        }
    }

    fun removeQueueItem(removeIndex: Int) {
        if (audioPlayer != null) {
            audioPlayer.removeQueueItem(removeIndex)
        } else {
            val qState = queueManager.removeItem(removeIndex)
            _uiState.update { it.copy(queue = qState.queue, currentIndex = qState.currentIndex) }
        }
    }

    fun playNext(track: Track) {
        playNext(listOf(track))
    }

    fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (audioPlayer != null) {
            audioPlayer.playNext(tracks)
        } else {
            if (_uiState.value.currentTrack == null) {
                playTrack(tracks.first(), tracks, 0, isUserQueue = true)
                return
            }
            val qState = queueManager.playNext(tracks)
            _uiState.update { it.copy(queue = qState.queue) }
        }
    }

    fun toggleFavorite(track: Track? = null) {
        val target = track ?: _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            libraryRepository.toggleFavorite(target)
        }
    }

    fun setLyricsOffset(offsetMs: Long) {
        _uiState.update { it.copy(lyricsOffsetMs = offsetMs) }
    }

    fun showLyrics() {
        _uiState.update { it.copy(showLyricsView = false, showInlineLyrics = true, lyricsViewRequestId = it.lyricsViewRequestId + 1) }
    }

    fun hideLyrics() {
        _uiState.update { it.copy(showLyricsView = false, showInlineLyrics = false, lyricsViewRequestId = it.lyricsViewRequestId + 1) }
    }

    fun toggleLyricsView() {
        _uiState.update { it.copy(showLyricsView = !it.showLyricsView) }
    }

    fun setSleepTimer(minutes: Int) {
        if (audioPlayer != null) {
            if (minutes == -1) {
                audioPlayer.setSleepTimerEndOfTrack()
            } else if (minutes <= 0) {
                audioPlayer.cancelSleepTimer()
            } else {
                audioPlayer.setSleepTimer(minutes)
            }
            return
        }

        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            sleepTimerManager.cancel()
            _uiState.update { it.copy(sleepTimerSeconds = 0, isSleepTimerEndOfSong = false) }
        } else {
            sleepTimerManager.setTimer(minutes)
            _uiState.update { it.copy(sleepTimerSeconds = sleepTimerManager.getRemainingSeconds(), isSleepTimerEndOfSong = false) }
            startSleepTimerTicker()
        }
    }

    fun cancelSleepTimer() {
        setSleepTimer(0)
    }

    private fun startSleepTimerTicker() {
        sleepTimerJob = viewModelScope.launch {
            while (sleepTimerManager.isSet) {
                val remaining = sleepTimerManager.getRemainingSeconds()
                _uiState.update { it.copy(sleepTimerSeconds = remaining) }
                if (remaining <= 0L || sleepTimerManager.isExpired()) {
                    sleepTimerManager.cancel()
                    if (audioPlayer != null) {
                        audioPlayer.pause()
                    }
                    _uiState.update { it.copy(isPlaying = false, sleepTimerSeconds = 0, isSleepTimerEndOfSong = false) }
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun refreshLyricsForResolvedPlayback() {
        val track = _uiState.value.currentTrack ?: return
        val resolvedId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(track.id) ?: track.id
        val playbackDuration = audioPlayer?.durationMs?.value?.takeIf { it > 0L } ?: return
        if (needsResolvedLyricsRefresh(lyricsLookupTrackId, track.id, lyricsLookupVideoId, resolvedId, lyricsLookupDurationMs, playbackDuration)) {
            Log.d("AuralisLyrics", "Refreshing lyrics for resolved playback: ${track.title}, videoId=$resolvedId, duration=$playbackDuration")
            loadLyrics(track, currentPlaybackRequestId.get(), forceSourceRefresh = true)
        }
    }

    private fun loadLyrics(
        track: Track,
        requestId: Long = currentPlaybackRequestId.get(),
        forceSourceRefresh: Boolean = false
    ) {
        lyricsJob?.cancel()
        val generation = lyricsLookupGeneration.incrementAndGet()
        fun isActive() = requestId == currentPlaybackRequestId.get() && generation == lyricsLookupGeneration.get()
        val resolvedIdAtStart = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(track.id) ?: track.id
        val resolvedDurationAtStart = audioPlayer?.durationMs?.value?.takeIf { it > 0L }
            ?: _uiState.value.durationMs.takeIf { it > 0L } ?: track.duration * 1000L
        val safeFallback = if (forceSourceRefresh) {
            retainLyricsForResolvedPlayback(_uiState.value.lyrics, track, resolvedIdAtStart, resolvedDurationAtStart)
        } else null
        _uiState.update {
            it.copy(
                lyrics = if (forceSourceRefresh) safeFallback else
                    it.lyrics?.takeIf { lyricsTier(it) >= 2 && it.trackName?.equals(track.title, ignoreCase = true) == true },
                isLoadingLyrics = true
            )
        }
        lyricsJob = viewModelScope.launch {
            val effectiveVideoId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(track.id) ?: track.id
            val effectiveDurationSec = com.auralis.music.data.network.AudioStreamResolver.getEffectiveDurationSec(track.id, track.duration)

            // 1. Instant check in local cache (memory + Room DB) for 0ms display
            val exactDurationMs = audioPlayer?.durationMs?.value?.takeIf { it > 0L }
                ?: _uiState.value.durationMs.takeIf { it > 0L }
                ?: (effectiveDurationSec * 1000L)
            lyricsLookupTrackId = track.id
            lyricsLookupVideoId = effectiveVideoId
            lyricsLookupDurationMs = exactDurationMs

            val currentSilence = audioPlayer?.audioLeadingSilenceMs?.value
            val cached = if (forceSourceRefresh) null else withContext(Dispatchers.IO) {
                lyricsRepository.getCachedLyrics(
                    title = track.title,
                    artist = track.artist,
                    durationSec = effectiveDurationSec,
                    videoId = effectiveVideoId,
                    album = track.album,
                    channelTitle = track.channelTitle,
                    durationMs = exactDurationMs,
                    audioLeadingSilenceMs = currentSilence
                )
            }
            // A cached RICHSYNC entry is already the best tier available; nothing to
            // upgrade to, so it settles here.
            if (cached != null && lyricsTier(cached) == 3) {
                if (isActive()) {
                    _uiState.update { it.copy(lyrics = cached, isLoadingLyrics = false) }
                    triggerAiTranslation(track, cached, requestId)
                }
                // MetroLyrics speaker layout (7.7): a cached word-sync entry written without
                // line-level speakers (older cache rows, or a speaker-less source that won the
                // race) gets one background look per session for a speaker-tagged copy. The
                // cached lyrics stay on screen; they are only replaced if one is found.
                val enrichKey = (effectiveVideoId.takeIf { it.isNotBlank() } ?: "${track.title}::${track.artist}::$effectiveDurationSec").lowercase()
                if (!com.auralis.music.data.network.LyricsClient.hasSpeakerMetadata(cached) &&
                    isMetroLyricsMode() && lyricsUpgradeAttempted.add(enrichKey)
                ) {
                    val enriched = try {
                        withContext(Dispatchers.IO) {
                            lyricsRepository.enrichSpeakerMetadata(
                                title = track.title,
                                artist = track.artist,
                                durationSec = effectiveDurationSec,
                                videoId = effectiveVideoId,
                                album = track.album,
                                channelTitle = track.channelTitle,
                                durationMs = exactDurationMs,
                                audioLeadingSilenceMs = audioPlayer?.audioLeadingSilenceMs?.value ?: currentSilence
                            )
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        null
                    }
                    if (enriched != null && isActive()) {
                        _uiState.update { it.copy(lyrics = enriched, isLoadingLyrics = false) }
                        triggerAiTranslation(track, enriched, requestId)
                    }
                }
                return@launch
            }

            // Otherwise, show whatever we have (even LINE_SYNC) while background upgrade runs.
            // A network upgrade is only attempted once per unique (title, artist, duration)
            // track per session, and the result is kept only if it ranks higher.
            val cachedIsUsable = lyricsTier(cached) >= 2
            if (isActive()) {
                _uiState.update { it.copy(lyrics = cached.takeIf { cachedIsUsable } ?: safeFallback, isLoadingLyrics = !cachedIsUsable) }
                if (cachedIsUsable) {
                    triggerAiTranslation(track, cached, requestId)
                }
            }

            val trackKey = (effectiveVideoId.takeIf { it.isNotBlank() } ?: "${track.title}::${track.artist}::$effectiveDurationSec").lowercase()
            if (cachedIsUsable && !lyricsUpgradeAttempted.add(trackKey)) {
                return@launch
            }

            try {
                // 2. Background network cascade (LRCLIB, JioSaavn, NetEase, KuGou, Musixmatch, etc.)
                // Early synced results are shown only if they beat what's on screen,
                // and never after the final answer has landed.
                val finalDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
                val data = searchForTimedLyrics(
                    timedLyricsVisible = {
                        !forceSourceRefresh && isActive() && lyricsTier(_uiState.value.lyrics) >= 2
                    },
                    search = { attempt ->
                        if (attempt > 0) Log.d("AuralisLyrics", "Retrying timed lyrics lookup: attempt ${attempt + 1}")
                        // Audio resolution can finish after the first lyrics request starts.
                        // Refresh its matched ID, duration and leading silence on every pass.
                        val resolvedVideoId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(track.id) ?: track.id
                        val resolvedDurationSec = com.auralis.music.data.network.AudioStreamResolver.getEffectiveDurationSec(track.id, track.duration)
                        val resolvedDurationMs = audioPlayer?.durationMs?.value?.takeIf { it > 0L }
                            ?: _uiState.value.durationMs.takeIf { it > 0L }
                            ?: (resolvedDurationSec * 1000L)
                        val lookupDurationSec = if (forceSourceRefresh && resolvedDurationMs > 0L) {
                            (resolvedDurationMs + 500L) / 1000L
                        } else resolvedDurationSec
                        withContext(Dispatchers.IO) {
                            lyricsRepository.getLyricsWithInterim(
                                title = track.title,
                                artist = track.artist,
                                durationSec = lookupDurationSec,
                                videoId = resolvedVideoId,
                                album = track.album,
                                channelTitle = track.channelTitle,
                                durationMs = resolvedDurationMs,
                                audioLeadingSilenceMs = audioPlayer?.audioLeadingSilenceMs?.value ?: currentSilence,
                                // A prior plain/empty result may be cached; retry the sources.
                                forceRefresh = true,
                                onInterim = { interim ->
                                    if (!finalDelivered.get() && lyricsTier(interim) >= 2 && isActive()) {
                                        _uiState.update { state ->
                                            if (!finalDelivered.get() && lyricsTier(interim) > lyricsTier(state.lyrics)) {
                                                state.copy(lyrics = interim, isLoadingLyrics = false)
                                            } else state
                                        }
                                    }
                                }
                            )
                        }
                    }
                )
                finalDelivered.set(true)

                if (isActive()) {
                    _uiState.update { state ->
                        state.copy(
                            lyrics = if (forceSourceRefresh && lyricsTier(data) >= 2) data else selectSettledLyrics(cached, if (forceSourceRefresh) safeFallback else state.lyrics, data),
                            isLoadingLyrics = false
                        )
                    }
                    if (data != null && _uiState.value.lyrics === data) {
                        triggerAiTranslation(track, data, requestId)
                    }
                }
                if (data == null || lyricsTier(data) <= lyricsTier(cached)) {
                    lyricsUpgradeAttempted.remove(trackKey)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lyricsUpgradeAttempted.remove(trackKey)
                if (isActive()) {
                    _uiState.update { it.copy(lyrics = cached.takeIf { lyricsTier(cached) >= 2 } ?: if (forceSourceRefresh) safeFallback else it.lyrics, isLoadingLyrics = false) }
                }
            }
        }
    }

    /** Whether MetroLyrics (the only view with a speaker-aware layout) is the active lyrics style. */
    private suspend fun isMetroLyricsMode(): Boolean {
        val ctx = context ?: return false
        return try {
            val settings = com.auralis.music.data.datastore.AppearanceSettingsDataStore(ctx).settingsFlow.first()
            com.auralis.music.domain.model.LyricsAnimationMode.fromDisplayName(settings.lyricsAnimation) ==
                com.auralis.music.domain.model.LyricsAnimationMode.METRO_LYRICS
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    private fun triggerAiTranslation(track: Track, lyricsData: LyricsData?, requestId: Long) {
        if (lyricsData == null || lyricsData.lines.isEmpty()) return
        translationJob?.cancel()
        translationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = context?.let { ctx ->
                    val dataStore = com.auralis.music.data.datastore.AiTranslationDataStore(ctx)
                    dataStore.settingsFlow.first()
                } ?: com.auralis.music.domain.model.AiTranslationSettings()

                val translated = com.auralis.music.data.network.AiLyricsTranslator.translateLyrics(
                    trackId = track.id,
                    lyrics = lyricsData,
                    settings = settings
                )
                if (translated != null && requestId == currentPlaybackRequestId.get()) {
                    _uiState.update { current ->
                        if (current.currentTrack?.id == track.id) {
                            current.copy(lyrics = translated)
                        } else {
                            current
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun searchLyricsManually(customTitle: String, customArtist: String) {
        val track = _uiState.value.currentTrack ?: return
        val reqId = currentPlaybackRequestId.get()
        _uiState.update { it.copy(isLoadingLyrics = true) }
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val effectiveVideoId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(track.id) ?: track.id
            val effectiveDurationSec = com.auralis.music.data.network.AudioStreamResolver.getEffectiveDurationSec(track.id, track.duration)
            val exactDurationMs = audioPlayer?.durationMs?.value?.takeIf { it > 0L }
                ?: _uiState.value.durationMs.takeIf { it > 0L }
                ?: (effectiveDurationSec * 1000L)
            val data = withContext(Dispatchers.IO) {
                lyricsRepository.getLyrics(
                    title = customTitle.ifBlank { track.title },
                    artist = customArtist.ifBlank { track.artist },
                    durationSec = effectiveDurationSec,
                    videoId = effectiveVideoId,
                    album = track.album,
                    channelTitle = track.channelTitle,
                    durationMs = exactDurationMs,
                    forceRefresh = true
                )
            }
            if (reqId == currentPlaybackRequestId.get()) {
                _uiState.update { it.copy(lyrics = data, isLoadingLyrics = false) }
                triggerAiTranslation(track, data, reqId)
            }
        }
    }

    // ── Lyrics provider sheet: ask one source at a time and switch to its lyrics ──
    private val _lyricsProviderPicks = MutableStateFlow(LyricsProviderPicks())
    val lyricsProviderPicks: StateFlow<LyricsProviderPicks> = _lyricsProviderPicks.asStateFlow()
    private val providerPickResults = java.util.concurrent.ConcurrentHashMap<LyricsProvider, LyricsData>()

    fun selectableLyricsProviders(): List<LyricsProvider> = lyricsRepository.selectableProviders()

    private fun providerPicksFor(trackId: String): LyricsProviderPicks {
        val picks = _lyricsProviderPicks.value
        if (picks.trackId == trackId) return picks
        providerPickResults.clear()
        return LyricsProviderPicks(trackId = trackId).also { _lyricsProviderPicks.value = it }
    }

    private fun setProviderStatus(
        trackId: String,
        provider: LyricsProvider,
        status: LyricsProviderStatus,
        syncType: SyncType? = null
    ) {
        _lyricsProviderPicks.update { picks ->
            if (picks.trackId != trackId) picks else picks.copy(
                status = picks.status + (provider to status),
                syncTypes = if (syncType != null) picks.syncTypes + (provider to syncType) else picks.syncTypes
            )
        }
    }

    /**
     * Fetches [provider] alone for the playing song, or reuses what it already returned.
     * Found lyrics replace the shown ones at once and become this song's cached choice.
     */
    fun pickLyricsProvider(provider: LyricsProvider) {
        val track = _uiState.value.currentTrack ?: return
        val picks = providerPicksFor(track.id)
        providerPickResults[provider]?.let { found ->
            applyPickedLyrics(track, found)
            return
        }
        if (picks.status[provider] == LyricsProviderStatus.FETCHING) return
        setProviderStatus(track.id, provider, LyricsProviderStatus.FETCHING)
        viewModelScope.launch {
            val effectiveVideoId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(track.id) ?: track.id
            val effectiveDurationSec = com.auralis.music.data.network.AudioStreamResolver.getEffectiveDurationSec(track.id, track.duration)
            val exactDurationMs = audioPlayer?.durationMs?.value?.takeIf { it > 0L }
                ?: _uiState.value.durationMs.takeIf { it > 0L }
                ?: (effectiveDurationSec * 1000L)
            val data = try {
                withContext(Dispatchers.IO) {
                    lyricsRepository.fetchFromProvider(
                        provider = provider,
                        title = track.title,
                        artist = track.artist,
                        durationSec = effectiveDurationSec,
                        videoId = effectiveVideoId,
                        album = track.album,
                        channelTitle = track.channelTitle,
                        durationMs = exactDurationMs,
                        audioLeadingSilenceMs = audioPlayer?.audioLeadingSilenceMs?.value
                    )
                }
            } catch (e: Exception) {
                Log.w("AuralisLyrics", "[pickLyricsProvider] $provider failed: ${e.message}")
                null
            }
            if (_uiState.value.currentTrack?.id != track.id) return@launch
            if (data == null || data.lines.isEmpty()) {
                setProviderStatus(track.id, provider, LyricsProviderStatus.NOT_FOUND)
            } else {
                providerPickResults[provider] = data
                setProviderStatus(track.id, provider, LyricsProviderStatus.FOUND, data.syncType)
                applyPickedLyrics(track, data)
            }
        }
    }

    private fun applyPickedLyrics(track: Track, data: LyricsData) {
        // The copy being replaced stays one tap away as "found".
        _uiState.value.lyrics?.takeIf { it.lines.isNotEmpty() && it.provider != data.provider }?.let { shown ->
            providerPickResults.putIfAbsent(shown.provider, shown)
            setProviderStatus(track.id, shown.provider, LyricsProviderStatus.FOUND, shown.syncType)
        }
        // A race still running for this song must not overwrite the user's pick.
        lyricsLookupGeneration.incrementAndGet()
        lyricsJob?.cancel()
        val reqId = currentPlaybackRequestId.get()
        _uiState.update { it.copy(lyrics = data, isLoadingLyrics = false) }
        triggerAiTranslation(track, data, reqId)
        viewModelScope.launch(Dispatchers.IO) {
            val effectiveVideoId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(track.id) ?: track.id
            val effectiveDurationSec = com.auralis.music.data.network.AudioStreamResolver.getEffectiveDurationSec(track.id, track.duration)
            lyricsRepository.useLyrics(data, track.title, track.artist, effectiveDurationSec, effectiveVideoId)
        }
    }

    fun getAudioPlayer(): AuralisAudioPlayer? = audioPlayer

    override fun onCleared() {
        super.onCleared()
        // Do NOT call closePlayer() here.
        // PlayerViewModel lifecycle is bound to the Activity/UI, while AuralisAudioPlayer
        // and AuralisMediaService are bound to the application/service lifecycle for
        // persistent background playback. Clearing the UI must never stop or reset audio.
    }
}
