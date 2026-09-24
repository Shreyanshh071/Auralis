package com.auralis.music.ui.lyrics.wavy

import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.util.fastForEach
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Gets a [Path] representation for a [RoundedPolygon] shape.
 *
 * Sourced directly from AndroidX Material 3 internal ShapeUtil.
 */
internal fun RoundedPolygon.toPath(
    path: Path = Path(),
    startAngle: Int = 270,
    repeatPath: Boolean = false,
    closePath: Boolean = true,
): Path {
    pathFromCubics(
        path = path,
        startAngle = startAngle,
        repeatPath = repeatPath,
        closePath = closePath,
        cubics = cubics,
        rotationPivotX = centerX,
        rotationPivotY = centerY,
    )
    return path
}

/**
 * Returns a [Path] for a [Morph].
 *
 * Sourced directly from AndroidX Material 3 internal ShapeUtil.
 */
internal fun Morph.toPath(
    progress: Float,
    path: Path = Path(),
    startAngle: Int = 270, // 12 O'clock
    repeatPath: Boolean = false,
    closePath: Boolean = true,
    rotationPivotX: Float = 0f,
    rotationPivotY: Float = 0f,
): Path {
    pathFromCubics(
        path = path,
        startAngle = startAngle,
        repeatPath = repeatPath,
        closePath = closePath,
        cubics = asCubics(progress),
        rotationPivotX = rotationPivotX,
        rotationPivotY = rotationPivotY,
    )
    return path
}

private fun pathFromCubics(
    path: Path,
    startAngle: Int,
    repeatPath: Boolean,
    closePath: Boolean,
    cubics: List<Cubic>,
    rotationPivotX: Float,
    rotationPivotY: Float,
) {
    var first = true
    var firstCubic: Cubic? = null
    path.rewind()
    cubics.fastForEach {
        if (first) {
            path.moveTo(it.anchor0X, it.anchor0Y)
            if (startAngle != 0) {
                firstCubic = it
            }
            first = false
        }
        path.cubicTo(
            it.control0X,
            it.control0Y,
            it.control1X,
            it.control1Y,
            it.anchor1X,
            it.anchor1Y,
        )
    }
    if (repeatPath) {
        var firstInRepeat = true
        cubics.fastForEach {
            if (firstInRepeat) {
                path.lineTo(it.anchor0X, it.anchor0Y)
                firstInRepeat = false
            }
            path.cubicTo(
                it.control0X,
                it.control0Y,
                it.control1X,
                it.control1Y,
                it.anchor1X,
                it.anchor1Y,
            )
        }
    }

    if (closePath) path.close()

    if (startAngle != 0 && firstCubic != null) {
        val angleToFirstCubic =
            radiansToDegrees(
                atan2(
                    y = cubics[0].anchor0Y - rotationPivotY,
                    x = cubics[0].anchor0X - rotationPivotX,
                )
            )
        // Rotate the Path to start from the given angle.
        path.transform(Matrix().apply { rotateZ(-angleToFirstCubic + startAngle) })
    }
}

private fun radiansToDegrees(radians: Float): Float {
    return (radians * 180.0 / PI).toFloat()
}
