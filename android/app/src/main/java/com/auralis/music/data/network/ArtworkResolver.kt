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

    private fun getCacheKey(track: Track): String {
        return "${track.title.trim()}:::${track.artist.trim()}".lowercase()
    }

    fun getArtwork(track: Track): String? {
        if (!track.thumbnail.isNullOrBlank()) return track.thumbnail
        val key = getCacheKey(track)
        return resolvedArtworkCache[key]
    }

    fun cacheArtwork(track: Track, url: String) {
        if (url.isNotBlank()) {
            val key = getCacheKey(track)
            resolvedArtworkCache[key] = url
            resolvedArtworkCache[track.id] = url
        }
    }

    suspend fun resolveArtwork(track: Track): String? = withContext(Dispatchers.IO) {
        if (!track.thumbnail.isNullOrBlank()) return@withContext track.thumbnail

        val key = getCacheKey(track)
        val cached = resolvedArtworkCache[key] ?: resolvedArtworkCache[track.id]
        if (!cached.isNullOrBlank()) return@withContext cached

        try {
            val query = if (track.artist.isNotBlank() && !track.artist.equals("Spotify Artist", ignoreCase = true) && !track.title.contains(track.artist, ignoreCase = true)) {
                "${track.title} ${track.artist}"
            } else {
                track.title
            }
            val searchClient = InnerTubeClient()
            val songs = searchClient.search(query, InnerTubeClient.FILTER_SONGS).songs
            val match = songs.firstOrNull { it.thumbnail.isNotBlank() }
                ?: searchClient.search(query).songs.firstOrNull { it.thumbnail.isNotBlank() }

            val resolvedThumb = match?.thumbnail
            if (!resolvedThumb.isNullOrBlank()) {
                resolvedArtworkCache[key] = resolvedThumb
                resolvedArtworkCache[track.id] = resolvedThumb
                return@withContext resolvedThumb
            }
        } catch (_: Exception) {}

        null
    }
}
