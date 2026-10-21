package com.auralis.music.windows.player

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Artwork blurred once, at a small size, and cached. Blurring the drawn artwork with a render
 * effect redid a large blur on the GPU every frame the screen animated (the app background, and
 * twice per Familiar Artists poster for its frosted buttons), which made frames after a window
 * resize take 15-60ms. A small blurred bitmap scaled up looks the same and costs only a draw.
 *
 * [blurFraction] is the blur radius as a fraction of the width the picture is shown at, so the
 * result matches a render-effect blur of that radius at that width.
 */
private const val BLURRED_SIDE = 192

private val blurredCache = java.util.Collections.synchronizedMap(
    object : java.util.LinkedHashMap<String, ImageBitmap>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 96
    })

private fun blurredKey(artwork: String, blurFraction: Float) = "${auraTextureKey(artwork)}@${"%.4f".format(blurFraction)}"

internal fun decodeArtwork(url: String): BufferedImage? = runCatching {
    if (java.io.File(url).isFile) ImageIO.read(java.io.File(url))
    else if (url.startsWith("file:")) ImageIO.read(java.io.File(java.net.URI(url)))
    else com.auralis.music.data.network.NetworkClientProvider.okHttpClient
        .newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful)
            response.body?.byteStream()?.use(ImageIO::read)
        }
}.getOrNull()

internal suspend fun loadBlurredArtwork(artwork: String, blurFraction: Float): ImageBitmap? = withContext(Dispatchers.IO) {
    val key = blurredKey(artwork, blurFraction)
    blurredCache[key] ?: decodeArtwork(auraTextureKey(artwork))?.let { source ->
        // Keep the aspect ratio; the longer side becomes BLURRED_SIDE.
        val scale = BLURRED_SIDE.toFloat() / maxOf(source.width, source.height)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val small = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(source, 0, 0, width, height, null); dispose()
        }
        // Compose's blur radius maps to a Gaussian sigma of about 0.58 x radius.
        val sigma = (blurFraction * width * .58f).coerceAtLeast(.1f)
        val input = org.jetbrains.skia.Image.makeFromBitmap(small.toComposeImageBitmap().asSkiaBitmap())
        Surface.makeRasterN32Premul(width, height).use { surface ->
            Paint().use { paint ->
                ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP).use { blur ->
                    paint.imageFilter = blur
                    surface.canvas.drawImageRect(input, Rect.makeWH(width.toFloat(), height.toFloat()), paint)
                }
            }
            input.close()
            surface.makeImageSnapshot().toComposeImageBitmap()
        }.also { blurredCache[key] = it }
    }
}

/** The blurred artwork, keeping the previous one until a new one is ready (no flash between songs). */
@Composable
internal fun rememberBlurredArtwork(artwork: String?, blurFraction: Float): State<ImageBitmap?> {
    val cached = remember { mutableStateOf(artwork?.let { blurredCache[blurredKey(it, blurFraction)] }) }
    LaunchedEffect(artwork, blurFraction) {
        if (artwork == null) cached.value = null
        else loadBlurredArtwork(artwork, blurFraction)?.let { cached.value = it }
    }
    return cached
}
