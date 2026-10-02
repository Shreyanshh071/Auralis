package com.auralis.music.data.network

import com.auralis.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Intelligent In-Memory & On-Demand Artwork Resolver.
 * Automatically recovers authentic high-res album covers for tracks that have missing,
 * empty, or broken thumbnails (e.g. Spotify imported tracks or restricted YouTube items).
 */
object ArtworkResolver {

    private val resolvedArtworkCache = ConcurrentHashMap<String, String>()

    fun clearCache() {
        resolvedArtworkCache.clear()
    }

    private fun getCacheKey(track: Track): String = ArtworkIdentity.cacheKey(track)

    fun getArtwork(track: Track): String? {
        if (!track.thumbnail.isNullOrBlank()) return track.thumbnail
        val localArt = com.auralis.music.data.download.AuralisDownloadManager.getDownloadedArtworkFile(track.id)
        if (localArt != null && localArt.exists() && localArt.length() > 500) {
            return android.net.Uri.fromFile(localArt).toString()
        }
        val key = getCacheKey(track)
        val cached = resolvedArtworkCache[key]
        if (!cached.isNullOrBlank()) return cached
        return null
    }

    fun cacheArtwork(track: Track, url: String) {
        if (url.isNotBlank()) {
            val key = getCacheKey(track)
            resolvedArtworkCache[key] = url
        }
    }

    suspend fun resolveArtwork(track: Track): String? = withContext(Dispatchers.IO) {
        val existing = getArtwork(track)
        if (!existing.isNullOrBlank()) return@withContext existing

        val key = getCacheKey(track)
        try {
            val master = com.auralis.music.util.MasterArtworkResolver.resolveMasterArtworkUrl(
                track.title, track.artist, null, getCacheKey(track), track.album, track.duration
            )
            if (!master.isNullOrBlank()) {
                resolvedArtworkCache[key] = master
                return@withContext master
            }
        } catch (_: Exception) {}

        try {
            val query = if (track.artist.isNotBlank() && !track.artist.equals("Spotify Artist", ignoreCase = true) && !track.title.contains(track.artist, ignoreCase = true)) {
                "${track.title} ${track.artist}"
            } else {
                track.title
            }
            val searchClient = InnerTubeClient()
            val songs = searchClient.search(query, InnerTubeClient.FILTER_SONGS).songs
            val match = songs.firstOrNull { ArtworkIdentity.matches(track, it, !track.album.isNullOrBlank()) }
                ?: searchClient.search(query).songs.firstOrNull {
                    ArtworkIdentity.matches(track, it, !track.album.isNullOrBlank())
                }

            val resolvedThumb = match?.thumbnail
            if (!resolvedThumb.isNullOrBlank()) {
                resolvedArtworkCache[key] = resolvedThumb
                return@withContext resolvedThumb
            }
        } catch (_: Exception) {}

        null
    }
}
