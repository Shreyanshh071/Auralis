package com.auralis.music.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
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
    private val cache = LruCache<String, String>(300)
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Resolves the highest-resolution lossless studio master artwork for a track.
     * Looks up YouTube Music InnerTube Google CDN (1200x1200), iTunes CDN (1400x1400), or Spotify CDN.
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
        }

        val cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
        val cleanArtist = artist?.replace(Regex("""\(.*?\)|\[.*?\]|feat\..*|ft\..*""", RegexOption.IGNORE_CASE), "")?.trim().orEmpty()

        // 2. Fetch authentic 1200x1200 lossless 1:1 square master artwork directly from YouTube Music InnerTube (Metrolist standard)
        try {
            val query = if (cleanArtist.isNotBlank()) "$cleanArtist $cleanTitle" else cleanTitle
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

        // 3. Fallback: Query iTunes Search API for 1400x1400 lossless studio master album art
        try {
            val query = if (cleanArtist.isNotBlank()) "$cleanArtist $cleanTitle" else cleanTitle
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val apiUrl = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=10"

            val url = URL(apiUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3500
            conn.readTimeout = 3500
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")

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

        // YouTube Video CDN: 1080p maxresdefault -> 720p hq720 -> 480p sddefault -> hqdefault
        if (cleaned.contains("i.ytimg.com") || cleaned.contains("img.youtube.com")) {
            val base = cleaned.substringBeforeLast('/')
            candidates.add("$base/maxresdefault.jpg")
            candidates.add("$base/hq720.jpg")
            candidates.add("$base/sddefault.jpg")
            candidates.add("$base/hqdefault.jpg")
            candidates.add(cleaned)
            return candidates.distinct()
        }

        candidates.add(cleaned)
        return candidates.distinct()
    }

    /**
     * Processes artwork to completely fill the system media notification card edge-to-edge (16:9 widescreen full-bleed).
     * Eliminates side pillarbox margins on Motorola Hello UI, OxygenOS, and Android 13/14 Quick Settings.
     */
    fun processForMediaNotification(bitmap: Bitmap, targetWidth: Int = 1280, targetHeight: Int = 720): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap

        // Calculate center-crop rectangle to match target 16:9 aspect ratio for edge-to-edge full-bleed background
        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
        val srcAspect = width.toFloat() / height.toFloat()

        val srcRect = if (srcAspect > targetAspect) {
            // Source is wider than target: crop left & right
            val cropWidth = (height * targetAspect).toInt()
            val xOffset = (width - cropWidth) / 2
            Rect(xOffset, 0, xOffset + cropWidth, height)
        } else {
            // Source is taller/square: crop top & bottom (standard 16:9 full-bleed center crop)
            val cropHeight = (width / targetAspect).toInt()
            val yOffset = (height - cropHeight) / 2
            Rect(0, yOffset, width, yOffset + cropHeight)
        }

        return try {
            val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            val destRect = Rect(0, 0, targetWidth, targetHeight)
            canvas.drawBitmap(bitmap, srcRect, destRect, paint)
            output
        } catch (_: Exception) {
            bitmap
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
     * Compresses bitmap to high-quality JPEG byte array for MediaMetadata.setArtworkData.
     */
    fun toByteArray(bitmap: Bitmap, quality: Int = 95): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
