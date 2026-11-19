package com.auralis.music.windows.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.domain.model.Track
import com.auralis.music.util.Log
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/*
 * Animated covers ("canvas"): for songs that have one, the cover in the Now Playing
 * sidebar becomes a short looping video. Found on Apple Music (album motion artwork), Tidal (album
 * video covers) or Vivi Music's curated library, as Vivi Music does on Android (GPL-3.0, adapted).
 * Matching is strict so another recording's video never shows: same title including version tags
 * (a "(Mixed)" or "(Live)" result is a different recording), every credited artist, and the same
 * album when the song's album is known; a compilation's video never stands in for a song elsewhere.
 */

/** Where animated covers come from. Auto tries Apple Music, then Tidal, then the curated library. */
internal enum class CanvasSource(val label: String) { AUTO("Auto"), APPLE("Apple Music"), TIDAL("Tidal"), CURATED("Curated library") }

internal enum class StageVisual(val label: String) {
    AUTO("Auto"), SPOTIFY("Spotify Canvas"), ALBUM("Animated cover")
}

internal data class CanvasPrefs(val enabled: Boolean = true, val source: CanvasSource = CanvasSource.AUTO, val spotifyCanvas: Boolean = true,
    /** Ask Spotify with the signed-in account when SpicyLyrics has no Canvas. Off by default. */
    val spotifyAccount: Boolean = false, val stageVisual: StageVisual = StageVisual.AUTO)

/** Appearance > Animated covers, kept in its own small file. */
internal object CanvasSettings {
    private val store by lazy {
        com.auralis.music.util.PropertiesFileKeyValueStore(File(com.auralis.music.util.desktopAppDataDir(), "animated-covers.properties"))
    }
    private val mutable = MutableStateFlow(load())
    val prefs: StateFlow<CanvasPrefs> = mutable
    private val songVisuals by lazy { SongStageVisuals(PropertiesFileKeyValueStore(File(desktopAppDataDir(), "stage-visuals.properties"))) }
    val songVisualChanges get() = songVisuals.changes

    fun songVisual(track: Track) = songVisuals.get(track)
    fun setSongVisual(track: Track, visual: StageVisual?) = songVisuals.set(track, visual)

    private var introPlays = -1
    private var lastIntroSession: Long? = null

    fun initializeIntro(newProfile: Boolean) {
        introPlays = runCatching {
            val stored = store.getString("loopIntroPlays", "")?.toIntOrNull()
            stored ?: if (newProfile) 0 else -1
        }.getOrDefault(-1)
        saveIntroProgress()
    }

    fun shouldOfferIntro(session: Long): Boolean {
        if (introPlays < 0) return false
        if (introPlays < 3 && session != lastIntroSession) {
            lastIntroSession = session
            introPlays++
            saveIntroProgress()
        }
        return introPlays >= 3
    }

    fun finishIntro(useCovers: Boolean) {
        introPlays = -1
        if (useCovers) update(prefs.value.copy(spotifyCanvas = false, enabled = true)) else saveIntroProgress()
    }

    private fun saveIntroProgress() {
        runCatching { store.putStrings(mapOf("loopIntroPlays" to introPlays.toString())) }
    }

    private fun load() = runCatching {
        CanvasPrefs(store.getString("enabled", "true") != "false",
            CanvasSource.entries.firstOrNull { it.name == store.getString("source", "AUTO") } ?: CanvasSource.AUTO,
            store.getString("spotifyCanvas", "true") != "false",
            store.getString("spotifyAccount", "false") == "true",
            StageVisual.entries.firstOrNull { it.name == store.getString("stageVisual", "") } ?: StageVisual.AUTO)
    }.getOrDefault(CanvasPrefs())

    fun update(prefs: CanvasPrefs) {
        mutable.value = prefs
        runCatching { store.putStrings(mapOf("enabled" to prefs.enabled.toString(), "source" to prefs.source.name,
            "spotifyCanvas" to prefs.spotifyCanvas.toString(), "spotifyAccount" to prefs.spotifyAccount.toString(),
            "stageVisual" to prefs.stageVisual.name, "loopIntroPlays" to introPlays.toString())) }
    }
}

/** Keyed by recording and release, with legacy tracks falling back to their song ID. */
internal class SongStageVisuals(private val store: com.auralis.music.util.KeyValueStore) {
    private val revision = MutableStateFlow(0L)
    val changes: StateFlow<Long> = revision

    private fun key(track: Track): String =
        "${track.id.length}:${track.id}:${track.albumId ?: track.album.orEmpty().trim().lowercase()}"

    fun get(track: Track): StageVisual? =
        StageVisual.entries.firstOrNull { it.name == store.getString(key(track), "") && it != StageVisual.AUTO }

    @Synchronized
    fun set(track: Track, visual: StageVisual?) {
        store.putStrings(mapOf(key(track) to (visual?.name ?: "")))
        revision.value++
    }
}

/** Finds a song's animated cover; results (and misses) are kept so a song is looked up once. */
internal object CanvasLookup {
    private const val TAG = "AnimatedCovers"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private val http by lazy { com.auralis.music.data.network.NetworkClientProvider.okHttpClient }
    private val found = ConcurrentHashMap<String, String>()   // key -> url, or "" for none
    private val lookupLocks = Array(32) { kotlinx.coroutines.sync.Mutex() }
    private val videoLocks = Array(32) { kotlinx.coroutines.sync.Mutex() }
    private val disk by lazy {
        com.auralis.music.util.PropertiesFileKeyValueStore(File(com.auralis.music.util.desktopAppDataDir(), "cache/animated-covers.properties"))
    }

    suspend fun find(track: Track, source: CanvasSource, preferTall: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (track.title.isBlank() || track.artist.isBlank()) return@withContext null
        // v3: version tags and the album count; v4 (preferTall) drops 3:4 / square assets from the stage.
        val version = if (preferTall) "v4" else "v3"
        val key = "$version|${source.name}|${titleKey(track.title)}|${norm(track.artist)}|${albumKey(track.album.orEmpty())}|tall=$preferTall"
        found[key]?.let { return@withContext it.ifBlank { null } }
        lookupLocks[key.hashCode().ushr(1) % lookupLocks.size].withLock {
            findUncached(key, track, source, preferTall)
        }
    }

    private suspend fun findUncached(key: String, track: Track, source: CanvasSource, preferTall: Boolean): String? {
        found[key]?.let { return it.ifBlank { null } }
        disk.getString(key, "")?.takeIf { it.isNotEmpty() }?.let { cached ->
            val (url, at) = cached.substringBefore('\t') to (cached.substringAfter('\t', "0").toLongOrNull() ?: 0L)
            // A miss is retried after a week; a found video is kept.
            if (url.isNotBlank() || System.currentTimeMillis() - at < 7 * 86_400_000L) {
                found[key] = url
                return url.ifBlank { null }
            }
        }
        val sources = if (source == CanvasSource.AUTO) listOf(CanvasSource.APPLE, CanvasSource.TIDAL, CanvasSource.CURATED) else listOf(source)
        val url = sources.firstNotNullOfOrNull { s ->
            runCatching {
                when (s) {
                    CanvasSource.APPLE -> apple(track, preferTall)
                    CanvasSource.TIDAL -> if (preferTall) null else tidal(track)
                    CanvasSource.CURATED -> curated(track)
                    CanvasSource.AUTO -> null
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "${s.label} lookup failed for '${track.title}': ${it.message}")
            }.getOrNull()
        }
        found[key] = url.orEmpty()
        runCatching { disk.putStrings(mapOf(key to "${url.orEmpty()}\t${System.currentTimeMillis()}")) }
        if (url != null) Log.i(TAG, "Animated cover (tall=$preferTall) for '${track.title}': $url")
        return url
    }

    private val videoFolder by lazy { File(com.auralis.music.util.desktopAppDataDir(), "cache/animated-covers") }
    private const val MAX_VIDEO_BYTES = 64L * 1024 * 1024

    /**
     * A source VLC can open. The curated library serves MP4s with their index at the end and
     * ignores range requests, so VLC cannot stream them; those are kept as local files (as the
     * phone's player effectively does). Apple's HLS streams are returned unchanged.
     */
    suspend fun playable(url: String): String = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://") || url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) return@withContext url
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val name = digest.take(16).joinToString("") { "%02x".format(it) } + ".mp4"
        videoLocks[name.hashCode().ushr(1) % videoLocks.size].withLock {
            val target = File(videoFolder, name)
            if (target.isFile && target.length() > 64 * 1024) return@withLock target.absolutePath
            runCatching {
                videoFolder.mkdirs()
                val temporary = File.createTempFile(name, ".partial", videoFolder)
                try {
                    http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { response ->
                        check(response.isSuccessful)
                        var bytes = 0L
                        temporary.outputStream().use { output ->
                            checkNotNull(response.body).byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    bytes += count
                                    check(bytes <= MAX_VIDEO_BYTES)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        check(bytes > 64 * 1024)
                    }
                    java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    target.absolutePath
                } finally {
                    temporary.delete()
                }
            }.onFailure { Log.w(TAG, "Could not keep animated cover locally: ${it.message}") }
                .getOrDefault(url)
        }
    }

    internal fun appleAssetKeys(preferTall: Boolean): List<String> = if (preferTall) {
        listOf("motionDetailTall")
    } else {
        listOf("motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall", "motionTallVideo3x4")
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

    private fun apple(track: Track, preferTall: Boolean = false): String? {
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
            for (asset in appleAssetKeys(preferTall)) {
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

    // ── The curated library (a JSON list of song, artist, album and video) ──
    @Volatile private var curatedItems: List<JSONObject>? = null
    private fun curated(track: Track): String? {
        val items = curatedItems ?: get("https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json")?.let { body ->
            JSONObject(body).optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        }?.also { curatedItems = it } ?: return null
        return items.firstOrNull { item ->
            titleMatch(track.title, item.optString("song")) && artistMatch(item.optString("artist"), listOf(track.artist)) &&
                albumMatch(track, item.optString("album"))
        }?.optString("url")?.takeIf { it.startsWith("http") }
    }
}

/** Spotify Canvas is a portrait track video. Album motion artwork has its own crop. */
internal fun isStageCanvasAspect(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && kotlin.math.abs(width.toDouble() / height - 9.0 / 16) < .05

internal suspend fun <T : Any> preferredStageSource(
    preferred: Deferred<T?>, fallback: Deferred<T?>, graceMs: Long? = null
): Pair<T?, Deferred<T?>?> {
    if (graceMs == null) {
        return preferred.await()?.let { it to fallback } ?: (fallback.await() to null)
    }
    // Give Auto's preferred source a head start. A slow catalogue lookup must not
    // keep an already playable album video off the stage indefinitely.
    withTimeoutOrNull(graceMs) { preferred.await() to Unit }?.let { (video, _) ->
        return if (video != null) video to fallback else fallback.await() to null
    }
    return select {
        preferred.onAwait { if (it != null) it to fallback else fallback.await() to null }
        fallback.onAwait { if (it != null) it to preferred else preferred.await() to null }
    }
}

internal const val AUTO_CANVAS_HEAD_START_MS = 1_250L

internal suspend fun <T : Any> chooseAutoStageSource(
    cached: Deferred<T?>, spotify: Deferred<T?>, album: Deferred<T?>
): Pair<T?, Deferred<T?>?> = cached.await()?.let { it to album }
    ?: preferredStageSource(spotify, album, graceMs = AUTO_CANVAS_HEAD_START_MS)

internal data class StageVideo(val path: String, val spotifyId: String? = null)

/** Survives closing the player. The owner creates a new choice for each playback session. */
internal class StagePlaybackChoice {
    private var settings: Pair<CanvasPrefs, StageVisual?>? = null
    private var video: StageVideo? = null

    fun current(prefs: CanvasPrefs, songVisual: StageVisual?): StageVideo? =
        video.takeIf { settings == (prefs to songVisual) }

    fun commit(prefs: CanvasPrefs, songVisual: StageVisual?, video: StageVideo) {
        if (current(prefs, songVisual) != null) return
        settings = prefs to songVisual
        this.video = video
    }

    fun forgetFailed(prefs: CanvasPrefs, songVisual: StageVisual?, path: String) {
        if (current(prefs, songVisual)?.path == path) video = null
    }
}

private enum class VideoStart { READY, REJECTED, COVER_ONLY }

/**
 * The cover with its animated version over it when there is one (and Appearance allows it): the
 * still [artwork] shows until the first video frame, then the video fades in.
 */
@Composable
internal fun AnimatedCover(track: Track?, modifier: Modifier = Modifier,
    motionAura: MotionAura? = null, spotifyCanvasId: String? = null, stage: Boolean = false,
    playbackClock: PlaybackClockSource? = null, enabled: Boolean = true,
    songVisual: StageVisual? = null,
    stageChoice: StagePlaybackChoice? = null,
    artwork: @Composable BoxScope.() -> Unit) {
    val prefs by CanvasSettings.prefs.collectAsState()
    val visual = songVisual ?: prefs.stageVisual
    val trackKey = if (stage) track?.id else track
    var lookupHint by remember(track?.id, stageChoice, prefs, songVisual) { mutableStateOf(spotifyCanvasId) }
    var url by remember(trackKey, prefs, lookupHint, stage, enabled, songVisual, stageChoice) { mutableStateOf<String?>(null) }
    var resolvedSpotifyId by remember(trackKey, prefs, lookupHint, stage, enabled, songVisual, stageChoice) { mutableStateOf<String?>(null) }
    var alternateStageVideo by remember(trackKey, prefs, lookupHint, stage, enabled, songVisual, stageChoice) {
        mutableStateOf<Deferred<StageVideo?>?>(null)
    }
    val rejectedStageUrls = remember(trackKey, prefs, lookupHint, stage, enabled, songVisual, stageChoice) { mutableSetOf<String>() }
    LaunchedEffect(spotifyCanvasId, url, stageChoice, track?.id, prefs, songVisual) {
        // Lyrics may supply an ID while the stage still shows the static cover.
        if (stage && url == null && stageChoice?.current(prefs, songVisual) == null && spotifyCanvasId != null) {
            lookupHint = spotifyCanvasId
        }
    }
    fun showStageVideo(video: StageVideo?) {
        resolvedSpotifyId = video?.spotifyId
        url = video?.path
        when {
            video == null -> Unit
            video.spotifyId != null -> Log.i("AnimatedCovers", "Stage for '${track?.title}': Spotify Canvas ${video.spotifyId}")
            else -> Log.i("AnimatedCovers", "Stage for '${track?.title}': album motion artwork")
        }
    }
    LaunchedEffect(trackKey, prefs, lookupHint, stage, enabled, songVisual, stageChoice) {
        val spotifyAllowed = stage && prefs.spotifyCanvas && songVisual != StageVisual.ALBUM
        val allowed = enabled && (prefs.enabled || spotifyAllowed)
        if (track != null && allowed && !com.auralis.music.windows.dev.PerfFlags.off("animatedcover")) coroutineScope {
            val engineAvailable = async(Dispatchers.IO) {
                com.auralis.music.desktop.playback.CanvasVideoEngine.available
            }
            if (stage) {
                val album = async {
                    // Use the original album motion artwork. Apple's tall rendition can contain a cover tile.
                    if (prefs.enabled) CanvasLookup.find(track, prefs.source)
                        ?.let { StageVideo(CanvasLookup.playable(it)) } else null
                }
                val cachedSpotify = async(Dispatchers.IO) {
                    if (spotifyAllowed && visual == StageVisual.AUTO)
                        TrackCanvasLookup.findCached(track, lookupHint, matchRelease = true)
                            ?.takeUnless { CoverOnlyCanvas.verdict(it.spotifyId) == true }
                            ?.let { StageVideo(it.path, it.spotifyId) }
                    else null
                }
                val spotify = async(Dispatchers.IO) {
                    cachedSpotify.await() ?: if (spotifyAllowed)
                        TrackCanvasLookup.find(track, lookupHint, matchRelease = visual != StageVisual.SPOTIFY)
                        ?.takeUnless { CoverOnlyCanvas.verdict(it.spotifyId) == true }
                        ?.let { StageVideo(it.path, it.spotifyId) } else null
                }
                if (engineAvailable.await()) {
                    val committed = stageChoice?.current(prefs, songVisual)
                    val (first, alternate) = when {
                        committed != null -> committed to if (committed.spotifyId == null) spotify else album
                        !spotifyAllowed || visual == StageVisual.ALBUM -> preferredStageSource(album, spotify)
                        !prefs.enabled || visual == StageVisual.SPOTIFY -> preferredStageSource(spotify, album)
                        else -> chooseAutoStageSource(cachedSpotify, spotify, album)
                    }
                    alternateStageVideo = alternate
                    showStageVideo(first)
                } else {
                    album.cancel()
                    cachedSpotify.cancel()
                    spotify.cancel()
                    showStageVideo(null)
                }
            } else if (engineAvailable.await()) {
                url = CanvasLookup.find(track, prefs.source)?.let { CanvasLookup.playable(it) }
                Log.i("AnimatedCovers", "Cover for '${track.title}': ${url ?: "still artwork"}")
            }
        }
    }
    Box(modifier) {
        artwork()
        val video = url
        val visualsActive = com.auralis.music.ui.player.LocalPlaybackVisualsActive.current
        val visibleNow by rememberUpdatedState(visualsActive)
        var retainDecoder by remember(trackKey, video, stageChoice) { mutableStateOf(false) }
        if (video != null && (visualsActive || (stage && retainDecoder))) key(track?.id, video, stageChoice) {
            var frame by remember { mutableStateOf<ImageBitmap?>(null) }
            val firstFrame = remember { CompletableDeferred<VideoStart>() }
            val decodedFrame = remember { CompletableDeferred<Unit>() }
            // First play of a stage Canvas: compare its frames with the
            // song's cover, so a Canvas that is only that cover on a blur is never shown.
            val stageSpotifyId = resolvedSpotifyId?.takeIf { stage }
            val checkId = stageSpotifyId?.takeIf { CoverOnlyCanvas.verdict(it) == null }
            val coverPixels = remember { java.util.concurrent.atomic.AtomicReference<IntArray?>(null) }
            val coverPending = remember(checkId) { java.util.concurrent.atomic.AtomicBoolean(checkId != null) }
            val gate = remember(checkId) { CoverOnlyGate(checkId, coverPixels, coverPending) }
            if (checkId != null) LaunchedEffect(checkId) {
                try {
                    coverPixels.set(withContext(Dispatchers.IO) { CoverOnlyCanvas.coverPixels(checkId, track?.thumbnail) })
                } finally { coverPending.set(false) }
            }
            LaunchedEffect(video, motionAura, stage, stageSpotifyId, playbackClock) {
                if (stage) retainDecoder = true
                val active = java.util.concurrent.atomic.AtomicBoolean(true)
                var stop: (() -> Unit)? = null
                try {
                    withContext(Dispatchers.IO) {
                        stop = com.auralis.music.desktop.playback.CanvasVideoEngine.play(
                            video, clock = playbackClock, frameShortSide = if (stage) 1440 else 960,
                            loop = true, canPause = { firstFrame.isCompleted },
                            videoVisible = if (stage) ({ visibleNow }) else null
                        ) {
                            synchronized(active) {
                                if (active.get()) {
                                    // Stage slots require ~9:16 portrait video (allowing slight VLC macroblock padding).
                                    if (stageSpotifyId == null || isStageCanvasAspect(it.width, it.height)) {
                                        decodedFrame.complete(Unit)
                                        if (!firstFrame.isCompleted || frame != null) when (gate.allow(it)) {
                                            true -> {
                                                frame = it
                                                motionAura?.offer(it)
                                                firstFrame.complete(VideoStart.READY)
                                            }
                                            false -> Unit
                                            null -> firstFrame.complete(VideoStart.COVER_ONLY)
                                        }
                                    } else firstFrame.complete(VideoStart.REJECTED)
                                }
                            }
                        }
                    }
                    if (stop == null) firstFrame.complete(VideoStart.REJECTED)
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    synchronized(active) { active.set(false); motionAura?.offer(null) }
                    stop?.invoke()
                }
            }
            if (stage) LaunchedEffect(firstFrame, video) {
                val started = System.currentTimeMillis()
                val alternate = alternateStageVideo
                val result = if (alternate == null) firstFrame.await() else {
                    val startupBudget = if (stageSpotifyId != null && visual == StageVisual.AUTO) 1_800L else 5_000L
                    val startedVideo = withTimeoutOrNull(startupBudget) { firstFrame.await() }
                    val assessedVideo = if (startedVideo == null && decodedFrame.isCompleted) {
                        // A decoding Canvas may still be going through the cover-only check.
                        withTimeoutOrNull(5_000L) { firstFrame.await() }
                    } else startedVideo
                    assessedVideo ?: select<VideoStart?> {
                        firstFrame.onAwait { it }
                        alternate.onAwait { if (it != null) null else firstFrame.await() }
                    }
                }
                if (result == VideoStart.READY) {
                    Log.i("AnimatedCovers", "Stage video visible after ${System.currentTimeMillis() - started}ms")
                    stageChoice?.commit(prefs, songVisual, StageVideo(video, stageSpotifyId))
                    // Source lookups finish in the background and warm the cache for the next play.
                    if (stageSpotifyId != null) SpotifyCanvasLookup.sharp(stageSpotifyId)
                    return@LaunchedEffect
                }
                stageChoice?.forgetFailed(prefs, songVisual, video)
                rejectedStageUrls.add(video)
                alternateStageVideo = null
                val replacement = alternate?.await()?.takeUnless { it.path in rejectedStageUrls }
                    ?: if (result == VideoStart.REJECTED && stageSpotifyId != null) {
                        // A stalled decoder alone does not invalidate the cached file.
                        SpotifyCanvasLookup.fallback(stageSpotifyId, video)
                            ?.takeUnless(rejectedStageUrls::contains)
                            ?.let { StageVideo(it, stageSpotifyId) }
                    } else null
                showStageVideo(replacement)
            }
            CanvasVideoFrame(frame, stage)
        }
    }
}


/**
 * Decides, from a stage Canvas's own frames, whether it is only the cover on a blur. [allow] says
 * per frame: true = show it, false = hold it (still checking), null = cover-only, never show.
 * Frames are sampled at about 1, 2, 3 and 4 s of playback and averaged. A first sample that is
 * clearly not the cover ends the check at once. Wait for artwork loading before judging the
 * Canvas: a fresh install can take longer than 1.5 s to download the reference cover.
 */
internal class CoverOnlyGate(
    private val spotifyId: String?,
    private val cover: java.util.concurrent.atomic.AtomicReference<IntArray?>,
    private val coverPending: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    @Volatile var checking = spotifyId != null
        private set
    private var start = 0L
    private var blocked = false
    private val scores = mutableListOf<Double>()

    fun allow(frame: ImageBitmap): Boolean? {
        if (blocked) return null
        if (!checking || spotifyId == null) return true
        val coverPixels = cover.get()
        // A slow reference-art download is not evidence that this Canvas is unavailable.
        if (coverPixels == null && coverPending.get()) return false
        val now = nowMs()
        if (start == 0L) start = now
        val elapsed = now - start
        if (coverPixels == null) {
            // Without the album art the Canvas cannot be checked, so it is not shown.
            if (elapsed < 1500) return false
            checking = false
            blocked = true
            return null
        }
        val due = (scores.size + 1) * 1000L
        if (elapsed < due) return false
        scores += CoverOnlyCanvas.bestMatch(CoverOnlyCanvas.framePixels(frame), CoverOnlyCanvas.FRAME_WIDTH,
            CoverOnlyCanvas.FRAME_HEIGHT, coverPixels, CoverOnlyCanvas.COVER_SIDE, CoverOnlyCanvas.COVER_SIDE)
        if (scores.size == 1 && scores[0] < CoverOnlyCanvas.REAL_FIRST) return decide(false)
        if (scores.size < 4) return false
        return decide(CoverOnlyCanvas.isCoverOnly(scores))
    }

    private fun decide(coverOnly: Boolean): Boolean? {
        checking = false
        CoverOnlyCanvas.remember(spotifyId!!, coverOnly)
        return if (coverOnly) null else true
    }
}
