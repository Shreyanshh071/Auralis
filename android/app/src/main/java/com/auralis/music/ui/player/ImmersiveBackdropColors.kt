package com.auralis.music.ui.player

/** A low-contrast continuation of the displayed cover, rather than a shuffled colour grid. */
internal object ImmersiveBackdropColors {
    fun texture(pixels: IntArray, width: Int, height: Int, size: Int = 48, cropSquare: Boolean = true): IntArray {
        require(width > 0 && height > 0 && size > 0 && pixels.size >= width * height)
        val side = minOf(width, height)
        val left = if (cropSquare) (width - side) / 2 else 0
        val top = if (cropSquare) (height - side) / 2 else 0
        val visibleWidth = if (cropSquare) side else width
        val visibleHeight = if (cropSquare) side else height
        val firstRow = top + visibleHeight * 3 / 4
        val lastRow = top + visibleHeight
        val edge = IntArray(visibleWidth) { x ->
            average(pixels, width, left + x, left + x + 1, firstRow, lastRow)
        }
        val mean = average(pixels, width, left, left + visibleWidth, firstRow, lastRow)
        val radius = (visibleWidth / 3).coerceAtLeast(1)
        val profile = IntArray(size) { x ->
            val center = ((x + .5f) / size * visibleWidth).toInt().coerceAtMost(visibleWidth - 1)
            val from = (center - radius).coerceAtLeast(0)
            val to = (center + radius + 1).coerceAtMost(visibleWidth)
            val local = average(edge, visibleWidth, from, to, 0, 1)
            // Most of the field is the edge's common tone. Do not boost saturation.
            blend(mean, local, .25f)
        }
        return IntArray(size * size) { index ->
            val y = index / size
            val fade = if (size == 1) 0f else y.toFloat() / (size - 1)
            // A continuous, restrained darkening; no alternating colour rows or black floor.
            shade(profile[index % size], .82f - .10f * fade)
        }
    }

    private fun average(pixels: IntArray, stride: Int, left: Int, right: Int, top: Int, bottom: Int): Int {
        var red = 0L; var green = 0L; var blue = 0L; var count = 0
        for (y in top until bottom) for (x in left until right) {
            val color = pixels[y * stride + x]
            red += (color ushr 16) and 255; green += (color ushr 8) and 255; blue += color and 255
            count++
        }
        return rgb((red / count.coerceAtLeast(1)).toInt(), (green / count.coerceAtLeast(1)).toInt(), (blue / count.coerceAtLeast(1)).toInt())
    }
    private fun blend(a: Int, b: Int, t: Float): Int = rgb(
        channel(a, b, 16, t), channel(a, b, 8, t), channel(a, b, 0, t))
    private fun channel(a: Int, b: Int, shift: Int, t: Float): Int =
        (((a ushr shift) and 255) * (1f - t) + ((b ushr shift) and 255) * t).toInt()
    private fun shade(color: Int, factor: Float): Int = rgb(
        (((color ushr 16) and 255) * factor).toInt(), (((color ushr 8) and 255) * factor).toInt(), ((color and 255) * factor).toInt())
    private fun rgb(r: Int, g: Int, b: Int): Int = (255 shl 24) or (r shl 16) or (g shl 8) or b
}
