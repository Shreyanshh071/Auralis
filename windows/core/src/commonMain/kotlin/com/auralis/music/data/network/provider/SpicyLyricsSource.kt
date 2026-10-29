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
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).build(),
    private val apiKey: () -> String = { effectiveKey() },
    private val spotifyToken: suspend () -> String? = {
        SpotifySession.getValidAccessToken() ?: SpotifyPlaylistImporter().getAccessToken()
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val albumCatalog: SpicyAlbumCatalog = SpicyAlbumCatalog(client, clock)
) : LyricsSource {
    override val provider = LyricsProvider.SPICY_LYRICS
    override val supportedSyncTypes = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC, SyncType.PLAIN)
    private data class Cached(val recording: SpicyRecording, val expires: Long)
    private val matches = object : LinkedHashMap<List<Any?>, Cached>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, Cached>?): Boolean = size > 256
    }
    @Volatile private var blockedUntil = 0L
    @Volatile private var blockedKey = ""
    @Volatile private var metadataBlockedUntil = 0L
    @Volatile private var metadataApiBlockedUntil = 0L

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val key = apiKey().trim()
        try {
            if (!isNativeKey(key) || (blockedKey == key && clock() < blockedUntil))
                throw IOException("Spicy Lyrics is temporarily unavailable or needs a valid publishable key")
            val recording = resolve(query) ?: return@withContext null
            val request = Request.Builder().url("https://api.spicylyrics.org/v1/lyrics/${recording.id}")
                .header("Authorization", "Bearer $key").header("Accept", "application/json").build()
            val response = fetch(request)
            if (response.code == 429 || response.code == 401 || response.code == 403) {
                blockedKey = key
                blockedUntil = response.retryUntil()
            }
            if (response.code != 200) {
                com.auralis.music.util.Log.w("SpicyLyrics", "Lyrics lookup returned HTTP ${response.code}")
                if (response.code != 404) throw IOException("Spicy Lyrics lookup failed: HTTP ${response.code}")
                return@withContext null
            }
            val lyrics = SpicyLyricsParser.parse(JSONObject(response.body), recording)?.copy(fetchedAtMs = clock()) ?: return@withContext null
            // A cross-service match is never an exact YouTube-video match.
            LyricsCandidate(lyrics, 95, lyrics.syncType, provider)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (query.reportFailures) throw e
            null
        } catch (e: org.json.JSONException) {
            if (query.reportFailures) throw IOException("Spicy Lyrics returned an invalid response", e)
            null
        }
    }

    private suspend fun resolve(query: LyricsSearchQuery): SpicyRecording? {
        val identity = listOf(query.videoId, query.spotifyId, query.recordingTitle ?: query.title,
            query.artist, query.album, query.durationMs, query.durationSec, query.isrc)
        synchronized(matches) { matches[identity] }?.takeIf { it.expires > clock() }?.let { return it.recording }
        if (clock() < metadataBlockedUntil) throw IOException("Recording metadata is rate limited; try again later")
        val id = SpicyRecordingMatcher.spotifyId(query.spotifyId) ?: SpicyRecordingMatcher.spotifyId(query.videoId)
            ?: RecordingMatches.matchedVideoIds.entries.firstOrNull {
                it.value == query.videoId && SpicyRecordingMatcher.spotifyId(it.key) != null
            }?.key?.let(SpicyRecordingMatcher::spotifyId)
        if (id != null) {
            val embed = fetch(Request.Builder().url("https://open.spotify.com/embed/track/$id")
                .header("Accept", "text/html").build())
            if (embed.code == 429) {
                metadataBlockedUntil = embed.retryUntil()
                throw IOException("Recording metadata is rate limited; try again later")
            }
            if (embed.code == 200) {
                val recording = SpicySpotifyMetadata.fromEmbed(embed.body, id)
                if (recording != null) {
                    if (!SpicyRecordingMatcher.matches(query, recording)) return null
                    synchronized(matches) { matches[identity] = Cached(recording, clock() + 86_400_000L) }
                    return recording
                }
            }
        }
        suspend fun publicAlbumRecording(): SpicyRecording? {
            if (id != null) {
                com.auralis.music.util.Log.i("SpicyCatalog", "Existing recording identity has no usable metadata: $id")
            }
            com.auralis.music.util.Log.i("SpicyCatalog", "Album lookup: album=${query.album}, duration=${query.durationMs}")
            val recording = albumCatalog.resolve(query) ?: return null
            synchronized(matches) { matches[identity] = Cached(recording, clock() + 86_400_000L) }
            return recording
        }
        if (clock() < metadataApiBlockedUntil) {
            publicAlbumRecording()?.let { return it }
            throw IOException("Recording metadata is rate limited; try again later")
        }
        val token = spotifyToken()?.takeIf { it.isNotBlank() } ?: run {
            publicAlbumRecording()?.let { return it }
            com.auralis.music.util.Log.w("SpicyLyrics", "Spotify metadata token unavailable; recording match cannot proceed")
            throw IOException("Spotify recording metadata is unavailable")
        }
        val url = if (id != null) "https://api.spotify.com/v1/tracks/$id".toHttpUrl() else {
            if (query.title.isBlank() || query.artist.isBlank()) return null
            val search = if (!query.isrc.isNullOrBlank()) "isrc:${query.isrc}" else "${query.title} ${query.artist}"
            "https://api.spotify.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", search).addQueryParameter("type", "track")
                .addQueryParameter("limit", "10").build()
        }
        val response = fetch(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
        if (response.code != 200) {
            if (response.code == 429) metadataApiBlockedUntil = response.retryUntil()
            com.auralis.music.util.Log.w("SpicyLyrics", "Spotify metadata lookup returned HTTP ${response.code}")
            if (response.code == 429) publicAlbumRecording()?.let { return it }
            if (response.code != 404) throw IOException("Recording metadata lookup failed: HTTP ${response.code}")
            return null
        }
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
    private fun Reply.retryUntil(): Long {
        val now = clock()
        val wait = TimeUnit.SECONDS.toMillis((retryAfter ?: 60L).coerceAtLeast(1L))
        return now + wait.coerceAtMost(Long.MAX_VALUE - now)
    }
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
        fun effectiveKey(): String = com.auralis.music.data.network.SpicyLyricsAccess.resolve(
            ContentSettingsSource.current().spicyLyricsApiKey)
        fun isNativeKey(key: String): Boolean = Regex("sl_pk_[A-Za-z0-9_-]+").matches(key)
    }
}
