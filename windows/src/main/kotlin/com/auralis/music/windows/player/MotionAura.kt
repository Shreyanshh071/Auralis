package com.auralis.music.windows.player

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong

/** Each visible cover owns its background feed; no second video decoder or network request. */
internal class MotionAura {
    val texture = mutableStateOf<ImageBitmap?>(null)
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
            val blurred = withContext(Dispatchers.Default) { motionAuraTexture(frame) }
            if (generation.get() == version) texture.value = blurred
            // A blurred background needs only a few colour updates a second; waiting longer than the
            // fade (MOTION_FADE_MS) lets each one finish, so no fade is cut short mid-way.
            delay(MOTION_FADE_MS + 100L)
        }
    }

    fun close() { offer(null); frames.close() }
}

@Composable
internal fun rememberMotionAura(trackId: String?): MotionAura {
    val feed = remember(trackId) { MotionAura() }
    LaunchedEffect(feed) { feed.collectFrames() }
    DisposableEffect(feed) { onDispose { feed.close() } }
    return feed
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
