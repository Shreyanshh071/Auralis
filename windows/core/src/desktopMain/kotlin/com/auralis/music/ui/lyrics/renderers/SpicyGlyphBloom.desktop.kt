package com.auralis.music.ui.lyrics.renderers

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

internal actual fun blurSpicyGlyphMask(mask: ImageBitmap, sigma: Float): ImageBitmap {
    Image.makeFromBitmap(mask.asSkiaBitmap()).use { input ->
        Surface.makeRasterN32Premul(mask.width, mask.height).use { surface ->
            surface.canvas.clear(0)
            Paint().use { paint ->
                ImageFilter.makeBlur(sigma, sigma, FilterTileMode.DECAL).use { blur ->
                    paint.imageFilter = blur
                    surface.canvas.drawImageRect(input, Rect.makeWH(mask.width.toFloat(), mask.height.toFloat()), paint)
                }
            }
            // Compose 1.7's Image.toComposeImageBitmap draws SrcOver onto an
            // uncleared allocation. Transparent halos then inherit recycled
            // pixels. Copy every pixel, including zero alpha, into owned storage.
            val pixels = Bitmap()
            check(pixels.allocPixels(ImageInfo.makeN32(mask.width, mask.height, ColorAlphaType.PREMUL)))
            surface.makeImageSnapshot().use { snapshot -> check(snapshot.readPixels(pixels)) }
            pixels.setImmutable()
            return pixels.asComposeImageBitmap()
        }
    }
}
