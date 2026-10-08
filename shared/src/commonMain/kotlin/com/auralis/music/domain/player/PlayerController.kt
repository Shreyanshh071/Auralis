package com.auralis.music.domain.player

import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Player state snapshot emitted by the PlayerController.
 */
data class PlayerState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isShuffled: Boolean = false,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val sleepTimerRemainingSeconds: Long = 0L,
    val errorMessage: String? = null
)

/**
 * StateFlow-driven reactive audio player controller interface.
 */
interface PlayerController {
    val playerState: StateFlow<PlayerState>
    val currentTrack: StateFlow<Track?>
    val isPlaying: StateFlow<Boolean>
    val playbackPositionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val isBuffering: StateFlow<Boolean>
    val playbackSpeed: StateFlow<Float>
    val repeatMode: StateFlow<RepeatMode>
    val isShuffled: StateFlow<Boolean>
    val queue: StateFlow<List<Track>>
    val currentIndex: StateFlow<Int>
    val sleepTimerSeconds: StateFlow<Long>

    fun playTrack(track: Track, newQueue: List<Track> = emptyList(), startIndex: Int = 0)
    fun togglePlayPause()
    fun resume()
    fun pause()
    fun seekTo(positionMs: Long)
    fun skipNext()
    fun skipPrevious()
    fun setPlaybackSpeed(speed: Float)
    fun setRepeatMode(mode: RepeatMode)
    fun toggleShuffle()
    fun setSleepTimer(minutes: Int)
    fun cancelSleepTimer()
    fun addToQueue(track: Track)
    fun playNext(track: Track)
    fun moveQueueItem(fromIndex: Int, toIndex: Int)
    fun removeQueueItem(index: Int)
    fun clearQueue()
}
