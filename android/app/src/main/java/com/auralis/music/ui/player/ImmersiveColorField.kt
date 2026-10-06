package com.auralis.music.ui.player

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The immersive player's backdrop: the cover's own colours carried on below it.
 *
 * The cover is averaged into a coarse grid, flipped so its first row is the cover's bottom
 * edge, and every row past that one is shifted sideways so faces or logos don't mirror back
 * under the artwork. The grid is smoothed to a small texture once, off the main thread, and
 * then only stretched: from [seamPx] down it is the grid, above it the cover's bottom row is
 * held, so there is no visible join where the artwork fades out.
 */
@Composable
internal fun ImmersiveColorField(
    artworkUrl: String?,
    seamPx: () -> Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<ImageBitmap?>(artworkUrl?.let { fieldCache[it] }) }
    var incoming by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(artworkUrl) {
        val url = artworkUrl ?: return@LaunchedEffect
        val next = fieldCache[url] ?: withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(FIELD_SOURCE_PX)
                .allowHardware(false)
                .build()
            val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            bitmap?.takeIf { !it.isRecycled }?.let { colorFieldOf(it, url.hashCode()) }
        }?.also { fieldCache[url] = it } ?: return@LaunchedEffect

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
            // Opaque floor outside the blur, so nothing behind the player can show through.
            .background(Color(0xFF121212))
            .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(32.dp) else Modifier)
    ) {
        val seam = seamPx().coerceIn(0f, size.height)
        shown?.let { drawField(it, seam, 1f) }
        incoming?.let { drawField(it, seam, fade.value) }
        // Just enough to keep white text readable on a bright cover.
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.30f))))
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
            filterQuality = FilterQuality.Low
        )
    }
    drawImage(
        image = field,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(field.width, field.height),
        dstOffset = IntOffset(0, seam.roundToInt()),
        dstSize = IntSize(width, (size.height - seam).roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        filterQuality = FilterQuality.Low
    )
}

private const val FIELD_SOURCE_PX = 120
private const val FIELD_GRID = 6
private const val FIELD_TEXTURE = 32
private const val FIELD_FADE_MS = 900

private val fieldCache = object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 48
}

private fun colorFieldOf(source: Bitmap, seed: Int): ImageBitmap? {
    val width = source.width
    val height = source.height
    if (width < 1 || height < 1) return null
    val cols = FIELD_GRID.coerceAtMost(width)
    val rows = FIELD_GRID.coerceAtMost(height)
    val cells = cols * rows
    val red = LongArray(cells)
    val green = LongArray(cells)
    val blue = LongArray(cells)
    val count = IntArray(cells)
    val line = IntArray(width)
    for (y in 0 until height) {
        source.getPixels(line, 0, width, 0, y, width, 1)
        // Flipped while reading: row 0 is the cover's bottom edge.
        val rowBase = ((height - 1 - y) * rows / height) * cols
        for (x in 0 until width) {
            val cell = rowBase + x * cols / width
            val pixel = line[x]
            red[cell] += (pixel shr 16) and 0xFF
            green[cell] += (pixel shr 8) and 0xFF
            blue[cell] += pixel and 0xFF
            count[cell]++
        }
    }
    val grid = IntArray(cells) { cell ->
        val n = count[cell].coerceAtLeast(1)
        lifted(argb((red[cell] / n).toInt(), (green[cell] / n).toInt(), (blue[cell] / n).toInt()))
    }
    val texels = smoothed(shiftedBelowSeam(grid, cols, rows, seed), cols, rows, FIELD_TEXTURE)
    return Bitmap.createBitmap(texels, FIELD_TEXTURE, FIELD_TEXTURE, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Every row but the seam row shifted (and sometimes mirrored) by one seeded amount. */
private fun shiftedBelowSeam(grid: IntArray, cols: Int, rows: Int, seed: Int): IntArray {
    if (rows <= 1) return grid
    val random = Random(seed)
    val mirror = random.nextBoolean()
    val shift = random.nextInt(cols)
    val out = grid.copyOf()
    for (row in 1 until rows) {
        val base = row * cols
        for (x in 0 until cols) {
            val src = if (mirror) cols - 1 - x else x
            out[base + x] = grid[base + (src + shift) % cols]
        }
    }
    return out
}

private fun smoothed(grid: IntArray, cols: Int, rows: Int, size: Int): IntArray {
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
            val top = lerpArgb(grid[y0 * cols + x0], grid[y0 * cols + x1], wx)
            val bottom = lerpArgb(grid[y1 * cols + x0], grid[y1 * cols + x1], wx)
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
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
    }
    return argb(channel(16), channel(8), channel(0))
}

private fun argb(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

/** Averaging greys colours out; put back some saturation and keep cells off pure black. */
private fun lifted(color: Int): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color, hsv)
    hsv[1] = (hsv[1] * 1.25f).coerceAtMost(1f)
    hsv[2] = hsv[2].coerceAtLeast(0.08f)
    return android.graphics.Color.HSVToColor(hsv)
}
