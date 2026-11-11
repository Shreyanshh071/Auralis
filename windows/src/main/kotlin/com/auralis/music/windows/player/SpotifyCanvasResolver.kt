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
        SpotifyCanvasResolver(SpotifyCanvasLookup::find, catalog::resolve)
    }

    suspend fun find(track: Track, lyricsSpotifyId: String?): ResolvedCanvas? = resolver.find(track, lyricsSpotifyId)
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
