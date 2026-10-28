package com.auralis.music.ui.onboarding

import kotlin.math.exp

/** One continuous timeline: fast orbit while drawing, then a smooth settle and line reveal. */
internal object TourIntroMotion {
    const val DRAW_MS = 850f
    private const val FAST_DEGREES_PER_SECOND = 720f
    private const val SLOW_DEGREES_PER_SECOND = 3f
    private const val SETTLE_SECONDS = .55f

    fun logoProgress(elapsedMs: Float): Float = (elapsedMs / DRAW_MS).coerceIn(0f, 1f)

    fun rotation(elapsedMs: Float): Float {
        val drawingSeconds = elapsedMs.coerceIn(0f, DRAW_MS) / 1000f
        val settlingSeconds = ((elapsedMs - DRAW_MS) / 1000f).coerceAtLeast(0f)
        return FAST_DEGREES_PER_SECOND * drawingSeconds +
            SLOW_DEGREES_PER_SECOND * settlingSeconds +
            (FAST_DEGREES_PER_SECOND - SLOW_DEGREES_PER_SECOND) * SETTLE_SECONDS *
            (1f - exp(-settlingSeconds / SETTLE_SECONDS))
    }

    fun linesProgress(elapsedMs: Float): Float = ((elapsedMs - DRAW_MS - 160f) / 2400f).coerceIn(0f, 1f)
    fun contentAlpha(elapsedMs: Float): Float = (elapsedMs / 420f).coerceIn(0f, 1f)
}
