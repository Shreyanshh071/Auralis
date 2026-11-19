package com.auralis.music.windows.player

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.SpicyAlbumCatalog
import com.auralis.music.data.network.SpicyRecording
import com.auralis.music.data.network.SpicyRecordingMatcher
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal data class ResolvedCanvas(val spotifyId: String, val path: String)

internal object TrackCanvasLookup {
    private val resolver by lazy {
        val catalog = SpicyAlbumCatalog(NetworkClientProvider.lyricsHttpClient)
        SpotifyCanvasResolver(SpotifyCanvasLookup::find, catalog::resolve, releaseAlbum = CanvasReleaseMetadata::album,
            cachedLookup = SpotifyCanvasLookup::cached)
    }

    // Imports already matched these Spotify recordings to the playing YouTube song.
    private fun importedIds(track: Track) =
        com.auralis.music.data.network.RecordingMatches.matchedVideoIds.entries
            .filter { it.value == track.id }
            .mapNotNull { SpicyRecordingMatcher.spotifyId(it.key) }
            .distinct().sorted()

    suspend fun findCached(track: Track, lyricsSpotifyId: String?, matchRelease: Boolean): ResolvedCanvas? =
        resolver.findCached(track, listOf(SpotifyCanvasLookup.songId(track.id), lyricsSpotifyId) + importedIds(track),
            matchRelease)

    suspend fun find(track: Track, lyricsSpotifyId: String?, matchRelease: Boolean = true): ResolvedCanvas? {
        val remembered = SpotifyCanvasLookup.songId(track.id)
        val hints = (listOfNotNull(remembered?.takeUnless { it == lyricsSpotifyId }) + importedIds(track)).distinct()
        val result = resolver.findCached(track, listOf(remembered, lyricsSpotifyId) + hints, matchRelease)
            ?: resolver.find(track, lyricsSpotifyId, hints, matchRelease, SpotifyCanvasLookup.accountAvailable)
        return result?.let { canvas ->
            SpotifyCanvasLookup.rememberSongId(track.id, canvas.spotifyId)
            SpotifyCanvasLookup.cached(canvas.spotifyId)?.let { canvas.copy(path = it) } ?: canvas
        }
    }
}

/** Resolve a track Canvas and check its release when its identity comes from another provider. */
internal class SpotifyCanvasResolver(
    private val lookup: suspend (String) -> String?,
    private val recording: suspend (LyricsSearchQuery, Set<String>) -> SpicyRecording?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val releaseAlbum: suspend (String) -> String? = { null },
    private val cachedLookup: (String) -> String? = { null }
) {
    private data class Key(val track: Track, val hint: String?, val importedIds: List<String>, val matchRelease: Boolean,
        val accountAvailable: Boolean)
    private data class Cached(val canvas: ResolvedCanvas?, val until: Long)
    private val cache = linkedMapOf<Key, Cached>()
    private val lock = Mutex()

    /** A local file may need the bounded release check, but never a provider search or media download. */
    suspend fun findCached(track: Track, ids: List<String?>, matchRelease: Boolean): ResolvedCanvas? =
        withTimeoutOrNull(2_000L) {
            val nativeId = SpicyRecordingMatcher.spotifyId(track.id)
            for (id in (listOf(nativeId) + ids).mapNotNull(SpicyRecordingMatcher::spotifyId).distinct()) {
                val path = cachedLookup(id) ?: continue
                if (!matchRelease || id == nativeId || track.album.isNullOrBlank() ||
                    canvasReleaseFits(track, releaseAlbum(id))) return@withTimeoutOrNull ResolvedCanvas(id, path)
            }
            null
        }

    suspend fun find(track: Track, lyricsSpotifyId: String?, importedIds: List<String> = emptyList(),
        matchRelease: Boolean = true, accountAvailable: Boolean = false): ResolvedCanvas? {
        val key = Key(track, lyricsSpotifyId, importedIds.toList(), matchRelease, accountAvailable)
        lock.withLock { cache[key] }
            ?.takeIf { it.until > clock() && (it.canvas == null || File(it.canvas.path).isFile) }
            ?.let { return it.canvas }
        val tried = linkedSetOf<String>()
        suspend fun ask(id: String?, catalogAlbum: String? = null, exactTrack: Boolean = false): ResolvedCanvas? {
            val valid = SpicyRecordingMatcher.spotifyId(id) ?: return null
            if (!tried.add(valid)) return null
            val path = lookup(valid) ?: return null
            if (matchRelease && !exactTrack && !track.album.isNullOrBlank() &&
                !canvasReleaseFits(track, catalogAlbum ?: releaseAlbum(valid))) return null
            return ResolvedCanvas(valid, path)
        }
        suspend fun resolve(): ResolvedCanvas? {
            ask(track.id, exactTrack = true)?.let { return it }
            ask(lyricsSpotifyId)?.let { return it }
            for (id in importedIds) ask(id)?.let { return it }
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
                        ask(candidate.id, candidate.album)?.let { return it }
                    } else tried += candidate.id
                }
            }
            return null
        }
        val result = resolve()
        lock.withLock {
            if (cache.size >= 128) cache.remove(cache.keys.first())
            cache[key] = Cached(result, clock() + if (result == null) 60_000L else 86_400_000L)
        }
        return result
    }
}
