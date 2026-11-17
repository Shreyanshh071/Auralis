/**
 * Liquid artwork background, after Metrolist's LiquidBackground (GPL-3.0).
 * Metrolist Project (C) 2026 | Licensed under GPL-3.0
 * Reimplemented for Auralis under the GNU General Public License v3.0.
 */

package com.auralis.music.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint as AndroidPaint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A slowly churning pool of the artwork's own colours.
 *
 * Three copies of the cover, colour-boosted, turn at different speeds (130 s, 90 s and 70 s per
 * revolution) and overlap on a tiny canvas. That canvas is darkened, heavily blurred and then
 * stretched over the screen, so the cover dissolves into soft, moving colour with no visible
 * edges. Rendering at ~128 px keeps it cheap; it redraws about 20 times a second, only while
 * music plays and the player is on screen. A new song's cover crossfades in over a second.
 */
@Composable
fun LiquidPlayerBackground(
    artworkUrl: String?,
    isPlaying: Boolean,
    isVisible: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var active by remember { mutableStateOf(LiquidArtCache.last) }
    var previous by remember { mutableStateOf<Bitmap?>(null) }
    val crossfade = remember { Animatable(1f) }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) return@LaunchedEffect
        val bitmap = LiquidArtCache.get(artworkUrl) ?: withContext(Dispatchers.IO) {
            runCatching {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(artworkUrl).size(256).allowHardware(false).build()
                )
                (result as? SuccessResult)?.drawable?.toBitmap(128, 128, Bitmap.Config.ARGB_8888)
            }.getOrNull()
        }?.also { LiquidArtCache.put(artworkUrl, it) } ?: return@LaunchedEffect
        val current = active
        if (current != null && current !== bitmap) {
            previous = current
            active = bitmap
            crossfade.snapTo(0f)
            crossfade.animateTo(1f, tween(1000))
            previous = null
        } else {
            active = bitmap
        }
    }

    val time = remember { mutableLongStateOf(LiquidArtCache.rotationMs) }
    LaunchedEffect(isPlaying, isVisible) {
        if (!isPlaying || !isVisible) return@LaunchedEffect
        val origin = withFrameMillis { it } - time.longValue
        while (true) {
            withFrameMillis { now ->
                time.longValue = now - origin
                LiquidArtCache.rotationMs = time.longValue
            }
            delay(50)
        }
    }

    val renderer = remember { LiquidRenderer() }
    Box(modifier.fillMaxSize().clipToBounds()) {
        val art = active
        if (art == null) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas
                val image = renderer.render(w / h, art, previous, crossfade.value, time.longValue)
                // Drawn 30% oversize so the blur's soft edges sit off screen.
                val bw = (w * 1.3f).roundToInt()
                val bh = (h * 1.3f).roundToInt()
                drawImage(
                    image = image,
                    dstOffset = IntOffset(-(bw - w.roundToInt()) / 2, -(bh - h.roundToInt()) / 2),
                    dstSize = IntSize(bw, bh),
                    filterQuality = FilterQuality.Low
                )
            }
        }
    }
}

/** Keeps the last few decoded covers, and the spin position, across player open/close. */
private object LiquidArtCache {
    private val map = LinkedHashMap<String, Bitmap>()
    var last: Bitmap? = null
        private set
    var rotationMs = 0L

    fun get(url: String): Bitmap? = map[url]

    fun put(url: String, bitmap: Bitmap) {
        if (map.size >= 8) map.remove(map.keys.first())
        map[url] = bitmap
        last = bitmap
    }
}

/** Draws the three turning covers into a small buffer, then darkens and blurs it. */
private class LiquidRenderer {
    private var buffer: Buffer? = null

    fun render(aspect: Float, active: Bitmap, previous: Bitmap?, crossfade: Float, timeMs: Long): ImageBitmap {
        val maxDim = 128
        val (bw, bh) = if (aspect >= 1f) maxDim to (maxDim / aspect).roundToInt().coerceAtLeast(1)
            else (maxDim * aspect).roundToInt().coerceAtLeast(1) to maxDim
        val b = buffer?.takeIf { it.width == bw && it.height == bh } ?: Buffer(bw, bh).also { buffer = it }
        return b.render(active, previous, crossfade, timeMs)
    }

    private class Buffer(val width: Int, val height: Int) {
        private val bitmaps = arrayOf(
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        )
        private val images = bitmaps.map { it.asImageBitmap() }
        private val canvases = bitmaps.map { AndroidCanvas(it) }
        private var index = 0
        private val pixels = IntArray(width * height)
        private val blur = StackBlur(width, height, BLUR_RADIUS)
        private val matrix = Matrix()
        private val layerPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG or AndroidPaint.DITHER_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(SATURATION) })
        }
        private val scrim = AndroidPaint()
        private val w = width.toFloat()
        private val h = height.toFloat()
        private val side = (max(w, h) * 1.3f).roundToInt().toFloat()
        private val pivot = side / 2f
        private val offX = -(side - w) / 2f
        private val offY = -(side - h) / 2f

        private var lastKey: Any? = null
        private var lastImage: ImageBitmap? = null

        fun render(active: Bitmap, previous: Bitmap?, crossfade: Float, timeMs: Long): ImageBitmap {
            val key = listOf(active, previous, crossfade, timeMs)
            if (key == lastKey) lastImage?.let { return it }
            lastKey = key
            index = 1 - index
            val target = bitmaps[index]
            val canvas = canvases[index]
            target.eraseColor(0)

            val rotA = -360f * (timeMs % 130_000L) / 130_000f
            val rotB = 360f * (timeMs % 90_000L) / 90_000f
            val rotC = 360f * (timeMs % 70_000L) / 70_000f
            if (previous != null && crossfade < 1f) drawLayers(canvas, previous, 1f - crossfade, rotA, rotB, rotC)
            drawLayers(canvas, active, crossfade, rotA, rotB, rotC)

            // Darken for legible white text, with a breath of white so it never goes flat.
            scrim.color = 0x80000000.toInt()
            canvas.drawPaint(scrim)
            scrim.color = 0x0DFFFFFF
            canvas.drawPaint(scrim)

            blur.apply(target, pixels)
            return images[index].also { lastImage = it }
        }

        private fun drawLayers(canvas: AndroidCanvas, art: Bitmap, alpha: Float, rotA: Float, rotB: Float, rotC: Float) {
            if (alpha <= 0f || art.isRecycled || art.height <= 0) return
            layerPaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
            val scale = side / art.height
            // Centre, upper-left and lower copies, each turning at its own pace.
            matrix.setScale(scale, scale); matrix.postRotate(rotA, pivot, pivot); matrix.postTranslate(offX, offY)
            canvas.drawBitmap(art, matrix, layerPaint)
            matrix.setScale(scale, scale); matrix.postRotate(rotB, pivot, pivot); matrix.postTranslate(offX - .95f * w, offY - .7f * h)
            canvas.drawBitmap(art, matrix, layerPaint)
            matrix.setScale(scale, scale); matrix.postRotate(rotC, pivot, pivot); matrix.postTranslate(offX - .5f * w, offY + .7f * h)
            matrix.postRotate(rotC, w / 2f, h / 2f)
            canvas.drawBitmap(art, matrix, layerPaint)
        }
    }

    private companion object {
        const val BLUR_RADIUS = 20
        const val SATURATION = 2.5f
    }
}

/** In-place stack blur with reusable buffers (no allocation per frame). */
private class StackBlur(width: Int, height: Int, private val radius: Int) {
    private val r = IntArray(width * height)
    private val g = IntArray(width * height)
    private val b = IntArray(width * height)
    private val div = radius * 2 + 1
    private val divSum = ((div + 1) shr 1).let { it * it }
    private val table = IntArray(256 * divSum) { it / divSum }
    private val stack = IntArray(div * 3)

    fun apply(bitmap: Bitmap, pix: IntArray) {
        val w = bitmap.width
        val h = bitmap.height
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)
        val wm = w - 1
        val hm = h - 1
        var yi = 0
        for (y in 0 until h) {
            val row = y * w
            var rIn = 0; var gIn = 0; var bIn = 0
            var rOut = 0; var gOut = 0; var bOut = 0
            var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val p = pix[row + minOf(wm, maxOf(i, 0))]
                val si = (i + radius) * 3
                val pr = (p shr 16) and 0xff; val pg = (p shr 8) and 0xff; val pb = p and 0xff
                stack[si] = pr; stack[si + 1] = pg; stack[si + 2] = pb
                val weight = radius + 1 - kotlin.math.abs(i)
                rSum += pr * weight; gSum += pg * weight; bSum += pb * weight
                if (i > 0) { rIn += pr; gIn += pg; bIn += pb } else { rOut += pr; gOut += pg; bOut += pb }
            }
            var sp = radius
            for (x in 0 until w) {
                r[yi] = table[rSum]; g[yi] = table[gSum]; b[yi] = table[bSum]
                rSum -= rOut; gSum -= gOut; bSum -= bOut
                var start = sp - radius + div
                if (start >= div) start -= div
                val so = start * 3
                rOut -= stack[so]; gOut -= stack[so + 1]; bOut -= stack[so + 2]
                val p = pix[row + minOf(x + radius + 1, wm)]
                val pr = (p shr 16) and 0xff; val pg = (p shr 8) and 0xff; val pb = p and 0xff
                stack[so] = pr; stack[so + 1] = pg; stack[so + 2] = pb
                rIn += pr; gIn += pg; bIn += pb
                rSum += rIn; gSum += gIn; bSum += bIn
                sp = if (sp + 1 == div) 0 else sp + 1
                val ss = sp * 3
                rOut += stack[ss]; gOut += stack[ss + 1]; bOut += stack[ss + 2]
                rIn -= stack[ss]; gIn -= stack[ss + 1]; bIn -= stack[ss + 2]
                yi++
            }
        }
        for (x in 0 until w) {
            var rIn = 0; var gIn = 0; var bIn = 0
            var rOut = 0; var gOut = 0; var bOut = 0
            var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val idx = x + maxOf(0, i) * w
                val si = (i + radius) * 3
                val pr = r[idx]; val pg = g[idx]; val pb = b[idx]
                stack[si] = pr; stack[si + 1] = pg; stack[si + 2] = pb
                val weight = radius + 1 - kotlin.math.abs(i)
                rSum += pr * weight; gSum += pg * weight; bSum += pb * weight
                if (i > 0) { rIn += pr; gIn += pg; bIn += pb } else { rOut += pr; gOut += pg; bOut += pb }
            }
            var idxOut = x
            var sp = radius
            for (y in 0 until h) {
                pix[idxOut] = (0xFF shl 24) or (table[rSum] shl 16) or (table[gSum] shl 8) or table[bSum]
                rSum -= rOut; gSum -= gOut; bSum -= bOut
                var start = sp - radius + div
                if (start >= div) start -= div
                val so = start * 3
                rOut -= stack[so]; gOut -= stack[so + 1]; bOut -= stack[so + 2]
                val p = x + minOf(y + radius + 1, hm) * w
                val pr = r[p]; val pg = g[p]; val pb = b[p]
                stack[so] = pr; stack[so + 1] = pg; stack[so + 2] = pb
                rIn += pr; gIn += pg; bIn += pb
                rSum += rIn; gSum += gIn; bSum += bIn
                sp = if (sp + 1 == div) 0 else sp + 1
                val ss = sp * 3
                rOut += stack[ss]; gOut += stack[ss + 1]; bOut += stack[ss + 2]
                rIn -= stack[ss]; gIn -= stack[ss + 1]; bIn -= stack[ss + 2]
                idxOut += w
            }
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }
}
