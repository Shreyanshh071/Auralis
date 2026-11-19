package com.auralis.music.windows.player

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong

/** Each visible cover owns its background feed; no second video decoder or network request. */
internal class MotionAura(private val stageHeight: () -> Int? = { null }) {
    val texture = mutableStateOf<ImageBitmap?>(null)
    var lastTexture: ImageBitmap? = null
        private set
    private val frames = Channel<Pair<Long, ImageBitmap>>(Channel.CONFLATED)
    private val generation = AtomicLong()

    fun offer(frame: ImageBitmap?) {
        if (frame == null) {
            generation.incrementAndGet()
            texture.value = null
        } else frames.trySend(generation.get() to frame)
    }

    suspend fun collectFrames() {
        for ((version, frame) in frames) {
            if (generation.get() != version) continue
            val height = stageHeight()
            val previous = texture.value
            val blurred = withContext(Dispatchers.Default) {
                if (height == null) motionAuraTexture(frame) else stageBackdropTexture(frame, height, previous)
            }
            if (generation.get() == version) {
                if (height != null) lastTexture = blurred
                texture.value = blurred
            }
            // The stage blends consecutive frames. Sidebar artwork keeps its slower crossfade.
            delay(if (height == null) MOTION_FADE_MS + 100L else 33L)
        }
    }

    fun close() { offer(null); frames.close() }
}

@Composable
internal fun rememberMotionAura(trackId: String?, stageHeight: Int? = null): MotionAura {
    val height = rememberUpdatedState(stageHeight)
    val feed = remember(trackId, stageHeight != null) { MotionAura { height.value } }
    LaunchedEffect(feed) { feed.collectFrames() }
    DisposableEffect(feed) { onDispose { feed.close() } }
    return feed
}

internal const val STAGE_BACKDROP_WIDTH = 80

/** Preserve the viewport crop of the original frame and soften it at thumbnail resolution. */
internal fun stageBackdropTexture(frame: ImageBitmap, height: Int, previous: ImageBitmap?): ImageBitmap {
    val width = STAGE_BACKDROP_WIDTH
    val bounds = Rect.makeWH(width.toFloat(), height.toFloat())
    val scale = maxOf(width.toFloat() / frame.width, height.toFloat() / frame.height) * 1.3f
    val imageWidth = frame.width * scale
    val imageHeight = frame.height * scale
    val target = Rect.makeXYWH((width - imageWidth) / 2, (height - imageHeight) / 2, imageWidth, imageHeight)
    return Surface.makeRasterN32Premul(width, height).use { surface ->
        // Sample the large decoded frame once, then blur only the small viewport.
        val tiny = ImageBitmap(width, height)
        Image.makeFromBitmap(frame.asSkiaBitmap()).use { image ->
            Canvas(tiny).nativeCanvas.drawImageRect(image, Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()),
                target, SamplingMode.LINEAR, null, true)
        }
        val blend = previous?.takeIf { it.width == width && it.height == height }
        if (blend != null) Image.makeFromBitmap(blend.asSkiaBitmap()).use { surface.canvas.drawImage(it, 0f, 0f) }
        Image.makeFromBitmap(tiny.asSkiaBitmap()).use { input ->
            ImageFilter.makeBlur(5f, 5f, FilterTileMode.CLAMP).use { blur ->
                Paint().use { paint ->
                    paint.imageFilter = blur
                    if (blend != null) paint.alpha = 77
                    surface.canvas.drawImageRect(input, bounds, paint)
                }
            }
        }
        surface.makeImageSnapshot().toComposeImageBitmap()
    }
}

/** Downsample before reading pixels, then use exactly the still-cover Kawarp preblur. */
internal fun motionAuraTexture(frame: ImageBitmap): ImageBitmap {
    val side = 128
    val tiny = ImageBitmap(side, side)
    val crop = minOf(frame.width, frame.height)
    Canvas(tiny).drawImageRect(frame,
        srcOffset = androidx.compose.ui.unit.IntOffset((frame.width - crop) / 2, (frame.height - crop) / 2),
        srcSize = IntSize(crop, crop), dstSize = IntSize(side, side),
        paint = androidx.compose.ui.graphics.Paint().apply { filterQuality = androidx.compose.ui.graphics.FilterQuality.Low })
    val pixels = IntArray(side * side)
    tiny.readPixels(pixels)
    val source = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
    source.setRGB(0, 0, side, side, pixels, 0, side)
    return auraTexture(source)
}
