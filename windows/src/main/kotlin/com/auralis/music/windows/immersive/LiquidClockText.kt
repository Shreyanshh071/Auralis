package com.auralis.music.windows.immersive

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.auralis.music.windows.DockGlassEffect
import com.auralis.music.windows.LocalPopupGlassSource

/** Refracted artwork masked to the glyphs, with a translucent face and a fine lit edge. */
@Composable
internal fun LiquidClockText(text: String, style: TextStyle) {
    val source = LocalPopupGlassSource.current
    val backdrop = rememberGraphicsLayer()
    val effect = remember { DockGlassEffect() }
    val measurer = rememberTextMeasurer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(source) {
        source?.users?.let { it.intValue++ }
        onDispose { source?.users?.let { it.intValue-- } }
    }
    DisposableEffect(effect) { onDispose { effect.close() } }
    Text(
        text = text,
        style = style.copy(color = Color.White),
        maxLines = 1,
        modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .onGloballyPositioned { origin = it.positionInWindow() }
            .drawWithCache {
                val layout = measurer.measure(text, style.copy(color = Color.White), maxLines = 1)
                val copiedDensity = Density(density, fontScale)
                val padding = 32.dp.toPx()
                backdrop.renderEffect = effect.create(
                    size.width, size.height, padding, density,
                    cornerRadius = size.height / 2f,
                    blurDp = 1.5f, lensHeightDp = 12f, lensAmountDp = 10f
                )
                val maskPaint = Paint().apply { blendMode = BlendMode.DstIn }
                val smallText = style.fontSize.value < 32f
                val face = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = .74f),
                    .35f to Color.White.copy(alpha = if (smallText) .60f else .30f),
                    .65f to Color.White.copy(alpha = if (smallText) .42f else .10f),
                    1f to Color.White.copy(alpha = .52f),
                    endY = size.height
                )
                val edge = Brush.linearGradient(
                    listOf(Color.White.copy(alpha = .95f), Color.White.copy(alpha = .12f), Color.White.copy(alpha = .65f)),
                    end = Offset(size.width, size.height)
                )
                onDrawWithContent {
                    source?.frame?.longValue
                    if (source?.ready?.value == true) {
                        val offset = origin - source.origin
                        backdrop.record(copiedDensity, layoutDirection,
                            IntSize((size.width + padding * 2).toInt(), (size.height + padding * 2).toInt())) {
                            drawContext.canvas.translate(padding - offset.x, padding - offset.y)
                            drawLayer(source.layer)
                        }
                        drawContext.canvas.save()
                        drawContext.canvas.translate(-padding, -padding)
                        drawLayer(backdrop)
                        drawContext.canvas.restore()
                        // Mask only this offscreen text layer, leaving the background untouched.
                        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), maskPaint)
                        drawContent()
                        drawContext.canvas.restore()
                    }
                    drawText(layout, brush = face)
                    drawText(layout, brush = edge, drawStyle = Stroke(width = (if (smallText) .3.dp else .65.dp).toPx()))
                }
            }
    )
}
