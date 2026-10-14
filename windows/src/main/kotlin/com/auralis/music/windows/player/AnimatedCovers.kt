package com.auralis.music.windows.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.auralis.music.domain.model.Track
import com.auralis.music.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/*
 * Animated covers ("canvas"): for songs that have one, the cover in the player and the Now Playing
 * sidebar becomes a short looping video. Found on Apple Music (album motion artwork), Tidal (album
 * video covers) or Vivi Music's curated library, as Vivi Music does on Android (GPL-3.0, adapted).
 * Matching is strict (same title and every credited artist) so another song's video never shows.
 */

/** Where animated covers come from. Auto tries Apple Music, then Tidal, then the Vivi library. */
internal enum class CanvasSource(val label: String) { AUTO("Auto"), APPLE("Apple Music"), TIDAL("Tidal"), VIVI("Vivi Music library") }

internal data class CanvasPrefs(val enabled: Boolean = true, val source: CanvasSource = CanvasSource.AUTO)

/** Appearance > Animated covers, kept in its own small file. */
internal object CanvasSettings {
    private val store by lazy {
        com.auralis.music.util.PropertiesFileKeyValueStore(File(com.auralis.music.util.desktopAppDataDir(), "animated-covers.properties"))
    }
    private val mutable = MutableStateFlow(load())
    val prefs: StateFlow<CanvasPrefs> = mutable

    private fun load() = runCatching {
        CanvasPrefs(store.getString("enabled", "true") != "false",
            CanvasSource.entries.firstOrNull { it.name == store.getString("source", "AUTO") } ?: CanvasSource.AUTO)
    }.getOrDefault(CanvasPrefs())

    fun update(prefs: CanvasPrefs) {
        mutable.value = prefs
        runCatching { store.putStrings(mapOf("enabled" to prefs.enabled.toString(), "source" to prefs.source.name)) }
    }
}

/** Finds a song's animated cover; results (and misses) are kept so a song is looked up once. */
internal object CanvasLookup {
    private const val TAG = "AnimatedCovers"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private val http by lazy { com.auralis.music.data.network.NetworkClientProvider.okHttpClient }
    private val found = ConcurrentHashMap<String, String>()   // key -> url, or "" for none
    private val disk by lazy {
        com.auralis.music.util.PropertiesFileKeyValueStore(File(com.auralis.music.util.desktopAppDataDir(), "cache/animated-covers.properties"))
    }

    suspend fun find(track: Track, source: CanvasSource): String? = withContext(Dispatchers.IO) {
        if (track.title.isBlank() || track.artist.isBlank()) return@withContext null
        val key = "${source.name}|${norm(track.title)}|${norm(track.artist)}"
        found[key]?.let { return@withContext it.ifBlank { null } }
        disk.getString(key, "")?.takeIf { it.isNotEmpty() }?.let { cached ->
            val (url, at) = cached.substringBefore('\t') to (cached.substringAfter('\t', "0").toLongOrNull() ?: 0L)
            // A miss is retried after a week; a found video is kept.
            if (url.isNotBlank() || System.currentTimeMillis() - at < 7 * 86_400_000L) {
                found[key] = url; return@withContext url.ifBlank { null }
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
            }.onFailure { Log.w(TAG, "${s.label} lookup failed for '${track.title}': ${it.message}") }.getOrNull()
        }
        found[key] = url.orEmpty()
        runCatching { disk.putStrings(mapOf(key to "${url.orEmpty()}\t${System.currentTimeMillis()}")) }
        if (url != null) Log.i(TAG, "Animated cover for '${track.title}': $url")
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
    private fun titleMatch(requested: String, returned: String) = norm(requested).isNotBlank() && norm(requested) == norm(returned)
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
            val albumId = song.optJSONObject("relationships")?.optJSONObject("albums")?.optJSONArray("data")?.optJSONObject(0)?.optString("id")
                ?.takeIf { it.isNotBlank() }
                ?: attrs.optString("url").substringAfter("/album/", "").substringBefore("?").substringAfterLast("/").takeIf { it.all(Char::isDigit) && it.isNotBlank() }
                ?: continue
            val album = get("https://amp-api.music.apple.com/v1/catalog/us/albums/$albumId?extend=editorialVideo", auth) ?: continue
            val video = JSONObject(album).optJSONArray("data")?.optJSONObject(0)?.optJSONObject("attributes")?.optJSONObject("editorialVideo")
                ?: continue
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
            val cover = item.optJSONObject("album")?.optString("videoCover")?.takeIf { it.isNotBlank() && it != "null" } ?: continue
            val parts = cover.split("-")
            // 640x640 is plenty for the player and far lighter to decode than 1280x1280.
            if (parts.size == 5) return "https://resources.tidal.com/videos/${parts.joinToString("/")}/640x640.mp4"
        }
        return null
    }

    // ── Vivi Music's curated library (a JSON list of song, artist, album and video) ──
    @Volatile private var viviItems: List<JSONObject>? = null
    private fun vivi(track: Track): String? {
        val items = viviItems ?: get("https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json")?.let { body ->
            JSONObject(body).optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        }?.also { viviItems = it } ?: return null
        return items.firstOrNull { item ->
            titleMatch(track.title, item.optString("song")) && artistMatch(item.optString("artist"), listOf(track.artist)) &&
                (track.album.isNullOrBlank() || item.optString("album").isBlank() || norm(track.album.orEmpty()) == norm(item.optString("album")))
        }?.optString("url")?.takeIf { it.startsWith("http") }
    }
}

/**
 * The cover with its animated version over it when there is one (and Appearance allows it): the
 * still [artwork] shows until the first video frame, then the video fades in.
 */
@Composable
internal fun AnimatedCover(track: Track?, modifier: Modifier = Modifier, artwork: @Composable BoxScope.() -> Unit) {
    val prefs by CanvasSettings.prefs.collectAsState()
    var url by remember(track?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(track?.id, prefs) {
        url = if (track != null && prefs.enabled && com.auralis.music.desktop.playback.CanvasVideoEngine.available) CanvasLookup.find(track, prefs.source) else null
    }
    Box(modifier) {
        artwork()
        val video = url
        if (video != null) key(video) {
            var frame by remember { mutableStateOf<ImageBitmap?>(null) }
            DisposableEffect(video) {
                val stop = com.auralis.music.desktop.playback.CanvasVideoEngine.play(video) { frame = it }
                onDispose { stop?.invoke() }
            }
            val shown by animateFloatAsState(if (frame != null) 1f else 0f, tween(450), label = "canvasFadeIn")
            frame?.let { Image(it, null, Modifier.matchParentSize().alpha(shown), contentScale = ContentScale.Crop) }
        }
    }
}
