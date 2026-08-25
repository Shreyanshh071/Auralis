package com.auralis.music.data.network

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class YouTubePlaylistImporter(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        fun extractPlaylistId(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.matches(Regex("^[A-Za-z0-9_-]{10,}$")) &&
                (trimmed.startsWith("PL") || trimmed.startsWith("OL") || trimmed.startsWith("RD") || trimmed.startsWith("FL") || trimmed.startsWith("LL") || trimmed.startsWith("VL"))
            ) {
                return if (trimmed.startsWith("VL")) trimmed.substring(2) else trimmed
            }

            try {
                val uri = URI(if (trimmed.startsWith("http")) trimmed else "https://$trimmed")
                val query = uri.query
                if (!query.isNullOrBlank()) {
                    val params = query.split("&").associate {
                        val parts = it.split("=")
                        if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
                    }
                    if (params.containsKey("list")) {
                        return params["list"]
                    }
                }

                val path = uri.path
                val match = Regex("/playlist/([A-Za-z0-9_-]+)").find(path)
                if (match != null) {
                    return match.groupValues[1]
                }
            } catch (_: Exception) {}

            val regexMatch = Regex("[?&]list=([A-Za-z0-9_-]+)").find(trimmed)
            if (regexMatch != null) {
                return regexMatch.groupValues[1]
            }

            return null
        }
    }

    suspend fun importPlaylist(urlOrId: String): Playlist? = withContext(Dispatchers.IO) {
        val playlistId = extractPlaylistId(urlOrId) ?: urlOrId.trim()
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"

        try {
            // 1. Fetch via YouTube Music InnerTube Browse
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241028.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("browseId", browseId)
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val parsed = parsePlaylistResponse(json, playlistId)
                if (parsed != null && parsed.tracks.isNotEmpty()) {
                    return@withContext parsed
                }
            }
        } catch (_: Exception) {}

        // Fallback: search for playlist tracks by query
        try {
            val searchPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241028.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", urlOrId)
            }

        } catch (_: Exception) {}

        null
    }

    private fun parsePlaylistResponse(json: JSONObject, playlistId: String): Playlist? {
        val tracks = mutableListOf<Track>()
        var playlistTitle = "Imported Playlist"

        // Recursively extract musicResponsiveListItemRenderer
        fun extractItems(obj: Any?) {
            if (obj is JSONObject) {
                if (obj.has("header")) {
                    val header = obj.optJSONObject("header")
                    val titleRun = header?.optJSONObject("musicDetailHeaderRenderer")
                        ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                        ?: header?.optJSONObject("musicResponsiveHeaderRenderer")
                            ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    if (!titleRun.isNullOrBlank()) playlistTitle = titleRun
                }

                if (obj.has("musicResponsiveListItemRenderer")) {
                    val item = obj.getJSONObject("musicResponsiveListItemRenderer")
                    val flexCols = item.optJSONArray("flexColumns")
                    val col0 = flexCols?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    val col1 = flexCols?.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")

                    var title = col0?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "Track"
                    
                    val col1Runs = col1?.optJSONObject("text")?.optJSONArray("runs")
                    var artist = "Artist"
                    var album = playlistTitle
                    var durationSec = 210L

                    if (col1Runs != null && col1Runs.length() > 0) {
                        val artistRuns = mutableListOf<String>()
                        var foundDot = false
                        for (r in 0 until col1Runs.length()) {
                            val rObj = col1Runs.optJSONObject(r) ?: continue
                            val txt = rObj.optString("text")
                            if (txt.trim() == "•") {
                                foundDot = true
                                continue
                            }
                            if (!foundDot) {
                                val browseId = rObj.optJSONObject("navigationEndpoint")
                                    ?.optJSONObject("browseEndpoint")?.optString("browseId")
                                if (browseId != null && browseId.startsWith("UC")) {
                                    artistRuns.add(txt)
                                } else {
                                    artistRuns.add(txt)
                                }
                            } else {
                                if (txt.matches(Regex("^\\d+:\\d+(:\\d+)?$"))) {
                                    durationSec = parseDurationToSeconds(txt)
                                } else if (txt.isNotBlank()) {
                                    album = txt
                                }
                            }
                        }
                        if (artistRuns.isNotEmpty()) {
                            artist = artistRuns.joinToString("").trim().replace(Regex("(?i) - Topic$"), "").trim()
                        }
                    }

                    // Clean title and split if title has "Artist - Title" format
                    if (title.contains(" - ")) {
                        val parts = title.split(" - ", limit = 2)
                        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                            if (artist == "Artist" || artist == "YouTube Music" || artist.isBlank()) {
                                artist = parts[0].trim().replace(Regex("(?i) - Topic$"), "").trim()
                            }
                            title = parts[1].trim()
                        }
                    }

                    var videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
                    if (videoId.isNullOrBlank()) {
                        videoId = item.optJSONObject("overlay")
                            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("musicPlayButtonRenderer")
                            ?.optJSONObject("playNavigationEndpoint")
                            ?.optJSONObject("watchEndpoint")
                            ?.optString("videoId")
                    }

                    if (!videoId.isNullOrBlank() && !title.startsWith("Track ") && title.isNotBlank()) {
                        tracks.add(
                            Track(
                                id = videoId,
                                title = title,
                                artist = if (artist.isBlank() || artist == "Artist") "YouTube Music" else artist,
                                album = album,
                                thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                                duration = durationSec,
                                source = TrackSource.YOUTUBE
                            )
                        )
                    }
                    return // Do not recurse deeper into this item
                }

                // Stop recursion if entering recommendations or related sections
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key != "continuations" && key != "shelfDivider" && key != "chipCloudRenderer") {
                        extractItems(obj.get(key))
                    }
                }
            } else if (obj is JSONArray) {
                for (i in 0 until obj.length()) {
                    extractItems(obj.get(i))
                }
            }
        }

        extractItems(json)

        val uniqueTracks = tracks.distinctBy { it.id }.filter { !it.title.startsWith("Track ") }

        return if (uniqueTracks.isNotEmpty()) {
            Playlist(
                id = playlistId,
                title = playlistTitle,
                description = "Imported from YouTube Music",
                tracks = uniqueTracks
            )
        } else null
    }

    private fun parseDurationToSeconds(timeStr: String): Long {
        if (timeStr.isBlank()) return 0L
        val parts = timeStr.trim().split(":")
        return try {
            when (parts.size) {
                1 -> parts[0].toLong()
                2 -> parts[0].toLong() * 60 + parts[1].toLong()
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}
