package com.auralis.music.data.network.provider

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Genius Lyrics Provider: High-reliability plain text fallback covering
 * English, Hindi, Latin, Rap, Indie, and niche music worldwide.
 */
class GeniusLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.GENIUS
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.PLAIN)

    companion object {
        private const val SEARCH_API = "https://genius.com/api/search/multi"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)

        searchGenius("$cleanTitle $cleanArtist", query)
            ?: (if (cleanTitle != query.title) searchGenius(cleanTitle, query) else null)
    }

    private fun searchGenius(searchTerm: String, query: LyricsSearchQuery): LyricsCandidate? {
        try {
            val encQuery = URLEncoder.encode(searchTerm.trim(), "UTF-8")
            val url = "$SEARCH_API?q=$encQuery"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val sections = json.optJSONObject("response")?.optJSONArray("sections") ?: return null

            for (s in 0 until sections.length()) {
                val sec = sections.optJSONObject(s) ?: continue
                if (sec.optString("type") != "song") continue

                val hits = sec.optJSONArray("hits") ?: continue
                for (h in 0 until hits.length()) {
                    val hit = hits.optJSONObject(h) ?: continue
                    if (hit.optString("type") != "song") continue

                    val res = hit.optJSONObject("result") ?: continue
                    val hitTitle = res.optString("title")
                    val hitArtist = res.optJSONObject("primary_artist")?.optString("name") ?: ""
                    val songUrl = res.optString("url")

                    if (songUrl.isBlank()) continue

                    val confidence = LyricsMatcher.calculateConfidence(
                        queryTitle = query.title,
                        queryArtist = query.artist,
                        candidateTitle = hitTitle,
                        candidateArtist = hitArtist,
                        queryDurationSec = query.durationSec,
                        candidateDurationSec = null // Genius doesn't provide track durations
                    )

                    if (confidence >= 50) {
                        val plainLyrics = fetchLyricsFromHtml(songUrl)
                        if (!plainLyrics.isNullOrBlank()) {
                            val lines = plainLyrics.lines()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .map { LyricLine(time = 0L, text = it) }

                            if (lines.isNotEmpty()) {
                                return LyricsCandidate(
                                    lyricsData = LyricsData(
                                        syncType = SyncType.PLAIN,
                                        lines = lines,
                                        plainLyrics = plainLyrics,
                                        provider = LyricsProvider.GENIUS,
                                        trackName = hitTitle,
                                        artistName = hitArtist
                                    ),
                                    confidence = confidence,
                                    syncType = SyncType.PLAIN,
                                    provider = LyricsProvider.GENIUS
                                )
                            }
                        }
                    }
                }
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }

    private fun fetchLyricsFromHtml(pageUrl: String): String? {
        try {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val html = resp.body?.string() ?: return null

            // Extract all lyrics containers (<div data-lyrics-container="true"...>...</div>)
            val containerRegex = Regex("""(?s)<div[^>]+data-lyrics-container="true"[^>]*>(.*?)</div>""")
            val matches = containerRegex.findAll(html).toList()

            if (matches.isEmpty()) {
                // Fallback container check for older Genius templates
                val fallbackRegex = Regex("""(?s)<div class="Lyrics__Container[^"]*"[^>]*>(.*?)</div>""")
                val fallbackMatches = fallbackRegex.findAll(html).toList()
                if (fallbackMatches.isEmpty()) return null

                return cleanHtmlSnippet(fallbackMatches.joinToString("\n\n") { it.groupValues[1] })
            }

            return cleanHtmlSnippet(matches.joinToString("\n\n") { it.groupValues[1] })
        } catch (_: Exception) {
            return null
        }
    }

    private fun cleanHtmlSnippet(html: String): String {
        return html
            .replace(Regex("""<br\s*/?>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""&[a-zA-Z0-9#]+;"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
