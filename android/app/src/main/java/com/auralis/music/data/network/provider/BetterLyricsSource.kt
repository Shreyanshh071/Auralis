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
        fetchFromBetterLyrics(cleanTitle, artistToUse, query)
            ?: (if (artistToUse != cleanArtist) fetchFromBetterLyrics(cleanTitle, cleanArtist, query) else null)
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

            return LyricsCandidate(
                lyricsData = aligned,
                confidence = confidence,
                syncType = aligned.syncType,
                provider = LyricsProvider.BETTER_LYRICS
            )
        } catch (_: Exception) {
            return null
        }
    }
}
