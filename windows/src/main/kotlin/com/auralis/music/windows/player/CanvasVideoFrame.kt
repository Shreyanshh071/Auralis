package com.auralis.music.windows.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale

/** Cover tiles fade locally; the fullscreen stage fades as a group with its backdrop. */
@Composable
internal fun BoxScope.CanvasVideoFrame(frame: ImageBitmap?, canvasOnly: Boolean) {
    val opacity = remember { Animatable(0f) }
    val ready = frame != null
    if (!canvasOnly) LaunchedEffect(ready) {
        if (ready) opacity.animateTo(1f, tween(450)) else opacity.snapTo(0f)
    }
    frame?.let { bitmap ->
        val edgeBlend = if (canvasOnly) Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val dockShade = Brush.verticalGradient(
                    0f to Color.Transparent,
                    .45f to Color.Transparent,
                    .72f to Color.Black.copy(alpha = .55f),
                    1f to Color.Black.copy(alpha = .55f)
                )
                // Let the blurred video backdrop take over before the lyrics start, so sharp
                // detail doesn't compete with the first words of each line.
                val lyricsFeather = Brush.horizontalGradient(
                    .49f to Color.Black,
                    .62f to Color.Black.copy(alpha = .88f),
                    .72f to Color.Black.copy(alpha = .57f),
                    .82f to Color.Black.copy(alpha = .23f),
                    .92f to Color.Black.copy(alpha = .04f),
                    1f to Color.Transparent
                )
                val verticalFeather = Brush.verticalGradient(
                    0f to Color.Transparent,
                    .09f to Color.Black.copy(alpha = .3f),
                    .22f to Color.Black,
                    1f to Color.Black
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(Color.Black.copy(alpha = .2f))
                    drawRect(dockShade)
                    drawRect(lyricsFeather, blendMode = BlendMode.DstIn)
                    drawRect(verticalFeather, blendMode = BlendMode.DstIn)
                }
            } else Modifier
        Image(bitmap, null, Modifier.matchParentSize().graphicsLayer {
            // Let the underlying frame carry through the sharp stage.
            alpha = if (canvasOnly) .76f else opacity.value
        }.then(edgeBlend),
            contentScale = ContentScale.Crop,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
    }
}
