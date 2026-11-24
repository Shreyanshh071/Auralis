package com.auralis.music.data.network

import com.auralis.music.domain.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Shared desktop admission check for lists and playback; duration alone never rejects a song. */
object YouTubeShortsFilter {
    @Volatile var classificationStore: com.auralis.music.util.KeyValueStore? = null
    private val checks = Array(64) { kotlinx.coroutines.sync.Mutex() }
    private val requestLimit = Semaphore(4)
    private val recent = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private const val CLASSIFICATION_TTL_MS = 7L * 86_400_000L
    private const val MAX_SHORT_SECONDS = 180L
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    /**
     * Saved verdicts carry the rule that made them. Version 1 read every square video as a Short,
     * which hid ordinary songs (their picture is the square album cover) for good: those verdicts
     * are checked again rather than trusted.
     */
    private const val RULE_VERSION = "2"

    private fun needsMetadata(track: Track) = track.duration <= MAX_SHORT_SECONDS &&
        videoIdPattern.matches(track.id) && track.source == com.auralis.music.domain.model.TrackSource.YOUTUBE

    private fun cachedRejection(id: String): Boolean? {
        val now = System.currentTimeMillis()
        recent[id]?.takeIf { it.second || it.first > now }?.let { return it.second }
        val saved = classificationStore?.getString(id, "").orEmpty().split('|')
        val until = saved.firstOrNull()?.toLongOrNull() ?: return null
        if (saved.size != 3 || saved[2] != RULE_VERSION || saved[1] !in setOf("true", "false") || (until <= now && saved[1] != "true")) return null
        return saved[1].toBoolean().also { recent[id] = until to it }
    }

    /** Synchronous cached previews omit unchecked videos until the live check completes. */
    fun filterCached(tracks: List<Track>): List<Track> = tracks.filter {
        if (needsMetadata(it)) cachedRejection(it.id) == false else cachedRejection(it.id) != true
    }
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

    /**
     * Not music: a Short, a podcast episode, or a plain YouTube upload (MUSIC_VIDEO_TYPE_UGC, e.g.
     * "RealMe 7 Pro Tips & Tricks" from a liked-videos list) that links no album or single.
     * Songs (ATV) and official music videos (OMV) always pass.
     */
    fun isNonMusicVideo(item: JSONObject): Boolean {
        if (hasShortsMarker(item)) return true
        val text = item.toString()
        if (text.contains("MUSIC_VIDEO_TYPE_PODCAST_EPISODE")) return true
        if (!text.contains("MUSIC_VIDEO_TYPE_UGC")) return false
        return !linksRelease(item)
    }

    private fun linksRelease(item: JSONObject): Boolean {
        val columns = item.optJSONArray("flexColumns") ?: return false
        for (index in 1 until columns.length()) {
            val runs = columns.optJSONObject(index)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.optJSONArray("runs") ?: continue
            for (runIndex in 0 until runs.length()) {
                val browseId = runs.optJSONObject(runIndex)?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                if (browseId.startsWith("MPRE") || browseId.startsWith("OLAK")) return true
            }
        }
        return false
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

    /**
     * A known nonmusic category or a square/portrait video classified as a Short. Song uploads
     * ("Artist - Topic") show their square album cover as the picture, so shape alone never marks
     * them, and a video YouTube itself says can't be a Short (isShortsEligible false) isn't one.
     */
    fun isShortOrNonMusic(track: Track, player: JSONObject?): Boolean {
        if (track.duration > MAX_SHORT_SECONDS || player == null) return false
        val microformat = player.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
        val category = microformat?.optString("category").orEmpty().lowercase()
        if (category in nonMusicCategories) return true
        val author = player.optJSONObject("videoDetails")?.optString("author").orEmpty()
        if (author.endsWith(" - Topic")) return false
        if (microformat?.has("isShortsEligible") == true && !microformat.optBoolean("isShortsEligible")) return false

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

    suspend fun filter(tracks: List<Track>): List<Track> {
        if (tracks.none { needsMetadata(it) && cachedRejection(it.id) == null }) return filterCached(tracks)
        return withContext(Dispatchers.IO) { coroutineScope {
        val updates = java.util.concurrent.ConcurrentHashMap<String, String>()
        val rejectedIds = tracks.asSequence().filter {
            needsMetadata(it)
        }
            .distinctBy { it.id }.map { track ->
            async {
                val rejected = checks[(track.id.hashCode() and Int.MAX_VALUE) % checks.size].withLock {
                    cachedRejection(track.id)?.let { return@withLock it }
                    val now = System.currentTimeMillis()
                    val player = requestLimit.withPermit { fetchPlayer(track.id) }
                    val value = isShortOrNonMusic(track, player)
                    // Authentication/network failures are not a verified music classification.
                    val verified = player?.optJSONObject("videoDetails")?.optString("videoId") == track.id &&
                        player.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer") != null
                    val until = now + if (verified) CLASSIFICATION_TTL_MS else 30_000L
                    if (recent.size >= 4096) recent.clear()
                    recent[track.id] = until to value
                    if (verified) updates[track.id] = "$until|$value|$RULE_VERSION"
                    value
                }
                if (rejected) track.id else null
            }
        }.toList().awaitAll().filterNotNull().toSet()
        if (updates.isNotEmpty()) classificationStore?.putStrings(updates)
        tracks.filterNot { it.id in rejectedIds || cachedRejection(it.id) == true }
    } }
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
