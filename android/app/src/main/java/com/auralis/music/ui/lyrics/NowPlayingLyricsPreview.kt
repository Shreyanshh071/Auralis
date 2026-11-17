package com.auralis.music.ui.lyrics

import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import com.auralis.music.domain.model.LyricsData

/**
 * What's playing right now, for screens that preview lyrics outside the player (the Lyrics
 * settings page). [position] stays a State so only the preview's leaves read the clock.
 */
class NowPlayingLyricsPreview(
    /** Read lazily: the app shell's player state leaves lyrics out, so this reads the full state. */
    private val lyricsSource: () -> LyricsData?,
    val title: String?,
    val artist: String?,
    val isPlaying: Boolean,
    val position: State<Long>,
    /** The player's own clock, so the preview advances every frame like the real lyrics do. */
    val clockSource: com.auralis.music.data.service.PlaybackClockSource? = null
) {
    val lyrics: LyricsData? get() = lyricsSource()
}

val LocalNowPlayingLyrics = staticCompositionLocalOf<NowPlayingLyricsPreview?> { null }
