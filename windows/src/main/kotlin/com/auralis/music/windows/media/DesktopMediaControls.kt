package com.auralis.music.windows.media

/**
 * Common interface for desktop platform-level media controls and "Now Playing" integration.
 *
 * Implemented by [WindowsMediaControls] on Windows (via WinRT System Media Transport Controls)
 * and [MacMediaControls] on macOS (via MediaPlayer MPNowPlayingInfoCenter and MPRemoteCommandCenter).
 */
interface DesktopMediaControls : AutoCloseable {
    enum class Button { PLAY, PAUSE, STOP, NEXT, PREVIOUS }
    enum class Status(val value: Int) { CLOSED(0), CHANGING(1), STOPPED(2), PLAYING(3), PAUSED(4) }

    fun setStatus(status: Status)
    fun setCanGoNext(enabled: Boolean)
    fun setCanGoPrevious(enabled: Boolean) = Unit
    fun setPosition(elapsedSeconds: Double, durationSeconds: Double) = Unit
    fun setMetadata(
        title: String,
        artist: String,
        artworkUrl: String?,
        durationSeconds: Double = 0.0,
        elapsedSeconds: Double = 0.0
    )
    fun clear()
    override fun close() = Unit
}
