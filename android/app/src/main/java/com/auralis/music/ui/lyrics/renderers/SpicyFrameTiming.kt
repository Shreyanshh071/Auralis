package com.auralis.music.ui.lyrics.renderers

data class SpicyFrameStep(val seconds: Float, val reset: Boolean)

/** A slow UI frame is not a seek. Compare playback movement with the full frame
 * interval before bounding spring integration; normal 0–2x playback stays continuous.
 */
object SpicyFrameTiming {
    fun sample(previousFrame: Long, frame: Long, previousPosition: Long, position: Long): SpicyFrameStep {
        if (previousFrame < 0L) return SpicyFrameStep(0f, true)
        val elapsedMs = ((frame - previousFrame).coerceAtLeast(0L) / 1_000_000.0)
        val moved = position - previousPosition
        val seek = moved < -250L || moved > elapsedMs * 2.0 + 250.0
        return SpicyFrameStep((elapsedMs / 1000.0).toFloat().coerceAtMost(.5f), seek)
    }
}
