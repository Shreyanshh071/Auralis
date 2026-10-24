package com.auralis.music.data.network.provider

import com.auralis.music.data.network.*
import com.auralis.music.data.parser.SpicyLyricsParser
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** YouTube playback is untouched: resolve a recording, then query the documented Spotify-ID API.
 * Only native publishable keys are accepted here; a server secret must never ship in this app. */
class SpicyLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient.newBuilder()
        .callTimeout(3, TimeUnit.SECONDS).followRedirects(false).build(),
    private val apiKey: () -> String = { ContentSettingsSource.current().spicyLyricsApiKey },
    private val spotifyToken: suspend () -> String? = {
        SpotifySession.getValidAccessToken() ?: SpotifyPlaylistImporter().getAccessToken()
    },
    private val clock: () -> Long = System::currentTimeMillis
) : LyricsSource {
    override val provider = LyricsProvider.SPICY_LYRICS
    override val supportedSyncTypes = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC, SyncType.PLAIN)
    private data class Cached(val recording: SpicyRecording, val expires: Long)
    private val matches = object : LinkedHashMap<List<Any?>, Cached>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, Cached>?): Boolean = size > 256
    }
    @Volatile private var blockedUntil = 0L
    @Volatile private var blockedKey = ""

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val key = apiKey().trim()
        if (!isNativeKey(key) || (blockedKey == key && clock() < blockedUntil)) return@withContext null
        try {
            val recording = resolve(query) ?: return@withContext null
            val request = Request.Builder().url("https://api.spicylyrics.org/v1/lyrics/${recording.id}")
                .header("Authorization", "Bearer $key").header("Accept", "application/json").build()
            val response = fetch(request)
            if (response.code == 429 || response.code == 401 || response.code == 403) {
                blockedKey = key
                blockedUntil = clock() + (response.retryAfter ?: 60L).coerceIn(1L, 3600L) * 1000L
            }
            if (response.code != 200) return@withContext null
            val lyrics = SpicyLyricsParser.parse(JSONObject(response.body), recording)?.copy(fetchedAtMs = clock()) ?: return@withContext null
            // A cross-service match is never an exact YouTube-video match.
            LyricsCandidate(lyrics, 95, lyrics.syncType, provider)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }

    private suspend fun resolve(query: LyricsSearchQuery): SpicyRecording? {
        val identity = listOf(query.videoId, query.spotifyId, query.recordingTitle ?: query.title,
            query.artist, query.album, query.durationMs, query.durationSec, query.isrc)
        synchronized(matches) { matches[identity] }?.takeIf { it.expires > clock() }?.let { return it.recording }
        val token = spotifyToken()?.takeIf { it.isNotBlank() } ?: return null
        val id = SpicyRecordingMatcher.spotifyId(query.spotifyId) ?: SpicyRecordingMatcher.spotifyId(query.videoId)
        val url = if (id != null) "https://api.spotify.com/v1/tracks/$id".toHttpUrl() else {
            if (query.title.isBlank() || query.artist.isBlank()) return null
            val search = if (!query.isrc.isNullOrBlank()) "isrc:${query.isrc}" else "${query.title} ${query.artist}"
            "https://api.spotify.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", search).addQueryParameter("type", "track")
                .addQueryParameter("limit", "10").build()
        }
        val response = fetch(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
        if (response.code != 200) return null
        val json = JSONObject(response.body)
        val candidates = if (id != null) listOfNotNull(parseRecording(json)) else {
            val items = json.optJSONObject("tracks")?.optJSONArray("items") ?: return null
            (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(::parseRecording) }
        }
        val accepted = candidates.filter { SpicyRecordingMatcher.matches(query, it) }
        // Matching releases of the same recording are common. Prefer the supplied album,
        // then duration; never use popularity or an unrelated first search result.
        val duration = query.durationMs ?: (query.durationSec ?: 0L) * 1000L
        val best = accepted.sortedWith(compareByDescending<SpicyRecording> {
            !query.album.isNullOrBlank() && query.album.equals(it.album, ignoreCase = true)
        }.thenBy { kotlin.math.abs(it.durationMs - duration) }).firstOrNull() ?: return null
        synchronized(matches) { matches[identity] = Cached(best, clock() + 86_400_000L) }
        return best
    }

    private fun parseRecording(json: JSONObject): SpicyRecording? {
        val id = SpicyRecordingMatcher.spotifyId(json.optString("id")) ?: return null
        val artists = json.optJSONArray("artists") ?: return null
        return SpicyRecording(id, json.optString("name"),
            (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name") }.joinToString(", "),
            json.optLong("duration_ms"), json.optJSONObject("album")?.optString("name"),
            json.optJSONObject("external_ids")?.optString("isrc"))
    }

    private data class Reply(val code: Int, val body: String, val retryAfter: Long?)
    private suspend fun fetch(request: Request): Reply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val reply = Reply(it.code, it.body?.string().orEmpty(), it.header("Retry-After")?.toLongOrNull())
                        if (continuation.isActive) continuation.resume(reply)
                    }
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    companion object {
        fun isNativeKey(key: String): Boolean = Regex("sl_pk_[A-Za-z0-9_-]+").matches(key)
    }
}
