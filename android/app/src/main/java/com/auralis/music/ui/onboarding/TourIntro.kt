package com.auralis.music.ui.onboarding

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.PathParser
import com.auralis.music.R
import com.auralis.music.ui.theme.LocalReducedMotion
import org.xmlpull.v1.XmlPullParser

@Stable
internal class TourIntroAnimation(val reducedMotion: Boolean) {
    var elapsedMs by mutableFloatStateOf(0f)
    var swipeRotation by mutableFloatStateOf(0f)
    val rotation: Float get() = if (reducedMotion) 0f else TourIntroMotion.rotation(elapsedMs) + swipeRotation
    private val linesState = derivedStateOf { if (reducedMotion) 1f else TourIntroMotion.linesProgress(elapsedMs) }
    val lines: Float get() = linesState.value
    private val alphaState = derivedStateOf { if (reducedMotion) 1f else TourIntroMotion.contentAlpha(elapsedMs) }
    val alpha: Float get() = alphaState.value
    private val logoState = derivedStateOf { if (reducedMotion) 1f else FastOutSlowInEasing.transform(TourIntroMotion.logoProgress(elapsedMs)) }
    val logo: Float get() = logoState.value
}

@Composable
internal fun rememberTourIntroAnimation(isSwiping: () -> Boolean = { false }): TourIntroAnimation {
    val reduced = LocalReducedMotion.current
    val animation = remember(reduced) { TourIntroAnimation(reduced) }
    val swiping = rememberUpdatedState(isSwiping)
    LaunchedEffect(animation) {
        if (!reduced) {
            val start = withFrameNanos { it }
            var previous = start
            var swipeSpeed = 0f
            while (true) withFrameNanos { now ->
                val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f)
                val targetSpeed = if (swiping.value()) 150f else 0f
                val response = if (targetSpeed > swipeSpeed) .09f else .4f
                swipeSpeed += (targetSpeed - swipeSpeed) * (1f - kotlin.math.exp(-seconds / response))
                animation.swipeRotation += swipeSpeed * seconds
                animation.elapsedMs = (now - start) / 1_000_000f
                previous = now
            }
        }
    }
    return animation
}

/** Reads the actual startup drawable's paths, so the tour and launch always draw the same mark. */
@Composable
internal fun TourIntroLogo(animation: TourIntroAnimation, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val paths = remember(context) {
        buildList {
            context.resources.getXml(R.drawable.launch_mark).use { xml ->
                while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG && xml.name == "path") {
                        val data = xml.getAttributeValue("http://schemas.android.com/apk/res/android", "pathData")
                        val stroke = xml.getAttributeValue("http://schemas.android.com/apk/res/android", "name") != null
                        add(PathParser.createPathFromPathData(data)!! to stroke)
                    }
                    xml.next()
                }
            }
        }
    }
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color(0xFFF0F2F7).toArgb()
        strokeWidth = 8.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    } }
    Canvas(modifier) {
        val progress = animation.logo
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            val scale = size.minDimension / 288f
            native.translate((size.width - size.minDimension) / 2f, (size.height - size.minDimension) / 2f)
            native.scale(scale, scale)
            native.translate(44f, 44f)
            native.scale(1.5625f, 1.5625f)
            paths.forEach { (path, stroked) ->
                paint.style = if (stroked) Paint.Style.STROKE else Paint.Style.FILL
                if (!stroked || progress >= 1f) native.drawPath(path, paint)
                else if (progress > 0f) {
                    measure.setPath(path, false)
                    segment.reset()
                    measure.getSegment(0f, measure.length * progress, segment, true)
                    native.drawPath(segment, paint)
                }
            }
            native.restore()
        }
    }
}
