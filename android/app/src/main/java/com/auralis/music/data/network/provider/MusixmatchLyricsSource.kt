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

class MusixmatchLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.MUSIXMATCH
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC, SyncType.PLAIN)

    private var musixmatchToken: String? = null

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanCoreSongTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)
        val primaryArtist = cleanArtist
            .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: cleanArtist

        if (primaryArtist.isNotBlank() && primaryArtist != cleanArtist) {
            searchMxm(cleanTitle, primaryArtist, query)?.let { return@withContext it }
        }

        searchMxm(cleanTitle, cleanArtist, query)
            ?: searchMxm(cleanTitle, "", query)
    }

    private fun searchMxm(title: String, artist: String, query: LyricsSearchQuery): LyricsCandidate? {
        try {
            if (musixmatchToken.isNullOrBlank()) {
                val tokReq = Request.Builder()
                    .url("https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Cookie", "AWSELBCORS=0; AWSELB=0")
                    .build()
                val tokResp = client.newCall(tokReq).execute()
                if (tokResp.isSuccessful) {
                    val tokJson = JSONObject(tokResp.body?.string() ?: "")
                    musixmatchToken = tokJson.optJSONObject("message")?.optJSONObject("body")?.optString("user_token")
                }
            }

            val token = musixmatchToken ?: return null
            val encTrack = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val searchUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.search?app_id=web-desktop-app-v1.0&usertoken=$token&q_track=$encTrack&q_artist=$encArtist&page_size=5&s_track_rating=desc"

            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Cookie", "AWSELBCORS=0; AWSELB=0")
                .build()

            val searchResp = client.newCall(searchReq).execute()
            if (!searchResp.isSuccessful) return null

            val searchJson = JSONObject(searchResp.body?.string() ?: "")
            val trackList = searchJson.optJSONObject("message")?.optJSONObject("body")?.optJSONArray("track_list") ?: return null

            for (i in 0 until trackList.length()) {
                val trackObj = trackList.optJSONObject(i)?.optJSONObject("track") ?: continue
                val trackId = trackObj.optLong("track_id", 0L)
                val candTitle = trackObj.optString("track_name")
                val candArtist = trackObj.optString("artist_name")
                val candDuration = trackObj.optLong("track_length", 0L)

                if (trackId == 0L) continue
                val hasRichsync = trackObj.optInt("has_richsync", 0) == 1
                val hasSubtitles = trackObj.optInt("has_subtitles", 0) == 1
                val hasLyrics = trackObj.optInt("has_lyrics", 0) == 1

                val confidence = LyricsMatcher.calculateConfidence(
                    queryTitle = query.title,
                    queryArtist = query.artist,
                    candidateTitle = candTitle,
                    candidateArtist = candArtist,
                    queryDurationSec = query.durationSec,
                    candidateDurationSec = candDuration
                )

                if (confidence < 50) continue

                // 1. Try Word-synced RichSync (Syllable/Word-by-word)
                if (hasRichsync || hasSubtitles) {
                    try {
                        val richUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get?app_id=web-desktop-app-v1.0&usertoken=$token&track_id=$trackId"
                        val richReq = Request.Builder().url(richUrl).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)").build()
                        val richResp = client.newCall(richReq).execute()
                        if (richResp.isSuccessful) {
                            val richJson = JSONObject(richResp.body?.string() ?: "")
                            val richBodyRaw = richJson.optJSONObject("message")?.optJSONObject("body")?.optJSONObject("richsync")?.optString("richsync_body")
                            if (!richBodyRaw.isNullOrBlank()) {
                                val parsed = MusixmatchRichsyncParser.parse(
                                    richsyncBody = richBodyRaw,
                                    provider = LyricsProvider.MUSIXMATCH,
                                    trackName = candTitle.ifBlank { title },
                                    artistName = candArtist.ifBlank { artist }
                                )
                                if (parsed != null && parsed.lines.isNotEmpty() && parsed.syncType == SyncType.RICHSYNC) {
                                    val alignedParsed = LyricsMatcher.autoAlignLyrics(parsed, query.durationSec, candDuration)
                                    return LyricsCandidate(
                                        lyricsData = alignedParsed,
                                        confidence = confidence,
                                        syncType = SyncType.RICHSYNC,
                                        provider = LyricsProvider.MUSIXMATCH
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // 2. Try Line-synced Subtitles (.lrc format)
                if (hasSubtitles) {
                    val subUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.subtitle.get?app_id=web-desktop-app-v1.0&usertoken=$token&track_id=$trackId&subtitle_format=lrc"
                    val subReq = Request.Builder().url(subUrl).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)").build()
                    val subResp = client.newCall(subReq).execute()
                    if (subResp.isSuccessful) {
                        val subJson = JSONObject(subResp.body?.string() ?: "")
                        val lrcBody = subJson.optJSONObject("message")?.optJSONObject("body")?.optJSONObject("subtitle")?.optString("subtitle_body")
                        if (!lrcBody.isNullOrBlank()) {
                            val parsed = LrcParser.parse(lrcBody, LyricsProvider.MUSIXMATCH)
                            if (parsed.lines.isNotEmpty()) {
                                val alignedParsed = LyricsMatcher.autoAlignLyrics(
                                    lyricsData = parsed.copy(
                                        trackName = candTitle.ifBlank { title },
                                        artistName = candArtist.ifBlank { artist }
                                    ),
                                    trackDurationSec = query.durationSec,
                                    lyricDurationSec = candDuration
                                )
                                return LyricsCandidate(
                                    lyricsData = alignedParsed,
                                    confidence = confidence,
                                    syncType = alignedParsed.syncType,
                                    provider = LyricsProvider.MUSIXMATCH
                                )
                            }
                        }
                    }
                }

                // 3. Try Plain Lyrics
                if (hasLyrics) {
                    val lrcUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.lyrics.get?app_id=web-desktop-app-v1.0&usertoken=$token&track_id=$trackId"
                    val lrcReq = Request.Builder().url(lrcUrl).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)").build()
                    val lrcResp = client.newCall(lrcReq).execute()
                    if (lrcResp.isSuccessful) {
                        val lrcJson = JSONObject(lrcResp.body?.string() ?: "")
                        val rawBody = lrcJson.optJSONObject("message")?.optJSONObject("body")?.optJSONObject("lyrics")?.optString("lyrics_body")
                        if (!rawBody.isNullOrBlank()) {
                            val cleanLyrics = rawBody.substringBefore("******* This Lyrics is NOT for Commercial use *******").trim()
                            val lines = cleanLyrics.lines()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .map { LyricLine(time = 0L, text = it) }
                            return LyricsCandidate(
                                lyricsData = LyricsData(
                                    provider = LyricsProvider.MUSIXMATCH,
                                    syncType = SyncType.PLAIN,
                                    lines = lines,
                                    plainLyrics = cleanLyrics,
                                    trackName = candTitle.ifBlank { title },
                                    artistName = candArtist.ifBlank { artist }
                                ),
                                confidence = confidence,
                                syncType = SyncType.PLAIN,
                                provider = LyricsProvider.MUSIXMATCH
                            )
                        }
                    }
                }
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }
}

