package com.auralis.music.windows.media

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the Windows media overlay in step with playback, and sends its buttons back. */
fun bindMediaControls(windowHandle: Long, playback: DesktopPlaybackController, scope: CoroutineScope): WindowsMediaControls? {
    val controls = WindowsMediaControls.create(windowHandle) { button ->
        scope.launch {
            when (button) {
                WindowsMediaControls.Button.PLAY,
                WindowsMediaControls.Button.PAUSE -> playback.togglePlayPause()
                WindowsMediaControls.Button.NEXT -> playback.next()
                WindowsMediaControls.Button.PREVIOUS -> playback.previous()
                WindowsMediaControls.Button.STOP -> if (playback.state.value == VlcAudioPlayer.State.PLAYING) playback.togglePlayPause()
            }
        }
    } ?: return null

    scope.launch {
        playback.queue.map { it.current }.distinctUntilChanged().collect { track ->
            if (track == null) controls.clear()
            else controls.setMetadata(track.title, track.artist, com.auralis.music.ui.components.getHighResArtworkUrl(track.thumbnail) ?: track.thumbnail)
        }
    }
    scope.launch {
        playback.queue.map { it.hasNext }.distinctUntilChanged().collect(controls::setCanGoNext)
    }
    scope.launch {
        playback.state.collect { state ->
            controls.setStatus(
                when (state) {
                    VlcAudioPlayer.State.PLAYING -> WindowsMediaControls.Status.PLAYING
                    VlcAudioPlayer.State.PAUSED -> WindowsMediaControls.Status.PAUSED
                    VlcAudioPlayer.State.BUFFERING -> WindowsMediaControls.Status.CHANGING
                    VlcAudioPlayer.State.IDLE, VlcAudioPlayer.State.ENDED, VlcAudioPlayer.State.ERROR ->
                        if (playback.queue.value.current != null) WindowsMediaControls.Status.STOPPED else WindowsMediaControls.Status.CLOSED
                }
            )
        }
    }
    return controls
}
