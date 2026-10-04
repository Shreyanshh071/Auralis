package com.auralis.music.data.network

import com.auralis.music.domain.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Filters short-form videos at import time without rejecting short audio recordings by duration alone. */
internal object YouTubeShortsFilter {
    private const val MAX_SHORT_SECONDS = 180L
    private val cutoffDate = "2024-10-15"
    private val nonMusicCategories = setOf(
        "gaming", "sports", "news & politics", "comedy", "education", "science & technology",
        "howto & style", "autos & vehicles", "pets & animals", "travel & events"
    )
    private val metadataClient = OkHttpClient.Builder().proxyAuthenticator(com.auralis.music.data.network.ContentProxy.authenticator)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    fun hasShortsMarker(item: JSONObject): Boolean {
        fun containsMarker(node: Any?): Boolean {
            when (node) {
                is JSONObject -> {
                    if (node.has("reelWatchEndpoint") || node.has("shortsLockupViewModel")) return true
                    if (node.optString("iconType").contains("SHORTS", ignoreCase = true) ||
                        node.optString("label").equals("Shorts", ignoreCase = true)
                    ) return true
                    val metadataUrl = node.optJSONObject("webCommandMetadata")?.optString("url").orEmpty()
                    if (metadataUrl.contains("/shorts/")) return true
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key != "thumbnail" && key != "thumbnails" && containsMarker(node.opt(key))) return true
                    }
                }
                is org.json.JSONArray -> for (index in 0 until node.length()) {
                    if (containsMarker(node.opt(index))) return true
                }
            }
            return false
        }
        return containsMarker(item)
    }

    /** Catch sub-minute user uploads with no music release link when player metadata is unavailable. */
    fun isUnlinkedShortUgc(item: JSONObject, durationSeconds: Long): Boolean {
        if (durationSeconds > 60L || !item.toString().contains("MUSIC_VIDEO_TYPE_UGC")) return false
        val columns = item.optJSONArray("flexColumns") ?: return false
        for (index in 1 until columns.length()) {
            val runs = columns.optJSONObject(index)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.optJSONArray("runs") ?: continue
            for (runIndex in 0 until runs.length()) {
                val browseId = runs.optJSONObject(runIndex)?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                if (browseId.startsWith("MPRE") || browseId.startsWith("OLAK")) return false
            }
        }
        return true
    }

    /** A known nonmusic category or a square/portrait video classified as a Short. */
    fun isShortOrNonMusic(track: Track, player: JSONObject?): Boolean {
        if (track.duration > MAX_SHORT_SECONDS || player == null) return false
        val microformat = player.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
        val category = microformat?.optString("category").orEmpty().lowercase()
        if (category in nonMusicCategories) return true

        val streaming = player.optJSONObject("streamingData")
        val formats = listOfNotNull(streaming?.optJSONArray("formats"), streaming?.optJSONArray("adaptiveFormats"))
        var hasSquareOrPortraitVideo = false
        for (formatList in formats) {
            for (index in 0 until formatList.length()) {
                val format = formatList.optJSONObject(index) ?: continue
                val width = format.optInt("width")
                val height = format.optInt("height")
                if (width > 0 && height > 0 && width <= height) hasSquareOrPortraitVideo = true
            }
        }
        if (!hasSquareOrPortraitVideo) return false
        val seconds = player.optJSONObject("videoDetails")?.optLong("lengthSeconds", track.duration)
            ?: track.duration
        if (seconds > MAX_SHORT_SECONDS) return false
        val uploadDate = microformat?.optString("uploadDate").orEmpty()
            .ifBlank { microformat?.optString("publishDate").orEmpty() }
        return seconds <= 60L || uploadDate >= cutoffDate
    }

    suspend fun filter(tracks: List<Track>): List<Track> = coroutineScope {
        val limit = Semaphore(4)
        val rejectedIds = tracks.asSequence().filter { it.duration <= MAX_SHORT_SECONDS }
            .distinctBy { it.id }.map { track ->
            async {
                val player = limit.withPermit { fetchPlayer(track.id) }
                if (isShortOrNonMusic(track, player)) track.id else null
            }
        }.toList().awaitAll().filterNotNull().toSet()
        tracks.filterNot { it.id in rejectedIds }
    }

    private fun fetchPlayer(videoId: String): JSONObject? = try {
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().put("client", JSONObject().apply {
                put("clientName", "WEB")
                put("clientVersion", "2.20240901.00.00")
                put("hl", "en")
                put("gl", "US")
            }))
        }
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36")
            .apply { YouTubeMusicLibrary.signedInHeaders().forEach { (key, value) -> header(key, value) } }
            .build()
        metadataClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()?.let(::JSONObject)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
