package com.auralis.music.ui.player

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

/** App-wide resolver; every player design uses the same cached recording and Canvas files. */
internal object PhoneCanvasLookup {
    @Volatile private var instance: Lookup? = null
    private class Lookup(context: android.content.Context) {
        private val http = NetworkClientProvider.okHttpClient.newBuilder()
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        val client = SpotifyCanvasClient(http, File(context.cacheDir, "spotify-canvas"),
            indexFile = File(context.filesDir, "spotify-canvas-index.properties"))
        private val catalog = SpicyAlbumCatalog(http)
        val resolver = SpotifyCanvasResolver(client::find, catalog::resolve)
    }
    private fun get(context: android.content.Context): Lookup = instance ?: synchronized(this) {
        instance ?: Lookup(context.applicationContext).also { instance = it }
    }
    suspend fun find(context: android.content.Context, track: Track, hint: String?): ResolvedCanvas? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val lookup = get(context)
            val remembered = hint ?: lookup.client.songId(track.id)
                ?: com.auralis.music.data.network.RecordingMatches.matchedVideoIds.entries.firstOrNull {
                    it.value == track.id && SpicyRecordingMatcher.spotifyId(it.key) != null
                }?.key
            kotlinx.coroutines.withTimeoutOrNull(15_000) { lookup.resolver.find(track, remembered) }?.also {
                lookup.client.rememberSongId(track.id, it.spotifyId)
            }
        }
    suspend fun fallback(context: android.content.Context, canvas: ResolvedCanvas): ResolvedCanvas? {
        val client = get(context).client
        val path = if (!File(canvas.path).isFile) client.find(canvas.spotifyId)
            else client.fallback(canvas.spotifyId, canvas.path)
        return path?.let { canvas.copy(path = it) }
    }
}

/** Canvas belongs to a recording, not to the provider/release that happened to supply its lyrics. */
internal class SpotifyCanvasResolver(
    private val lookup: suspend (String) -> String?,
    private val recording: suspend (LyricsSearchQuery, Set<String>) -> SpicyRecording?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Key(val track: Track, val hint: String?)
    private data class Cached(val canvas: ResolvedCanvas?, val until: Long)
    private val cache = linkedMapOf<Key, Cached>()
    private val lock = Mutex()

    suspend fun find(track: Track, lyricsSpotifyId: String?): ResolvedCanvas? = lock.withLock {
        val key = Key(track, lyricsSpotifyId)
        cache[key]?.takeIf { it.until > clock() && (it.canvas == null || File(it.canvas.path).isFile) }
            ?.let { return@withLock it.canvas }
        val tried = linkedSetOf<String>()
        suspend fun ask(id: String?): ResolvedCanvas? {
            val valid = SpicyRecordingMatcher.spotifyId(id) ?: return null
            if (!tried.add(valid)) return null
            return lookup(valid)?.let { ResolvedCanvas(valid, it) }
        }
        suspend fun resolve(): ResolvedCanvas? {
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
        if (cache.size >= 128) cache.remove(cache.keys.first())
        cache[key] = Cached(result, clock() + if (result == null) 60_000L else 86_400_000L)
        result
    }
}
