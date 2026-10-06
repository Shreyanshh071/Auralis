package com.auralis.music.data.network.provider

import android.util.Log
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/**
 * AMLL (Apple Music-like Lyrics) Provider connected directly to the community-maintained
 * AMLL TTML Database (https://github.com/amll-dev/amll-ttml-db).
 *
 * All TTML files in this repository are CC0 1.0 Universal (Public Domain) and provide
 * genuine syllable-level karaoke synchronization without synthetic stretching.
 *
 * Resolution hierarchy:
 * 1. Direct Apple Music Track ID (query.appleMusicId) -> am-lyrics/{id}.ttml
 * 2. Direct Spotify Track ID (query.spotifyId) -> spotify-lyrics/{id}.ttml
 * 3. Apple Music Catalog Search -> verified AppleMusicTrack -> am-lyrics/{id}.ttml
 * 4. NetEase Cloud Music Search -> verified songId -> ncm-lyrics/{id}.ttml
 * 5. Fast global edge CDN (jsDelivr) with automatic fallback to GitHub raw.
 */
class AmllLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient,
    private val tokenManager: AppleTokenManager = AppleTokenManager(client)
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.AMLL
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC, SyncType.PLAIN)

    companion object {
        private const val TAG = "AmllLyricsSource"
        private const val JSDELIVR_BASE = "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main"
        private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main"
        private const val APPLE_MUSIC_API_BASE = "https://amp-api.music.apple.com/v1/catalog/us"
        private const val NETEASE_SEARCH_URL = "https://music.163.com/api/search/get"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val INDEX_PATH = "metadata/raw-lyrics-index.jsonl"
        private const val INDEX_FILE_NAME = "amll-raw-lyrics-index.jsonl"
        private const val INDEX_TTL_MS = 24L * 60 * 60 * 1000

        /** Where the DB index is kept between launches; set once from the Application. */
        @Volatile var indexCacheDir: java.io.File? = null
    }

    /**
     * Fetches raw TTML content from the AMLL TTML DB: GitHub raw first (~0.5s), jsDelivr as the
     * fallback (~2s per file, and slower still on a 404).
     */
    fun fetchTtmlFromDb(folder: String, id: String): String? {
        if (id.isBlank() || folder.isBlank()) return null
        return fetchText("$GITHUB_RAW_BASE/$folder/$id.ttml", "application/xml, text/xml, */*")
            ?: fetchText("$JSDELIVR_BASE/$folder/$id.ttml", "application/xml, text/xml, */*")
    }

    private fun fetchText(url: String, accept: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string()?.takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) {
        null
    }

    @Volatile private var index: AmllIndex? = null
    @Volatile private var indexLoadedAtMs = 0L
    private val indexLock = Any()

    /**
     * The DB's metadata index, refreshed daily. Kept on disk when [indexCacheDir] is set so a cold
     * start doesn't re-download 1.6 MB; a stale copy is used when the refresh fails.
     * Null only when no copy could be had at all.
     */
    private fun loadIndex(): AmllIndex? = synchronized(indexLock) {
        val now = System.currentTimeMillis()
        index?.let { if (now - indexLoadedAtMs < INDEX_TTL_MS) return it }

        val file = indexCacheDir?.let { java.io.File(it, INDEX_FILE_NAME) }
        if (index == null && file != null && file.exists() && now - file.lastModified() < INDEX_TTL_MS) {
            runCatching { AmllIndex.parse(file.readText()) }.getOrNull()?.takeIf { it.size > 0 }?.let {
                index = it
                indexLoadedAtMs = file.lastModified()
                return it
            }
        }

        val fresh = (fetchText("$GITHUB_RAW_BASE/$INDEX_PATH", "*/*") ?: fetchText("$JSDELIVR_BASE/$INDEX_PATH", "*/*"))
            ?.let { text -> runCatching { AmllIndex.parse(text) to text }.getOrNull() }
            ?.takeIf { it.first.size > 0 }
        if (fresh != null) {
            index = fresh.first
            indexLoadedAtMs = now
            file?.let { f -> runCatching { f.writeText(fresh.second) } }
            Log.d(TAG, "AMLL index loaded: ${fresh.first.size} entries")
            return fresh.first
        }

        // Refresh failed: keep whatever copy there is, and retry no sooner than a minute from now.
        val stale = index ?: file?.takeIf { it.exists() }
            ?.let { runCatching { AmllIndex.parse(it.readText()) }.getOrNull() }
            ?.takeIf { it.size > 0 }
        if (stale != null) {
            index = stale
            indexLoadedAtMs = now - INDEX_TTL_MS + 60_000L
        }
        stale
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanCoreSongTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)
        if (cleanTitle.isBlank()) return@withContext null

        val targetDurationMs = query.durationMs?.takeIf { it > 0L }
            ?: (query.durationSec?.let { it * 1000L }?.takeIf { it > 0L })

        // 1. Direct platform track ID lookups if provided
        if (!query.appleMusicId.isNullOrBlank()) {
            fetchTtmlCandidate("am-lyrics", query.appleMusicId, cleanTitle, cleanArtist, targetDurationMs, query)?.let {
                Log.d(TAG, "Resolved AMLL TTML via explicit Apple Music ID: ${query.appleMusicId}")
                return@withContext it
            }
        }

        if (!query.spotifyId.isNullOrBlank()) {
            fetchTtmlCandidate("spotify-lyrics", query.spotifyId, cleanTitle, cleanArtist, targetDurationMs, query)?.let {
                Log.d(TAG, "Resolved AMLL TTML via explicit Spotify ID: ${query.spotifyId}")
                return@withContext it
            }
        }

        // 2. The DB's own index lists every song it holds (~3k, mostly CJK). Matching it locally
        // costs one cached download; the Apple/NetEase path below takes 6-12 sequential requests
        // (10-18s on a phone), so it always lost the race and AMLL never showed anything.
        val index = loadIndex()
        if (index != null) {
            val matches = index.match(query, cleanTitle, cleanArtist)
            for (entry in matches.take(3)) {
                fetchTtmlCandidate("raw-lyrics", entry.rawFile.removeSuffix(".ttml"), cleanTitle, cleanArtist, targetDurationMs, query)?.let {
                    Log.d(TAG, "Resolved AMLL TTML via DB index: ${entry.rawFile}")
                    return@withContext it
                }
            }
            // The index is the whole DB: a song it doesn't list isn't there to find.
            return@withContext null
        }

        // 3. Index unreachable: resolve via Apple Music Catalog Search -> am-lyrics/{appleMusicId}.ttml
        searchViaAppleMusicCatalog(cleanTitle, cleanArtist, targetDurationMs, query)?.let {
            return@withContext it
        }

        // 4. Resolve via NetEase Search -> ncm-lyrics/{ncmMusicId}.ttml
        searchViaNetEase(cleanTitle, cleanArtist, targetDurationMs, query)?.let {
            return@withContext it
        }

        null
    }

    private fun fetchTtmlCandidate(
        folder: String,
        id: String,
        fallbackTitle: String,
        fallbackArtist: String,
        targetDurationMs: Long?,
        query: LyricsSearchQuery
    ): LyricsCandidate? {
        val ttmlContent = fetchTtmlFromDb(folder, id) ?: return null
        if (ttmlContent.isBlank()) return null

        val parsed = TtmlParser.parse(ttmlContent, LyricsProvider.AMLL)
        if (parsed.lines.isEmpty()) return null

        val candTitle = parsed.trackName?.ifBlank { null } ?: fallbackTitle
        val candArtist = parsed.artistName?.ifBlank { null } ?: fallbackArtist
        val effectiveDurMs = parsed.durationMs ?: parsed.effectiveDurationMs

        // Master alignment gate:
        // When parsed.durationMs is explicitly provided by TTML (<body dur="...">), verify match against audio stream.
        // When parsed.durationMs is absent, effectiveDurMs is merely the timestamp of the last vocal line.
        // Outros (fades, guitar solos) frequently mean effectiveDurMs is 5-30s shorter than audio duration.
        // Therefore, only reject if vocal timestamps overrun the audio stream (+ tolerance).
        if (targetDurationMs != null && targetDurationMs > 0L) {
            if (parsed.durationMs != null && parsed.durationMs > 0L) {
                val deltaMs = abs(targetDurationMs - parsed.durationMs)
                if (deltaMs > 5000L) {
                    Log.w(TAG, "[Master Mismatch] Rejecting AMLL candidate '$candTitle' ($folder/$id) due to duration delta ${deltaMs}ms > 5s")
                    return null
                }
            } else if (effectiveDurMs > 0L) {
                if (effectiveDurMs > targetDurationMs + LyricsAlignmentEngine.COMPATIBLE_OFFSET_MAX_DELTA_MS) {
                    Log.w(TAG, "[Master Overrun] Rejecting AMLL candidate '$candTitle' ($folder/$id) vocal end ${effectiveDurMs}ms exceeds audio ${targetDurationMs}ms")
                    return null
                }
            }
        }

        val candDurSec = parsed.durationMs?.let { it / 1000L }
        val confidence = LyricsMatcher.calculateConfidence(
            queryTitle = query.title,
            queryArtist = query.artist,
            candidateTitle = candTitle,
            candidateArtist = candArtist,
            queryDurationSec = query.durationSec,
            candidateDurationSec = candDurSec,
            queryAlbum = query.album
        )

        if (confidence < 50) {
            Log.d(TAG, "Rejecting AMLL candidate '$candTitle' due to confidence $confidence < 50")
            return null
        }

        val hasWords = WordTiming.hasGenuineWordStarts(parsed.lines)
        val resolvedSyncType = if (hasWords) SyncType.RICHSYNC else SyncType.LINE_SYNC

        val resolvedData = parsed.copy(
            trackName = candTitle,
            artistName = candArtist,
            durationMs = parsed.durationMs ?: targetDurationMs ?: effectiveDurMs.takeIf { it > 0L },
            syncType = resolvedSyncType
        )

        return LyricsCandidate(
            lyricsData = resolvedData,
            confidence = confidence,
            syncType = resolvedSyncType,
            provider = LyricsProvider.AMLL
        )
    }

    private suspend fun searchViaAppleMusicCatalog(
        cleanTitle: String,
        cleanArtist: String,
        targetDurationMs: Long?,
        query: LyricsSearchQuery
    ): LyricsCandidate? {
        try {
            val primaryArtist = cleanArtist
                .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+""", RegexOption.IGNORE_CASE))
                .firstOrNull()?.trim() ?: cleanArtist

            val searchTerm = "$cleanTitle $primaryArtist".trim()
            var token = tokenManager.getToken() ?: return null
            var tracks = executeAppleMusicSearch(searchTerm, token)

            if (tracks == null) {
                tokenManager.clearToken()
                token = tokenManager.getToken(forceRefresh = true) ?: return null
                tracks = executeAppleMusicSearch(searchTerm, token)
            }

            if (tracks.isNullOrEmpty() && primaryArtist != cleanArtist) {
                tracks = executeAppleMusicSearch("$cleanTitle $cleanArtist".trim(), token)
            }
            if (tracks.isNullOrEmpty()) {
                tracks = executeAppleMusicSearch(cleanTitle, token)
            }

            val candidates = tracks ?: emptyList()
            if (candidates.isEmpty()) return null

            val bestTrack = selectBestAppleTrack(candidates, cleanTitle, cleanArtist, targetDurationMs, query.album)
                ?: return null

            // Enforce master match window (tolerant up to 5s between YouTube stream and Apple master)
            if (targetDurationMs != null && targetDurationMs > 0L && bestTrack.durationInMillis != null && bestTrack.durationInMillis > 0L) {
                val deltaMs = abs(targetDurationMs - bestTrack.durationInMillis)
                if (deltaMs > 5000L) {
                    return null
                }
            }

            return fetchTtmlCandidate("am-lyrics", bestTrack.id, bestTrack.name, bestTrack.artistName, targetDurationMs, query)
        } catch (e: Exception) {
            Log.w(TAG, "Error in searchViaAppleMusicCatalog: ${e.message}")
            return null
        }
    }

    private fun executeAppleMusicSearch(term: String, token: String): List<AppleMusicTrack>? {
        try {
            val encodedTerm = URLEncoder.encode(term, "UTF-8")
            val url = "$APPLE_MUSIC_API_BASE/search?term=$encodedTerm&types=songs&limit=10"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.code == 401) return null
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                return parseAppleMusicResults(body)
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private fun parseAppleMusicResults(jsonStr: String): List<AppleMusicTrack> {
        val tracks = mutableListOf<AppleMusicTrack>()
        try {
            val root = JSONObject(jsonStr)
            val results = root.optJSONObject("results") ?: return emptyList()
            val songs = results.optJSONObject("songs") ?: return emptyList()
            val data = songs.optJSONArray("data") ?: return emptyList()

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val attr = item.optJSONObject("attributes") ?: continue
                val name = attr.optString("name")
                val artistName = attr.optString("artistName")
                val albumName = attr.optString("albumName").takeIf { it.isNotBlank() }
                val durationMs = if (attr.has("durationInMillis")) attr.optLong("durationInMillis") else null
                val isrc = attr.optString("isrc").takeIf { it.isNotBlank() }

                if (id.isNotBlank() && name.isNotBlank() && artistName.isNotBlank()) {
                    tracks.add(
                        AppleMusicTrack(
                            id = id,
                            name = name,
                            artistName = artistName,
                            albumName = albumName,
                            durationInMillis = durationMs,
                            isrc = isrc
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return tracks
    }

    private fun selectBestAppleTrack(
        tracks: List<AppleMusicTrack>,
        cleanTitle: String,
        cleanArtist: String,
        targetDurationMs: Long?,
        album: String?
    ): AppleMusicTrack? {
        var bestTrack: AppleMusicTrack? = null
        var bestScore = -100.0

        for (t in tracks) {
            val titleMatches = LyricsMatcher.isTitleMatching(cleanTitle, t.name)
            if (!titleMatches) continue

            val artistMatches = LyricsMatcher.isArtistMatching(cleanArtist, t.artistName)
            if (!artistMatches) continue

            var score = LyricsMatcher.diceCoefficient(cleanTitle, t.name) * 50.0 +
                    LyricsMatcher.diceCoefficient(cleanArtist, t.artistName) * 30.0

            if (targetDurationMs != null && t.durationInMillis != null) {
                val delta = abs(targetDurationMs - t.durationInMillis)
                if (delta <= 1500L) score += 20.0
                else if (delta <= 3500L) score += 10.0
                else if (delta <= 5000L) score += 5.0
                else continue // Delta exceeds acceptable window
            }

            if (!album.isNullOrBlank() && !t.albumName.isNullOrBlank() &&
                t.albumName.contains(album, ignoreCase = true)
            ) {
                score += 15.0
            }

            if (score > bestScore) {
                bestScore = score
                bestTrack = t
            }
        }
        return bestTrack
    }

    private fun searchViaNetEase(
        cleanTitle: String,
        cleanArtist: String,
        targetDurationMs: Long?,
        query: LyricsSearchQuery
    ): LyricsCandidate? {
        try {
            val encQuery = URLEncoder.encode("$cleanTitle $cleanArtist", "UTF-8")
            val url = "$NETEASE_SEARCH_URL?csrf_token=&type=1&offset=0&total=true&limit=5&s=$encQuery"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "http://music.163.com")
                .header("Cookie", "os=pc")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return null

            for (i in 0 until songs.length()) {
                val songObj = songs.optJSONObject(i) ?: continue
                val songId = songObj.optLong("id", 0L)
                if (songId == 0L) continue

                val songName = songObj.optString("name")
                val artists = songObj.optJSONArray("artists")
                val artistName = if (artists != null && artists.length() > 0) {
                    artists.optJSONObject(0)?.optString("name") ?: ""
                } else ""
                val durationMs = songObj.optLong("duration", 0L).takeIf { it > 0L }

                if (!LyricsMatcher.isTitleMatching(cleanTitle, songName)) continue
                if (!LyricsMatcher.isArtistMatching(cleanArtist, artistName)) continue

                if (targetDurationMs != null && durationMs != null) {
                    val delta = abs(targetDurationMs - durationMs)
                    if (delta > 5000L) continue
                }

                val cand = fetchTtmlCandidate("ncm-lyrics", songId.toString(), songName, artistName, targetDurationMs, query)
                if (cand != null) {
                    return cand
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

/** One song in the AMLL DB index: its names, artists, platform ids and the raw-lyrics file. */
internal class AmllIndexEntry(
    val names: List<String>,
    val artists: List<String>,
    val ids: Set<String>,
    val rawFile: String,
    val normNames: List<String>
)

internal class AmllIndex(private val entries: List<AmllIndexEntry>) {
    val size: Int get() = entries.size

    /** Matching entries, newest first (the index is appended in submission order). */
    fun match(query: LyricsSearchQuery, cleanTitle: String, cleanArtist: String): List<AmllIndexEntry> {
        val ids = listOfNotNull(query.spotifyId, query.appleMusicId).filter { it.isNotBlank() }
        if (ids.isNotEmpty()) {
            val byId = entries.asReversed().filter { e -> ids.any { it in e.ids } }
            if (byId.isNotEmpty()) return byId
        }
        val qNorm = normalize(TitleCleaner.extractBareSongTitle(cleanTitle))
        if (qNorm.isEmpty()) return emptyList()
        return entries.asReversed().filter { e ->
            // Cheap containment pre-filter before the real matchers run.
            e.normNames.any { it.isNotEmpty() && (it.contains(qNorm) || qNorm.contains(it)) } &&
                e.names.any { LyricsMatcher.isTitleMatching(cleanTitle, it) } &&
                LyricsMatcher.isArtistMatching(cleanArtist, e.artists.joinToString(", "))
        }
    }

    companion object {
        private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

        fun normalize(s: String): String = s.lowercase().replace(NON_ALNUM, "")

        fun parse(jsonl: String): AmllIndex {
            val out = ArrayList<AmllIndexEntry>(4096)
            jsonl.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                runCatching {
                    val obj = JSONObject(line)
                    val rawFile = obj.optString("rawLyricFile").takeIf { it.isNotBlank() } ?: return@runCatching
                    val meta = obj.optJSONArray("metadata") ?: return@runCatching
                    val names = mutableListOf<String>()
                    val artists = mutableListOf<String>()
                    val ids = mutableSetOf<String>()
                    for (i in 0 until meta.length()) {
                        val pair = meta.optJSONArray(i) ?: continue
                        val values = pair.optJSONArray(1) ?: continue
                        val list = (0 until values.length()).mapNotNull { values.optString(it).takeIf { v -> v.isNotBlank() } }
                        when (pair.optString(0)) {
                            "musicName" -> names += list
                            "artists" -> artists += list
                            "spotifyId", "appleMusicId", "ncmMusicId", "qqMusicId" -> ids += list
                        }
                    }
                    if (names.isEmpty()) return@runCatching
                    out += AmllIndexEntry(names, artists, ids, rawFile, names.map { normalize(TitleCleaner.extractBareSongTitle(it)) })
                }
            }
            return AmllIndex(out)
        }
    }
}
