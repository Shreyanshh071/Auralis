package com.auralis.music.ui.player

import android.graphics.Bitmap
import androidx.core.graphics.scale
import coil.size.Size
import coil.transform.Transformation

/**
 * The full player's Blur backdrop, blurred once on a small copy instead of by a render effect.
 * Measured on a 144 Hz phone, the render-effect blur cost ~8.5ms of GPU per frame (twice during a
 * song crossfade) against a 7ms frame budget, which made song changes stutter. A heavily blurred
 * picture has no detail to lose, so a 192px copy scaled up looks the same and costs only a draw.
 *
 * [radiusFraction] is the old blur radius as a fraction of the shown artwork's side.
 */
class ArtworkPreBlur(private val radiusFraction: Float) : Transformation {
    override val cacheKey: String = "artworkPreBlur-$radiusFraction"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val scale = SIDE.toFloat() / maxOf(input.width, input.height)
        val width = (input.width * scale).toInt().coerceAtLeast(1)
        val height = (input.height * scale).toInt().coerceAtLeast(1)
        val small = input.copy(Bitmap.Config.ARGB_8888, false).scale(width, height)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        // Android's blur radius is a Gaussian of sigma ≈ 0.577 × radius + 0.5. Three box passes
        // of radius r approximate that sigma when (2r + 1)² = 4σ² + 1.
        val sigma = radiusFraction * maxOf(width, height) * .577f + .5f
        val box = ((kotlin.math.sqrt(4f * sigma * sigma + 1f) - 1f) / 2f).toInt().coerceAtLeast(1)
        val scratch = IntArray(pixels.size)
        repeat(3) {
            boxBlur(pixels, scratch, width, height, box, horizontal = true)
            boxBlur(scratch, pixels, width, height, box, horizontal = false)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val SIDE = 192

        /** One box pass along rows or columns, clamping at the edges like the render effect did. */
        fun boxBlur(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
            val lines = if (horizontal) height else width
            val length = if (horizontal) width else height
            val window = 2 * radius + 1
            for (line in 0 until lines) {
                fun at(i: Int): Int {
                    val clamped = i.coerceIn(0, length - 1)
                    return src[if (horizontal) line * width + clamped else clamped * width + line]
                }
                var a = 0; var r = 0; var g = 0; var b = 0
                for (i in -radius..radius) {
                    val p = at(i)
                    a += p ushr 24; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
                }
                for (i in 0 until length) {
                    dst[if (horizontal) line * width + i else i * width + line] =
                        ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                    val out = at(i - radius); val inc = at(i + radius + 1)
                    a += (inc ushr 24) - (out ushr 24)
                    r += ((inc shr 16) and 255) - ((out shr 16) and 255)
                    g += ((inc shr 8) and 255) - ((out shr 8) and 255)
                    b += (inc and 255) - (out and 255)
                }
            }
        }
    }
}
