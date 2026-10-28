package com.auralis.music.windows.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import kotlin.math.*

/** A quiet expand mark and pointer-led water rings; the artwork remains the focus. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.coverExpandCue(waves: Boolean = true): Modifier {
    var hovered by remember { mutableStateOf(false) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var activity by remember { mutableLongStateOf(0L) }
    var idle by remember { mutableStateOf(false) }
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(activity, hovered) { idle = false; if (hovered) { kotlinx.coroutines.delay(2200); idle = true } }
    val reveal by animateFloatAsState(if (hovered && !idle) 1f else 0f, tween(240), label = "coverExpand")
    val rippleCenter by animateOffsetAsState(pointer, spring(dampingRatio = 1f, stiffness = 650f), label = "coverWaterPointer")
    LaunchedEffect(hovered, idle) {
        if (hovered && !idle) {
            var previous = 0L
            while (true) withFrameNanos { now ->
                if (previous != 0L) phase = (phase + (now - previous) / 1_000_000_000f) % 10f
                previous = now
            }
        }
    }
    return this.onPointerEvent(PointerEventType.Enter) { hovered = true; activity++ }
        .onPointerEvent(PointerEventType.Exit) { hovered = false }
        .onPointerEvent(PointerEventType.Move) { pointer = it.changes.first().position; activity++ }
        .drawWithContent {
            drawContent()
            if (reveal <= .001f) return@drawWithContent
            if (waves) for (i in 0..2) {
                val progress = ((phase * .55f + i / 3f) % 1f)
                val radius = size.minDimension * (.08f + progress * .65f)
                drawCircle(Color.White.copy(alpha = reveal * .11f * (1 - progress)), radius, rippleCenter,
                    style = Stroke((1.2f + progress) * density))
            }
            val length = size.minDimension * .16f
            val margin = size.minDimension * .13f
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(Color.Black.copy(alpha = .35f * reveal), size.minDimension * .25f, center)
            val white = Color.White.copy(alpha = .95f * reveal)
            val stroke = (size.minDimension * .035f).coerceIn(1.5.dp.toPx(), 3.dp.toPx())
            val a = center - Offset(margin, margin)
            val b = center + Offset(margin, margin)
            drawLine(white, a, a + Offset(length, 0f), stroke, StrokeCap.Round)
            drawLine(white, a, a + Offset(0f, length), stroke, StrokeCap.Round)
            drawLine(white, b, b - Offset(length, 0f), stroke, StrokeCap.Round)
            drawLine(white, b, b - Offset(0f, length), stroke, StrokeCap.Round)
        }
}
