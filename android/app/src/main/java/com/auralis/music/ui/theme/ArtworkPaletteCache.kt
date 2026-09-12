package com.auralis.music.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import coil.imageLoader
import com.materialkolor.ktx.toHct
import com.materialkolor.score.Score
import coil.request.ImageRequest
import coil.size.Scale
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.getHighResArtworkUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data class representing harmonious colors extracted from album artwork.
 */
data class ArtworkPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val seedColor: Color = primary,
    val isMonochrome: Boolean = false,
    val glowColors: List<Color> = emptyList()
) {
    val isDefault: Boolean
        get() = this == ArtworkPaletteCache.defaultPalette
}

/**
 * High-performance thread-safe LRU Cache, Reactive StateFlow & Async Extractor for album artwork palettes.
 * 
 * - Single shared extraction pipeline shared by App Dynamic Theme, Mini-Player, and Now-Playing Modal.
 * - Downsamples images to 128x128 for ultra-fast (<2ms) CPU palette generation.
 * - Robust dual-URL fallback (studio HD with raw URL fallback).
 * - Multi-criteria swatch scoring (vibrancy, saturation, population, luminance).
 * - Filters out monochrome borders (pure white/black) to extract authentic artwork hues.
 */
object ArtworkPaletteCache {

    private const val MAX_ENTRIES = 100
    private val memoryCache = object : LinkedHashMap<String, ArtworkPalette>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkPalette>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    // Default palette for ambient UI when no image is loaded
    val defaultPalette = ArtworkPalette(
        primary = Color(0xFF161619),   // Neutral Slate Charcoal
        secondary = Color(0xFF121215), // Deep Midnight
        tertiary = Color(0xFF0E0E10),  // Obsidian Base
        seedColor = Color.Unspecified, // Pure unspecified seed - prevents artificial blue HCT tints!
        isMonochrome = true,
        glowColors = listOf(
            Color(0xFF202024),
            Color(0xFF161619),
            Color(0xFF121215),
            Color(0xFF0E0E10),
            Color(0xFF1A1A1E),
            Color(0xFF0A0A0C)
        )
    )

    // Sleek monochrome palette for black & white / grayscale album artwork (e.g. NEFFEX Fight Back)
    val monochromePalette = ArtworkPalette(
        primary = Color(0xFFE0E0E0),   // Pure neutral silver accent
        secondary = Color(0xFF9E9E9E), // Neutral mid-gray
        tertiary = Color(0xFF616161),  // Neutral dark charcoal
        seedColor = Color(0xFF808080), // Pure neutral mid-gray seed (Hue=0, Chroma=0)
        isMonochrome = true,
        glowColors = listOf(
            Color(0xFFE2E2E2),
            Color(0xFFB8B8B8),
            Color(0xFF8E8E8E),
            Color(0xFF686868),
            Color(0xFF484848),
            Color(0xFFA2A2A2)
        )
    )

    private val _currentPalette = MutableStateFlow(defaultPalette)
    val currentPalette: StateFlow<ArtworkPalette> = _currentPalette.asStateFlow()

    private var currentTrackId: String? = null
    private var extractionJob: Job? = null
    private val cacheScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isCurrentTrack(trackId: String?): Boolean {
        if (trackId.isNullOrBlank() || currentTrackId.isNullOrBlank()) return false
        return currentTrackId == trackId
    }

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
     * Resets the active palette back to the neutral default palette on startup or queue reset.
     */
    fun resetToDefault() {
        currentTrackId = null
        extractionJob?.cancel()
        _currentPalette.value = defaultPalette
    }

    /**
     * Extracts dynamic colors from a Bitmap directly and synchronously.
     * Handles hardware bitmaps safely and performs ultra-fast 64x64 palette quantization (<2ms).
     */
    fun extractFromBitmap(bitmap: Bitmap): ArtworkPalette {
        if (bitmap.isRecycled) return defaultPalette

        val softwareBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE) {
            try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
            } catch (_: Exception) {
                bitmap
            }
        } else {
            bitmap
        }

        val palette = try {
            Palette.from(softwareBitmap)
                .maximumColorCount(8)
                .resizeBitmapArea(64 * 64)
                .generate()
        } catch (_: Exception) {
            return defaultPalette
        }

        val colorsToPopulation = palette.swatches.associate { it.rgb to it.population }
        val scoredColors = Score.score(colorsToPopulation)

        val seedRgb = scoredColors.firstOrNull()
            ?: palette.vibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: palette.darkVibrantSwatch?.rgb
            ?: palette.swatches.maxByOrNull { it.population }?.rgb

        if (seedRgb == null) {
            return defaultPalette
        }

        val rawSeedColor = Color(seedRgb)
        val hct = rawSeedColor.toHct()
        val chroma = hct.chroma
        val tone = hct.tone
        val isMonochrome = chroma < 7.0 || (chroma < 12.0 && (tone > 80.0 || tone < 18.0))

        val extracted = if (isMonochrome) {
            val r = (seedRgb shr 16) and 0xFF
            val g = (seedRgb shr 8) and 0xFF
            val b = seedRgb and 0xFF
            val grayVal = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
            val neutralSeed = Color(grayVal, grayVal, grayVal)

            ArtworkPalette(
                primary = Color(0xFFE0E0E0),
                secondary = Color(0xFF9E9E9E),
                tertiary = Color(0xFF616161),
                seedColor = neutralSeed,
                isMonochrome = true,
                glowColors = listOf(
                    Color(0xFFE2E2E2),
                    Color(0xFFB8B8B8),
                    Color(0xFF8E8E8E),
                    Color(0xFF686868),
                    Color(0xFF484848),
                    Color(0xFFA2A2A2)
                )
            )
        } else {
            val secRgb = scoredColors.getOrNull(1)
                ?: palette.mutedSwatch?.rgb
                ?: palette.darkVibrantSwatch?.rgb
                ?: seedRgb

            val tertRgb = scoredColors.getOrNull(2)
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.darkMutedSwatch?.rgb
                ?: secRgb

            val secondaryColor = Color(secRgb)
            val tertiaryColor = Color(tertRgb)

            val rawGlowList = listOfNotNull(
                palette.vibrantSwatch?.rgb?.let { Color(it) },
                palette.lightVibrantSwatch?.rgb?.let { Color(it) },
                palette.darkVibrantSwatch?.rgb?.let { Color(it) },
                palette.mutedSwatch?.rgb?.let { Color(it) },
                palette.lightMutedSwatch?.rgb?.let { Color(it) },
                palette.darkMutedSwatch?.rgb?.let { Color(it) }
            ).distinct()

            val actualGlowColors = if (rawGlowList.isNotEmpty()) {
                rawGlowList
            } else {
                listOf(rawSeedColor, secondaryColor, tertiaryColor).distinct()
            }

            ArtworkPalette(
                primary = rawSeedColor,
                secondary = secondaryColor,
                tertiary = tertiaryColor,
                seedColor = rawSeedColor,
                isMonochrome = false,
                glowColors = actualGlowColors
            )
        }

        if (softwareBitmap != bitmap && !softwareBitmap.isRecycled) {
            try { softwareBitmap.recycle() } catch (_: Exception) {}
        }

        return extracted
    }

    /**
     * Updates the shared active artwork palette for the currently playing track.
     * Checks memory cache first for 0ms response time, or initiates async extraction off the main thread.
     */
    fun updateForTrack(context: Context, track: Track?) {
        if (track == null || track.thumbnail.isBlank()) {
            currentTrackId = null
            extractionJob?.cancel()
            _currentPalette.value = defaultPalette
            return
        }

        val trackId = track.id.ifBlank { track.thumbnail }
        if (trackId == currentTrackId && _currentPalette.value != defaultPalette) return
        currentTrackId = trackId

        // 1. Instant hit from internal LRU memory cache
        val cached = getCached(trackId) ?: getCached(track.thumbnail)
        if (cached != null) {
            extractionJob?.cancel()
            _currentPalette.value = cached
            return
        }

        // 2. Synchronous check: inspect Coil's in-memory cache for preloaded bitmap
        try {
            val memCache = context.imageLoader.memoryCache
            if (memCache != null) {
                val candidateKeys = listOfNotNull(
                    coil.memory.MemoryCache.Key(track.thumbnail),
                    getHighResArtworkUrl(track.thumbnail)?.let { coil.memory.MemoryCache.Key(it) }
                )
                for (cacheKey in candidateKeys) {
                    val value = memCache[cacheKey]
                    val bmp = value?.bitmap
                    if (bmp != null && !bmp.isRecycled) {
                        val palette = extractFromBitmap(bmp)
                        put(trackId, palette)
                        put(track.thumbnail, palette)
                        extractionJob?.cancel()
                        _currentPalette.value = palette
                        return
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Off-main-thread extraction
        // Keep the current palette active during async extraction so Compose animateColorAsState
        // can smoothly morph colors from the previous song to the new song once ready.
        extractionJob?.cancel()
        extractionJob = cacheScope.launch {
            val palette = extractPalette(context, trackId, track.thumbnail)
            if (currentTrackId == trackId) {
                _currentPalette.value = palette
            }
        }
    }

    /**
     * Extracts dynamic colors from artwork asynchronously with multi-candidate image loading.
     */
    suspend fun extractPalette(context: Context, key: String, artworkUrl: String): ArtworkPalette {
        if (artworkUrl.isBlank()) return defaultPalette

        // 1. Check memory cache first
        getCached(key)?.let { return it }
        getCached(artworkUrl)?.let { return it }

        val targetUrl = getHighResArtworkUrl(artworkUrl)
        val urlCandidates = mutableListOf<String>()
        urlCandidates.add(artworkUrl)
        if (targetUrl != null && targetUrl != artworkUrl) {
            urlCandidates.add(targetUrl)
        }
        if (artworkUrl.contains("i.ytimg.com") || artworkUrl.contains("img.youtube.com")) {
            val hqDefault = artworkUrl.replace("hq720.jpg", "hqdefault.jpg")
                .replace("maxresdefault.jpg", "hqdefault.jpg")
            if (!urlCandidates.contains(hqDefault)) urlCandidates.add(hqDefault)
            val mqDefault = artworkUrl.replace("hq720.jpg", "mqdefault.jpg")
            if (!urlCandidates.contains(mqDefault)) urlCandidates.add(mqDefault)
        }

        return withContext(Dispatchers.IO) {
            try {
                val imageLoader = context.imageLoader
                var bitmap: Bitmap? = null

                for (candidate in urlCandidates) {
                    try {
                        val req = ImageRequest.Builder(context)
                            .data(candidate)
                            .size(128, 128)
                            .allowHardware(false)
                            .build()
                        val res = imageLoader.execute(req)
                        val drawable = res.drawable
                        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                            bitmap = drawable.bitmap
                            break
                        }
                    } catch (_: Exception) {}
                }

                if (bitmap != null && !bitmap.isRecycled) {
                    val extracted = extractFromBitmap(bitmap)
                    put(key, extracted)
                    if (targetUrl != null) put(targetUrl, extracted)
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
}
