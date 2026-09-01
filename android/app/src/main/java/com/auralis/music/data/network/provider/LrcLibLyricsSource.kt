package com.auralis.music.data.network.provider

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class LrcLibLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.LRCLIB
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.LINE_SYNC, SyncType.PLAIN)

    companion object {
        private const val BASE_URL = "https://lrclib.net/api"
        private const val CLIENT_HEADER = "Auralis-Music-Android/1.0.0 (https://github.com/shreyanshchoubey09/Auralis)"
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanCoreSongTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)
        val primaryArtist = cleanArtist
            .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: cleanArtist

        // 1. Try exact get with primary artist first (matches studio catalogue metadata)
        var exactCandidate = getExact(cleanTitle, primaryArtist, query.durationSec, query.album)
        if (exactCandidate == null || exactCandidate.syncType != SyncType.LINE_SYNC) {
            exactCandidate = getExact(cleanTitle, cleanArtist, query.durationSec, query.album) ?: exactCandidate
        }
        if (exactCandidate != null && exactCandidate.syncType == SyncType.LINE_SYNC && exactCandidate.confidence >= 70) {
            return@withContext exactCandidate
        }

        // 2. Try search queries with Title + Primary Artist
        val primarySearchCandidate = if (primaryArtist.isNotBlank() && primaryArtist != cleanArtist) {
            searchQuery("$cleanTitle $primaryArtist", query)
        } else null
        if (primarySearchCandidate != null && primarySearchCandidate.syncType == SyncType.LINE_SYNC && primarySearchCandidate.confidence >= 65) {
            return@withContext primarySearchCandidate
        }

        // 3. Try search queries with Title + Full Artist
        val searchCandidate = searchQuery("$cleanTitle $cleanArtist", query)
        if (searchCandidate != null && searchCandidate.syncType == SyncType.LINE_SYNC && searchCandidate.confidence >= 65) {
            return@withContext searchCandidate
        }

        // 4. Fallback to search query with Title alone (vital for multi-artist / composer tracks)
        val titleOnlyCandidate = if (cleanTitle.isNotBlank()) searchQuery(cleanTitle, query) else null
        if (titleOnlyCandidate != null && titleOnlyCandidate.syncType == SyncType.LINE_SYNC && titleOnlyCandidate.confidence >= 55) {
            return@withContext titleOnlyCandidate
        }

        // 5. Return best candidate found (preferring synced over plain)
        val validCandidates = listOfNotNull(exactCandidate, primarySearchCandidate, searchCandidate, titleOnlyCandidate)
            .filter { it.confidence >= 50 && it.lyricsData.lines.isNotEmpty() }

        return@withContext validCandidates.maxByOrNull { (if (it.syncType == SyncType.LINE_SYNC) 1000 else 0) + it.confidence }
    }

    private fun getExact(title: String, artist: String, durationSec: Long?, album: String?): LyricsCandidate? {
        try {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val url = "$BASE_URL/get?track_name=$encTitle&artist_name=$encArtist"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", CLIENT_HEADER)
                .header("Lrclib-Client", CLIENT_HEADER)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val rawLyrics = parseLrcItem(json) ?: return null
            val candDuration = json.optLong("duration", 0L)
            val lyricsData = LyricsMatcher.autoAlignLyrics(rawLyrics, durationSec, candDuration)

            val confidence = LyricsMatcher.calculateConfidence(
                queryTitle = title,
                queryArtist = artist,
                candidateTitle = lyricsData.trackName ?: title,
                candidateArtist = lyricsData.artistName ?: artist,
                queryDurationSec = durationSec,
                candidateDurationSec = candDuration
            )

            if (confidence < 45) return null

            return LyricsCandidate(
                lyricsData = lyricsData,
                confidence = confidence,
                syncType = lyricsData.syncType,
                provider = LyricsProvider.LRCLIB
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun searchQuery(searchTerm: String, query: LyricsSearchQuery): LyricsCandidate? {
        try {
            val encQuery = URLEncoder.encode(searchTerm.trim(), "UTF-8")
            val url = "$BASE_URL/search?q=$encQuery"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", CLIENT_HEADER)
                .header("Lrclib-Client", CLIENT_HEADER)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val body = resp.body?.string() ?: return null
            val array = JSONArray(body)

            var bestCandidate: LyricsCandidate? = null
            var bestScore = 0.0

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val candTitle = item.optString("trackName")
                val candArtist = item.optString("artistName")
                val candDuration = item.optLong("duration", 0L)
                val hasSynced = !item.optString("syncedLyrics").isNullOrBlank()

                val confidence = LyricsMatcher.calculateConfidence(
                    queryTitle = query.title,
                    queryArtist = query.artist,
                    candidateTitle = candTitle,
                    candidateArtist = candArtist,
                    queryDurationSec = query.durationSec,
                    candidateDurationSec = candDuration
                )

                if (confidence < 45) continue

                // Calculate duration accuracy bonus (closer duration = higher score)
                val durDiff = if (query.durationSec != null && query.durationSec > 0 && candDuration > 0) {
                    kotlin.math.abs(query.durationSec - candDuration)
                } else 0L

                val durationBonus = when {
                    durDiff <= 2 -> 35.0
                    durDiff <= 5 -> 25.0
                    durDiff <= 10 -> 10.0
                    durDiff <= 20 -> 0.0
                    else -> -20.0
                }

                val syncBonus = if (hasSynced) 40.0 else 0.0
                val totalScore = confidence.toDouble() + syncBonus + durationBonus

                if (totalScore > bestScore) {
                    val rawParsed = parseLrcItem(item)
                    if (rawParsed != null && rawParsed.lines.isNotEmpty()) {
                        val alignedParsed = LyricsMatcher.autoAlignLyrics(rawParsed, query.durationSec, candDuration)
                        bestScore = totalScore
                        bestCandidate = LyricsCandidate(
                            lyricsData = alignedParsed,
                            confidence = confidence,
                            syncType = alignedParsed.syncType,
                            provider = LyricsProvider.LRCLIB
                        )
                        if (alignedParsed.syncType == SyncType.LINE_SYNC && confidence >= 80 && durDiff <= 4) {
                            return bestCandidate
                        }
                    }
                }
            }

            return bestCandidate
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseLrcItem(json: JSONObject): LyricsData? {
        val syncedLyrics = json.optString("syncedLyrics")
        val plainLyrics = json.optString("plainLyrics")
        val trackName = json.optString("trackName")
        val artistName = json.optString("artistName")
        val isInstrumental = json.optBoolean("instrumental", false)

        if (isInstrumental) {
            return LyricsData(
                provider = LyricsProvider.LRCLIB,
                syncType = SyncType.PLAIN,
                lines = listOf(LyricLine(time = 0L, text = "♪ Instrumental ♪", isInstrumental = true)),
                plainLyrics = "[Instrumental]",
                trackName = trackName,
                artistName = artistName
            )
        }

        if (syncedLyrics.isNotBlank()) {
            return LrcParser.parse(syncedLyrics, LyricsProvider.LRCLIB).copy(
                trackName = trackName,
                artistName = artistName,
                plainLyrics = plainLyrics.ifBlank { null }
            )
        } else if (plainLyrics.isNotBlank()) {
            val lines = plainLyrics.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { LyricLine(time = 0L, text = it) }
            return LyricsData(
                provider = LyricsProvider.LRCLIB,
                syncType = SyncType.PLAIN,
                lines = lines,
                plainLyrics = plainLyrics,
                trackName = trackName,
                artistName = artistName
            )
        }
        return null
    }
}
