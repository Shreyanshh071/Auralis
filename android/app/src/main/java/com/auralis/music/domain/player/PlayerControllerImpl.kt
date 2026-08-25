package com.auralis.music.domain.player

import android.content.Context
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.QueueState
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.SleepTimerManager
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Concrete implementation of PlayerController orchestrating ExoPlayer playback,
 * queue state, play history logging, sleep timers, and playback speeds.
 */
@UnstableApi
class PlayerControllerImpl(
    context: Context,
    private val historyRepository: HistoryRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) : PlayerController {

    private val audioPlayer = AuralisAudioPlayer.getInstance(context)
    private val queueManager = AudioQueueManager()
    private val sleepTimerManager = SleepTimerManager()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    override val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _isShuffled = MutableStateFlow(false)
    override val isShuffled: StateFlow<Boolean> = _isShuffled.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    override val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _sleepTimerSeconds = MutableStateFlow(0L)
    override val sleepTimerSeconds: StateFlow<Long> = _sleepTimerSeconds.asStateFlow()

    override val currentTrack: StateFlow<Track?> = audioPlayer.currentTrack
    override val isPlaying: StateFlow<Boolean> = audioPlayer.isPlaying
    override val playbackPositionMs: StateFlow<Long> = audioPlayer.playbackPositionMs
    override val durationMs: StateFlow<Long> = audioPlayer.durationMs
    override val isBuffering: StateFlow<Boolean> = audioPlayer.isBuffering

    private var sleepTimerJob: Job? = null

    private val _playerState = MutableStateFlow(PlayerState())
    override val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    init {
        // Automatically advance queue when track finishes
        audioPlayer.setOnTrackCompletedCallback {
            skipNext()
        }

        // Sync unified player state
        scope.launch {
            combine(
                audioPlayer.currentTrack,
                audioPlayer.isPlaying,
                audioPlayer.isBuffering,
                audioPlayer.playbackPositionMs,
                audioPlayer.durationMs,
                _playbackSpeed,
                _repeatMode,
                _isShuffled,
                _queue,
                _currentIndex,
                _sleepTimerSeconds,
                audioPlayer.playbackError
            ) { values ->
                PlayerState(
                    currentTrack = values[0] as? Track,
                    isPlaying = values[1] as Boolean,
                    isBuffering = values[2] as Boolean,
                    playbackPositionMs = values[3] as Long,
                    durationMs = values[4] as Long,
                    playbackSpeed = values[5] as Float,
                    repeatMode = values[6] as RepeatMode,
                    isShuffled = values[7] as Boolean,
                    queue = @Suppress("UNCHECKED_CAST") (values[8] as List<Track>),
                    currentIndex = values[9] as Int,
                    sleepTimerRemainingSeconds = values[10] as Long,
                    errorMessage = values[11] as? String
                )
            }.collect { state ->
                _playerState.value = state
            }
        }
    }

    override fun playTrack(track: Track, newQueue: List<Track>, startIndex: Int) {
        val qState = if (newQueue.isNotEmpty()) {
            queueManager.setQueue(newQueue, startIndex)
        } else {
            queueManager.playTrack(track)
        }

        syncQueueState(qState)
        audioPlayer.play(track)

        // Log track play for local taste profiler and analytics
        scope.launch {
            historyRepository.addToHistory(track)
            historyRepository.recordPlay(track)
        }
    }

    override fun togglePlayPause() {
        audioPlayer.togglePlayPause()
    }

    override fun resume() {
        audioPlayer.resume()
    }

    override fun pause() {
        audioPlayer.pause()
    }

    override fun seekTo(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }

    override fun skipNext() {
        val nextTrack = queueManager.advanceNext()
        if (nextTrack != null) {
            syncQueueState(queueManager.state)
            audioPlayer.play(nextTrack)
            scope.launch {
                historyRepository.addToHistory(nextTrack)
                historyRepository.recordPlay(nextTrack)
            }
        } else {
            audioPlayer.pause()
        }
    }

    override fun skipPrevious() {
        if (playbackPositionMs.value > 3000L) {
            seekTo(0L)
            return
        }
        val prevTrack = queueManager.advancePrevious()
        if (prevTrack != null) {
            syncQueueState(queueManager.state)
            audioPlayer.play(prevTrack)
            scope.launch {
                historyRepository.addToHistory(prevTrack)
                historyRepository.recordPlay(prevTrack)
            }
        } else {
            seekTo(0L)
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        val validSpeed = speed.coerceIn(0.25f, 3.0f)
        _playbackSpeed.value = validSpeed
        audioPlayer.exoPlayer.playbackParameters = PlaybackParameters(validSpeed)
    }

    override fun setRepeatMode(mode: RepeatMode) {
        queueManager.setRepeatMode(mode)
        _repeatMode.value = mode
    }

    override fun toggleShuffle() {
        val qState = queueManager.toggleShuffle()
        syncQueueState(qState)
    }

    override fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            sleepTimerManager.cancel()
            _sleepTimerSeconds.value = 0L
        } else {
            sleepTimerManager.setTimer(minutes)
            _sleepTimerSeconds.value = sleepTimerManager.getRemainingSeconds()
            startSleepTimerTicker()
        }
    }

    override fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerManager.cancel()
        _sleepTimerSeconds.value = 0L
    }

    private fun startSleepTimerTicker() {
        sleepTimerJob = scope.launch {
            while (sleepTimerManager.isActive) {
                delay(1000L)
                val remaining = sleepTimerManager.getRemainingSeconds()
                _sleepTimerSeconds.value = remaining
                if (sleepTimerManager.isExpired()) {
                    sleepTimerManager.cancel()
                    audioPlayer.pause()
                    _sleepTimerSeconds.value = 0L
                    break
                }
            }
        }
    }

    override fun addToQueue(track: Track) {
        val qState = queueManager.addToQueue(track)
        syncQueueState(qState)
    }

    override fun playNext(track: Track) {
        val qState = queueManager.playNext(track)
        syncQueueState(qState)
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val qState = queueManager.moveItem(fromIndex, toIndex)
        syncQueueState(qState)
    }

    override fun removeQueueItem(index: Int) {
        val qState = queueManager.removeItem(index)
        syncQueueState(qState)
    }

    override fun clearQueue() {
        val qState = queueManager.clearQueue()
        syncQueueState(qState)
        audioPlayer.pause()
    }

    private fun syncQueueState(state: QueueState) {
        _queue.value = state.queue
        _currentIndex.value = state.currentIndex
        _isShuffled.value = state.isShuffled
        _repeatMode.value = state.repeatMode
    }
}
