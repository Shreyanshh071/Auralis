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
import kotlin.math.roundToInt

/** Decodes a cover's colour field, or returns it from the cache. Safe to call ahead of a song change. */
internal suspend fun loadColorField(context: android.content.Context, url: String, cropArtwork: Boolean): ImageBitmap? {
    val cacheKey = "$url#edge-v2:$cropArtwork"
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
            if (bitmap != null && !bitmap.isRecycled) colorFieldOf(bitmap, cropArtwork) else null
        }?.also { fieldCache.put(cacheKey, it) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { null }
}

/** The visible cover's bottom-edge colours carried softly and continuously below it. */
@Composable
internal fun ImmersiveColorField(
    artworkUrl: String?,
    seamPx: () -> Float,
    modifier: Modifier = Modifier,
    cropArtwork: Boolean = true
) {
    val context = LocalContext.current
    val cacheKey = artworkUrl?.let { "$it#edge-v2:$cropArtwork" }
    var shown by remember { mutableStateOf<ImageBitmap?>(cacheKey?.let { fieldCache.get(it) }) }
    var incoming by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(artworkUrl, cropArtwork) {
        val url = artworkUrl ?: return@LaunchedEffect
        val next = loadColorField(context, url, cropArtwork) ?: return@LaunchedEffect

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
        val seam = seamPx().coerceIn(0f, size.height)
        shown?.let { drawField(it, seam, 1f) }
        incoming?.let { drawField(it, seam, fade.value) }
        // Just enough to keep white text readable on a bright cover.
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.16f))))
    }
}

private fun DrawScope.drawField(field: ImageBitmap, seam: Float, alpha: Float) {
    if (alpha <= 0.001f) return
    val width = size.width.roundToInt()
    if (seam > 0.5f) {
        drawImage(
            image = field,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(field.width, 1),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, seam.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.Medium
        )
    }
    drawImage(
        image = field,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(field.width, field.height),
        dstOffset = IntOffset(0, seam.roundToInt()),
        dstSize = IntSize(width, (size.height - seam).roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        filterQuality = FilterQuality.Medium
    )
}

private const val FIELD_SOURCE_PX = 120
private const val FIELD_TEXTURE = 48
private const val FIELD_FADE_MS = 400

private val fieldCache = android.util.LruCache<String, ImageBitmap>(32)

private fun colorFieldOf(source: Bitmap, cropSquare: Boolean): ImageBitmap? = try {
    if (source.width < 1 || source.height < 1 || source.isRecycled) null else {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val texture = ImmersiveBackdropColors.texture(pixels, source.width, source.height, FIELD_TEXTURE, cropSquare)
        Bitmap.createBitmap(texture, FIELD_TEXTURE, FIELD_TEXTURE, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
} catch (_: Exception) { null }
