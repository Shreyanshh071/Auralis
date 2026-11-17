package com.auralis.music.data.importer

import android.net.Uri
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Services whose public playlist links can be imported (besides YouTube and Spotify). */
enum class ExternalService(val id: String, val displayName: String) {
    DEEZER("deezer", "Deezer"),
    TIDAL("tidal", "Tidal"),
    APPLE_MUSIC("apple", "Apple Music"),
    AMAZON_MUSIC("amazon", "Amazon Music");

    companion object {
        fun detect(url: String): ExternalService? {
            val host = runCatching { Uri.parse(url.trim()).host }.getOrNull().orEmpty().lowercase()
            return when {
                host == "deezer.com" || host.endsWith(".deezer.com") || host == "deezer.page.link" -> DEEZER
                host == "tidal.com" || host.endsWith(".tidal.com") -> TIDAL
                host == "music.apple.com" || host == "itunes.apple.com" || host == "embed.music.apple.com" -> APPLE_MUSIC
                host.startsWith("music.amazon.") || host.endsWith(".music.amazon.com") || host == "amzn.to" -> AMAZON_MUSIC
                else -> null
            }
        }
    }
}

/** A playlist (or album) read from a service, before its songs are matched to playable audio. */
data class ExternalPlaylist(
    val service: ExternalService,
    val remoteId: String,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val tracks: List<Track>
)

class ExternalImportException(message: String) : Exception(message)

/**
 * Reads public playlist and album links from Deezer, Tidal, Apple Music and Amazon Music.
 *
 * - Deezer: the public api.deezer.com API (no sign-in needed).
 * - Tidal: the API Tidal's own web player uses, with its public web token.
 * - Apple Music: the public web page, which embeds the track list as structured data.
 * - Amazon Music: best effort from the public page; Amazon often hides the list from non-browsers.
 *
 * Every song gets an "sp_" placeholder id so the shared matcher finds its official audio, exactly
 * like Spotify imports.
 */
class ExternalPlaylistImporter(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .proxyAuthenticator(com.auralis.music.data.network.ContentProxy.authenticator)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun fetch(url: String, onProgress: (String) -> Unit = {}): ExternalPlaylist = withContext(Dispatchers.IO) {
        val service = ExternalService.detect(url) ?: throw ExternalImportException("This link isn't from a supported service")
        onProgress("Reading ${service.displayName} playlist…")
        val resolved = resolveShortLink(url.trim())
        when (service) {
            ExternalService.DEEZER -> fetchDeezer(resolved, onProgress)
            ExternalService.TIDAL -> fetchTidal(resolved, onProgress)
            ExternalService.APPLE_MUSIC -> fetchApple(resolved)
            ExternalService.AMAZON_MUSIC -> fetchAmazon(resolved)
        }
    }

    // ── Deezer ─────────────────────────────────────────────────────────────────

    private fun fetchDeezer(url: String, onProgress: (String) -> Unit): ExternalPlaylist {
        val match = Regex("""deezer\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(playlist|album)/(\d+)""").find(url)
            ?: throw ExternalImportException("Paste a Deezer playlist or album link")
        val (kind, id) = match.destructured
        val meta = getJson("https://api.deezer.com/$kind/$id")
        meta.optJSONObject("error")?.let { throw ExternalImportException("Deezer: ${it.optString("message", "playlist not found")}. Make sure it's public.") }
        val title = meta.optString("title").ifBlank { "Deezer $kind" }
        val cover = meta.optString("picture_xl").ifBlank { meta.optString("cover_xl") }.ifBlank { null }
        val albumTitle = if (kind == "album") title else null
        val tracks = ArrayList<Track>()
        var next: String? = "https://api.deezer.com/$kind/$id/tracks?limit=100&index=0"
        while (next != null && tracks.size < 5000) {
            val page = getJson(next)
            val data = page.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val t = data.optJSONObject(i) ?: continue
                val name = t.optString("title")
                if (name.isBlank()) continue
                tracks += placeholder(
                    ExternalService.DEEZER, t.optString("id"), name,
                    t.optJSONObject("artist")?.optString("name").orEmpty(),
                    t.optJSONObject("album")?.optString("title")?.ifBlank { null } ?: albumTitle,
                    t.optLong("duration"),
                    t.optJSONObject("album")?.optString("cover_xl").orEmpty().ifBlank { cover.orEmpty() }
                )
            }
            onProgress("Read ${tracks.size} songs from Deezer…")
            next = page.optString("next").takeIf { it.startsWith("http") }
        }
        return ExternalPlaylist(ExternalService.DEEZER, "$kind:$id", title, meta.optString("description").ifBlank { null }, cover, tracks)
    }

    // ── Tidal ──────────────────────────────────────────────────────────────────

    private fun fetchTidal(url: String, onProgress: (String) -> Unit): ExternalPlaylist {
        val playlist = Regex("""playlist/([0-9a-fA-F-]{36})""").find(url)?.groupValues?.get(1)
        val album = Regex("""album/(\d+)""").find(url)?.groupValues?.get(1)
        val (base, remoteId) = when {
            playlist != null -> "https://api.tidal.com/v1/playlists/$playlist" to "playlist:$playlist"
            album != null -> "https://api.tidal.com/v1/albums/$album" to "album:$album"
            else -> throw ExternalImportException("Paste a Tidal playlist or album link")
        }
        val headers = mapOf("x-tidal-token" to TIDAL_WEB_TOKEN)
        val meta = runCatching { getJson("$base?countryCode=US", headers) }.getOrElse {
            throw ExternalImportException("Tidal didn't share this playlist. Make sure it's public, or export it as a CSV.")
        }
        val title = meta.optString("title").ifBlank { "Tidal playlist" }
        val coverId = meta.optString("squareImage").ifBlank { meta.optString("image") }.ifBlank { meta.optString("cover") }
        val cover = coverId.takeIf { it.isNotBlank() }?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg" }
        val tracks = ArrayList<Track>()
        var offset = 0
        val itemsPath = if (playlist != null) "items" else "tracks"
        while (tracks.size < 5000) {
            val page = getJson("$base/$itemsPath?countryCode=US&limit=100&offset=$offset", headers)
            val items = page.optJSONArray("items") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val wrapper = items.optJSONObject(i) ?: continue
                val t = wrapper.optJSONObject("item") ?: wrapper
                if (wrapper.optString("type", "track") != "track" && wrapper.has("type")) continue
                val name = t.optString("title")
                if (name.isBlank()) continue
                val artists = t.optJSONArray("artists")?.let { names(it) }
                    ?: t.optJSONObject("artist")?.optString("name").orEmpty()
                val albumObj = t.optJSONObject("album")
                val art = albumObj?.optString("cover")?.takeIf { it.isNotBlank() }
                    ?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg" }
                tracks += placeholder(ExternalService.TIDAL, t.optString("id"), name, artists,
                    albumObj?.optString("title")?.ifBlank { null }, t.optLong("duration"), art ?: cover.orEmpty())
            }
            onProgress("Read ${tracks.size} songs from Tidal…")
            offset += items.length()
            if (offset >= page.optInt("totalNumberOfItems", Int.MAX_VALUE)) break
        }
        return ExternalPlaylist(ExternalService.TIDAL, remoteId, title, meta.optString("description").ifBlank { null }, cover, tracks)
    }

    // ── Apple Music ────────────────────────────────────────────────────────────

    private fun fetchApple(url: String): ExternalPlaylist {
        val html = getText(url, BROWSER_HEADERS)
        val remoteId = Regex("""/(pl\.[A-Za-z0-9-]+|\d{6,})(?:\?|$|/)""").find(url)?.groupValues?.get(1)
            ?: url.substringAfterLast('/').substringBefore('?')
        // 1. schema.org structured data: MusicPlaylist / MusicAlbum with its tracks.
        for (block in jsonLdBlocks(html)) {
            val obj = runCatching { JSONObject(block) }.getOrNull() ?: continue
            val list = obj.optJSONArray("track") ?: obj.optJSONObject("track")?.optJSONArray("itemListElement")
                ?: obj.optJSONArray("tracks") ?: continue
            val tracks = (0 until list.length()).mapNotNull { i ->
                val t = list.optJSONObject(i)?.let { it.optJSONObject("item") ?: it } ?: return@mapNotNull null
                val name = t.optString("name").ifBlank { return@mapNotNull null }
                val by = t.opt("byArtist")
                val artist = when (by) {
                    is JSONObject -> by.optString("name")
                    is JSONArray -> names(by)
                    else -> obj.optJSONObject("byArtist")?.optString("name").orEmpty()
                }
                placeholder(ExternalService.APPLE_MUSIC, t.optString("url").ifBlank { "$i" }, name, artist,
                    t.optJSONObject("inAlbum")?.optString("name")?.ifBlank { null }, isoDuration(t.optString("duration")), "")
            }
            if (tracks.isNotEmpty()) {
                return ExternalPlaylist(ExternalService.APPLE_MUSIC, remoteId, obj.optString("name").ifBlank { metaTitle(html) },
                    obj.optString("description").ifBlank { null }, imageOf(obj) ?: ogImage(html), tracks)
            }
        }
        // 2. The page's serialized app data: track rows carry "title" and "artistName".
        val serialized = Regex("""<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        if (serialized != null) {
            val rows = ArrayList<Track>()
            collectAppleRows(runCatching { JSONArray(serialized) as Any }.getOrElse { runCatching { JSONObject(serialized) }.getOrNull() }, rows)
            if (rows.isNotEmpty()) {
                return ExternalPlaylist(ExternalService.APPLE_MUSIC, remoteId, metaTitle(html), null, ogImage(html), rows.distinctBy { "${it.title}|${it.artist}" })
            }
        }
        throw ExternalImportException("Couldn't read this Apple Music playlist. Make sure it's shared publicly.")
    }

    private fun collectAppleRows(node: Any?, out: MutableList<Track>) {
        when (node) {
            is JSONObject -> {
                val title = node.optString("title")
                val artist = node.optString("artistName")
                val isSong = node.has("duration") || node.optString("contentDescriptor").contains("song", true) ||
                    node.optJSONObject("contentDescriptor")?.optString("kind")?.contains("song", true) == true
                if (title.isNotBlank() && artist.isNotBlank() && isSong) {
                    val ms = node.optLong("duration", 0L)
                    out += placeholder(ExternalService.APPLE_MUSIC, node.optString("id").ifBlank { "${out.size}" }, title, artist,
                        node.optString("albumName").ifBlank { null }, if (ms > 10_000) ms / 1000 else ms, "")
                    return
                }
                node.keys().forEach { collectAppleRows(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectAppleRows(node.opt(i), out)
        }
    }

    // ── Amazon Music ───────────────────────────────────────────────────────────

    private fun fetchAmazon(url: String): ExternalPlaylist {
        val html = runCatching { getText(url, BROWSER_HEADERS) }.getOrElse {
            throw ExternalImportException(AMAZON_HELP)
        }
        val remoteId = Regex("""/(?:playlists|user-playlists|albums)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringAfterLast('/').substringBefore('?')
        for (block in jsonLdBlocks(html)) {
            val obj = runCatching { JSONObject(block) }.getOrNull() ?: continue
            val list = obj.optJSONArray("track") ?: continue
            val tracks = (0 until list.length()).mapNotNull { i ->
                val t = list.optJSONObject(i) ?: return@mapNotNull null
                val name = t.optString("name").ifBlank { return@mapNotNull null }
                val artist = t.optJSONObject("byArtist")?.optString("name") ?: t.optJSONArray("byArtist")?.let { names(it) }.orEmpty()
                placeholder(ExternalService.AMAZON_MUSIC, "$i", name, artist, null, isoDuration(t.optString("duration")), "")
            }
            if (tracks.isNotEmpty()) {
                return ExternalPlaylist(ExternalService.AMAZON_MUSIC, remoteId, obj.optString("name").ifBlank { metaTitle(html) },
                    null, imageOf(obj) ?: ogImage(html), tracks)
            }
        }
        // Amazon's server-rendered rows: <music-image-row primary-text="Song" secondary-text-1="Artist" ...>
        val rows = Regex("""<music-(?:image|text)-row[^>]*primary-text="([^"]+)"[^>]*secondary-text-1="([^"]*)"""")
            .findAll(html).mapIndexed { i, m ->
                placeholder(ExternalService.AMAZON_MUSIC, "$i", unescape(m.groupValues[1]), unescape(m.groupValues[2]), null, 0L, "")
            }.toList()
        if (rows.isNotEmpty()) {
            return ExternalPlaylist(ExternalService.AMAZON_MUSIC, remoteId, metaTitle(html), null, ogImage(html), rows)
        }
        throw ExternalImportException(AMAZON_HELP)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun placeholder(
        service: ExternalService, remoteId: String, title: String, artist: String,
        album: String?, durationSec: Long, thumbnail: String
    ) = Track(
        id = "sp_${service.id}_" + remoteId.ifBlank { Integer.toHexString("$artist|$title".hashCode()) }
            .replace(Regex("[^A-Za-z0-9_.-]"), "_").takeLast(48),
        title = title.trim(),
        artist = artist.trim().ifBlank { "Unknown artist" },
        album = album,
        duration = durationSec,
        thumbnail = thumbnail,
        source = TrackSource.YOUTUBE
    )

    private fun names(array: JSONArray) = (0 until array.length())
        .mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
        .joinToString(", ")

    /** Follows deezer.page.link / amzn.to style short links to the real page. */
    private fun resolveShortLink(url: String): String {
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
        if (host != "deezer.page.link" && host != "link.deezer.com" && host != "amzn.to") return url
        return runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()).execute().use { it.request.url.toString() }
        }.getOrDefault(url)
    }

    private fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ExternalImportException("The service answered ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject =
        JSONObject(getText(url, headers + ("Accept" to "application/json")))

    private fun jsonLdBlocks(html: String): List<String> =
        Regex("""<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { it.groupValues[1].trim() }.toList()

    private fun metaTitle(html: String): String =
        Regex("""<meta[^>]*property="og:title"[^>]*content="([^"]+)"""").find(html)?.groupValues?.get(1)?.let(::unescape)
            ?: Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim()?.let(::unescape)
            ?: "Imported playlist"

    private fun ogImage(html: String): String? =
        Regex("""<meta[^>]*property="og:image"[^>]*content="([^"]+)"""").find(html)?.groupValues?.get(1)

    private fun imageOf(obj: JSONObject): String? = when (val image = obj.opt("image")) {
        is String -> image
        is JSONArray -> image.optString(0).takeIf { it.isNotBlank() }
        is JSONObject -> image.optString("url").takeIf { it.isNotBlank() }
        else -> null
    }

    /** ISO 8601 durations like PT3M25S. */
    private fun isoDuration(raw: String): Long {
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.\d+)?S)?""").find(raw) ?: return 0L
        val (h, min, s) = m.destructured
        return (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")

    companion object {
        /** The public token Tidal's own web player sends with every request. */
        private const val TIDAL_WEB_TOKEN = "CzET4vdadNUFQ5JU"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private val BROWSER_HEADERS = mapOf("User-Agent" to BROWSER_UA, "Accept-Language" to "en-US,en;q=0.9")
        private const val AMAZON_HELP =
            "Amazon Music didn't share this playlist's songs. Export it to CSV (for example with TuneMyMusic or Soundiiz) and import the file."
    }
}
