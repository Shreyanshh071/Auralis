package com.auralis.music.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.domain.repository.LyricsRepository
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
    private val audioPlayer: AuralisAudioPlayer? = null
) : ViewModel() {

    private val queueManager = AudioQueueManager()
    private val sleepTimerManager = SleepTimerManager()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var sleepTimerJob: Job? = null
    private var playJob: Job? = null
    private var lyricsJob: Job? = null

    init {
        // Collect current track favorite state reactively
        viewModelScope.launch {
            _uiState.map { it.currentTrack?.id }
                .distinctUntilChanged()
                .collectLatest { trackId ->
                    if (trackId != null) {
                        libraryRepository.isFavorite(trackId).collect { isFav ->
                            _uiState.update { it.copy(isFavorite = isFav) }
                        }
                    } else {
                        _uiState.update { it.copy(isFavorite = false) }
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
                onPrevious = { previous() }
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

    private fun triggerPlayback(track: Track, debounceMs: Long = 100L, requestId: Long = currentPlaybackRequestId.get()) {
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
            audioPlayer?.play(track, requestId)
            historyRepository.addToHistory(track)
            historyRepository.recordPlay(track)
        }

        lyricsJob = viewModelScope.launch {
            loadLyrics(track)
        }
    }

    fun playTrack(track: Track, newQueue: List<Track> = emptyList(), startIndex: Int = 0) {
        val reqId = currentPlaybackRequestId.incrementAndGet()
        Log.d("AuralisPlayback", "[UI Tap] playTrack #$reqId: id=${track.id}, title='${track.title}', queueSize=${newQueue.size}")
        val qState = if (newQueue.isNotEmpty()) {
            val isSameQueue = queueManager.state.queue.isNotEmpty() &&
                              newQueue.map { it.id } == queueManager.state.queue.map { it.id }
            queueManager.setQueue(newQueue, startIndex, preserveOrderIfSame = isSameQueue)
        } else {
            queueManager.playTrack(track)
        }

        _uiState.update {
            it.copy(
                currentTrack = qState.currentTrack,
                queue = qState.queue,
                currentIndex = qState.currentIndex,
                isShuffled = qState.isShuffled,
                isPlaying = true,
                playbackPositionMs = 0,
                durationMs = track.duration * 1000,
                errorMessage = null
            )
        }

        triggerPlayback(track, debounceMs = 0L, requestId = reqId)
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
        } else {
            if (audioPlayer != null) {
                audioPlayer.pause()
            }
            _uiState.update { it.copy(isPlaying = false) }
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
        val currentQ = _uiState.value.queue.toMutableList()
        currentQ.addAll(tracks)
        _uiState.update { it.copy(queue = currentQ) }
        if (_uiState.value.currentTrack == null && tracks.isNotEmpty()) {
            playTrack(tracks.first(), currentQ, 0)
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

    fun toggleFavorite() {
        val track = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            libraryRepository.toggleFavorite(track)
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
                durationSec = track.duration
            )
            _uiState.update { it.copy(lyrics = data, isLoadingLyrics = false) }
        }
    }

    fun getAudioPlayer(): AuralisAudioPlayer? = audioPlayer
}
