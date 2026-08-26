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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
    private val searchRepository: SearchRepository? = null
) : ViewModel() {

    private val queueManager = AudioQueueManager()
    private val sleepTimerManager = SleepTimerManager()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var sleepTimerJob: Job? = null
    private var playJob: Job? = null
    private var lyricsJob: Job? = null
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
            player.setOnTrackCompletedCallback {
                next()
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
                    _uiState.update { it.copy(playbackPositionMs = pos) }
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
        }
    }

    private val currentPlaybackRequestId = java.util.concurrent.atomic.AtomicLong(0L)

    private fun triggerPlayback(
        track: Track,
        debounceMs: Long = 100L,
        requestId: Long = currentPlaybackRequestId.get(),
        initialPositionMs: Long = 0L
    ) {
        playJob?.cancel()
        lyricsJob?.cancel()

        playJob = viewModelScope.launch {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            if (requestId != currentPlaybackRequestId.get()) {
                Log.d("AuralisPlayback", "[Stale triggerPlayback dropped] reqId=$requestId vs active=${currentPlaybackRequestId.get()}")
                return@launch
            }
            audioPlayer?.play(track, initialPositionMs, requestId)
            historyRepository.addToHistory(track)
            historyRepository.recordPlay(track)
        }

        lyricsJob = viewModelScope.launch {
            loadLyrics(track)
        }
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

        val upcomingTrack = qState.queue.getOrNull(qState.currentIndex + 1)
        audioPlayer?.prefetchTrack(upcomingTrack)

        if (isAutoRadioMode) {
            fetchAndAppendRadioTracks(track)
        }
    }

    private fun fetchAndAppendRadioTracks(seedTrack: Track) {
        radioJob?.cancel()
        radioJob = viewModelScope.launch {
            try {
                val radioTracks = innerTubeClient.getRadioTracks(seedTrack.id)
                if (radioTracks.isNotEmpty()) {
                    val qState = queueManager.appendTracks(radioTracks)
                    _uiState.update {
                        it.copy(queue = qState.queue)
                    }
                    Log.d("AuralisPlayback", "[AutoRadio] Appended ${radioTracks.size} radio tracks for '${seedTrack.title}' (total queue: ${qState.queue.size})")
                    val upcoming = qState.queue.getOrNull(qState.currentIndex + 1)
                    audioPlayer?.prefetchTrack(upcoming)
                } else {
                    loadFallbackTracks(seedTrack)
                }
            } catch (e: Exception) {
                Log.w("AuralisPlayback", "[AutoRadio] Error fetching radio tracks: ${e.message}")
                loadFallbackTracks(seedTrack)
            }
        }
    }

    private suspend fun loadFallbackTracks(seedTrack: Track) {
        try {
            val heavyRotation = historyRepository.getRecentHeavyRotation()
            val history = historyRepository.getHistory().firstOrNull()?.map { it.track } ?: emptyList()
            val liked = historyRepository.getLikedSeeds()
            val allCandidates = (heavyRotation + history + liked).distinctBy { it.id }
            val existingIds = queueManager.state.queue.map { it.id }.toSet()
            val candidates = allCandidates.filter { it.id != seedTrack.id && it.id !in existingIds }
            if (candidates.isNotEmpty()) {
                val qState = queueManager.appendTracks(candidates.shuffled().take(10))
                _uiState.update { it.copy(queue = qState.queue) }
            }
        } catch (_: Exception) {}
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

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0, _uiState.value.durationMs.coerceAtLeast(0))
        if (audioPlayer != null) {
            audioPlayer.seekTo(clamped)
        } else {
            _uiState.update { it.copy(playbackPositionMs = clamped) }
        }
    }

    fun next() {
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
            triggerPlayback(nextTrack, debounceMs = 0L, requestId = reqId)

            val upcoming = queueManager.state.queue.getOrNull(queueManager.state.currentIndex + 1)
            audioPlayer?.prefetchTrack(upcoming)

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
                    val fetched = innerTubeClient.getRadioTracks(curTrack.id)
                    val existingIds = queueManager.state.queue.map { it.id }.toSet()
                    nextCandidate = fetched.firstOrNull { it.id !in existingIds && it.id != curTrack.id }
                        ?: fetched.firstOrNull { it.id != curTrack.id }

                    if (fetched.isNotEmpty()) {
                        queueManager.appendTracks(fetched)
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
                fetchAndAppendRadioTracks(advanced)
            } else {
                // Keep playing current if available, or stop if nothing exists
                if (curTrack != null) {
                    triggerPlayback(curTrack, debounceMs = 0L, requestId = reqId)
                } else {
                    audioPlayer?.pause()
                    _uiState.update { it.copy(isPlaying = false) }
                }
            }
        }
    }

    fun previous() {
        if (_uiState.value.playbackPositionMs > 3000) {
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

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val qState = queueManager.appendTracks(tracks)
        _uiState.update { it.copy(queue = qState.queue) }
        if (_uiState.value.currentTrack == null && tracks.isNotEmpty()) {
            playTrack(tracks.first(), qState.queue, 0, isUserQueue = true)
        }
    }

    fun toggleShuffle() {
        val qState = queueManager.toggleShuffle()
        _uiState.update {
            it.copy(
                queue = qState.queue,
                currentIndex = qState.currentIndex,
                isShuffled = qState.isShuffled
            )
        }
    }

    fun toggleRepeat() {
        val nextMode = when (_uiState.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        queueManager.setRepeatMode(nextMode)
        _uiState.update { it.copy(repeatMode = nextMode) }
    }

    fun playNext(track: Track) {
        if (_uiState.value.currentTrack == null) {
            playTrack(track, listOf(track), 0, isUserQueue = true)
            return
        }
        val qState = queueManager.playNext(track)
        _uiState.update { it.copy(queue = qState.queue) }
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

    private fun loadLyrics(track: Track) {
        _uiState.update { it.copy(isLoadingLyrics = true, lyrics = null) }
        viewModelScope.launch {
            val data = lyricsRepository.getLyrics(
                title = track.title,
                artist = track.artist,
                durationSec = track.duration,
                videoId = track.id
            )
            _uiState.update { it.copy(lyrics = data, isLoadingLyrics = false) }
        }
    }

    fun getAudioPlayer(): AuralisAudioPlayer? = audioPlayer
}
