package com.auralis.music.ui.player

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/** Presentation timing only; reorder thresholds and track commitment stay with their owners. */
internal object PlayerTransitionMotion {
    // Starts moving on the first frame, with a short settle and no spring overshoot.
    val queuePlacement = tween<IntOffset>(160, easing = LinearOutSlowInEasing)

    const val paletteDurationMillis = 240

    fun paletteSpec(progress: Float, durationMillis: Int = paletteDurationMillis) = tween<Float>(
        durationMillis = if (progress < 0.85f) {
            (durationMillis * 0.65f).toInt().coerceAtLeast(120)
        } else {
            durationMillis
        },
        easing = LinearOutSlowInEasing
    )
}
