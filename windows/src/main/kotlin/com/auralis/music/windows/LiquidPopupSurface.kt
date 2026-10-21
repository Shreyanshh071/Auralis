package com.auralis.music.windows

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.*
import androidx.compose.foundation.shape.RoundedCornerShape
import java.util.concurrent.atomic.AtomicLong

/** App-only display list, recorded while a popup is open. Never includes the popup itself. */
internal class PopupGlassSource(val layer: GraphicsLayer) {
    val users = mutableIntStateOf(0)
    val frame = mutableLongStateOf(0L)
    val ready = mutableStateOf(false)
    val counter = AtomicLong()
    var origin = Offset.Zero
}
internal val LocalPopupGlassSource = staticCompositionLocalOf<PopupGlassSource?> { null }

@Composable internal fun rememberPopupGlassSource(): PopupGlassSource {
    val layer = rememberGraphicsLayer()
    return remember(layer) { PopupGlassSource(layer) }
}

internal fun Modifier.recordPopupGlass(source: PopupGlassSource): Modifier = this
    .onGloballyPositioned { source.origin = it.positionInWindow() }
    .drawWithContent {
        if (source.users.intValue > 0) {
            // Copy density: a mutable DrawScope must not be its own recorded layer's density.
            val copiedDensity = Density(density, fontScale)
            source.layer.record(size.toIntSize()) {
                val previousDensity = drawContext.density
                drawContext.density = copiedDensity
                try { this@drawWithContent.drawContent() }
                finally { drawContext.density = previousDensity }
            }
            drawLayer(source.layer)
            source.ready.value = true
            source.frame.longValue = source.counter.incrementAndGet()
        } else drawContent()
    }

/** Shared by options menus and search; popup coordinates are relative to the owner window. */
@Composable
internal fun LiquidPopupSurface(modifier: Modifier = Modifier,
    tint: Color = Color(0xFF101010).copy(alpha = .38f), frosted: Boolean = false, content: @Composable () -> Unit) {
    val source = LocalPopupGlassSource.current
    val shape = RoundedCornerShape(18.dp)
    val layer = rememberGraphicsLayer()
    val effect = remember { DockGlassEffect() }
    var localOrigin by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(source) {
        source?.users?.let { it.intValue++ }
        onDispose { source?.users?.let { it.intValue-- } }
    }
    DisposableEffect(effect) { onDispose { effect.close() } }
    Box(modifier.clip(shape)
        .onGloballyPositioned { localOrigin = it.positionInWindow() }
        .drawWithCache {
            val padding = 54.dp.toPx()
            val copiedDensity = Density(density, fontScale)
            layer.renderEffect = if (frosted) androidx.compose.ui.graphics.BlurEffect(24.dp.toPx(), 24.dp.toPx())
                else effect.create(size.width, size.height, padding, density, 18.dp.toPx())
            onDrawWithContent {
                // Observing the capture frame repaints the popup scene when the page changes.
                source?.frame?.longValue
                val captured = source?.ready?.value == true
                if (captured) {
                    val x = localOrigin.x - source!!.origin.x
                    val y = localOrigin.y - source.origin.y
                    layer.record(copiedDensity, layoutDirection,
                        IntSize((size.width + padding * 2).toInt(), (size.height + padding * 2).toInt())) {
                        drawContext.canvas.translate(padding - x, padding - y)
                        drawLayer(source.layer)
                    }
                    drawContext.canvas.translate(-padding, -padding)
                    drawLayer(layer)
                    drawContext.canvas.translate(padding, padding)
                }
                drawRect(tint.copy(alpha = if (captured) tint.alpha else .68f))
                if (!frosted) drawRect(Brush.verticalGradient(
                    0f to Color.White.copy(alpha = .18f),
                    0.35f to Color.White.copy(alpha = .04f),
                    1f to Color.Transparent
                ))
                if (!frosted) drawRect(Brush.linearGradient(
                    0f to Color.White.copy(alpha = .10f),
                    0.45f to Color.Transparent,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                ))
                drawContent()
            }
        }
        .border(1.dp, if (frosted) androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = .14f)) else Brush.verticalGradient(listOf(
            Color.White.copy(alpha = .38f),
            Color.White.copy(alpha = .08f),
            Color.White.copy(alpha = .18f)
        )), shape)) {
        content()
    }
}
