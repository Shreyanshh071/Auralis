package com.auralis.music.windows.player

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.searchBody
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/** Public track metadata is optional and never needs a Spotify account. */
internal object CanvasReleaseMetadata {
    private val albums = object : LinkedHashMap<String, Pair<String?, Long>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String?, Long>>?): Boolean =
            size > 256
    }

    suspend fun album(id: String): String? {
        val now = System.currentTimeMillis()
        synchronized(albums) { albums[id] }?.takeIf { it.second > now }?.let { return it.first }
        val album = try {
            withTimeoutOrNull(1_600) {
                val request = Request.Builder().url("https://open.spotify.com/track/$id").build()
                spotifyPageAlbum(NetworkClientProvider.lyricsHttpClient.searchBody(request, 1_600), id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        synchronized(albums) { albums[id] = album to (now + if (album == null) 60_000L else 86_400_000L) }
        return album
    }
}

internal fun spotifyPageAlbum(html: String, id: String): String? {
    val tags = Regex("""<meta\s+(?:property|name)="([^"]+)"\s+content="([^"]*)"[^>]*>""", RegexOption.IGNORE_CASE)
        .findAll(html.substringBefore("</head>")).associate { it.groupValues[1] to it.groupValues[2] }
    if (tags["og:url"] != "https://open.spotify.com/track/$id" ||
        tags["music:album"]?.startsWith("https://open.spotify.com/album/") != true) return null
    val parts = tags["og:description"]?.replace("&#xB7;", "·", ignoreCase = true)
        ?.replace("&#183;", "·")?.replace("&middot;", "·")?.split(" · ") ?: return null
    if (parts.size != 4 || parts[2] != "Song") return null
    return parts[1].replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&quot;", "\"").trim().takeIf(String::isNotEmpty)
}

internal fun canvasReleaseFits(track: Track, spotifyAlbum: String?): Boolean {
    val target = track.album?.takeIf(String::isNotBlank) ?: return true
    val candidate = spotifyAlbum?.takeIf(String::isNotBlank) ?: return true
    fun key(album: String) = CanvasLookup.titleKey(
        album.replace(Regex("""\s*[-–—]\s*(single|ep)$""", RegexOption.IGNORE_CASE), "")
    )
    // A corresponding single often carries the only Canvas for the album recording.
    return key(target) == key(candidate) || key(candidate) == key(track.title)
}
