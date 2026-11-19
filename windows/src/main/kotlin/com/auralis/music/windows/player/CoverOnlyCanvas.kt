package com.auralis.music.windows.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.auralis.music.util.Log
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import okhttp3.Request
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Some Spotify Canvases are only the album cover pasted in the middle of a blurred copy of itself
 * (e.g. The Neighbourhood's "Softcore"). They pass the 9:16 stage check, but on the lyrics stage
 * they just show a cropped cover, so they are treated as no Canvas and the animated cover is used.
 * Same search and thresholds as the phone (android ui/player/CoverOnlyCanvas.kt).
 */
internal object CoverOnlyCanvas {
    /** Average over the sampled frames at which the Canvas is only the cover (1 = identical). */
    const val COVER_AVERAGE = 0.5
    /** A first frame this low is a real moving Canvas: no more frames are read. */
    const val REAL_FIRST = 0.3
    const val FRAME_WIDTH = 54
    const val FRAME_HEIGHT = 96
    const val COVER_SIDE = 32

    /**
     * Measured against Spotify's art: a cover pasted on a blur scores 0.75-0.94 on every sampled
     * frame (Softcore); a real moving Canvas 0.08-0.10. Samples are averaged, so one low frame
     * does not decide it.
     */
    fun isCoverOnly(scores: List<Double>): Boolean =
        scores.size >= 2 && scores.average() >= COVER_AVERAGE

    /** Best correlation of the cover, centred horizontally at 60-100% of the frame width, near the middle. */
    fun bestMatch(frame: IntArray, frameWidth: Int, frameHeight: Int,
                  cover: IntArray, coverWidth: Int, coverHeight: Int): Double {
        if (frameWidth < 8 || frameHeight < 8 || coverWidth < 4 || coverHeight < 4) return 0.0
        if (frame.size < frameWidth * frameHeight || cover.size < coverWidth * coverHeight) return 0.0
        var best = -1.0
        for (size in (frameWidth * .6).roundToInt()..minOf(frameWidth, frameHeight)) {
            val centreY = (frameHeight - size) / 2
            val spread = (frameHeight * .25).roundToInt()
            val x0 = (frameWidth - size) / 2
            for (y0 in (centreY - spread).coerceAtLeast(0)..(centreY + spread).coerceAtMost(frameHeight - size)) {
                best = maxOf(best, correlation(frame, frameWidth, x0, y0, size, cover, coverWidth, coverHeight))
                if (best >= .97) return best
            }
        }
        return best
    }

    private fun correlation(frame: IntArray, frameWidth: Int, x0: Int, y0: Int, size: Int,
                            cover: IntArray, coverWidth: Int, coverHeight: Int): Double {
        var n = 0
        var sumA = 0.0; var sumB = 0.0; var sumAA = 0.0; var sumBB = 0.0; var sumAB = 0.0
        for (y in 0 until size) for (x in 0 until size) {
            val a = frame[(y0 + y) * frameWidth + (x0 + x)]
            val b = cover[(y * coverHeight / size) * coverWidth + (x * coverWidth / size)]
            for (shift in intArrayOf(16, 8, 0)) {
                val va = ((a shr shift) and 255).toDouble()
                val vb = ((b shr shift) and 255).toDouble()
                sumA += va; sumB += vb; sumAA += va * va; sumBB += vb * vb; sumAB += va * vb; n++
            }
        }
        val cov = sumAB - sumA * sumB / n
        val varA = sumAA - sumA * sumA / n
        val varB = sumBB - sumB * sumB / n
        if (varA <= 1e-6 || varB <= 1e-6) return 0.0
        return cov / sqrt(varA * varB)
    }

    // Verdicts persist, so a Canvas is only ever checked once and a cover-only one never plays again.
    // v2: compared against Spotify's own art; v1 verdicts (YouTube thumbnails) are not trusted.
    private val verdicts by lazy {
        PropertiesFileKeyValueStore(File(desktopAppDataDir(), "cache/spotify-canvas/cover-only-v2.properties"), synchronous = true)
    }

    /** true = cover-only, false = a real Canvas, null = not checked yet. */
    fun verdict(spotifyId: String): Boolean? = verdicts.getString(spotifyId, "")?.takeIf { it.isNotEmpty() }?.toBoolean()

    fun remember(spotifyId: String, coverOnly: Boolean) {
        runCatching { verdicts.putStrings(mapOf(spotifyId to coverOnly.toString())) }
        if (coverOnly) Log.i("AnimatedCovers", "Spotify Canvas $spotifyId only shows the cover; using the animated cover")
    }

    /** A video frame shrunk to [FRAME_WIDTH] x [FRAME_HEIGHT] ARGB. */
    fun framePixels(frame: ImageBitmap): IntArray = shrink(frame.toAwtImage(), FRAME_WIDTH, FRAME_HEIGHT, square = false)

    private val NETWORK get() = com.auralis.music.data.network.NetworkClientProvider.okHttpClient

    /** Spotify's own album art for a track (what its Canvas pastes in), from the public oEmbed. */
    private fun spotifyArt(spotifyId: String): String? = runCatching {
        if (!Regex("[A-Za-z0-9]{22}").matches(spotifyId)) return null
        val request = Request.Builder()
            .url("https://open.spotify.com/oembed?url=https://open.spotify.com/track/$spotifyId").build()
        NETWORK.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            org.json.JSONObject(response.body?.string().orEmpty()).optString("thumbnail_url").takeIf { it.startsWith("https://") }
        }
    }.getOrNull()

    /**
     * The cover the Canvas pastes in, as [COVER_SIDE]² ARGB centre-cropped to a square: Spotify's own
     * album art for [spotifyId], else [artworkUrl]; null if neither loads.
     */
    fun coverPixels(spotifyId: String, artworkUrl: String?): IntArray? =
        spotifyArt(spotifyId)?.let(::loadPixels) ?: loadPixels(artworkUrl)

    private fun loadPixels(artworkUrl: String?): IntArray? = runCatching {
        val url = artworkUrl?.takeIf { it.isNotBlank() } ?: return null
        val bytes = com.auralis.music.ui.components.DesktopArtworkRenderer.decodeDataUri(url)
            ?: if (File(url).isFile) File(url).readBytes()
            else if (url.startsWith("http")) com.auralis.music.data.network.NetworkClientProvider.okHttpClient
                .newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes() else null
                }
            else null
        bytes ?: return null
        val image = org.jetbrains.skia.Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap().toAwtImage() }
        shrink(image, COVER_SIDE, COVER_SIDE, square = true)
    }.getOrNull()

    private fun shrink(source: java.awt.image.BufferedImage, width: Int, height: Int, square: Boolean): IntArray {
        val side = minOf(source.width, source.height)
        val crop = if (square) source.getSubimage((source.width - side) / 2, (source.height - side) / 2, side, side) else source
        val small = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(crop, 0, 0, width, height, null)
            dispose()
        }
        return small.getRGB(0, 0, width, height, null, 0, width)
    }
}
