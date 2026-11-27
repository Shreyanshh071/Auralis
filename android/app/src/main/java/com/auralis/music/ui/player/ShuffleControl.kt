package com.auralis.music.ui.player

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.State
import com.auralis.music.domain.model.Track

/**
 * The one device-wide shuffle switch, like Spotify's: every Shuffle button in the app shows and
 * flips this same state, and it stays on for every list played afterwards.
 */
class ShuffleControl(
    private val state: State<Boolean>,
    val toggle: () -> Unit,
    val turnOn: () -> Unit
) {
    val isOn: Boolean get() = state.value

    /** Where a list's Play button starts: a random song while shuffle is on, else the first. */
    fun startIndex(tracks: List<Track>): Int = if (isOn && tracks.isNotEmpty()) tracks.indices.random() else 0

    /** "Shuffle play": switch shuffle on, then start [tracks] on a random song. */
    fun shufflePlay(tracks: List<Track>, play: (Track, List<Track>) -> Unit) {
        if (tracks.isEmpty()) return
        if (!isOn) turnOn()
        play(tracks.random(), tracks)
    }

    /** Play button: from the top, or from a random song while shuffle is on. */
    fun playAll(tracks: List<Track>, play: (Track, List<Track>) -> Unit) {
        if (tracks.isEmpty()) return
        play(tracks[startIndex(tracks)], tracks)
    }
}

val LocalShuffleControl = staticCompositionLocalOf<ShuffleControl?> { null }
