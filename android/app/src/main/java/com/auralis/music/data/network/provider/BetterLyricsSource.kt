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
    private val client: OkHttpClient = NetworkClientProvider.okHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.BETTER_LYRICS
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC, SyncType.PLAIN)

    companion object {
        private const val BASE_URL = "https://api.betterlyrics.org"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)

        if (cleanTitle.isBlank()) return@withContext null

        try {
            val encSong = URLEncoder.encode(cleanTitle, "UTF-8")
            val encArtist = URLEncoder.encode(cleanArtist, "UTF-8")
            val url = "$BASE_URL/getLyrics?s=$encSong&a=$encArtist"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null

            val body = resp.body?.string() ?: return@withContext null
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

            if (lyricsContent.isBlank()) return@withContext null

            val rawParsed = BetterLyricsParser.parse(
                content = lyricsContent,
                provider = LyricsProvider.BETTER_LYRICS,
                trackName = cleanTitle,
                artistName = cleanArtist
            ) ?: return@withContext null

            val aligned = LyricsMatcher.autoAlignLyrics(rawParsed, query.durationSec, null)

            val confidence = if (aligned.syncType == SyncType.RICHSYNC) 95 else 80

            LyricsCandidate(
                lyricsData = aligned,
                confidence = confidence,
                syncType = aligned.syncType,
                provider = LyricsProvider.BETTER_LYRICS
            )
        } catch (_: Exception) {
            null
        }
    }
}
