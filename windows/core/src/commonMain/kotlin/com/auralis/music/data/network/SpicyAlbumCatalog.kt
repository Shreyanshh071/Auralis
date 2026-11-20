package com.auralis.music.data.network

import com.auralis.music.data.network.provider.LyricsSearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public release relationships supply hints; measured track metadata must still match. */
class SpicyAlbumCatalog(private val client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        private val metadataLock = Mutex()
        private var lastMetadataRequestNanos = 0L
    }
    private data class Cached(val tracks: List<SpicyRecording>, val until: Long)
    private val cache = linkedMapOf<List<String>, Cached>()
    private val lookupLock = Mutex()
    private var blockedUntil = 0L

    suspend fun resolve(query: LyricsSearchQuery, excludedIds: Set<String> = emptySet()): SpicyRecording? = lookupLock.withLock {
        val album = query.album?.substringBefore('(')?.trim()?.takeIf { it.isNotBlank() }
        if (query.artist.isBlank() || (query.durationMs ?: (query.durationSec ?: 0) * 1000) <= 0) return@withLock null
        if (album == null && query.title.isBlank()) return@withLock null
        // An album cache contains its whole track list. A recording search is scoped to
        // one song, so unrelated album-less songs must never share its negative result.
        val key = listOf(if (album == null) "recording" else "album",
            (album ?: query.recordingTitle ?: query.title).lowercase(), query.artist.lowercase())
        cache[key]?.takeIf { it.until > clock() }?.let { entry ->
            if (entry.tracks.isEmpty()) return@withLock null
            entry.tracks.firstOrNull { it.id !in excludedIds && SpicyRecordingMatcher.matches(query, it) }?.let { return@withLock it }
        }
        if (clock() < blockedUntil) return@withLock null
        val tracks = mutableListOf<SpicyRecording>()
        try {
            fun quote(value: String) = "\"" + value.replace('"', ' ').replace('\\', ' ') + "\""
            val entity = if (album == null) "recording" else "release"
            val term = if (album == null) "recording:${quote(query.recordingTitle ?: query.title)}" else "release:${quote(album)}"
            val search = "https://musicbrainz.org/ws/2/$entity/".toHttpUrl().newBuilder()
                .addQueryParameter("query", "$term AND artist:${quote(query.artist)}")
                .addQueryParameter("fmt", "json").addQueryParameter("limit", "100").build()
            val result = JSONObject(metadata(search.toString()))
            val releases = if (album != null) {
                val items = result.optJSONArray("releases")
                (0 until (items?.length() ?: 0)).mapNotNull { items?.optJSONObject(it) }
            } else {
                val recordings = result.optJSONArray("recordings")
                (0 until (recordings?.length() ?: 0)).flatMap { index ->
                    val items = recordings?.optJSONObject(index)?.optJSONArray("releases")
                    (0 until (items?.length() ?: 0)).mapNotNull { items?.optJSONObject(it) }
                }.distinctBy { it.optString("id") }
            }
            val digital = releases
                .filter { release ->
                    val media = release.optJSONArray("media")
                    // Recording search can omit media formats; release relationships
                    // are only discovery hints and Spotify metadata is verified below.
                    (album == null && (media == null || media.length() == 0)) ||
                        (0 until (media?.length() ?: 0)).any { media?.optJSONObject(it)?.optString("format") == "Digital Media" }
                }.sortedByDescending { it.optString("country") == "XW" }.take(5)
            val albums = mutableSetOf<String>()
            for (release in digital) {
                val id = release.optString("id").takeIf { Regex("[a-f0-9-]{36}").matches(it) } ?: continue
                val detail = JSONObject(metadata("https://musicbrainz.org/ws/2/release/$id?inc=url-rels&fmt=json"))
                val relations = detail.optJSONArray("relations")
                val links = (0 until (relations?.length() ?: 0)).mapNotNull {
                    relations?.optJSONObject(it)?.optJSONObject("url")?.optString("resource")
                }.mapNotNull { Regex("https://open\\.spotify\\.com/album/([A-Za-z0-9]{22})").matchEntire(it)?.groupValues?.get(1) }
                for (spotifyAlbum in links.take(1)) {
                    if (!albums.add(spotifyAlbum)) continue
                    val html = client.searchBody(Request.Builder().url("https://open.spotify.com/embed/album/$spotifyAlbum").build(), 8000)
                    tracks += SpicySpotifyMetadata.fromAlbumEmbed(html, spotifyAlbum)
                    if (tracks.any { it.id !in excludedIds && SpicyRecordingMatcher.matches(query, it) }) break
                }
                if (tracks.any { it.id !in excludedIds && SpicyRecordingMatcher.matches(query, it) }) break
            }
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
              com.auralis.music.util.Log.w("SpicyCatalog", "Album metadata failed: ${e.javaClass.simpleName}")
              // A failed request is not proof of a catalogue miss. In particular,
              // do not hide a successful earlier release behind a five-minute miss.
              return@withLock tracks.firstOrNull { it.id !in excludedIds && SpicyRecordingMatcher.matches(query, it) }
          }
        if (cache.size >= 64) cache.clear()
        cache[key] = Cached(tracks, clock() + if (tracks.isEmpty()) 300_000 else 86_400_000)
        tracks.firstOrNull { it.id !in excludedIds && SpicyRecordingMatcher.matches(query, it) }.also {
            com.auralis.music.util.Log.i("SpicyCatalog", "Album tracks=${tracks.size}, recording matched=${it != null}")
        }
    }

    private class TemporaryMetadataFailure(val waitMs: Long) : IOException("Recording catalogue temporarily unavailable")

    private suspend fun metadata(url: String): String = try { metadataOnce(url) }
        catch (e: TemporaryMetadataFailure) {
            if (e.waitMs > 10_000L) throw e
            delay(e.waitMs + 100L)
            metadataOnce(url)
        }
        catch (e: java.net.SocketTimeoutException) {
            delay(1100L)
            metadataOnce(url)
        }

    private suspend fun metadataOnce(url: String): String = metadataLock.withLock {
        if (clock() < blockedUntil) throw IOException("Recording catalogue is temporarily unavailable")
        val elapsed = (System.nanoTime() - lastMetadataRequestNanos) / 1_000_000L
        if (elapsed < 1100L) delay(1100L - elapsed)
        lastMetadataRequestNanos = System.nanoTime()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Auralis/1.1.2 (https://github.com/Shreyanshh071/Auralis)")
            .header("Accept", "application/json").build()
        try { suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            call.timeout().timeout(15, java.util.concurrent.TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        val body = response.use {
                            if (!it.isSuccessful) {
                                com.auralis.music.util.Log.w("SpicyCatalog", "Public recording catalogue HTTP ${it.code}")
                                val waitSeconds = it.header("Retry-After")?.toLongOrNull()?.coerceAtLeast(1L)
                                    ?: if (it.code == 503) 2L else 300L
                                blockedUntil = maxOf(blockedUntil, clock() + waitSeconds * 1000L)
                                if (it.code == 503) throw TemporaryMetadataFailure(waitSeconds * 1000L)
                                throw IOException("Recording catalogue HTTP ${it.code}")
                            }
                            it.body?.string() ?: throw IOException("Empty recording metadata")
                        }
                        if (continuation.isActive) continuation.resume(body)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        } }
        catch (e: CancellationException) { throw e }
        catch (e: TemporaryMetadataFailure) { throw e }
        catch (e: java.net.SocketTimeoutException) { throw e }
        catch (e: Exception) { blockedUntil = maxOf(blockedUntil, clock() + 300_000); throw e }
    }
}
