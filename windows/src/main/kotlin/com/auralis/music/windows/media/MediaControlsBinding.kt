package com.auralis.music.windows.media

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps platform media overlays (Windows SMTC or macOS Now Playing) in step with playback, and routes controls back. */
fun bindMediaControls(windowHandle: Long, playback: DesktopPlaybackController, scope: CoroutineScope): DesktopMediaControls? {
    val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    val onButton: (DesktopMediaControls.Button) -> Unit = { button ->
        scope.launch {
            when (button) {
                DesktopMediaControls.Button.PLAY,
                DesktopMediaControls.Button.PAUSE -> playback.togglePlayPause()
                DesktopMediaControls.Button.NEXT -> playback.next()
                DesktopMediaControls.Button.PREVIOUS -> playback.previous()
                DesktopMediaControls.Button.STOP -> if (playback.state.value == VlcAudioPlayer.State.PLAYING) playback.togglePlayPause()
            }
        }
    }

    val controls: DesktopMediaControls = when {
        isWindows -> WindowsMediaControls.create(windowHandle, onButton)
        isMac -> MacMediaControls.create(onButton, onSeek = { ms -> playback.seekTo(ms) })
        else -> null
    } ?: return null

    scope.launch {
        playback.queue.map { it.current }.distinctUntilChanged().collect { track ->
            if (track == null) controls.clear()
            else {
                val artworkUrl = com.auralis.music.ui.components.getHighResArtworkUrl(track.thumbnail) ?: track.thumbnail
                val durationSec = (playback.lengthMs.value.takeIf { it > 0 } ?: (track.duration * 1000L).takeIf { it > 0 } ?: 0L) / 1000.0
                val elapsedSec = playback.positionMs.value / 1000.0
                controls.setMetadata(
                    title = track.title,
                    artist = track.artist,
                    artworkUrl = artworkUrl,
                    durationSeconds = durationSec,
                    elapsedSeconds = elapsedSec
                )
            }
        }
    }
    scope.launch {
        playback.queue.map { it.hasNext }.distinctUntilChanged().collect(controls::setCanGoNext)
    }
    scope.launch {
        playback.queue.map { it.index > 0 }.distinctUntilChanged().collect(controls::setCanGoPrevious)
    }
    scope.launch {
        playback.state.collect { state ->
            controls.setStatus(
                when (state) {
                    VlcAudioPlayer.State.PLAYING -> DesktopMediaControls.Status.PLAYING
                    VlcAudioPlayer.State.PAUSED -> DesktopMediaControls.Status.PAUSED
                    VlcAudioPlayer.State.BUFFERING -> DesktopMediaControls.Status.CHANGING
                    VlcAudioPlayer.State.IDLE, VlcAudioPlayer.State.ENDED, VlcAudioPlayer.State.ERROR ->
                        if (playback.queue.value.current != null) DesktopMediaControls.Status.STOPPED else DesktopMediaControls.Status.CLOSED
                }
            )
        }
    }
    scope.launch {
        playback.seekEvents.collect { ms ->
            val durationSec = playback.lengthMs.value / 1000.0
            controls.setPosition(ms / 1000.0, durationSec)
        }
    }
    return controls
}
