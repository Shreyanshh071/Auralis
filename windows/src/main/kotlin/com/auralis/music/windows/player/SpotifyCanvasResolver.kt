package com.auralis.music.windows.player

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.SpicyAlbumCatalog
import com.auralis.music.data.network.SpicyRecording
import com.auralis.music.data.network.SpicyRecordingMatcher
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

internal data class ResolvedCanvas(val spotifyId: String, val path: String)

internal object TrackCanvasLookup {
    private val resolver by lazy {
        val catalog = SpicyAlbumCatalog(NetworkClientProvider.lyricsHttpClient)
        SpotifyCanvasResolver(SpotifyCanvasLookup::find, catalog::resolve,
            cachedLookup = SpotifyCanvasLookup::cached,
            mappings = com.auralis.music.util.PropertiesFileKeyValueStore(
                File(com.auralis.music.util.desktopAppDataDir(), "cache/canvas-recordings.properties")))
    }

    fun cached(track: Track, lyricsSpotifyId: String?): ResolvedCanvas? = resolver.cached(track, lyricsSpotifyId)
    suspend fun find(track: Track, lyricsSpotifyId: String?): ResolvedCanvas? = resolver.find(track, lyricsSpotifyId)
}

/** Canvas belongs to a recording, not to the provider/release that happened to supply its lyrics. */
internal class SpotifyCanvasResolver(
    private val lookup: suspend (String) -> String?,
    private val recording: suspend (LyricsSearchQuery, Set<String>) -> SpicyRecording?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cachedLookup: (String) -> String? = { null },
    private val mappings: com.auralis.music.util.KeyValueStore? = null
) {
    private data class Cached(val canvas: ResolvedCanvas?, val until: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private val locks = Array(32) { Mutex() }

    // Identity metadata is part of the key: another version/artist/recording must be verified anew.
    private fun recordingKey(track: Track): String {
        val identity = org.json.JSONArray(listOf(track.id, track.title, track.artist, track.album, track.duration)).toString()
        return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun cached(track: Track, lyricsSpotifyId: String?): ResolvedCanvas? {
        val key = recordingKey(track)
        cache[key]?.takeIf { it.until > clock() }?.canvas?.takeIf { File(it.path).isFile }?.let { return it }
        val ids = listOfNotNull(mappings?.getString(key, ""), track.id, lyricsSpotifyId)
        for (candidate in ids) {
            val id = SpicyRecordingMatcher.spotifyId(candidate) ?: continue
            cachedLookup(id)?.takeIf { File(it).isFile }?.let { path -> return ResolvedCanvas(id, path) }
        }
        return null
    }

    private fun rememberMatch(key: String, result: ResolvedCanvas): ResolvedCanvas {
        if (cache.size >= 128) cache.entries.removeIf { it.value.until <= clock() }
        if (cache.size >= 256) cache.clear()
        cache[key] = Cached(result, clock() + 86_400_000L)
        if (mappings?.getString(key, "") != result.spotifyId) mappings?.putStrings(mapOf(key to result.spotifyId))
        return result
    }

    suspend fun find(track: Track, lyricsSpotifyId: String?): ResolvedCanvas? {
        val key = recordingKey(track)
        cached(track, lyricsSpotifyId)?.let { return rememberMatch(key, it) }
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            cached(track, lyricsSpotifyId)?.let { return@withLock rememberMatch(key, it) }
            val missKey = "$key|$lyricsSpotifyId"
            cache[missKey]?.takeIf { it.until > clock() && it.canvas == null }?.let { return@withLock null }
            val tried = linkedSetOf<String>()
            suspend fun ask(id: String?): ResolvedCanvas? {
                val valid = SpicyRecordingMatcher.spotifyId(id) ?: return null
                if (!tried.add(valid)) return null
                return lookup(valid)?.let { ResolvedCanvas(valid, it) }
            }
            suspend fun resolve(): ResolvedCanvas? {
                ask(mappings?.getString(key, ""))?.let { return it }
                ask(track.id)?.let { return it }
                ask(lyricsSpotifyId)?.let { return it }
                if (track.title.isBlank() || track.artist.isBlank() || track.duration <= 0) return null
                val query = LyricsSearchQuery(track.title, track.artist, durationSec = track.duration,
                    videoId = track.id, album = track.album, recordingTitle = track.title)
                // A single and its album can have different Spotify IDs and different canvas availability.
                // Only measured equivalents are accepted; never attach another version's video.
                val releases = listOfNotNull(track.album?.takeIf(String::isNotBlank), track.title).distinct()
                var attempts = 0
                for (album in releases) {
                    for (attempt in 0 until 2) {
                        if (attempts >= 3) return null
                        val candidate = recording(query.copy(album = album), tried) ?: break
                        attempts++
                        if (candidate.id in tried) break
                        if (SpicyRecordingMatcher.matches(query, candidate) &&
                            CanvasLookup.titleKey(track.title) == CanvasLookup.titleKey(candidate.title)) {
                            ask(candidate.id)?.let { return it }
                        } else tried += candidate.id
                    }
                }
                return null
            }
            val result = resolve()
            if (result != null) rememberMatch(key, result)
            else {
                if (cache.size >= 256) cache.clear()
                cache[missKey] = Cached(null, clock() + 60_000L)
            }
            result
        }
    }
}
