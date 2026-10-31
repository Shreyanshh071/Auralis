package com.auralis.music.ui.player

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// Full-cover blurred backdrop adapted from BitChord (GPL-3.0): https://github.com/kushagrasinghx/BitChord

/** Decodes a cover's blurred backdrop, or returns it from the cache. Safe to call ahead of a song change. */
internal suspend fun loadColorField(context: android.content.Context, url: String): ImageBitmap? {
    val cacheKey = "$url#full-blur"
    return try {
        fieldCache.get(cacheKey) ?: withContext(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            val candidates = listOfNotNull(com.auralis.music.ui.components.getHighResArtworkUrl(url), url).distinct()
            for (candidate in candidates) {
                try {
                    val request = ImageRequest.Builder(context).data(candidate).size(FIELD_SOURCE_PX).allowHardware(false).build()
                    val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
                    val decoded = (drawable as? BitmapDrawable)?.bitmap
                    if (decoded != null && !decoded.isRecycled) { bitmap = decoded; break }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* Try the original thumbnail if high-resolution artwork is unavailable. */ }
            }
            if (bitmap != null && !bitmap.isRecycled) blurredField(bitmap) else null
        }?.also { fieldCache.put(cacheKey, it) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { null }
}

/**
 * The whole cover, cropped across the player and blurred, so its colours run down the screen in
 * their own order. The blur is baked once off the main thread, so drawing is one image sample.
 */
@Composable
internal fun ImmersiveColorField(
    artworkUrl: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<ImageBitmap?>(artworkUrl?.let { fieldCache.get("$it#full-blur") }) }
    var incoming by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(artworkUrl) {
        val url = artworkUrl ?: return@LaunchedEffect
        val next = loadColorField(context, url) ?: return@LaunchedEffect

        incoming?.let { shown = it }
        incoming = null
        if (next === shown) return@LaunchedEffect
        if (shown == null) {
            shown = next
            return@LaunchedEffect
        }
        incoming = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(FIELD_FADE_MS, easing = FastOutSlowInEasing))
        shown = next
        incoming = null
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // Opaque floor outside the backdrop, so nothing behind the player can show through.
            .background(Color(0xFF121212))
    ) {
        shown?.let { drawField(it, 1f) }
        incoming?.let { drawField(it, fade.value) }
        // Everything on this is white: keep the colours while bright covers stay readable.
        drawRect(FieldScrim)
    }
}

private fun DrawScope.drawField(field: ImageBitmap, alpha: Float) {
    if (alpha <= 0.001f) return
    val scale = max(size.width / field.width, size.height / field.height)
    val srcWidth = (size.width / scale).roundToInt().coerceIn(1, field.width)
    val srcHeight = (size.height / scale).roundToInt().coerceIn(1, field.height)
    drawImage(
        image = field,
        srcOffset = IntOffset((field.width - srcWidth) / 2, (field.height - srcHeight) / 2),
        srcSize = IntSize(srcWidth, srcHeight),
        dstSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low
    )
}

private const val FIELD_SOURCE_PX = 128
private const val FIELD_BLUR_PASSES = 3
private const val FIELD_FADE_MS = 400

private val FieldScrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.34f),
    0.55f to Color.Black.copy(alpha = 0.48f),
    1f to Color.Black.copy(alpha = 0.64f)
)

private val fieldCache = android.util.LruCache<String, ImageBitmap>(32)

private fun blurredField(source: Bitmap): ImageBitmap? = try {
    val width = source.width
    val height = source.height
    if (width < 2 || height < 2 || source.isRecycled) null else {
        var from = IntArray(width * height)
        source.getPixels(from, 0, width, 0, 0, width, height)
        val to = IntArray(from.size)
        val radius = (minOf(width, height) / 12).coerceAtLeast(2)
        repeat(FIELD_BLUR_PASSES) {
            boxBlurPass(from, to, width, height, radius, horizontal = true)
            boxBlurPass(to, from, width, height, radius, horizontal = false)
        }
        Bitmap.createBitmap(from, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
} catch (_: Exception) { null }

private fun boxBlurPass(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val major = if (horizontal) width else height
    val minor = if (horizontal) height else width
    val window = radius * 2 + 1
    for (fixed in 0 until minor) {
        fun pixel(at: Int): Int {
            val position = at.coerceIn(0, major - 1)
            return if (horizontal) source[fixed * width + position] else source[position * width + fixed]
        }
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val color = pixel(offset)
            red += (color shr 16) and 0xFF
            green += (color shr 8) and 0xFF
            blue += color and 0xFF
        }
        for (moving in 0 until major) {
            val index = if (horizontal) fixed * width + moving else moving * width + fixed
            target[index] = (0xFF shl 24) or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
            val leaving = pixel(moving - radius)
            val entering = pixel(moving + radius + 1)
            red += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            green += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}
