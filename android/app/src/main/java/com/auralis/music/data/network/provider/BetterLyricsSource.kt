package com.auralis.music.data.network.provider

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.BetterLyricsParser
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
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

        val targetDurSec = query.durationSec?.takeIf { it > 0 }
            ?: query.durationMs?.let { it / 1000L }?.takeIf { it > 0 }
        val effectiveQuery = if (query.durationSec == null && targetDurSec != null) {
            query.copy(durationSec = targetDurSec)
        } else query

        val artistToUse = primaryArtist.ifBlank { cleanArtist }

        coroutineScope {
            val boiduDeferred = async {
                var cand = fetchFromBetterLyrics(cleanTitle, artistToUse, effectiveQuery, useAlbum = true)
                    ?: (if (artistToUse != cleanArtist) fetchFromBetterLyrics(cleanTitle, cleanArtist, effectiveQuery, useAlbum = true) else null)
                if (cand == null && !effectiveQuery.album.isNullOrBlank()) {
                    cand = fetchFromBetterLyrics(cleanTitle, artistToUse, effectiveQuery, useAlbum = false)
                        ?: (if (artistToUse != cleanArtist) fetchFromBetterLyrics(cleanTitle, cleanArtist, effectiveQuery, useAlbum = false) else null)
                }
                cand
            }

            val binimumDeferred = async {
                fetchFromBinimum(cleanTitle, artistToUse, effectiveQuery)
                    ?: (if (artistToUse != cleanArtist) fetchFromBinimum(cleanTitle, cleanArtist, effectiveQuery) else null)
            }

            select<LyricsCandidate?> {
                boiduDeferred.onAwait { cand: LyricsCandidate? ->
                    if (cand != null && com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(cand.lyricsData.lines)) {
                        binimumDeferred.cancel()
                        cand
                    } else {
                        val bini = binimumDeferred.await()
                        if (bini != null && com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(bini.lyricsData.lines)) {
                            bini
                        } else cand ?: bini
                    }
                }
                binimumDeferred.onAwait { cand: LyricsCandidate? ->
                    if (cand != null && com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(cand.lyricsData.lines)) {
                        boiduDeferred.cancel()
                        cand
                    } else {
                        val boidu = boiduDeferred.await()
                        if (boidu != null && com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(boidu.lyricsData.lines)) {
                            boidu
                        } else boidu ?: cand
                    }
                }
            }
        }
    }

    private fun fetchFromBetterLyrics(
        cleanTitle: String,
        artistToUse: String,
        query: LyricsSearchQuery,
        useAlbum: Boolean = true,
        useDuration: Boolean = true
    ): LyricsCandidate? {
        try {
            val encSong = URLEncoder.encode(cleanTitle, "UTF-8")
            val encArtist = URLEncoder.encode(artistToUse, "UTF-8")
            val durSec = if (useDuration) {
                query.durationSec?.takeIf { it > 0 }
                    ?: query.durationMs?.let { it / 1000L }?.takeIf { it > 0 }
            } else null
            val durationParam = durSec?.let { "&d=$it" } ?: ""
            val albumParam = if (useAlbum) {
                query.album?.takeIf { it.isNotBlank() }
                    ?.let { "&al=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
            } else ""
            val url = "$BASE_URL/getLyrics?s=$encSong&a=$encArtist$durationParam$albumParam"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val (isSuccess, code, body) = client.newCall(req).execute().use { resp ->
                Triple(resp.isSuccessful, resp.code, if (resp.isSuccessful) resp.body?.string() else null)
            }
            if (!isSuccess) {
                // If query with duration fails with 401 or 404, retry immediately without duration
                if (useDuration && (code == 401 || code == 404)) {
                    return fetchFromBetterLyrics(
                        cleanTitle = cleanTitle,
                        artistToUse = artistToUse,
                        query = query,
                        useAlbum = useAlbum,
                        useDuration = false
                    )
                }
                return null
            }

            val bodyStr = body ?: return null
            val json = JSONObject(bodyStr)

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

            val sourceTitle = json.optString("trackName").ifBlank { json.optString("name") }
            val candTitle = sourceTitle.ifBlank { cleanTitle }
            val candArtist = json.optString("artistName").ifBlank { json.optString("artist").ifBlank { artistToUse } }

            val rawParsed = BetterLyricsParser.parse(
                content = lyricsContent,
                provider = LyricsProvider.BETTER_LYRICS,
                // The server matches by title and length, and can answer a remix query with the
                // album cut. Only the server's own title may claim a version; an unnamed answer
                // gets the untagged title, so "(Hyper Mix)" in our query can't vouch for it.
                trackName = sourceTitle.ifBlank { TitleCleaner.withoutBracketedTags(cleanTitle) },
                artistName = candArtist
            ) ?: return null

            // Derive candidate duration from parsed TTML when JSON duration is missing (e.g. Boidu TTML)
            val candDuration = rawParsed.durationMs?.let { it / 1000L }?.takeIf { it > 0 }
                ?: json.optLong("duration", 0L).takeIf { it > 0 }

            val aligned = LyricsMatcher.autoAlignLyrics(rawParsed, query.durationSec, candDuration)

            val confidence = LyricsMatcher.calculateConfidence(
                queryTitle = query.title,
                queryArtist = query.artist,
                candidateTitle = candTitle,
                candidateArtist = candArtist,
                queryDurationSec = query.durationSec,
                candidateDurationSec = candDuration,
                queryAlbum = query.album
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
            var body = client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
            var json = if (body != null) JSONObject(body) else null
            var results = json?.optJSONArray("results")

            // 2. Search fallback
            if (results == null || results.length() == 0) {
                val encQuery = URLEncoder.encode("$cleanTitle $artistToUse", "UTF-8")
                url = "$BINIMUM_URL/search?q=$encQuery"
                req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                body = client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
                json = if (body != null) JSONObject(body) else null
                results = json?.optJSONArray("results")
            }

            if (results == null || results.length() == 0) return null

            var bestUrl: String? = null
            var candTitle = cleanTitle
            var candSourceTitle = ""
            var candArtist = artistToUse
            var candDuration: Long? = null
            var bestDurDiff = Long.MAX_VALUE
            var bestIsWord = false
            var bestConfidence = -1

            val targetDurSec = query.durationSec?.takeIf { it > 0 }
                ?: query.durationMs?.let { it / 1000L }?.takeIf { it > 0 }

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val lUrl = item.optString("lyricsUrl")
                if (lUrl.isNotBlank()) {
                    val itemTitle = item.optString("track_name").ifBlank { cleanTitle }
                    val itemArtist = item.optString("artist_name").ifBlank { artistToUse }
                    val itemAlbum = item.optString("album_name").takeIf { it.isNotBlank() }
                    val itemDur = item.optLong("duration", 0L).takeIf { it > 0 }
                    val isWord = item.optString("timing_type").equals("word", ignoreCase = true)

                    val durDiff = if (targetDurSec != null && itemDur != null) {
                        kotlin.math.abs(targetDurSec - itemDur)
                    } else 0L

                    // 3.5s master alignment duration gate
                    if (targetDurSec != null && itemDur != null && durDiff > 3) continue

                    val itemConfidence = LyricsMatcher.calculateConfidence(
                        queryTitle = query.title,
                        queryArtist = query.artist,
                        candidateTitle = itemTitle,
                        candidateArtist = itemArtist,
                        queryDurationSec = query.durationSec,
                        candidateDurationSec = itemDur,
                        queryAlbum = query.album,
                        candidateAlbum = itemAlbum
                    )

                    val qHasRemaster = query.title.contains("remaster", ignoreCase = true) ||
                            (query.album?.contains("remaster", ignoreCase = true) == true)
                    val candHasRemaster = itemTitle.contains("remaster", ignoreCase = true) ||
                            (itemAlbum?.contains("remaster", ignoreCase = true) == true)

                    var effectiveScore = itemConfidence.toDouble()
                    // 1. Remaster / version alignment
                    if (qHasRemaster && candHasRemaster) {
                        effectiveScore += 25.0
                    }

                    val isBetter = bestUrl == null ||
                            (isWord && !bestIsWord) ||
                            (isWord == bestIsWord && effectiveScore > bestConfidence) ||
                            (isWord == bestIsWord && effectiveScore == bestConfidence.toDouble() && durDiff < bestDurDiff)

                    if (isBetter) {
                        bestUrl = lUrl
                        candTitle = itemTitle
                        candSourceTitle = item.optString("track_name")
                        candArtist = itemArtist
                        candDuration = itemDur
                        bestDurDiff = durDiff
                        bestIsWord = isWord
                        bestConfidence = effectiveScore.toInt()
                    }
                }
            }

            val lyricsUrl = bestUrl ?: return null

            val ttmlReq = Request.Builder().url(lyricsUrl).header("User-Agent", USER_AGENT).build()
            val ttmlContent = client.newCall(ttmlReq).execute().use { ttmlResp ->
                if (ttmlResp.isSuccessful) ttmlResp.body?.string() else null
            } ?: return null
            if (ttmlContent.isBlank()) return null

            val rawParsed = BetterLyricsParser.parse(
                content = ttmlContent,
                provider = LyricsProvider.BETTER_LYRICS,
                // Same rule as above: an unnamed result never inherits our query's version tags.
                trackName = candSourceTitle.ifBlank { TitleCleaner.withoutBracketedTags(cleanTitle) },
                artistName = candArtist
            ) ?: return null

            val finalCandDur = candDuration ?: rawParsed.durationMs?.let { it / 1000L }

            val aligned = LyricsMatcher.autoAlignLyrics(rawParsed, query.durationSec, finalCandDur)
            val confidence = LyricsMatcher.calculateConfidence(
                queryTitle = query.title,
                queryArtist = query.artist,
                candidateTitle = candTitle,
                candidateArtist = candArtist,
                queryDurationSec = query.durationSec,
                candidateDurationSec = finalCandDur,
                queryAlbum = query.album
            )

            if (confidence < 50) return null

            val finalData = if (aligned.durationMs == null && finalCandDur != null) {
                aligned.copy(durationMs = finalCandDur * 1000L)
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
