package com.auralis.music.ui.player

import com.auralis.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** Matching adapted from Auralis desktop; providers follow Vivi Music's motion artwork sources. */
internal enum class CanvasSource(val label: String) {
    AUTO("Auto"), APPLE("Apple Music"), TIDAL("Tidal"), VIVI("Curated library");
    companion object { fun from(value: String) = entries.firstOrNull { it.name == value } ?: AUTO }
}

internal object CanvasLookup {
    private const val TAG = "AnimatedCovers"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private val http by lazy { com.auralis.music.data.network.NetworkClientProvider.okHttpClient }


    suspend fun find(context: android.content.Context, track: Track, source: CanvasSource): String? = withContext(Dispatchers.IO) {
        val disk = context.getSharedPreferences("animated_cover_cache", android.content.Context.MODE_PRIVATE)
        if (track.title.isBlank() || track.artist.isBlank()) return@withContext null
        // v3: version tags and the album count (v2 matched "Let Down" to a DJ mix's "Let Down (Mixed)").
        val key = "v3|${source.name}|${titleKey(track.title)}|${track.artist.lowercase()}|${albumKey(track.album.orEmpty())}"
        disk.getString(key, "")?.takeIf { it.isNotEmpty() }?.let { cached ->
            val (url, at) = cached.substringBefore('\t') to (cached.substringAfter('\t', "0").toLongOrNull() ?: 0L)
            // Refresh video URLs daily; retry misses shortly after a temporary outage.
            if (System.currentTimeMillis() - at in 0 until (if (url.isNotBlank()) 86_400_000L else 60_000L)) {
                return@withContext url.ifBlank { null }
            }
        }
        val sources = if (source == CanvasSource.AUTO) listOf(CanvasSource.APPLE, CanvasSource.TIDAL, CanvasSource.VIVI) else listOf(source)
        val url = sources.firstNotNullOfOrNull { s ->
            runCatching {
                when (s) {
                    CanvasSource.APPLE -> apple(track)
                    CanvasSource.TIDAL -> tidal(track)
                    CanvasSource.VIVI -> vivi(track)
                    CanvasSource.AUTO -> null
                }
            }.onFailure { android.util.Log.w(TAG, "${s.label} lookup failed for '${track.title}': ${it.message}") }.getOrNull()
        }
        runCatching { disk.edit().putString(key, "${url.orEmpty()}\t${System.currentTimeMillis()}").apply() }
        if (url != null) android.util.Log.i(TAG, "Animated cover for '${track.title}': $url")
        url
    }

    // ── Matching ──
    private fun norm(s: String) = s.lowercase().replace(Regex("""\(.*?\)|\[.*?\]"""), " ")
        .replace(Regex("""\s-\s.*(remaster|version|edit|mix).*$"""), " ")
        .replace(Regex("""[^\p{L}\p{Nd}]+"""), "")
    private val artistSplit = Regex("""\s*(?:,|&|\s×\s|\sx\s|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)
    private fun artists(s: String) = s.split(artistSplit).map(::norm).filter { it.isNotBlank() }
    /** Every artist the song credits is among the result's artists. */
    private fun artistMatch(requested: String, returned: List<String>): Boolean {
        val want = artists(requested); val have = returned.flatMap(::artists).toSet()
        return want.isNotEmpty() && want.all { it in have }
    }
    /**
     * A title with its bracketed and " - " parts: tags that don't change the recording (remaster,
     * feat., explicit, single/album version) are dropped, any other tag (mixed, live, remix,
     * acoustic, sped up...) is kept, so it has to be on both sides.
     */
    private val sameRecordingTag = Regex("""^(\d{4}\s*)?(re-?master(ed)?(\s*\d{4})?(\s*version)?|\d{4}\s*re-?master(ed)?(\s*version)?|explicit|clean|mono|stereo|(feat|ft|featuring|with)\b.*|single version|album version|original version|from .*|official( music| lyric)?( video| audio)?|lyrics?( video)?|visuali[sz]er|audio|video|m/?v|hd|hq|4k)$""", RegexOption.IGNORE_CASE)
    internal fun titleKey(title: String): String {
        val tags = mutableListOf<String>()
        var base = title.replace(Regex("""\(([^)]*)\)|\[([^\]]*)\]""")) { m ->
            tags += (m.groups[1] ?: m.groups[2])!!.value.trim(); " "
        }
        Regex("""\s[-–—]\s(.+)$""").find(base)?.let { m -> tags += m.groupValues[1].trim(); base = base.substring(0, m.range.first) }
        val kept = tags.filterNot { sameRecordingTag.matches(it.trim()) }.map { it.lowercase().replace(Regex("""[^\p{L}\p{Nd}]+"""), "") }
            .filter { it.isNotBlank() }.sorted()
        val core = base.lowercase().replace(Regex("""[^\p{L}\p{Nd}]+"""), "")
        return if (kept.isEmpty()) core else core + "|" + kept.joinToString("+")
    }
    private fun titleMatch(requested: String, returned: String) = titleKey(requested).isNotBlank() && titleKey(requested) == titleKey(returned)
    /** An album's name without its edition ("Deluxe", "2017 Remaster") or " - Single"/" - EP". */
    private fun albumKey(album: String) = norm(album.replace(Regex("""\s-\s(single|ep)$""", RegexOption.IGNORE_CASE), ""))
    /** The result's album is the song's own (any edition), or the song's album isn't known. */
    private fun albumMatch(track: Track, returned: String) =
        track.album.isNullOrBlank() || returned.isBlank() || albumKey(track.album.orEmpty()) == albumKey(returned)
    private fun primaryArtist(track: Track) = track.artist.split(artistSplit).first().trim()
    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        val request = Request.Builder().url(url).header("User-Agent", UA).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    // ── Apple Music: album motion artwork (HLS), via the web player's public token ──
    @Volatile private var appleToken: String? = null
    @Volatile private var appleTokenExpiry = 0L
    private fun appleToken(): String? {
        val now = System.currentTimeMillis()
        appleToken?.takeIf { now < appleTokenExpiry - 60_000 }?.let { return it }
        val html = get("https://music.apple.com/us/browse") ?: return null
        for (script in Regex("""/assets/index(?:-legacy)?[~-][a-zA-Z0-9_-]+\.js""").findAll(html).map { it.value }.distinct()) {
            val js = get("https://music.apple.com$script") ?: continue
            for (token in Regex("""ey[a-zA-Z0-9_-]+\.ey[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+""").findAll(js).map { it.value }) {
                val payload = runCatching { String(java.util.Base64.getUrlDecoder().decode(token.split(".")[1])) }.getOrNull() ?: continue
                val exp = Regex(""""exp":(\d+)""").find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                if (exp * 1000 > now) { appleToken = token; appleTokenExpiry = exp * 1000; return token }
            }
        }
        return null
    }

    private fun apple(track: Track): String? {
        val token = appleToken() ?: return null
        val auth = mapOf("Authorization" to "Bearer $token", "Origin" to "https://music.apple.com", "Referer" to "https://music.apple.com/")
        val search = get("https://amp-api.music.apple.com/v1/catalog/us/search?term=${enc("${primaryArtist(track)} ${track.title}")}" +
            "&types=songs&limit=10&include=albums", auth) ?: return null
        val songs = JSONObject(search).optJSONObject("results")?.optJSONObject("songs")?.optJSONArray("data") ?: return null
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val attrs = song.optJSONObject("attributes") ?: continue
            if (!titleMatch(track.title, attrs.optString("name")) || !artistMatch(track.artist, listOf(attrs.optString("artistName")))) continue
            if (!albumMatch(track, attrs.optString("albumName"))) continue
            val albumId = song.optJSONObject("relationships")?.optJSONObject("albums")?.optJSONArray("data")?.optJSONObject(0)?.optString("id")
                ?.takeIf { it.isNotBlank() }
                ?: attrs.optString("url").substringAfter("/album/", "").substringBefore("?").substringAfterLast("/").takeIf { it.all(Char::isDigit) && it.isNotBlank() }
                ?: continue
            val album = get("https://amp-api.music.apple.com/v1/catalog/us/albums/$albumId?extend=editorialVideo", auth) ?: continue
            val albumAttrs = JSONObject(album).optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes") ?: continue
            // A DJ mix or various-artists compilation's video is that compilation's, not this song's.
            if (albumAttrs.optBoolean("isCompilation") && albumKey(track.album.orEmpty()) != albumKey(albumAttrs.optString("name"))) continue
            val video = albumAttrs.optJSONObject("editorialVideo") ?: continue
            for (asset in listOf("motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall", "motionTallVideo3x4")) {
                video.optJSONObject(asset)?.optString("video")?.takeIf { it.startsWith("http") }?.let { return it }
            }
        }
        return null
    }

    // ── Tidal: album video covers (MP4), via the embed player's public token ──
    private fun tidal(track: Track): String? {
        val body = get("https://api.tidal.com/v1/search?query=${enc("${track.title} ${primaryArtist(track)}")}&limit=10&types=TRACKS&countryCode=US",
            mapOf("X-Tidal-Token" to "vNVdglQOjFJJGG2U")) ?: return null
        val items = JSONObject(body).optJSONObject("tracks")?.optJSONArray("items") ?: return null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val names = item.optJSONArray("artists")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optString("name").orEmpty() } }.orEmpty()
            if (!titleMatch(track.title, item.optString("title")) || !artistMatch(track.artist, names)) continue
            if (!albumMatch(track, item.optJSONObject("album")?.optString("title").orEmpty())) continue
            val cover = item.optJSONObject("album")?.optString("videoCover")?.takeIf { it.isNotBlank() && it != "null" } ?: continue
            val parts = cover.split("-")
            // The full 1280x1280: the player's cover is large, and 640 looked soft on it.
            if (parts.size == 5) return "https://resources.tidal.com/videos/${parts.joinToString("/")}/1280x1280.mp4"
        }
        return null
    }

    // ── Vivi Music's curated library (a JSON list of song, artist, album and video) ──
    @Volatile private var viviItems: List<JSONObject>? = null
    @Volatile private var viviExpiresAt = 0L
    private fun vivi(track: Track): String? {
        val items = viviItems?.takeIf { System.currentTimeMillis() < viviExpiresAt } ?: get("https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json")?.let { body ->
            JSONObject(body).optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        }?.also { viviItems = it; viviExpiresAt = System.currentTimeMillis() + 60_000L } ?: return null
        return items.firstOrNull { item ->
            titleMatch(track.title, item.optString("song")) && artistMatch(item.optString("artist"), listOf(track.artist)) &&
                albumMatch(track, item.optString("album"))
        }?.optString("url")?.takeIf { it.startsWith("http") }
    }
}
