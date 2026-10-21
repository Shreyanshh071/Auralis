package com.auralis.music.windows.settings

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.auralis.music.ui.theme.ArtworkPalette
import com.auralis.music.ui.theme.DefaultArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import okhttp3.Request

private val paletteFlow = MutableStateFlow(DefaultArtworkPalette)

object DesktopArtworkPaletteStore {
    private val memoryCache = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, ArtworkPalette>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkPalette>?): Boolean = size > 128
        }
    )

    fun get(url: String): ArtworkPalette? {
        val inMem = memoryCache[url]
        if (inMem != null) return inMem
        val session = com.auralis.music.windows.player.DesktopPlaybackSessionStore.currentSession()
        if (session != null && (session.artworkUrl == url || session.track.thumbnail == url) && session.palette != null && !session.palette.isDefault) {
            memoryCache[url] = session.palette
            return session.palette
        }
        return null
    }

    fun put(url: String, palette: ArtworkPalette) {
        if (!palette.isDefault) {
            memoryCache[url] = palette
        }
    }

    fun lastPalette(): ArtworkPalette? = com.auralis.music.windows.player.DesktopPlaybackSessionStore.currentSession()?.palette?.takeIf { !it.isDefault }
}

@Composable
fun rememberArtworkPalette(url: String?): ArtworkPalette {
    val initial = remember(url) {
        if (!url.isNullOrBlank()) {
            DesktopArtworkPaletteStore.get(url) ?: run {
                val session = com.auralis.music.windows.player.DesktopPlaybackSessionStore.currentSession()
                if (session != null && (session.artworkUrl == url || session.track.thumbnail == url)) {
                    session.palette?.takeIf { !it.isDefault }
                } else null
            } ?: DefaultArtworkPalette
        } else {
            DesktopArtworkPaletteStore.lastPalette() ?: DefaultArtworkPalette
        }
    }
    var palette by remember { mutableStateOf(initial) }

    LaunchedEffect(url) {
        com.auralis.music.ui.theme.ArtworkPaletteSource.current = paletteFlow
        if (initial != DefaultArtworkPalette) {
            palette = initial
            paletteFlow.value = initial
        }
        if (!url.isNullOrBlank()) {
            val cached = DesktopArtworkPaletteStore.get(url)
            if (cached != null && !cached.isDefault) {
                palette = cached
                paletteFlow.value = cached
                com.auralis.music.windows.player.DesktopPlaybackSessionStore.updatePalette(cached)
            } else {
                val result = withContext(Dispatchers.IO) {
                    loadAndExtractPalette(url)
                }
                if (!result.isDefault) {
                    DesktopArtworkPaletteStore.put(url, result)
                    com.auralis.music.windows.player.DesktopPlaybackSessionStore.updatePalette(result)
                    palette = result
                    paletteFlow.value = result
                }
            }
        }
    }
    return palette
}

internal fun loadAndExtractPalette(url: String): ArtworkPalette {
    val decoded: BufferedImage? = when {
        java.io.File(url).isFile -> runCatching { ImageIO.read(java.io.File(url)) }.getOrNull()
        url.startsWith("file:") -> runCatching { ImageIO.read(java.io.File(java.net.URI(url))) }.getOrNull()
        else -> {
            val candidateUrls = buildList {
                val optimized = com.auralis.music.ui.components.getOptimizedThumbnailUrl(url)
                if (!optimized.isNullOrBlank()) add(optimized)
                if (optimized != url) add(url)
                if (optimized?.contains("/hq720.jpg") == true) {
                    add(optimized.replace("/hq720.jpg", "/hqdefault.jpg"))
                }
            }.distinct()

            var img: BufferedImage? = null
            for (candidate in candidateUrls) {
                try {
                    val request = Request.Builder()
                        .url(candidate)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()
                    com.auralis.music.data.network.NetworkClientProvider.okHttpClient
                        .newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                img = response.body?.byteStream()?.use(ImageIO::read)
                            }
                        }
                    if (img != null) break
                } catch (_: Exception) {}
            }
            img
        }
    }
    return decoded?.let(::extractArtworkPalette) ?: DefaultArtworkPalette
}

/** One colour cluster of the cover: its average colour, HSL and how many sampled pixels it covers. */
private class Swatch(val rgb: Int, val population: Int) {
    val hsl: FloatArray = rgbToHsl(rgb)
    val chroma: Float get() = hsl[1] * (1f - kotlin.math.abs(2f * hsl[2] - 1f))
}

private fun rgbToHsl(rgb: Int): FloatArray {
    val r = ((rgb shr 16) and 255) / 255f; val g = ((rgb shr 8) and 255) / 255f; val b = (rgb and 255) / 255f
    val max = maxOf(r, g, b); val min = minOf(r, g, b); val l = (max + min) / 2f
    if (max == min) return floatArrayOf(0f, 0f, l)
    val d = max - min
    val s = if (l > .5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) { r -> ((g - b) / d + (if (g < b) 6f else 0f)); g -> ((b - r) / d + 2f); else -> ((r - g) / d + 4f) } * 60f
    return floatArrayOf(h, s, l)
}

private fun hueDifference(a: Float, b: Float) = kotlin.math.abs(a - b).let { if (it > 180f) 360f - it else it }

/**
 * Android Palette's targets (vibrant, light/dark vibrant, muted, light/dark muted), scored with
 * its default weights: saturation 0.24, lightness 0.52, population 0.24.
 */
private class Target(val minSat: Float, val targetSat: Float, val maxSat: Float, val minLum: Float, val targetLum: Float, val maxLum: Float)
private val VIBRANT = Target(.35f, 1f, 1f, .3f, .5f, .7f)
private val LIGHT_VIBRANT = Target(.35f, 1f, 1f, .55f, .74f, 1f)
private val DARK_VIBRANT = Target(.35f, 1f, 1f, 0f, .26f, .45f)
private val MUTED = Target(0f, .3f, .4f, .3f, .5f, .7f)
private val LIGHT_MUTED = Target(0f, .3f, .4f, .55f, .74f, 1f)
private val DARK_MUTED = Target(0f, .3f, .4f, 0f, .26f, .45f)

private fun pick(target: Target, swatches: List<Swatch>, used: MutableSet<Int>): Swatch? {
    val maxPop = swatches.maxOfOrNull { it.population }?.toFloat() ?: return null
    return swatches.filter { it.rgb !in used && it.hsl[1] in target.minSat..target.maxSat && it.hsl[2] in target.minLum..target.maxLum }
        .maxByOrNull { .24f * (1f - kotlin.math.abs(it.hsl[1] - target.targetSat)) + .52f * (1f - kotlin.math.abs(it.hsl[2] - target.targetLum)) + .24f * (it.population / maxPop) }
        ?.also { used += it.rgb }
}

/**
 * The phone's artwork palette on Windows (ArtworkPaletteCache): cluster the cover, take Palette's
 * six target swatches for glows, and choose primary/secondary/tertiary by colourful presence with
 * distinct hues. Transparent pixels and near-black/near-white borders are ignored.
 */
internal fun extractArtworkPalette(image: BufferedImage): ArtworkPalette {
    class Bucket { var r = 0L; var g = 0L; var b = 0L; var n = 0 }
    val buckets = HashMap<Int, Bucket>()
    val stepY = (image.height / 96).coerceAtLeast(1); val stepX = (image.width / 96).coerceAtLeast(1)
    for (y in 0 until image.height step stepY) for (x in 0 until image.width step stepX) {
        val argb = image.getRGB(x, y)
        if ((argb ushr 24) < 128) continue
        val r = (argb ushr 16) and 255; val g = (argb ushr 8) and 255; val b = argb and 255
        val hsl = rgbToHsl(argb)
        if (hsl[2] < .05f || hsl[2] > .95f) continue
        val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
        val bucket = buckets.getOrPut(key) { Bucket() }
        bucket.r += r; bucket.g += g; bucket.b += b; bucket.n++
    }
    if (buckets.isEmpty()) return DefaultArtworkPalette
    // Merge 5-bit cells into coarser clusters so one colour's shades count together.
    val clusters = HashMap<Int, Bucket>()
    buckets.forEach { (key, cell) ->
        val coarse = (((key shr 10) shr 1) shl 8) or ((((key shr 5) and 31) shr 1) shl 4) or ((key and 31) shr 1)
        val c = clusters.getOrPut(coarse) { Bucket() }
        c.r += cell.r; c.g += cell.g; c.b += cell.b; c.n += cell.n
    }
    val swatches = clusters.values.sortedByDescending { it.n }.take(24).map {
        Swatch((0xFF shl 24) or (((it.r / it.n).toInt()) shl 16) or (((it.g / it.n).toInt()) shl 8) or (it.b / it.n).toInt(), it.n)
    }
    val totalPop = swatches.sumOf { it.population }.toFloat()
    val colourful = swatches.filter { it.hsl[1] > .15f && it.chroma > .08f }
    if (colourful.isEmpty()) {
        val grey = Color(swatches.first().rgb)
        return ArtworkPalette(grey, grey, grey, isMonochrome = true, glowColors = List(6) { grey })
    }
    val colourfulPop = colourful.sumOf { it.population }.toFloat()
    val scored = colourful.sortedByDescending {
        val lumScore = (1f - kotlin.math.abs(it.hsl[2] - .52f) * 1.5f).coerceIn(.15f, 1f)
        val satScore = (it.hsl[1] / .7f).coerceIn(.2f, 1f)
        val chromaScore = (it.chroma / .45f).coerceIn(.2f, 1f)
        it.population / colourfulPop * .55f + it.population / totalPop * .15f + satScore * .15f + chromaScore * .1f + lumScore * .05f
    }
    val primary = scored.firstOrNull { it.population / colourfulPop >= .08f || it.population / totalPop >= .03f } ?: scored.first()
    val secondary = scored.firstOrNull { hueDifference(it.hsl[0], primary.hsl[0]) >= 25f } ?: scored.getOrNull(1) ?: primary
    val tertiary = scored.firstOrNull { hueDifference(it.hsl[0], primary.hsl[0]) >= 20f && hueDifference(it.hsl[0], secondary.hsl[0]) >= 20f }
        ?: scored.getOrNull(2) ?: secondary
    val used = mutableSetOf<Int>()
    val glow = (listOf(VIBRANT, LIGHT_VIBRANT, DARK_VIBRANT, MUTED, LIGHT_MUTED, DARK_MUTED).mapNotNull { pick(it, swatches, used)?.rgb } +
        listOf(primary.rgb, secondary.rgb, tertiary.rgb)).distinct().map { Color(it) }
    return ArtworkPalette(Color(primary.rgb), Color(secondary.rgb), Color(tertiary.rgb), glowColors = glow)
}
