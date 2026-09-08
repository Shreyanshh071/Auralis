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
)

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

        // 1. Instant hit from memory cache
        val cached = getCached(trackId) ?: getCached(track.thumbnail)
        if (cached != null) {
            extractionJob?.cancel()
            _currentPalette.value = cached
            return
        }

        // 2. Off-main-thread extraction
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
        // Prioritize artworkUrl first: it is already in Coil's in-memory cache from the UI,
        // giving instant 0ms extraction without triggering network fetches.
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
                    // ViVi & NomaTune extraction pipeline:
                    // 1. maximumColorCount(8): quantizes into 8 dominant color fields,
                    // absorbing text/logos/minor artifacts so true artwork color dominates.
                    val palette = withContext(Dispatchers.Default) {
                        Palette.from(bitmap)
                            .maximumColorCount(8)
                            .resizeBitmapArea(128 * 128)
                            .generate()
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
                        return@withContext defaultPalette
                    }

                    val rawSeedColor = Color(seedRgb)
                    val hct = rawSeedColor.toHct()
                    val chroma = hct.chroma
                    val tone = hct.tone
                    // Off-white / light-gray artwork has high tone (>80) and low chroma (<12),
                    // and dark/monochrome artwork has low tone (<18) and low chroma (<12).
                    val isMonochrome = chroma < 7.0 || (chroma < 12.0 && (tone > 80.0 || tone < 18.0))

                    val extracted = if (isMonochrome) {
                        // Genuine monochrome/grayscale artwork (e.g. Break The Night With Colour):
                        // Preserve the actual artwork's dominant grayscale luminance without injecting blue.
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

                        // ViVi extraction of swatches for Glow Motion - strictly authentic artwork colors
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
