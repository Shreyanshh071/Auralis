package com.auralis.music.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.network.NetworkClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MasterArtworkResolver {
    private val cache = LruCache<String, String>(400)
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Cleans noisy video titles to extract the pure song title for high-confidence catalog matching.
     */
    fun sanitizeTitle(raw: String): String {
        return raw
            .replace(Regex("""(?i)\(?(?:official\s*(?:music\s*)?video|official\s*audio|lyric\s*video|lyrics|audio|visualizer|remastered(?:\s*\d{4})?|4k|hd|hq|video|full\s*song|clean\s*version)\)?"""), "")
            .replace(Regex("""(?i)\[(?:official\s*(?:music\s*)?video|official\s*audio|lyric\s*video|lyrics|audio|visualizer|remastered(?:\s*\d{4})?|4k|hd|hq|video|full\s*song|clean\s*version)\]"""), "")
            .replace(Regex("""(?i)\b(?:feat\.|ft\.|featuring)\b.*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', '_', ':')
    }

    /**
     * Resolves the highest-resolution lossless studio master artwork for a track.
     * Looks up Apple Music / iTunes CDN (1400x1400), YouTube Music InnerTube Google CDN (1200x1200), or Spotify CDN.
     */
    suspend fun resolveMasterArtworkUrl(title: String?, artist: String?, fallbackUrl: String?): String? = withContext(Dispatchers.IO) {
        if (title.isNullOrBlank()) return@withContext fallbackUrl

        val cacheKey = "${artist.orEmpty().trim().lowercase()} - ${title.trim().lowercase()}"
        cache.get(cacheKey)?.let { return@withContext it }

        // 1. If fallbackUrl is already high-res Google or Spotify or Apple CDN, upgrade it directly
        if (!fallbackUrl.isNullOrBlank()) {
            val cleaned = fallbackUrl.trim()
            if (cleaned.contains("googleusercontent.com") || cleaned.contains("ggpht.com")) {
                val upgraded = cleaned.replace(Regex("""=w\d+-h\d+.*"""), "=w1200-h1200-l90-rj")
                    .replace(Regex("""=s\d+.*"""), "=s1200-c")
                cache.put(cacheKey, upgraded)
                return@withContext upgraded
            }
            if (cleaned.contains("mzstatic.com")) {
                val upgraded = cleaned.replace(Regex("""\d+x\d+bb"""), "1400x1400bb")
                cache.put(cacheKey, upgraded)
                return@withContext upgraded
            }
            if (cleaned.contains("i.scdn.co/image/")) {
                val upgraded = cleaned.replace("ab67616d00004851", "ab67616d0000b273")
                    .replace("ab67616d00001e02", "ab67616d0000b273")
                cache.put(cacheKey, upgraded)
                return@withContext upgraded
            }
            if (cleaned.contains("jiosaavn.com") || cleaned.contains("saavncdn.com")) {
                val upgraded = cleaned.replace(Regex("""\d+x\d+\.jpg"""), "500x500.jpg")
                cache.put(cacheKey, upgraded)
                return@withContext upgraded
            }
        }

        val (splitArtist, splitTitle) = TitleCleaner.splitArtistAndTitle(title, artist)
        val cleanTitle = sanitizeTitle(splitTitle)
        val isGenericChannel = splitArtist.contains("VEVO", ignoreCase = true) ||
                splitArtist.contains("T-Series", ignoreCase = true) ||
                splitArtist.contains("Sony Music", ignoreCase = true) ||
                splitArtist.contains("Zee Music", ignoreCase = true) ||
                splitArtist.contains("Speed Records", ignoreCase = true) ||
                splitArtist.contains("YRF", ignoreCase = true) ||
                splitArtist.contains("Tips Official", ignoreCase = true) ||
                splitArtist.contains("Topic", ignoreCase = true) ||
                splitArtist.contains("Unknown", ignoreCase = true)

        val cleanArtist = if (isGenericChannel) "" else sanitizeTitle(splitArtist)

        val searchQueries = listOfNotNull(
            if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) "$cleanArtist $cleanTitle" else null,
            if (cleanTitle.isNotBlank()) cleanTitle else null
        ).distinct()

        // 2. Query Apple Music / iTunes Search API for 1400x1400 lossless studio master album art
        for (query in searchQueries) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val apiUrl = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=5"

                val url = URL(apiUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val json = JSONObject(response)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        for (i in 0 until results.length()) {
                            val item = results.optJSONObject(i) ?: continue
                            val itemArtist = item.optString("artistName", "").trim()
                            val rawArtwork = item.optString("artworkUrl100")

                            val isArtistMatch = cleanArtist.isBlank() ||
                                    itemArtist.contains(cleanArtist, ignoreCase = true) ||
                                    cleanArtist.contains(itemArtist, ignoreCase = true)

                            if (isArtistMatch && rawArtwork.isNotBlank()) {
                                val master1400 = rawArtwork.replace("100x100bb", "1400x1400bb")
                                cache.put(cacheKey, master1400)
                                return@withContext master1400
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fetch authentic 1200x1200 lossless 1:1 square master artwork directly from YouTube Music InnerTube
        for (query in searchQueries) {
            try {
                val requestBody = JSONObject().apply {
                    put("query", query)
                    put("params", "Eg-KAQwIARAAGAAgACgAMABqChAMEAUSAhACEAU%3D") // FILTER_SONGS
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB_REMIX")
                            put("clientVersion", "1.20241201.01.00")
                            put("hl", "en")
                            put("gl", "US")
                        })
                    })
                }

                val ytReq = Request.Builder()
                    .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
                    .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Referer", "https://music.youtube.com/")
                    .header("Origin", "https://music.youtube.com")
                    .build()

                val ytResp = NetworkClientProvider.okHttpClient.newCall(ytReq).execute()
                if (ytResp.isSuccessful) {
                    val bodyStr = ytResp.body?.string().orEmpty()
                    val json = JSONObject(bodyStr)
                    val sectionList = json.optJSONObject("contents")
                        ?.optJSONObject("tabbedSearchResultsRenderer")
                        ?.optJSONArray("tabs")
                        ?.optJSONObject(0)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")

                    if (sectionList != null) {
                        for (s in 0 until sectionList.length()) {
                            val shelf = sectionList.optJSONObject(s)?.optJSONObject("musicShelfRenderer") ?: continue
                            val contents = shelf.optJSONArray("contents") ?: continue
                            for (c in 0 until contents.length()) {
                                val respItem = contents.optJSONObject(c)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                                val thumbs = respItem.optJSONObject("thumbnail")
                                    ?.optJSONObject("musicThumbnailRenderer")
                                    ?.optJSONObject("thumbnail")
                                    ?.optJSONArray("thumbnails")

                                if (thumbs != null && thumbs.length() > 0) {
                                    val lastThumb = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")
                                    if (!lastThumb.isNullOrBlank() && (lastThumb.contains("googleusercontent.com") || lastThumb.contains("ggpht.com"))) {
                                        val masterUrl = lastThumb.replace(Regex("""=w\d+-h\d+.*"""), "=w1200-h1200-l90-rj")
                                            .replace(Regex("""=s\d+.*"""), "=s1200-c")
                                        cache.put(cacheKey, masterUrl)
                                        return@withContext masterUrl
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. Fallback to upgraded YouTube thumbnail candidates
        val bestCandidate = ArtworkProcessor.getHighResArtworkCandidates(fallbackUrl).firstOrNull() ?: fallbackUrl
        if (!bestCandidate.isNullOrBlank()) {
            cache.put(cacheKey, bestCandidate)
        }
        bestCandidate
    }
}

object ArtworkProcessor {

    /**
     * Returns a prioritized list of high-definition artwork URL candidates.
     */
    fun getHighResArtworkCandidates(url: String?): List<String> {
        if (url.isNullOrBlank()) return emptyList()
        var cleaned = url.trim()
        if (cleaned.startsWith("//")) cleaned = "https:$cleaned"

        val candidates = mutableListOf<String>()

        // YouTube Music / Google CDN: studio 1200x1200 uncompressed album art
        if (cleaned.contains("googleusercontent.com") || cleaned.contains("ggpht.com")) {
            val highRes = cleaned.replace(Regex("""=w\d+-h\d+.*"""), "=w1200-h1200-l90-rj")
                .replace(Regex("""=s\d+.*"""), "=s1200-c")
            candidates.add(highRes)
            candidates.add(cleaned)
            return candidates.distinct()
        }

        // Apple Music: 1400x1400 master
        if (cleaned.contains("mzstatic.com")) {
            candidates.add(cleaned.replace(Regex("""\d+x\d+bb"""), "1400x1400bb"))
            candidates.add(cleaned)
            return candidates.distinct()
        }

        // Spotify: 640x640 / high-res master
        if (cleaned.contains("i.scdn.co/image/")) {
            candidates.add(
                cleaned.replace("ab67616d00004851", "ab67616d0000b273")
                    .replace("ab67616d00001e02", "ab67616d0000b273")
            )
            candidates.add(cleaned)
            return candidates.distinct()
        }

        // JioSaavn / Saavn: 500x500 master
        if (cleaned.contains("jiosaavn.com") || cleaned.contains("saavncdn.com")) {
            candidates.add(cleaned.replace(Regex("""\d+x\d+\.jpg"""), "500x500.jpg"))
            candidates.add(cleaned)
            return candidates.distinct()
        }

        // YouTube Video CDN: Extract 11-char video ID cleanly to avoid query-string truncation
        if (cleaned.contains("i.ytimg.com") || cleaned.contains("img.youtube.com") || cleaned.contains("youtu")) {
            val videoIdRegex = Regex("""(?:vi/|vi_webp/|v=|embed/|\.be/)([a-zA-Z0-9_-]{11})""")
            val match = videoIdRegex.find(cleaned)?.groupValues?.getOrNull(1)
            if (!match.isNullOrBlank()) {
                candidates.add("https://i.ytimg.com/vi/$match/maxresdefault.jpg")
                candidates.add("https://i.ytimg.com/vi_webp/$match/maxresdefault.webp")
                candidates.add("https://i.ytimg.com/vi/$match/sddefault.jpg")
                candidates.add("https://i.ytimg.com/vi/$match/hqdefault.jpg")
            } else {
                val base = cleaned.substringBeforeLast('?').substringBeforeLast('/')
                candidates.add("$base/maxresdefault.jpg")
                candidates.add("$base/sddefault.jpg")
                candidates.add("$base/hqdefault.jpg")
            }
            candidates.add(cleaned)
            return candidates.distinct()
        }

        candidates.add(cleaned)
        return candidates.distinct()
    }

    /**
     * Fast scan to detect and strip solid black letterbox/pillarbox bars from video stills.
     */
    fun stripBlackBars(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 32 || height < 32) return bitmap

        fun isDarkPixel(color: Int): Boolean {
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            return r < 20 && g < 20 && b < 20
        }

        fun isRowDark(y: Int): Boolean {
            val step = (width / 16).coerceAtLeast(1)
            for (x in 0 until width step step) {
                if (!isDarkPixel(bitmap.getPixel(x, y))) return false
            }
            return true
        }

        fun isColDark(x: Int): Boolean {
            val step = (height / 16).coerceAtLeast(1)
            for (y in 0 until height step step) {
                if (!isDarkPixel(bitmap.getPixel(x, y))) return false
            }
            return true
        }

        var top = 0
        while (top < height / 4 && isRowDark(top)) {
            top++
        }

        var bottom = height - 1
        while (bottom > (height * 3) / 4 && isRowDark(bottom)) {
            bottom--
        }

        var left = 0
        while (left < width / 4 && isColDark(left)) {
            left++
        }

        var right = width - 1
        while (right > (width * 3) / 4 && isColDark(right)) {
            right--
        }

        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1

        if (cropWidth <= 0 || cropHeight <= 0 || (cropWidth == width && cropHeight == height)) {
            return bitmap
        }

        return try {
            Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
        } catch (_: Exception) {
            bitmap
        }
    }

    /**
     * Processes artwork to a pristine, uncompressed 1:1 square master bitmap (800x800).
     * Perfectly formatted for OnePlus Nord CE (OxygenOS), Pixel / Android 13-15 Quick Settings, One UI, and Lockscreen.
     */
    fun processForMediaNotification(bitmap: Bitmap, targetSize: Int = 800): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap

        // 1. If not 1:1, strip black bars first and center-crop to 1:1 square
        val cleanedBitmap = if (width != height) {
            val stripped = stripBlackBars(bitmap)
            cropToCenterSquare(stripped)
        } else {
            bitmap
        }

        val cleanWidth = cleanedBitmap.width
        val cleanHeight = cleanedBitmap.height
        if (cleanWidth == targetSize && cleanHeight == targetSize) {
            return cleanedBitmap
        }

        // 2. High-fidelity bilinear scaling with anti-aliasing and dithering enabled
        return try {
            val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            val srcRect = Rect(0, 0, cleanWidth, cleanHeight)
            val destRect = Rect(0, 0, targetSize, targetSize)
            canvas.drawBitmap(cleanedBitmap, srcRect, destRect, paint)
            output
        } catch (_: Exception) {
            cleanedBitmap
        }
    }

    /**
     * Crops any aspect ratio bitmap (e.g. 16:9 or 4:3) to a centered 1:1 square.
     */
    fun cropToCenterSquare(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        if (width == height) return src

        return try {
            if (width > height) {
                val xOffset = (width - height) / 2
                Bitmap.createBitmap(src, xOffset, 0, height, height)
            } else {
                val yOffset = (height - width) / 2
                Bitmap.createBitmap(src, 0, yOffset, width, width)
            }
        } catch (_: Exception) {
            src
        }
    }

    /**
     * Compresses bitmap to high-quality JPEG byte array (95% quality) for MediaMetadata.setArtworkData.
     * Fits perfectly under Android's 1MB Binder transaction limit while remaining razor sharp on high-DPI screens.
     */
    fun toByteArray(bitmap: Bitmap, quality: Int = 95): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}

