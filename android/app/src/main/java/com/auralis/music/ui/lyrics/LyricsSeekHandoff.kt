package com.auralis.music.ui.lyrics

/** Keep the visual seek target stable while old audio-clock reports are draining. */
internal fun shouldHoldLyricsSeek(
    targetMs: Long,
    audioPositionMs: Long,
    elapsedMs: Long,
    advancing: Boolean,
    playing: Boolean,
    buffering: Boolean
): Boolean {
    val reached = kotlin.math.abs(audioPositionMs - targetMs) <= 350L
    if (elapsedMs >= 80L && reached && !buffering && (advancing || !playing)) return false
    val timedOut = elapsedMs > 1500L && (advancing || !playing || elapsedMs > 6000L)
    return !timedOut
}
