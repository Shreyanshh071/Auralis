package com.auralis.music.windows.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale

/** Owns the fade independently of lookup, validation and native-player effect branches. */
@Composable
internal fun BoxScope.CanvasVideoFrame(frame: ImageBitmap?, canvasOnly: Boolean) {
    val opacity = remember { Animatable(0f) }
    val ready = frame != null
    LaunchedEffect(ready) {
        if (ready) opacity.animateTo(1f, tween(450)) else opacity.snapTo(0f)
    }
    frame?.let { bitmap ->
        val edgeBlend = if (canvasOnly) Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    // A long, eased fade straight into the background colours (taken from this
                    // video), with no dark band and no hard seam.
                    Brush.horizontalGradient(
                        .62f to Color.Black,
                        .72f to Color.Black.copy(alpha = .88f),
                        .80f to Color.Black.copy(alpha = .62f),
                        .88f to Color.Black.copy(alpha = .32f),
                        .95f to Color.Black.copy(alpha = .10f),
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            } else Modifier
        Image(bitmap, null, Modifier.matchParentSize().graphicsLayer { alpha = opacity.value }.then(edgeBlend),
            contentScale = ContentScale.Crop,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
    }
}
