package com.auralis.music.windows.history

import com.auralis.music.desktop.library.DesktopListeningStats
import com.auralis.music.desktop.library.ListeningAccumulator
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.PrivacySettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

class DesktopListenRecorder(
    private val playback: DesktopPlaybackController,
    private val stats: DesktopListeningStats,
    private val privacy: StateFlow<PrivacySettings>,
    scope: CoroutineScope
) {
    private val accumulator = ListeningAccumulator()
    private var savedMs = 0L
    private var clearVersion = stats.clearVersion
    private var session = playback.session.value
    private val job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            synchronized(accumulator) {
                resetAfterClear()
                if (session != playback.session.value) {
                    flush(); accumulator.reset(); savedMs = 0; session = playback.session.value
                }
                val track = if (privacy.value.pauseListenHistory) null else playback.queue.value.current
                val playing = playback.state.value == VlcAudioPlayer.State.PLAYING
                val changed = track?.id != accumulator.track?.id
                if (changed || !playing) flush()
                accumulator.sample(track, playing, System.nanoTime(), System.currentTimeMillis())
                if (changed) savedMs = 0
                if (accumulator.timeMs - savedMs >= 5000) flush()
            }
            delay(250)
        }
    }

    private fun flush() {
        if (stats.clearVersion != clearVersion) { resetAfterClear(); return }
        val track = accumulator.track ?: return
        if (accumulator.timeMs <= savedMs) return
        stats.record(accumulator.sessionId, track, accumulator.startedAt, accumulator.timeMs)
        savedMs = accumulator.timeMs
    }

    private fun resetAfterClear() {
        if (stats.clearVersion != clearVersion) {
            accumulator.reset(); savedMs = 0; clearVersion = stats.clearVersion
        }
    }

    fun close() { job.cancel(); synchronized(accumulator) { flush() } }
}
