package com.auralis.music.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom Audio Output / Spatial Device icon matching the video reference.
 * Depicts a listener silhouette with radiating soundwaves.
 */
val AudioOutputIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AudioOutput",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Head / listener ring
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9.5f, 6.2f)
            curveTo(10.8f, 6.2f, 11.8f, 7.2f, 11.8f, 8.5f)
            curveTo(11.8f, 9.8f, 10.8f, 10.8f, 9.5f, 10.8f)
            curveTo(8.2f, 10.8f, 7.2f, 9.8f, 7.2f, 8.5f)
            curveTo(7.2f, 7.2f, 8.2f, 6.2f, 9.5f, 6.2f)
            close()
        }

        // Shoulders / body outline
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4.5f, 18f)
            lineTo(4.5f, 15.5f)
            curveTo(4.5f, 14.5f, 5.5f, 13.5f, 7f, 13.5f)
            lineTo(12f, 13.5f)
            curveTo(13.5f, 13.5f, 14.5f, 14.5f, 14.5f, 15.5f)
            lineTo(14.5f, 18f)
            close()
        }

        // Inner soundwave arc
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(14.5f, 5.5f)
            curveTo(16.2f, 7.0f, 16.8f, 8.5f, 16.8f, 10.2f)
        }

        // Outer soundwave arc
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(17.8f, 3.5f)
            curveTo(20.4f, 6.0f, 21.0f, 8.5f, 21.0f, 11.5f)
        }
    }.build()
}
