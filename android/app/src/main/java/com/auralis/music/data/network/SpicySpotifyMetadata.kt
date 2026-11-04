package com.auralis.music.data.network

import org.json.JSONObject

/** Public Spotify track embeds include recording metadata; no session token is extracted. */
object SpicySpotifyMetadata {
    fun fromAlbumEmbed(html: String, expectedAlbumId: String): List<SpicyRecording> = runCatching {
        val json = nextData.find(html)?.groupValues?.get(1)?.let(::JSONObject) ?: return emptyList()
        val entity = json.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity") ?: return emptyList()
        if (entity.optString("type") != "album" || entity.optString("uri") != "spotify:album:$expectedAlbumId") return emptyList()
        val tracks = entity.optJSONArray("trackList") ?: return emptyList()
        (0 until tracks.length()).mapNotNull { index ->
            val track = tracks.optJSONObject(index) ?: return@mapNotNull null
            val id = SpicyRecordingMatcher.spotifyId(track.optString("uri")) ?: return@mapNotNull null
            val title = track.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = track.optString("subtitle").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val duration = (track.opt("duration") as? Number)?.toLong()?.takeIf { it in 1..86_400_000L } ?: return@mapNotNull null
            SpicyRecording(id, title, artist, duration, entity.optString("name"))
        }
    }.getOrDefault(emptyList())

    private val nextData = Regex("""<script\b(?=[^>]*\bid=["']__NEXT_DATA__["'])[^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun fromEmbed(html: String, expectedId: String): SpicyRecording? = runCatching {
        if (SpicyRecordingMatcher.spotifyId(expectedId) == null) return null
        val json = nextData.find(html)?.groupValues?.get(1)?.let(::JSONObject) ?: return null
        val entity = json.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity") ?: return null
        if (entity.optString("type") != "track" ||
            SpicyRecordingMatcher.spotifyId(entity.optString("uri")) != expectedId) return null
        val title = entity.optString("name").ifBlank { entity.optString("title") }.takeIf(String::isNotBlank) ?: return null
        val duration = (entity.opt("duration") as? Number)?.toLong()?.takeIf { it in 1..86_400_000L } ?: return null
        val artists = entity.optJSONArray("artists") ?: return null
        val artist = (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name")
            ?.takeIf(String::isNotBlank) }.joinToString(", ").takeIf(String::isNotBlank) ?: return null
        SpicyRecording(expectedId, title, artist, duration)
    }.getOrNull()
}
