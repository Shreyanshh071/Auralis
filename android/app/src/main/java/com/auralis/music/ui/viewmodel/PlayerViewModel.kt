package com.auralis.music.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.auralis.music.data.network.InnerTubeClient
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

data class PlayerUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val playbackPositionMs: Long = 0,
    val durationMs: Long = 0,
    val isBuffering: Boolean = false,
    val isFavorite: Boolean = false,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isShuffled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val lyrics: LyricsData? = null,
    val isLoadingLyrics: Boolean = false,
    val lyricsOffsetMs: Long = 0,
    val sleepTimerSeconds: Long = 0,
    val showLyricsView: Boolean = false,
    val errorMessage: String? = null
)

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

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _playerSettings = MutableStateFlow(com.auralis.music.domain.model.PlayerSettings())
    val playerSettings: StateFlow<com.auralis.music.domain.model.PlayerSettings> = _playerSettings.asStateFlow()

    fun getPlaybackPosition(): Long = _playbackPositionMs.value

    private var sleepTimerJob: Job? = null
    private var playJob: Job? = null
    private var lyricsJob: Job? = null
    private var translationJob: Job? = null
    private var radioJob: Job? = null
    private var isAutoRadioMode: Boolean = true

    init {
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
            val initialTrack = player.currentTrack.value
            val initialQState = player.queueState.value
            if (initialTrack != null) {
                _uiState.update {
                    it.copy(
                        currentTrack = initialTrack,
                        queue = initialQState.queue,
                        currentIndex = initialQState.currentIndex,
                        isShuffled = initialQState.isShuffled,
                        repeatMode = initialQState.repeatMode,
                        isPlaying = player.isPlaying.value,
                        playbackPositionMs = player.playbackPositionMs.value,
                        durationMs = player.durationMs.value.takeIf { d -> d > 0 } ?: (initialTrack.duration * 1000L)
                    )
                }
                loadLyrics(initialTrack)
            }

            viewModelScope.launch {
                player.queueState.collect { qState ->
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
                player.currentTrack.collect { activeTrack ->
                    if (activeTrack != null && _uiState.value.currentTrack?.id != activeTrack.id) {
                        _uiState.update {
                            it.copy(
                                currentTrack = activeTrack,
                                durationMs = player.durationMs.value.takeIf { d -> d > 0 } ?: (activeTrack.duration * 1000L)
                            )
                        }
                        loadLyrics(activeTrack)
                    }
                }
            }

            player.setNavigationCallbacks(
                onNext = { next() },
                onPrevious = { previous() },
                onToggleFavorite = { toggleFavorite() },
                onToggleRepeat = { toggleRepeat() }
            )

            viewModelScope.launch {
                player.isPlaying.collect { playing ->
                    _uiState.update { it.copy(isPlaying = playing) }
                }
            }

            viewModelScope.launch {
                player.playbackPositionMs.collect { pos ->
                    _playbackPositionMs.value = pos
                }
            }

            viewModelScope.launch {
                player.durationMs.collect { dur ->
                    if (dur > 0) {
                        _uiState.update { it.copy(durationMs = dur) }
                    }
                }
            }

            viewModelScope.launch {
                player.isBuffering.collect { buffering ->
                    _uiState.update { it.copy(isBuffering = buffering) }
                }
            }

            viewModelScope.launch {
                player.playbackError.collect { error ->
                    _uiState.update { it.copy(errorMessage = error) }
                }
            }

            player.setOnGaplessTransitionCallback { nextTrack ->
                val advanced = queueManager.advanceNext()
                val effectiveTrack = advanced ?: nextTrack
                val reqId = currentPlaybackRequestId.incrementAndGet()
                val qState = queueManager.state
                _uiState.update {
                    it.copy(
                        currentTrack = effectiveTrack,
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

                // Pre-enqueue next song in queue for continuous gapless playback
                val nextInQueue = qState.queue.getOrNull(qState.currentIndex + 1)
                audioPlayer?.prefetchTrack(nextInQueue)

                // Background pre-fetch lyrics for next song in queue for 0ms instant display upon transition
                if (nextInQueue != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            lyricsRepository.getLyrics(
                                title = nextInQueue.title,
                                artist = nextInQueue.artist,
                                durationSec = nextInQueue.duration,
                                videoId = nextInQueue.id,
                                forceRefresh = false
                            )
                        } catch (_: Exception) {}
                    }
                }

                if (isAutoRadioMode && queueManager.isNearEnd(threshold = 4)) {
                    fetchAndAppendRadioTracks(effectiveTrack)
                }
            }
        }

        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _playerSettings.value = settings
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
                        val updatedTrack = track.copy(thumbnail = resolvedThumb)
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
                historyRepository.addToHistory(track)
                historyRepository.recordPlay(track)
            }
        }

        loadLyrics(track, requestId)
    }

    fun playTrack(
        track: Track,
        newQueue: List<Track> = emptyList(),
        startIndex: Int = 0,
        isUserQueue: Boolean = (newQueue.size > 1),
        initialPositionMs: Long = 0L
    ) {
        val reqId = currentPlaybackRequestId.incrementAndGet()
        val isAutoQueue = !isUserQueue || newQueue.size <= 1
        isAutoRadioMode = isAutoQueue

        Log.d("AuralisPlayback", "[UI Tap] playTrack #$reqId: id=${track.id}, title='${track.title}', queueSize=${newQueue.size}, isAutoRadio=$isAutoRadioMode, initialPos=${initialPositionMs}ms")
        if (audioPlayer != null) {
            audioPlayer.playTrack(
                track = track,
                newQueue = newQueue,
                startIndex = startIndex,
                isUserQueue = isUserQueue,
                initialPositionMs = initialPositionMs
            )
        } else {
            val qState = if (newQueue.isNotEmpty()) {
                val isSameQueue = queueManager.state.queue.isNotEmpty() &&
                                  newQueue.map { it.id } == queueManager.state.queue.map { it.id }
                queueManager.setQueue(newQueue, startIndex, preserveOrderIfSame = isSameQueue, isUserQueue = !isAutoQueue)
            } else {
                queueManager.playTrack(track, isUserQueue = !isAutoQueue)
            }
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

        if (isAutoRadioMode) {
            fetchAndAppendRadioTracks(track)
        }
    }

    private fun fetchAndAppendRadioTracks(seedTrack: Track) {
        radioJob?.cancel()
        radioJob = viewModelScope.launch {
            try {
                delay(600)
                val radioTracks = withContext(Dispatchers.IO) {
                    innerTubeClient.getRadioTracks(seedTrack.id, seedTrack.artist, seedTrack.title)
                }
                if (radioTracks.isNotEmpty()) {
                    if (audioPlayer != null) {
                        audioPlayer.appendTracks(radioTracks)
                    } else {
                        val qState = queueManager.appendTracks(radioTracks)
                        _uiState.update {
                            it.copy(queue = qState.queue)
                        }
                    }
                    Log.d("AuralisPlayback", "[AutoRadio] Appended ${radioTracks.size} radio tracks for '${seedTrack.title}'")
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
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0, _uiState.value.durationMs.coerceAtLeast(0))
        _playbackPositionMs.value = clamped
        if (audioPlayer != null) {
            audioPlayer.seekTo(clamped)
        }
    }

    fun next() {
        Log.d("AuralisPlayback", "[PlayerViewModel] next() triggered")
        val reqId = currentPlaybackRequestId.incrementAndGet()
        val nextTrack = queueManager.advanceNext()
        if (nextTrack != null) {
            val qState = queueManager.state
            _uiState.update {
                it.copy(
                    currentTrack = nextTrack,
                    currentIndex = qState.currentIndex,
                    isPlaying = true,
                    playbackPositionMs = 0,
                    durationMs = nextTrack.duration * 1000,
                    errorMessage = null
                )
            }
            if (audioPlayer != null) {
                audioPlayer.play(nextTrack, initialSeekMs = 0L)
                val upcoming = queueManager.state.queue.getOrNull(queueManager.state.currentIndex + 1)
                audioPlayer.prefetchTrack(upcoming)
            } else {
                triggerPlayback(nextTrack, debounceMs = 0L, requestId = reqId)
            }

            if (isAutoRadioMode && queueManager.isNearEnd(threshold = 4)) {
                fetchAndAppendRadioTracks(nextTrack)
            }
        } else {
            if (isAutoRadioMode) {
                Log.d("AuralisPlayback", "[AutoRadio] End of queue reached in auto-radio mode -> advancing infinitely")
                handleInfiniteRadioAdvance(reqId)
            } else {
                if (audioPlayer != null) {
                    audioPlayer.pause()
                }
                _uiState.update { it.copy(isPlaying = false) }
            }
        }
    }

    private fun handleInfiniteRadioAdvance(reqId: Long) {
        val curTrack = _uiState.value.currentTrack
        viewModelScope.launch {
            var nextCandidate: Track? = null

            // 1. Fetch radio tracks immediately for current track
            if (curTrack != null) {
                try {
                    val fetched = innerTubeClient.getRadioTracks(curTrack.id, curTrack.artist, curTrack.title)
                    val existingIds = queueManager.state.queue.map { it.id }.toSet()
                    nextCandidate = fetched.firstOrNull { it.id !in existingIds && it.id != curTrack.id }
                        ?: fetched.firstOrNull { it.id != curTrack.id }

                    if (fetched.isNotEmpty()) {
                        if (audioPlayer != null) {
                            audioPlayer.appendTracks(fetched)
                        } else {
                            queueManager.appendTracks(fetched)
                        }
                    }
                } catch (_: Exception) {}
            }

            // 2. Fallback to history / local recommendations
            if (nextCandidate == null) {
                try {
                    val heavyRotation = historyRepository.getRecentHeavyRotation()
                    val history = historyRepository.getHistory().firstOrNull()?.map { it.track } ?: emptyList()
                    val liked = historyRepository.getLikedSeeds()
                    val allCandidates = (heavyRotation + history + liked).distinctBy { it.id }
                    val existingIds = queueManager.state.queue.map { it.id }.toSet()
                    nextCandidate = allCandidates.filter { it.id !in existingIds && it.id != curTrack?.id }.shuffled().firstOrNull()
                        ?: allCandidates.filter { it.id != curTrack?.id }.shuffled().firstOrNull()
                } catch (_: Exception) {}
            }

            // 3. Play found candidate seamlessly
            if (nextCandidate != null) {
                if (audioPlayer != null) {
                    audioPlayer.appendTracks(listOf(nextCandidate))
                } else {
                    queueManager.appendTracks(listOf(nextCandidate))
                }
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
                if (audioPlayer != null) {
                    audioPlayer.play(advanced, initialSeekMs = 0L)
                } else {
                    triggerPlayback(advanced, debounceMs = 0L, requestId = reqId)
                }
                fetchAndAppendRadioTracks(advanced)
            } else {
                // Keep playing current if available, or stop if nothing exists
                if (curTrack != null) {
                    if (audioPlayer != null) {
                        audioPlayer.play(curTrack, initialSeekMs = 0L)
                    } else {
                        triggerPlayback(curTrack, debounceMs = 0L, requestId = reqId)
                    }
                } else {
                    audioPlayer?.pause()
                    _uiState.update { it.copy(isPlaying = false) }
                }
            }
        }
    }

    fun previous() {
        Log.d("AuralisPlayback", "[PlayerViewModel] previous() triggered")
        if (audioPlayer != null) {
            audioPlayer.previous()
        } else {
            if (_playbackPositionMs.value > 3000) {
                seekTo(0)
                return
            }
            val reqId = currentPlaybackRequestId.incrementAndGet()
            val prevTrack = queueManager.advancePrevious()
            if (prevTrack != null) {
                val qState = queueManager.state
                _uiState.update {
                    it.copy(
                        currentTrack = prevTrack,
                        currentIndex = qState.currentIndex,
                        isPlaying = true,
                        playbackPositionMs = 0,
                        durationMs = prevTrack.duration * 1000,
                        errorMessage = null
                    )
                }
                triggerPlayback(prevTrack, debounceMs = 0L, requestId = reqId)
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
        if (audioPlayer != null) {
            audioPlayer.playNext(track)
        } else {
            if (_uiState.value.currentTrack == null) {
                playTrack(track, listOf(track), 0, isUserQueue = true)
                return
            }
            val qState = queueManager.playNext(track)
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

    fun toggleLyricsView() {
        _uiState.update { it.copy(showLyricsView = !it.showLyricsView) }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            sleepTimerManager.cancel()
            _uiState.update { it.copy(sleepTimerSeconds = 0) }
        } else {
            sleepTimerManager.setTimer(minutes)
            _uiState.update { it.copy(sleepTimerSeconds = sleepTimerManager.getRemainingSeconds()) }
            startSleepTimerTicker()
        }
    }

    private fun startSleepTimerTicker() {
        sleepTimerJob = viewModelScope.launch {
            while (sleepTimerManager.isActive) {
                delay(1000)
                val remaining = sleepTimerManager.getRemainingSeconds()
                _uiState.update { it.copy(sleepTimerSeconds = remaining) }
                if (sleepTimerManager.isExpired()) {
                    sleepTimerManager.cancel()
                    if (audioPlayer != null) {
                        audioPlayer.pause()
                    }
                    _uiState.update { it.copy(isPlaying = false, sleepTimerSeconds = 0) }
                    break
                }
            }
        }
    }

    private fun loadLyrics(track: Track, requestId: Long = currentPlaybackRequestId?.get() ?: 0L) {
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            // 1. Instant check in local cache (memory + Room DB) for 0ms display
            val cached = withContext(Dispatchers.IO) {
                lyricsRepository.getCachedLyrics(
                    title = track.title,
                    artist = track.artist,
                    durationSec = track.duration,
                    videoId = track.id
                )
            }
            if (cached != null && cached.syncType != com.auralis.music.domain.model.SyncType.PLAIN) {
                if (requestId == currentPlaybackRequestId.get()) {
                    _uiState.update { it.copy(lyrics = cached, isLoadingLyrics = false) }
                    triggerAiTranslation(track, cached, requestId)
                }
                return@launch
            } else {
                if (requestId == currentPlaybackRequestId.get()) {
                    _uiState.update { it.copy(lyrics = cached, isLoadingLyrics = true) }
                }
            }

            try {
                // 2. Background network cascade (LRCLIB, JioSaavn, NetEase, KuGou, Musixmatch, etc.)
                val data = withContext(Dispatchers.IO) {
                    lyricsRepository.getLyrics(
                        title = track.title,
                        artist = track.artist,
                        durationSec = track.duration,
                        videoId = track.id,
                        forceRefresh = (cached == null || cached.syncType == com.auralis.music.domain.model.SyncType.PLAIN)
                    )
                }

                if (requestId == currentPlaybackRequestId.get()) {
                    _uiState.update { it.copy(lyrics = data ?: cached, isLoadingLyrics = false) }
                    if (data != null) {
                        triggerAiTranslation(track, data, requestId)
                    }
                }
            } catch (_: Exception) {
                if (requestId == currentPlaybackRequestId.get()) {
                    _uiState.update { it.copy(isLoadingLyrics = false) }
                }
            }
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
            val data = withContext(Dispatchers.IO) {
                lyricsRepository.getLyrics(
                    title = customTitle.ifBlank { track.title },
                    artist = customArtist.ifBlank { track.artist },
                    durationSec = track.duration,
                    videoId = track.id,
                    forceRefresh = true
                )
            }
            if (reqId == currentPlaybackRequestId.get()) {
                _uiState.update { it.copy(lyrics = data, isLoadingLyrics = false) }
                triggerAiTranslation(track, data, reqId)
            }
        }
    }

    fun getAudioPlayer(): AuralisAudioPlayer? = audioPlayer
}
