package com.auralis.music.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
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
import com.auralis.music.ui.components.getOptimizedThumbnailUrl
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
    val glowColors: List<Color> = emptyList(),
    val isPlaceholder: Boolean = false
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
        return synchronized(memoryCache) {
            val item = memoryCache[key]
            if (item != null && !item.isPlaceholder) item else null
        }
    }

    /**
     * Instantly retrieves the palette from memory cache or Coil's preloaded bitmap cache.
     * Returns in <1ms without hitting disk or network, enabling zero-frame-delay background updates.
     */
    fun getCachedOrFastExtract(context: Context, trackId: String?, thumbnailUrl: String?): ArtworkPalette? {
        if (!trackId.isNullOrBlank()) {
            getCached(trackId)?.takeIf { !it.isDefault && !it.isPlaceholder }?.let { return it }
        }
        val effectiveThumb = when {
            !thumbnailUrl.isNullOrBlank() -> thumbnailUrl
            !trackId.isNullOrBlank() -> {
                val localArt = com.auralis.music.data.download.AuralisDownloadManager.getDownloadedArtworkFile(trackId)
                if (localArt != null && localArt.exists() && localArt.length() > 500) {
                    Uri.fromFile(localArt).toString()
                } else null
            }
            else -> null
        }
        if (!effectiveThumb.isNullOrBlank()) {
            getCached(effectiveThumb)?.takeIf { !it.isDefault && !it.isPlaceholder }?.let { return it }
            getOptimizedThumbnailUrl(effectiveThumb)?.let { opt ->
                getCached(opt)?.takeIf { !it.isDefault && !it.isPlaceholder }?.let { return it }
            }
        }
        if (effectiveThumb.isNullOrBlank()) return null

        try {
            val memCache = context.imageLoader.memoryCache
            if (memCache != null) {
                val candidateKeys = listOfNotNull(
                    coil.memory.MemoryCache.Key(effectiveThumb),
                    getOptimizedThumbnailUrl(effectiveThumb)?.let { coil.memory.MemoryCache.Key(it) },
                    getHighResArtworkUrl(effectiveThumb)?.let { coil.memory.MemoryCache.Key(it) }
                )
                for (cacheKey in candidateKeys) {
                    val value = memCache[cacheKey]
                    val bmp = value?.bitmap
                    if (bmp != null && !bmp.isRecycled) {
                        try {
                            val palette = extractFromBitmap(bmp)
                            if (!palette.isDefault && !palette.isPlaceholder) {
                                if (!trackId.isNullOrBlank()) put(trackId, palette)
                                put(effectiveThumb, palette)
                                getOptimizedThumbnailUrl(effectiveThumb)?.let { put(it, palette) }
                                if (currentTrackId == trackId) {
                                    _currentPalette.value = palette
                                }
                                return palette
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Synchronously returns an authoritative, distinct ArtworkPalette for the track.
     * Checks memory cache, Coil's preloaded bitmap cache, dominant color, and finally
     * a deterministic vibrant seed derived from the track. Guarantees immediate color
     * resolution on Frame 0 with zero delay or freeze.
     */
    fun getOrCreatePalette(context: Context, track: Track?): ArtworkPalette {
        if (track == null) return defaultPalette
        val trackId = track.id.ifBlank { track.thumbnail }
        getCachedOrFastExtract(context, trackId, track.thumbnail)?.let { return it }

        if (track.dominantColor != null && track.dominantColor != 0) {
            val color = Color(track.dominantColor)
            val fallback = ArtworkPalette(
                primary = color,
                secondary = color,
                tertiary = color,
                seedColor = color,
                glowColors = listOf(color, color, color),
                isPlaceholder = false
            )
            if (trackId.isNotBlank()) put(trackId, fallback)
            return fallback
        }

        // Return current active palette as a temporary placeholder so UI holds existing color
        // without flashing a synthetic hash color!
        val cur = _currentPalette.value
        if (!cur.isDefault) {
            return cur.copy(isPlaceholder = true)
        }

        val hash = kotlin.math.abs((track.id.ifBlank { track.title }).hashCode())
        val hue = (hash % 360).toFloat()
        val hsv = floatArrayOf(hue, 0.70f, 0.60f)
        val argb = android.graphics.Color.HSVToColor(hsv)
        val seed = Color(argb)
        return ArtworkPalette(
            primary = seed,
            secondary = seed,
            tertiary = seed,
            seedColor = seed,
            glowColors = listOf(seed, seed, seed),
            isPlaceholder = true
        )
    }

    fun put(key: String, palette: ArtworkPalette) {
        if (key.isBlank() || palette.isPlaceholder) return
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

    private fun isNeutralColor(hsl: FloatArray, chroma: Double): Boolean {
        val sat = hsl[1]
        val lum = hsl[2]
        // 1. Low saturation or low chroma (grayscale, charcoal, slate)
        if (chroma < 9.0 || sat < 0.10f) return true
        // 2. High luminance (white, off-white, light cream, parchment, paper)
        if (lum > 0.82f && (chroma < 18.0 || sat < 0.22f)) return true
        // 3. Very high luminance (pure white / near white canvas background)
        if (lum > 0.90f) return true
        // 4. Low luminance (pitch black, dark obsidian, deep charcoal)
        if (lum < 0.12f && (chroma < 18.0 || sat < 0.22f)) return true
        // 5. Very low luminance (near black)
        if (lum < 0.06f) return true
        return false
    }

    private fun hueDifference(hue1: Float, hue2: Float): Float {
        val diff = kotlin.math.abs(hue1 - hue2) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    /**
     * Extracts dynamic colors from a Bitmap directly and synchronously.
     * Analyzes overall artwork composition: prevents white/light-themed artworks with tiny colored text
     * from skewing red, and extracts diverse, vibrant swatches across the full color spectrum.
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
                .maximumColorCount(24)
                .resizeBitmapArea(128 * 128)
                .generate()
        } catch (_: Exception) {
            return defaultPalette
        }

        if (palette.swatches.isEmpty()) {
            return defaultPalette
        }

        val totalPop = palette.swatches.sumOf { it.population }.coerceAtLeast(1)

        data class SwatchData(
            val swatch: Palette.Swatch,
            val chroma: Double,
            val isNeutral: Boolean
        )

        val allSwatches = palette.swatches.map { swatch ->
            val chroma = Color(swatch.rgb).toHct().chroma
            val neutral = isNeutralColor(swatch.hsl, chroma)
            SwatchData(swatch, chroma, neutral)
        }

        val neutralPop = allSwatches.filter { it.isNeutral }.sumOf { it.swatch.population }
        val neutralRatio = neutralPop.toFloat() / totalPop

        val nonNeutralSwatches = allSwatches.filter { !it.isNeutral }
        val maxNonNeutralPop = nonNeutralSwatches.maxOfOrNull { it.swatch.population } ?: 0
        val maxNonNeutralRatio = maxNonNeutralPop.toFloat() / totalPop

        // Determine if artwork is predominantly monochrome/neutral/white-themed.
        // If neutral pixels account for >= 75% and any non-neutral accent is minor (< 20%, e.g. title handwriting,
        // barcode, tiny logo or sticker), or if there are no colorful pixels at all, treat as monochrome.
        val isMonochromeArtwork = nonNeutralSwatches.isEmpty() ||
                (neutralRatio >= 0.75f && maxNonNeutralRatio < 0.20f) ||
                (neutralRatio >= 0.88f)

        val extracted = if (isMonochromeArtwork) {
            // Check whether the dominant neutral tone is light (white, cream, paper) or dark (black, obsidian)
            val dominantNeutral = allSwatches.filter { it.isNeutral }.maxByOrNull { it.swatch.population }?.swatch
                ?: palette.dominantSwatch
            val dominantLum = dominantNeutral?.hsl?.get(2) ?: 0.5f
            val isWhiteOrLightCover = dominantLum > 0.55f

            if (isWhiteOrLightCover) {
                // White / Light Monochrome (e.g. Sidney Gish - No Dogs Allowed, Beatles White Album):
                // Clean platinum / silver accents, crisp neutral dark base, zero reddish or salmon tint.
                val platinumPrimary = Color(0xFFE2E8F0)
                val slateSecondary = Color(0xFF94A3B8)
                val charcoalTertiary = Color(0xFF64748B)
                val neutralSeed = Color(0xFFE2E8F0)

                ArtworkPalette(
                    primary = platinumPrimary,
                    secondary = slateSecondary,
                    tertiary = charcoalTertiary,
                    seedColor = neutralSeed,
                    isMonochrome = true,
                    glowColors = listOf(
                        Color(0xFFF1F5F9),
                        Color(0xFFE2E8F0),
                        Color(0xFFCBD5E1),
                        Color(0xFF94A3B8),
                        Color(0xFF64748B),
                        Color(0xFF475569)
                    )
                )
            } else {
                // Dark / Mid Monochrome (e.g. NEFFEX Fight Back, black covers)
                monochromePalette
            }
        } else {
            // Colorful artwork: balanced, diversity-preserving scoring across all hues
            val colorfulPop = nonNeutralSwatches.sumOf { it.swatch.population }.coerceAtLeast(1)

            val scoredCandidates = nonNeutralSwatches.map { item ->
                val swatch = item.swatch
                val sat = swatch.hsl[1]
                val lum = swatch.hsl[2]
                val popRatio = swatch.population.toFloat() / totalPop
                val colorfulPopRatio = swatch.population.toFloat() / colorfulPop

                // Ideal UI accent luminance: ~0.40..0.70
                val lumScore = (1f - kotlin.math.abs(lum - 0.52f) * 1.5f).coerceIn(0.15f, 1f)
                val satScore = (sat / 0.70f).coerceIn(0.2f, 1f)
                val chromaScore = (item.chroma.toFloat() / 45f).coerceIn(0.2f, 1f)

                // Primary score prioritizes the visual identity of the album (colorfulPopRatio + total popRatio).
                // Prevents incidental 1-2% accents (like barcode labels, tiny red stripes) from overpowering dominant album colors.
                val score = (colorfulPopRatio * 0.55f) + (popRatio * 0.15f) + (satScore * 0.15f) + (chromaScore * 0.10f) + (lumScore * 0.05f)
                item to score
            }.sortedByDescending { it.second }

            // Primary Seed: prefer candidates with real visual presence (at least 8% of colorful pixels, or at least 3% of total artwork)
            val primaryCandidates = scoredCandidates.filter { candidate ->
                val pop = candidate.first.swatch.population
                (pop.toFloat() / colorfulPop >= 0.08f) || (pop.toFloat() / totalPop >= 0.03f)
            }.ifEmpty { scoredCandidates }

            val primaryData = primaryCandidates.firstOrNull()?.first ?: scoredCandidates.firstOrNull()?.first
            val primarySwatch = primaryData?.swatch
                ?: palette.vibrantSwatch
                ?: palette.dominantSwatch
                ?: palette.mutedSwatch
                ?: palette.swatches.maxByOrNull { it.population }!!

            val primaryColor = Color(primarySwatch.rgb)
            val primaryHue = primarySwatch.hsl[0]

            // Secondary: pick highest-scoring candidate with a harmonically distinct hue (>= 25 degrees)
            val distinctSecondaryData = scoredCandidates.firstOrNull { candidate ->
                hueDifference(candidate.first.swatch.hsl[0], primaryHue) >= 25f
            }?.first

            val secondarySwatch = distinctSecondaryData?.swatch
                ?: palette.mutedSwatch
                ?: palette.darkVibrantSwatch
                ?: palette.lightVibrantSwatch
                ?: scoredCandidates.getOrNull(1)?.first?.swatch
                ?: primarySwatch

            val secondaryColor = Color(secondarySwatch.rgb)
            val secondaryHue = secondarySwatch.hsl[0]

            // Tertiary: pick highest-scoring candidate distinct from both primary and secondary (>= 20 degrees)
            val distinctTertiaryData = scoredCandidates.firstOrNull { candidate ->
                val hue = candidate.first.swatch.hsl[0]
                hueDifference(hue, primaryHue) >= 20f && hueDifference(hue, secondaryHue) >= 20f
            }?.first

            val tertiarySwatch = distinctTertiaryData?.swatch
                ?: palette.lightVibrantSwatch
                ?: palette.darkMutedSwatch
                ?: scoredCandidates.getOrNull(2)?.first?.swatch
                ?: secondarySwatch

            val tertiaryColor = Color(tertiarySwatch.rgb)

            // Collect rich vibrant and muted swatches for dynamic glow
            val glowSwatches = listOfNotNull(
                palette.vibrantSwatch?.rgb,
                palette.lightVibrantSwatch?.rgb,
                palette.darkVibrantSwatch?.rgb,
                palette.mutedSwatch?.rgb,
                palette.lightMutedSwatch?.rgb,
                palette.darkMutedSwatch?.rgb,
                primarySwatch.rgb,
                secondarySwatch.rgb,
                tertiarySwatch.rgb
            ).distinct().map { Color(it) }

            ArtworkPalette(
                primary = primaryColor,
                secondary = secondaryColor,
                tertiary = tertiaryColor,
                seedColor = primaryColor,
                isMonochrome = false,
                glowColors = if (glowSwatches.isNotEmpty()) glowSwatches else listOf(primaryColor, secondaryColor, tertiaryColor)
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
        if (track == null) {
            currentTrackId = null
            extractionJob?.cancel()
            _currentPalette.value = defaultPalette
            return
        }

        val localArt = com.auralis.music.data.download.AuralisDownloadManager.getDownloadedArtworkFile(track.id)
        val effectiveThumbnail = when {
            !track.thumbnail.isNullOrBlank() -> track.thumbnail
            localArt != null && localArt.exists() && localArt.length() > 500 -> Uri.fromFile(localArt).toString()
            else -> com.auralis.music.data.network.ArtworkResolver.getArtwork(track) ?: ""
        }

        if (effectiveThumbnail.isBlank()) {
            currentTrackId = null
            extractionJob?.cancel()
            _currentPalette.value = defaultPalette
            return
        }

        val trackId = track.id.ifBlank { effectiveThumbnail }
        if (trackId == currentTrackId && _currentPalette.value != defaultPalette && !_currentPalette.value.isPlaceholder) return
        currentTrackId = trackId

        // 1. Instant hit from internal LRU memory cache
        val cached = getCached(trackId)
            ?: getCached(effectiveThumbnail)
            ?: getOptimizedThumbnailUrl(effectiveThumbnail)?.let { getCached(it) }
        if (cached != null && !cached.isPlaceholder) {
            extractionJob?.cancel()
            _currentPalette.value = cached
            return
        }

        // 2. Check Coil's in-memory cache for preloaded bitmap → extract in background
        // ── PERF FIX #2: Previously this called extractFromBitmap() synchronously on the ──
        // ── caller's thread (often Main). Now offloaded to Dispatchers.IO. ──
        var coilBitmap: Bitmap? = null
        try {
            val memCache = context.imageLoader.memoryCache
            if (memCache != null) {
                val candidateKeys = listOfNotNull(
                    coil.memory.MemoryCache.Key(effectiveThumbnail),
                    getOptimizedThumbnailUrl(effectiveThumbnail)?.let { coil.memory.MemoryCache.Key(it) },
                    getHighResArtworkUrl(effectiveThumbnail)?.let { coil.memory.MemoryCache.Key(it) }
                )
                for (cacheKey in candidateKeys) {
                    val value = memCache[cacheKey]
                    val bmp = value?.bitmap
                    if (bmp != null && !bmp.isRecycled) {
                        coilBitmap = bmp
                        break
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Off-main-thread extraction (from Coil cache hit or network fetch)
        // Keep the current palette active during async extraction so Compose animateColorAsState
        // can smoothly morph colors from the previous song to the new song once ready.
        extractionJob?.cancel()
        extractionJob = cacheScope.launch {
            val palette = if (coilBitmap != null && !coilBitmap.isRecycled) {
                extractFromBitmap(coilBitmap)
            } else {
                extractPalette(context, trackId, effectiveThumbnail)
            }
            if (!palette.isDefault && !palette.isPlaceholder && currentTrackId == trackId) {
                put(trackId, palette)
                put(effectiveThumbnail, palette)
                getOptimizedThumbnailUrl(effectiveThumbnail)?.let { put(it, palette) }
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
        getCached(key)?.takeIf { !it.isPlaceholder }?.let { return it }
        getCached(artworkUrl)?.takeIf { !it.isPlaceholder }?.let { return it }
        val optUrl = getOptimizedThumbnailUrl(artworkUrl)
        if (optUrl != null) {
            getCached(optUrl)?.takeIf { !it.isPlaceholder }?.let { return it }
        }

        val targetUrl = getHighResArtworkUrl(artworkUrl)
        val urlCandidates = mutableListOf<String>()
        if (optUrl != null && optUrl != artworkUrl) {
            urlCandidates.add(optUrl)
        }
        urlCandidates.add(artworkUrl)
        if (targetUrl != null && targetUrl != artworkUrl && !urlCandidates.contains(targetUrl)) {
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
                    if (optUrl != null) put(optUrl, extracted)
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
