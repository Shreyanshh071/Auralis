package com.auralis.music.data.network.provider

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.data.parser.MusixmatchRichsyncParser
import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Musixmatch word sync (RichSync), line sync and plain lyrics. The only source with word timing
 * for many Indian songs Apple Music syncs by line only ("Babuaan", Pawan Singh feat. Shilpi Raj).
 */
class MusixmatchLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.MUSIXMATCH
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC, SyncType.PLAIN)

    companion object {
        // The desktop app id now gets an all-zero token and decoy results ("Casual" by Doja Cat
        // for every search); the Android app id still gets a real token and real results.
        private const val API_BASE = "https://apic.musixmatch.com/ws/1.1"
        private const val APP_ID = "android-player-v1.0"
        private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 13)"
        /** After Musixmatch answers "captcha", stay away this long instead of asking every song. */
        private const val CAPTCHA_BACKOFF_MS = 30L * 60_000L

        // One token per app run, shared by every instance: token.get is the rate-limited call.
        @Volatile private var token: String? = null
        @Volatile private var blockedUntilMs = 0L
    }

    private class MxmTrack(json: JSONObject) {
        val id = json.optLong("track_id", 0L)
        val title: String = json.optString("track_name")
        val artist: String = json.optString("artist_name")
        val lengthSec = json.optLong("track_length", 0L)
        val hasRichsync = json.optInt("has_richsync", 0) == 1
        val hasSubtitles = json.optInt("has_subtitles", 0) == 1
        val hasLyrics = json.optInt("has_lyrics", 0) == 1
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() < blockedUntilMs) return@withContext null
        val cleanTitle = TitleCleaner.cleanCoreSongTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)
        if (cleanTitle.isBlank()) return@withContext null
        val primaryArtist = cleanArtist
            .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: cleanArtist

        // Field search misses entries credited differently ("Babuaan" is filed under
        // "Pawan Singh feat. Shilpi Raj"), so a free-text search runs too.
        val found = LinkedHashMap<Long, MxmTrack>()
        for (params in listOf(
            "q_track=${enc(cleanTitle)}&q_artist=${enc(primaryArtist)}&s_track_rating=desc",
            "q=${enc("$cleanTitle $cleanArtist".trim())}"
        )) {
            val list = apiGet("track.search", "$params&page_size=6")?.optJSONArray("track_list") ?: continue
            for (i in 0 until list.length()) {
                val t = MxmTrack(list.optJSONObject(i)?.optJSONObject("track") ?: continue)
                if (t.id != 0L) found.putIfAbsent(t.id, t)
            }
        }

        val ranked = found.values
            .filter { it.hasRichsync || it.hasSubtitles || it.hasLyrics }
            .map { it to confidenceOf(it, query) }
            .filter { it.second >= 50 }
            .sortedWith(
                compareByDescending<Pair<MxmTrack, Int>> { it.first.hasRichsync }
                    .thenByDescending { it.first.hasSubtitles }
                    .thenByDescending { it.second }
            )

        for ((track, confidence) in ranked) {
            candidateFor(track, confidence, cleanTitle, cleanArtist, query)?.let { return@withContext it }
        }
        null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun confidenceOf(t: MxmTrack, query: LyricsSearchQuery) = LyricsMatcher.calculateConfidence(
        queryTitle = query.title,
        queryArtist = query.artist,
        candidateTitle = t.title,
        candidateArtist = t.artist,
        queryDurationSec = query.durationSec,
        candidateDurationSec = t.lengthSec.takeIf { it > 0L }
    )

    /** GET [url] and return its message (header + body), or null. Starts the back-off on "captcha". */
    private fun request(url: String): JSONObject? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val message = JSONObject(resp.body?.string() ?: "").optJSONObject("message")
            val header = message?.optJSONObject("header")
            if (header?.optInt("status_code") == 401 && header.optString("hint") == "captcha") {
                blockedUntilMs = System.currentTimeMillis() + CAPTCHA_BACKOFF_MS
            }
            message
        }
    } catch (_: Exception) { null }

    private fun fetchToken(): String? {
        token?.let { return it }
        val body = request("$API_BASE/token.get?app_id=$APP_ID&format=json")?.optJSONObject("body") ?: return null
        val t = body.optString("user_token").takeIf { it.isNotBlank() && it.any { c -> c != '0' } } ?: return null
        token = t
        return t
    }

    /** message.body of an API call, or null. Renews an expired token once. */
    private fun apiGet(method: String, params: String): JSONObject? {
        repeat(2) {
            if (System.currentTimeMillis() < blockedUntilMs) return null
            val t = fetchToken() ?: return null
            val message = request("$API_BASE/$method?app_id=$APP_ID&format=json&usertoken=$t&$params") ?: return null
            if (message.optJSONObject("header")?.optInt("status_code") == 401) {
                token = null
                return@repeat
            }
            return message.optJSONObject("body")
        }
        return null
    }

    private fun candidateFor(
        t: MxmTrack,
        confidence: Int,
        title: String,
        artist: String,
        query: LyricsSearchQuery
    ): LyricsCandidate? {
        val candTitle = t.title.ifBlank { title }
        val candArtist = t.artist.ifBlank { artist }

        // 1. Word-synced RichSync
        if (t.hasRichsync) {
            val richsync = apiGet("track.richsync.get", "track_id=${t.id}")?.optJSONObject("richsync")
            val body = richsync?.optString("richsync_body")
            if (!body.isNullOrBlank()) {
                // Some entries have no track_length; the richsync states the length it was timed to.
                val lengthSec = t.lengthSec.takeIf { it > 0L } ?: richsync.optLong("richsync_length", 0L)
                val lengthMs = lengthSec.takeIf { it > 0L }?.times(1000L)
                val parsed = MusixmatchRichsyncParser.parse(
                    richsyncBody = body,
                    provider = LyricsProvider.MUSIXMATCH,
                    trackName = candTitle,
                    artistName = candArtist
                )
                if (parsed != null && parsed.lines.isNotEmpty() && parsed.syncType == SyncType.RICHSYNC) {
                    val aligned = LyricsMatcher.autoAlignLyrics(parsed, query.durationSec, lengthSec)
                        .copy(durationMs = lengthMs ?: parsed.durationMs)
                    return LyricsCandidate(aligned, confidence, SyncType.RICHSYNC, LyricsProvider.MUSIXMATCH)
                }
            }
        }

        // 2. Line-synced subtitles (LRC)
        if (t.hasSubtitles) {
            val lrc = apiGet("track.subtitle.get", "track_id=${t.id}&subtitle_format=lrc")
                ?.optJSONObject("subtitle")?.optString("subtitle_body")
            if (!lrc.isNullOrBlank()) {
                val parsed = LrcParser.parse(lrc, LyricsProvider.MUSIXMATCH)
                if (parsed.lines.isNotEmpty()) {
                    val lengthMs = t.lengthSec.takeIf { it > 0L }?.times(1000L)
                    val aligned = LyricsMatcher.autoAlignLyrics(
                        lyricsData = parsed.copy(trackName = candTitle, artistName = candArtist, durationMs = lengthMs ?: parsed.durationMs),
                        trackDurationSec = query.durationSec,
                        lyricDurationSec = t.lengthSec
                    ).copy(durationMs = lengthMs ?: parsed.durationMs)
                    return LyricsCandidate(aligned, confidence, aligned.syncType, LyricsProvider.MUSIXMATCH)
                }
            }
        }

        // 3. Plain lyrics
        if (t.hasLyrics) {
            val raw = apiGet("track.lyrics.get", "track_id=${t.id}")?.optJSONObject("lyrics")?.optString("lyrics_body")
            if (!raw.isNullOrBlank()) {
                val clean = raw.substringBefore("******* This Lyrics is NOT for Commercial use *******").trim()
                val lines = clean.lines().map { it.trim() }.filter { it.isNotBlank() }.map { LyricLine(time = 0L, text = it) }
                if (lines.isNotEmpty()) {
                    return LyricsCandidate(
                        lyricsData = LyricsData(
                            provider = LyricsProvider.MUSIXMATCH,
                            syncType = SyncType.PLAIN,
                            lines = lines,
                            plainLyrics = clean,
                            trackName = candTitle,
                            artistName = candArtist,
                            durationMs = t.lengthSec.takeIf { it > 0L }?.times(1000L)
                        ),
                        confidence = confidence,
                        syncType = SyncType.PLAIN,
                        provider = LyricsProvider.MUSIXMATCH
                    )
                }
            }
        }
        return null
    }
}
