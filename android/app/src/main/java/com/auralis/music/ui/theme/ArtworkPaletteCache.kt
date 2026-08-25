package com.auralis.music.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Scale
import com.auralis.music.ui.components.getHighResArtworkUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Data class representing the 3 primary harmonious colors extracted from album artwork.
 */
data class ArtworkPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color
)

/**
 * High-performance thread-safe LRU Cache & Async Extractor for album artwork palettes.
 * 
 * - Downsamples images to 128x128 for ultra-fast (<2ms) CPU palette generation.
 * - Intelligently filters out monochrome backgrounds (white/black) to extract true vibrant album art tones.
 * - Pure JVM/Android compatible LinkedHashMap memory cache for testability and runtime speed.
 */
object ArtworkPaletteCache {

    private const val MAX_ENTRIES = 100
    private val memoryCache = object : LinkedHashMap<String, ArtworkPalette>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkPalette>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    val defaultPalette = ArtworkPalette(
        primary = Color(0xFF8E24AA),   // Vibrant Electric Violet
        secondary = Color(0xFFE91E63), // Radiant Magenta
        tertiary = Color(0xFF3949AB)   // Deep Indigo
    )

    fun getCached(key: String): ArtworkPalette? {
        if (key.isBlank()) return null
        return synchronized(memoryCache) { memoryCache[key] }
    }

    fun put(key: String, palette: ArtworkPalette) {
        if (key.isBlank()) return
        synchronized(memoryCache) { memoryCache[key] = palette }
    }

    fun clear() {
        synchronized(memoryCache) { memoryCache.clear() }
    }

    /**
     * Extracts vibrancy-boosted colors from artwork asynchronously on Dispatchers.Default.
     */
    suspend fun extractPalette(context: Context, key: String, artworkUrl: String): ArtworkPalette {
        if (artworkUrl.isBlank()) return defaultPalette

        val targetUrl = getHighResArtworkUrl(artworkUrl) ?: artworkUrl

        // 1. Check memory cache first
        getCached(key)?.let { return it }
        getCached(targetUrl)?.let { return it }
        getCached(artworkUrl)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val imageLoader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(targetUrl)
                    .size(128, 128) // Downsample for ultra-fast <2ms extraction
                    .scale(Scale.FIT)
                    .allowHardware(false)
                    .build()

                val result = imageLoader.execute(request)
                val drawable = result.drawable
                if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                    val bitmap = drawable.bitmap
                    val palette = withContext(Dispatchers.Default) {
                        Palette.from(bitmap)
                            .maximumColorCount(24)
                            .generate()
                    }

                    // Filter out monochrome backgrounds (white/grey/black) to extract true vibrant colors
                    val colorfulSwatches = palette.swatches.filter { swatch ->
                        val hsl = swatch.hsl
                        val s = hsl[1]
                        val l = hsl[2]
                        s > 0.18f && l in 0.12f..0.88f
                    }.sortedByDescending { it.population }

                    val rawPrimary = palette.vibrantSwatch?.takeIf { it.hsl[1] > 0.20f }?.rgb
                        ?: colorfulSwatches.firstOrNull()?.rgb
                        ?: palette.dominantSwatch?.rgb
                        ?: palette.lightVibrantSwatch?.rgb

                    val rawSecondary = palette.lightVibrantSwatch?.takeIf { it.hsl[1] > 0.20f }?.rgb
                        ?: colorfulSwatches.getOrNull(1)?.rgb
                        ?: palette.vibrantSwatch?.rgb
                        ?: palette.darkVibrantSwatch?.rgb
                        ?: rawPrimary

                    val rawTertiary = palette.darkVibrantSwatch?.takeIf { it.hsl[1] > 0.20f }?.rgb
                        ?: colorfulSwatches.getOrNull(2)?.rgb
                        ?: palette.darkMutedSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                        ?: rawPrimary

                    val primary = if (rawPrimary != null) {
                        boostVibrancy(Color(rawPrimary), minSaturation = 0.70f, targetLightness = 0.52f)
                    } else defaultPalette.primary

                    val secondary = if (rawSecondary != null) {
                        boostVibrancy(Color(rawSecondary), minSaturation = 0.65f, targetLightness = 0.58f)
                    } else defaultPalette.secondary

                    val tertiary = if (rawTertiary != null) {
                        boostVibrancy(Color(rawTertiary), minSaturation = 0.60f, targetLightness = 0.42f)
                    } else defaultPalette.tertiary

                    val extracted = ArtworkPalette(primary, secondary, tertiary)
                    put(key, extracted)
                    put(targetUrl, extracted)
                    put(artworkUrl, extracted)
                    extracted
                } else {
                    defaultPalette
                }
            } catch (_: Exception) {
                defaultPalette
            }
        }
    }

    private fun boostVibrancy(color: Color, minSaturation: Float = 0.65f, targetLightness: Float = 0.50f): Color {
        val r = color.red
        val g = color.green
        val b = color.blue

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f

        var s: Float
        var h: Float

        if (max == min) {
            h = 0f
            s = 0f
        } else {
            val d = max - min
            s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            h = when (max) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            }
            h /= 6f
        }

        val boostedS = s.coerceAtLeast(minSaturation)
        val tunedL = targetLightness.coerceIn(0.38f, 0.68f)

        fun hslToRgb(hVal: Float, sVal: Float, lVal: Float): Color {
            if (sVal == 0f) return Color(lVal, lVal, lVal)
            val q = if (lVal < 0.5f) lVal * (1f + sVal) else lVal + sVal - lVal * sVal
            val p = 2f * lVal - q

            fun hueToRgb(t: Float): Float {
                var v = t
                if (v < 0f) v += 1f
                if (v > 1f) v -= 1f
                return when {
                    v < 1f / 6f -> p + (q - p) * 6f * v
                    v < 1f / 2f -> q
                    v < 2f / 3f -> p + (q - p) * (2f / 3f - v) * 6f
                    else -> p
                }
            }

            return Color(hueToRgb(hVal + 1f / 3f), hueToRgb(hVal), hueToRgb(hVal - 1f / 3f))
        }

        return hslToRgb(h, boostedS, tunedL)
    }
}
