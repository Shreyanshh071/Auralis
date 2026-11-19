package com.auralis.music.ui.player

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Some Spotify Canvases are only the album cover pasted in the middle of a blurred copy of itself
 * (e.g. The Neighbourhood's "Softcore"). They pass the 9:16 check, but full-screen they just show
 * the cover again, so they are treated as no Canvas and the animated or still cover is used.
 *
 * Works on small ARGB pixel arrays: a frame (about 54x96) and the cover (about 32x32). The cover is
 * looked for centred horizontally at 60–100% of the frame width, around the vertical middle.
 */
internal object CoverOnlyCanvas {
    /** Correlation at which a frame region clearly is the cover (1 = identical). */
    private const val MATCH = 0.85
    /** Average over the sampled frames at which the Canvas is only the cover. */
    const val COVER_AVERAGE = 0.5
    /** A first frame this low is a real moving Canvas: no more frames are read. */
    const val REAL_FIRST = 0.3

    fun frameShowsCover(frame: IntArray, frameWidth: Int, frameHeight: Int,
                        cover: IntArray, coverWidth: Int, coverHeight: Int): Boolean =
        bestMatch(frame, frameWidth, frameHeight, cover, coverWidth, coverHeight) >= MATCH

    /**
     * Measured against Spotify's art: a cover pasted on a blur scores 0.75-0.94 on every sampled
     * frame (Softcore); a real moving Canvas 0.08-0.10. Samples are averaged, so one low frame
     * does not decide it.
     */
    fun isCoverOnly(scores: List<Double>): Boolean =
        scores.size >= 2 && scores.average() >= COVER_AVERAGE

    fun bestMatch(frame: IntArray, frameWidth: Int, frameHeight: Int,
                  cover: IntArray, coverWidth: Int, coverHeight: Int): Double {
        if (frameWidth < 8 || frameHeight < 8 || coverWidth < 4 || coverHeight < 4) return 0.0
        if (frame.size < frameWidth * frameHeight || cover.size < coverWidth * coverHeight) return 0.0
        var best = -1.0
        val minSize = (frameWidth * .6).roundToInt()
        for (size in minSize..frameWidth) {
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

    private val verdicts = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val NETWORK get() = com.auralis.music.data.network.NetworkClientProvider.okHttpClient

    /**
     * Whether [video] (a file path or https link) is only the cover pasted on a blur, judged from
     * frames at about 1 s and 4 s against Spotify's album art for [spotifyId] (else [fallbackArtwork]).
     * Two frames, so a Canvas that only opens on the cover and then animates is kept.
     * null = could not tell (frames or cover unreadable): the caller may try again on another copy.
     */
    suspend fun check(context: android.content.Context, spotifyId: String, video: String, fallbackArtwork: String?): Boolean? {
        verdicts[spotifyId]?.let { return it }
        val verdict: Boolean? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val art = spotifyArt(spotifyId) ?: fallbackArtwork?.takeIf { it.isNotBlank() }
                val cover = art?.let { loadCover(context, it) }
                if (cover == null) {
                    com.auralis.music.util.Log.w("SpotifyCanvas", "Cover check $spotifyId: no cover to compare")
                    return@withContext null
                }
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    if (video.startsWith("https://")) retriever.setDataSource(video, emptyMap()) else retriever.setDataSource(video)
                    val scores = mutableListOf<Double>()
                    for (at in listOf(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L)) {
                        // Exact frames: these loops have keyframes seconds apart.
                        val frame = retriever.getFrameAtTime(at, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                        if (frame == null) {
                            com.auralis.music.util.Log.w("SpotifyCanvas", "Cover check $spotifyId: no frame at ${at / 1000} s of $video")
                            return@withContext null
                        }
                        val small = android.graphics.Bitmap.createScaledBitmap(frame, 54, 96, true)
                        val pixels = IntArray(54 * 96).also { small.getPixels(it, 0, 54, 0, 0, 54, 96) }
                        scores += bestMatch(pixels, 54, 96, cover, 32, 32)
                        if (scores.size == 1 && scores[0] < REAL_FIRST) break
                    }
                    val coverOnly = isCoverOnly(scores)
                    com.auralis.music.util.Log.i("SpotifyCanvas", "Cover check $spotifyId: scores ${scores.map { "%.2f".format(it) }} -> " +
                        if (coverOnly) "only the cover, using the animated cover" else "real Canvas")
                    coverOnly
                } finally { runCatching { retriever.release() } }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                com.auralis.music.util.Log.w("SpotifyCanvas", "Cover check $spotifyId failed on $video: $error")
                null
            }
        }
        verdict?.let { verdicts[spotifyId] = it }
        return verdict
    }

    /** Spotify's own album art for a track (what its Canvas pastes in), from the public oEmbed. */
    private fun spotifyArt(spotifyId: String): String? = runCatching {
        if (!Regex("[A-Za-z0-9]{22}").matches(spotifyId)) return null
        val request = okhttp3.Request.Builder()
            .url("https://open.spotify.com/oembed?url=https://open.spotify.com/track/$spotifyId").build()
        NETWORK.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            org.json.JSONObject(response.body?.string().orEmpty()).optString("thumbnail_url").takeIf { it.startsWith("https://") }
        }
    }.getOrNull()

    /** The cover as 32x32 ARGB, centre-cropped to a square. */
    private suspend fun loadCover(context: android.content.Context, url: String): IntArray? {
        val request = coil.request.ImageRequest.Builder(context).data(url).size(256).allowHardware(false).build()
        val drawable = (coil.Coil.imageLoader(context).execute(request) as? coil.request.SuccessResult)?.drawable ?: return null
        val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null
        val side = minOf(bitmap.width, bitmap.height)
        val square = android.graphics.Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val small = android.graphics.Bitmap.createScaledBitmap(square, 32, 32, true)
        return IntArray(32 * 32).also { small.getPixels(it, 0, 32, 0, 0, 32, 32) }
    }

    /** Pearson correlation over RGB of the cover scaled to [size] against the frame region at (x0, y0). */
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
}
