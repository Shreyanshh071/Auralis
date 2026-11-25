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
import androidx.core.graphics.ColorUtils
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

// Artwork mesh backdrop adapted from BitChord (GPL-3.0): https://github.com/kushagrasinghx/BitChord

/**
 * Reads a cover's backdrop mesh, or returns it from the cache. Safe to call ahead of a song
 * change. The mesh is a small, soft texture of the cover's own colours: its first row is the
 * cover's bottom edge and the rows after it run back up into the cover.
 */
internal suspend fun loadColorField(context: android.content.Context, url: String): ImageBitmap? {
    val cacheKey = "$url#mesh"
    return try {
        fieldCache.get(cacheKey) ?: withContext(Dispatchers.IO) {
            var bitmap: Bitmap? = null
            val candidates = listOfNotNull(com.auralis.music.ui.components.getHighResArtworkUrl(url), url).distinct()
            for (candidate in candidates) {
                try {
                    val request = ImageRequest.Builder(context).data(candidate).size(MESH_SOURCE_PX).allowHardware(false).build()
                    val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
                    val decoded = (drawable as? BitmapDrawable)?.bitmap
                    if (decoded != null && !decoded.isRecycled) { bitmap = decoded; break }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* Try the original thumbnail if high-resolution artwork is unavailable. */ }
            }
            bitmap?.takeIf { !it.isRecycled }?.let { meshOf(it, url.hashCode()) }
        }?.also { fieldCache.put(cacheKey, it) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { null }
}

/**
 * The player's backdrop: the cover's colours carried on below it. The mesh hangs from [seam]
 * (px from the top, where the cover's bottom edge sits), so the colour right under the cover is
 * the colour the cover ended on and there is no join; above the seam that first row is held.
 * The softness is baked into a tiny texture, so drawing is two stretched image samples.
 */
@Composable
internal fun ImmersiveColorField(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    seam: () -> Float = { 0f }
) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<ImageBitmap?>(artworkUrl?.let { fieldCache.get("$it#mesh") }) }
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
        val seamY = seam().coerceIn(0f, size.height)
        shown?.let { drawMesh(it, seamY, 1f) }
        incoming?.let { drawMesh(it, seamY, fade.value) }
        // Just enough to keep white text readable on a bright cover. These are the cover's own
        // colours, so a cover that ends dark leaves a dark screen and a bright one stays bright.
        drawRect(FieldScrim)
    }
}

private fun DrawScope.drawMesh(mesh: ImageBitmap, seamY: Float, alpha: Float) {
    if (alpha <= 0.001f) return
    val width = size.width.roundToInt()
    // Above the seam: the cover's bottom edge, held (it is behind the cover's faded foot).
    if (seamY > 0.5f) {
        drawImage(
            image = mesh,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(mesh.width, 1),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, seamY.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.Low
        )
    }
    // From the seam down: the mesh, stretched over the rest of the screen.
    drawImage(
        image = mesh,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(mesh.width, mesh.height),
        dstOffset = IntOffset(0, seamY.roundToInt()),
        dstSize = IntSize(width, (size.height - seamY).roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        filterQuality = FilterQuality.Low
    )
}

private const val MESH_SOURCE_PX = 120
private const val MESH_GRID = 6
private const val MESH_TEX = 32
private const val MESH_BLUR_RADIUS = 1
private const val MESH_BLUR_PASSES = 3
private const val MESH_VIBRANCE = 1.12f
private const val MESH_FLOOR = 0.045f
private const val FIELD_FADE_MS = 700

private val FieldScrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.06f),
    1f to Color.Black.copy(alpha = 0.30f)
)

private val fieldCache = android.util.LruCache<String, ImageBitmap>(64)

/**
 * Averages the cover into a 6x6 grid, read bottom-up so row 0 is the cover's bottom edge, then
 * shifts the rows below it sideways (seeded per cover) so the backdrop doesn't read as a mirror
 * of the cover, smooths it up to a 32px texture and softens it.
 */
private fun meshOf(source: Bitmap, seed: Int): ImageBitmap? = try {
    val width = source.width
    val height = source.height
    if (width < 1 || height < 1) null else {
        val cols = MESH_GRID.coerceAtMost(width)
        val rows = MESH_GRID.coerceAtMost(height)
        val cells = rows * cols
        val red = LongArray(cells)
        val green = LongArray(cells)
        val blue = LongArray(cells)
        val count = IntArray(cells)
        val line = IntArray(width)
        for (y in 0 until height) {
            source.getPixels(line, 0, width, 0, y, width, 1)
            val rowBase = ((height - 1 - y) * rows / height) * cols
            for (x in 0 until width) {
                val cell = rowBase + x * cols / width
                val pixel = line[x]
                red[cell] += ((pixel shr 16) and 0xFF).toLong()
                green[cell] += ((pixel shr 8) and 0xFF).toLong()
                blue[cell] += (pixel and 0xFF).toLong()
                count[cell]++
            }
        }
        val grid = IntArray(cells) { cell ->
            val n = count[cell].coerceAtLeast(1)
            argb((red[cell] / n).toInt(), (green[cell] / n).toInt(), (blue[cell] / n).toInt()).lifted()
        }
        val texels = grid.shiftedBelowSeam(cols, rows, seed).resampled(cols, rows, MESH_TEX)
        val scratch = IntArray(texels.size)
        repeat(MESH_BLUR_PASSES) {
            boxBlurPass(texels, scratch, MESH_TEX, MESH_TEX, MESH_BLUR_RADIUS, horizontal = true)
            boxBlurPass(scratch, texels, MESH_TEX, MESH_TEX, MESH_BLUR_RADIUS, horizontal = false)
        }
        Bitmap.createBitmap(texels, MESH_TEX, MESH_TEX, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
} catch (_: Exception) { null }

/** Every row but the seam row, carried sideways by one seeded amount (and mirrored half the time). */
private fun IntArray.shiftedBelowSeam(cols: Int, rows: Int, seed: Int): IntArray {
    if (rows <= 1) return this
    val random = Random(seed)
    val mirror = random.nextBoolean()
    val shift = random.nextInt(cols)
    val out = copyOf()
    for (row in 1 until rows) {
        val base = row * cols
        for (x in 0 until cols) {
            val src = if (mirror) cols - 1 - x else x
            out[base + x] = this[base + (src + shift) % cols]
        }
    }
    return out
}

private fun IntArray.resampled(cols: Int, rows: Int, size: Int): IntArray {
    val out = IntArray(size * size)
    for (ty in 0 until size) {
        val fy = (ty + 0.5f) / size * rows - 0.5f
        val y0 = floor(fy).toInt().coerceIn(0, rows - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val wy = smoothstep(fy - y0)
        for (tx in 0 until size) {
            val fx = (tx + 0.5f) / size * cols - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, cols - 1)
            val x1 = (x0 + 1).coerceAtMost(cols - 1)
            val wx = smoothstep(fx - x0)
            val top = lerpArgb(this[y0 * cols + x0], this[y0 * cols + x1], wx)
            val bottom = lerpArgb(this[y1 * cols + x0], this[y1 * cols + x1], wx)
            out[ty * size + tx] = lerpArgb(top, bottom, wy)
        }
    }
    return out
}

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun lerpArgb(from: Int, to: Int, t: Float): Int {
    if (t <= 0f) return from
    if (t >= 1f) return to
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + ((b - a) * t)).roundToInt().coerceIn(0, 255)
    }
    return argb(channel(16), channel(8), channel(0))
}

private fun argb(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

/** Averaging greys colours a little; put roughly that back, and keep off pure black. */
private fun Int.lifted(): Int {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(this, it) }
    hsl[1] = (hsl[1] * MESH_VIBRANCE).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceAtLeast(MESH_FLOOR)
    return ColorUtils.HSLToColor(hsl)
}

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
            target[index] = argb((red + window / 2) / window, (green + window / 2) / window, (blue + window / 2) / window)
            val leaving = pixel(moving - radius)
            val entering = pixel(moving + radius + 1)
            red += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            green += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}
