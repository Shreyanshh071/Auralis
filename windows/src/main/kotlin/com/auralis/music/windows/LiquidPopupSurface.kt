package com.auralis.music.windows

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
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

// BitChord's fixed liquid-glass recipe (LiquidGlass.kt, commit 04a4a83889bb8fa5bd6ee6f6097f0c6608671d68).
// https://github.com/kushagrasinghx/BitChord (GPL-3.0); lens shader is Kyant's Apache-2.0 implementation.
internal fun bitChordGlassTint(light: Boolean): Color =
    (if (light) Color(0xFFFAFAFA) else Color(0xFF121212)).copy(alpha = .4f)
private const val GLASS_SCALE = .33f

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
    tint: Color = bitChordGlassTint(false), frosted: Boolean = false, content: @Composable () -> Unit) {
    val source = LocalPopupGlassSource.current
    val shape = RoundedCornerShape(18.dp)
    val layer = rememberGraphicsLayer()
    val effect = remember { DockGlassEffect() }
    val rim = remember { BitChordGlassRim() }
    var localOrigin by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(source) {
        source?.users?.let { it.intValue++ }
        onDispose { source?.users?.let { it.intValue-- } }
    }
    DisposableEffect(effect) { onDispose { effect.close() } }
    DisposableEffect(rim) { onDispose { rim.close() } }
    Box(modifier.shadow(if (frosted) 0.dp else 6.dp, shape,
        ambientColor = Color.Black.copy(alpha = .1f), spotColor = Color.Black.copy(alpha = .1f)).clip(shape)
        .onGloballyPositioned { localOrigin = it.positionInWindow() }
        .drawWithCache {
            val scale = if (frosted) 1f else GLASS_SCALE
            val padding = 54.dp.toPx() * scale
            val copiedDensity = Density(density, fontScale)
            layer.renderEffect = if (frosted) androidx.compose.ui.graphics.BlurEffect(24.dp.toPx(), 24.dp.toPx())
                else effect.create(size.width * scale, size.height * scale, padding, density * scale,
                    18.dp.toPx() * scale, blurDp = 8f, lensHeightDp = 24f, lensAmountDp = 24f)
            onDrawWithContent {
                // Observing the capture frame repaints the popup scene when the page changes.
                source?.frame?.longValue
                val captured = source?.ready?.value == true
                if (captured) {
                    val x = localOrigin.x - source!!.origin.x
                    val y = localOrigin.y - source.origin.y
                    layer.record(copiedDensity, layoutDirection,
                        IntSize((size.width * scale + padding * 2).toInt(), (size.height * scale + padding * 2).toInt())) {
                        drawContext.canvas.translate(padding, padding)
                        drawContext.canvas.scale(scale, scale)
                        drawContext.canvas.translate(-x, -y)
                        drawLayer(source.layer)
                    }
                    drawContext.canvas.save()
                    drawContext.canvas.scale(1f / scale, 1f / scale)
                    drawContext.canvas.translate(-padding, -padding)
                    drawLayer(layer)
                    drawContext.canvas.restore()
                }
                drawRect(tint.copy(alpha = if (captured) tint.alpha else .68f))
                drawContent()
                if (!frosted) rim.draw(drawContext.canvas.nativeCanvas, size.width, size.height, 18.dp.toPx(), density)
            }
        }
        .then(if (frosted) Modifier.border(1.dp, Color.White.copy(alpha = .14f), shape) else Modifier)) {
        content()
    }
}
