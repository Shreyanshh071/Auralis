package com.auralis.music.ui.onboarding

import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** A uniform miniature of this window, never an independently compressed player layout. */
internal data class PlayerPreviewSize(val width: Int, val height: Int, val scale: Float)

internal fun fitPlayerPreview(viewport: IntSize, available: IntSize): PlayerPreviewSize {
    require(viewport.width > 0 && viewport.height > 0)
    require(available.width >= 0 && available.height >= 0)
    val scale = minOf(
        available.width.toFloat() / viewport.width,
        available.height.toFloat() / viewport.height
    )
    return PlayerPreviewSize(
        (viewport.width * scale).roundToInt().coerceIn(0, available.width),
        (viewport.height * scale).roundToInt().coerceIn(0, available.height),
        scale
    )
}
