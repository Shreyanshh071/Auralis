package com.auralis.music.data.network.provider

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.BetterLyricsParser
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Better Lyrics / Portato provider delivering TTML and QRC syllable/word-by-word synchronized lyrics.
 */
class BetterLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.BETTER_LYRICS
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC, SyncType.PLAIN)

    companion object {
        /**
         * The Better Lyrics API host. `api.betterlyrics.org` does not resolve
         * (NXDOMAIN) — pointing at it made this provider, the only source of
         * Apple Music word-level TTML, fail instantly and silently on every
         * track, which left Musixmatch richsync as the de-facto word-timing
         * source even where its data is compressed and wrong.
         */
        private const val BASE_URL = "https://lyrics-api.boidu.dev"
        private const val BINIMUM_URL = "https://lyrics-api.binimum.org"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanCoreSongTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)
        val primaryArtist = cleanArtist
            .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: cleanArtist

        if (cleanTitle.isBlank()) return@withContext null

        val artistToUse = primaryArtist.ifBlank { cleanArtist }
        val boiduCand = fetchFromBetterLyrics(cleanTitle, artistToUse, query)
            ?: (if (artistToUse != cleanArtist) fetchFromBetterLyrics(cleanTitle, cleanArtist, query) else null)

        // If boidu returned rich word-level TTML, return immediately
        if (boiduCand != null && boiduCand.lyricsData.lines.any { it.hasWordTiming }) {
            return@withContext boiduCand
        }

        // If boidu returned 401 (uncached query) or lacked word timing, try Binimum (Metrolist's LyricsPlus mirror)
        val binimumCand = fetchFromBinimum(cleanTitle, artistToUse, query)
            ?: (if (artistToUse != cleanArtist) fetchFromBinimum(cleanTitle, cleanArtist, query) else null)

        if (binimumCand != null && binimumCand.lyricsData.lines.any { it.hasWordTiming }) {
            return@withContext binimumCand
        }

        boiduCand ?: binimumCand
    }

    private fun fetchFromBetterLyrics(cleanTitle: String, artistToUse: String, query: LyricsSearchQuery): LyricsCandidate? {
        try {
            val encSong = URLEncoder.encode(cleanTitle, "UTF-8")
            val encArtist = URLEncoder.encode(artistToUse, "UTF-8")
            val durationParam = query.durationSec?.takeIf { it > 0 }?.let { "&d=$it" } ?: ""
            val albumParam = query.album?.takeIf { it.isNotBlank() }
                ?.let { "&al=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
            val url = "$BASE_URL/getLyrics?s=$encSong&a=$encArtist$durationParam$albumParam"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)

            val ttml = json.optString("ttml")
            val qrc = json.optString("qrc")
            val lrc = json.optString("lrc")

            val lyricsContent = when {
                ttml.isNotBlank() -> ttml
                qrc.isNotBlank() -> qrc
                lrc.isNotBlank() -> lrc
                else -> ""
            }

            if (lyricsContent.isBlank()) return null

            val candTitle = json.optString("trackName").ifBlank { json.optString("name").ifBlank { cleanTitle } }
            val candArtist = json.optString("artistName").ifBlank { json.optString("artist").ifBlank { artistToUse } }
            val candDuration = json.optLong("duration", 0L).takeIf { it > 0 }

            val rawParsed = BetterLyricsParser.parse(
                content = lyricsContent,
                provider = LyricsProvider.BETTER_LYRICS,
                trackName = candTitle,
                artistName = candArtist
            ) ?: return null

            val aligned = LyricsMatcher.autoAlignLyrics(rawParsed, query.durationSec, candDuration)

            val confidence = LyricsMatcher.calculateConfidence(
                queryTitle = query.title,
                queryArtist = query.artist,
                candidateTitle = candTitle,
                candidateArtist = candArtist,
                queryDurationSec = query.durationSec,
                candidateDurationSec = candDuration
            )

            if (confidence < 50) return null

            val finalData = if (aligned.durationMs == null && candDuration != null) {
                aligned.copy(durationMs = candDuration * 1000L)
            } else {
                aligned
            }

            return LyricsCandidate(
                lyricsData = finalData,
                confidence = confidence,
                syncType = finalData.syncType,
                provider = LyricsProvider.BETTER_LYRICS
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun fetchFromBinimum(cleanTitle: String, artistToUse: String, query: LyricsSearchQuery): LyricsCandidate? {
        try {
            val encSong = URLEncoder.encode(cleanTitle, "UTF-8")
            val encArtist = URLEncoder.encode(artistToUse, "UTF-8")
            val durationParam = query.durationSec?.takeIf { it > 0 }?.let { "&duration=$it" } ?: ""
            val albumParam = query.album?.takeIf { it.isNotBlank() }
                ?.let { "&album=${URLEncoder.encode(it, "UTF-8")}" } ?: ""

            // 1. Direct metadata query
            var url = "$BINIMUM_URL/?track=$encSong&artist=$encArtist$durationParam$albumParam"
            var req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            var resp = client.newCall(req).execute()

            var body = if (resp.isSuccessful) resp.body?.string() else null
            var json = if (body != null) JSONObject(body) else null
            var results = json?.optJSONArray("results")

            // 2. Search fallback
            if (results == null || results.length() == 0) {
                val encQuery = URLEncoder.encode("$cleanTitle $artistToUse", "UTF-8")
                url = "$BINIMUM_URL/search?q=$encQuery"
                req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                resp = client.newCall(req).execute()
                body = if (resp.isSuccessful) resp.body?.string() else null
                json = if (body != null) JSONObject(body) else null
                results = json?.optJSONArray("results")
            }

            if (results == null || results.length() == 0) return null

            var bestUrl: String? = null
            var candTitle = cleanTitle
            var candArtist = artistToUse
            var candDuration: Long? = null

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val lUrl = item.optString("lyricsUrl")
                if (lUrl.isNotBlank()) {
                    bestUrl = lUrl
                    candTitle = item.optString("track_name").ifBlank { cleanTitle }
                    candArtist = item.optString("artist_name").ifBlank { artistToUse }
                    candDuration = item.optLong("duration", 0L).takeIf { it > 0 }
                    if (item.optString("timing_type").equals("word", ignoreCase = true)) {
                        break
                    }
                }
            }

            val lyricsUrl = bestUrl ?: return null

            val ttmlReq = Request.Builder().url(lyricsUrl).header("User-Agent", USER_AGENT).build()
            val ttmlResp = client.newCall(ttmlReq).execute()
            if (!ttmlResp.isSuccessful) return null
            val ttmlContent = ttmlResp.body?.string() ?: return null
            if (ttmlContent.isBlank()) return null

            val rawParsed = BetterLyricsParser.parse(
                content = ttmlContent,
                provider = LyricsProvider.BETTER_LYRICS,
                trackName = candTitle,
                artistName = candArtist
            ) ?: return null

            val aligned = LyricsMatcher.autoAlignLyrics(rawParsed, query.durationSec, candDuration)
            val confidence = LyricsMatcher.calculateConfidence(
                queryTitle = query.title,
                queryArtist = query.artist,
                candidateTitle = candTitle,
                candidateArtist = candArtist,
                queryDurationSec = query.durationSec,
                candidateDurationSec = candDuration
            )

            if (confidence < 50) return null

            val finalData = if (aligned.durationMs == null && candDuration != null) {
                aligned.copy(durationMs = candDuration * 1000L)
            } else {
                aligned
            }

            return LyricsCandidate(
                lyricsData = finalData,
                confidence = confidence,
                syncType = finalData.syncType,
                provider = LyricsProvider.BETTER_LYRICS
            )
        } catch (_: Exception) {
            return null
        }
    }
}
