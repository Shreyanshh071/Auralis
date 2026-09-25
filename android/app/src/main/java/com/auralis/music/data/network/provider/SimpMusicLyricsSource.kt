package com.auralis.music.data.network.provider

import android.util.Log
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * SimpMusic's open community lyrics database (lyrics.simpmusic.org), keyed by YouTube video ID.
 * Reads need no key; only community writes are HMAC-signed.
 *
 * Entries are submitted for a specific video, so a hit is timed for the audio being played.
 * Entries can carry word timing (`richSyncLyrics`, enhanced LRC with a `<mm:ss.xx>`
 * stamp before each word), line timing (`syncedLyrics`) or plain text.
 *
 * Its Cloudflare blocks some networks outright (403); that just reads as "no lyrics" here.
 */
class SimpMusicLyricsSource(
    client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.SIMPMUSIC
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC, SyncType.PLAIN)

    private val client = client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()

    companion object {
        private const val TAG = "SimpMusicLyricsSource"
        private const val BASE_URL = "https://api-lyrics.simpmusic.org/v1/"
        private const val USER_AGENT = "Auralis-Music-Android/1.0 (https://github.com/Shreyanshh071/Auralis)"

        /** Community entries for a different cut of the song are skipped beyond this. */
        private const val MAX_DURATION_DELTA_SEC = 8L

        private val WORD_STAMP_SPACE = Regex("""(<\d{1,2}:\d{2}\.\d{2,3}>)[ \t]+""")

        /**
         * Picks the best entry from a SimpMusic response and converts it, preferring word timing,
         * then line timing, then plain text. Entries voted below zero are ignored.
         */
        internal fun parse(body: String, durationSec: Long?): LyricsData? {
            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) return null
            val items = json.optJSONArray("data") ?: return null
            val entries = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                .filter { it.optInt("vote", 0) >= 0 }
                .filter { entry ->
                    val d = entry.optLong("duration", 0L)
                    durationSec == null || durationSec <= 0 || d <= 0 || kotlin.math.abs(d - durationSec) <= MAX_DURATION_DELTA_SEC
                }
                .sortedWith(compareByDescending<JSONObject> { it.optInt("vote", 0) }
                    .thenBy { e -> durationSec?.let { kotlin.math.abs(e.optLong("duration", 0L) - it) } ?: 0L })
            val entry = entries.firstOrNull() ?: return null

            // SimpMusic writes "<00:16.62> Và <00:16.64> em"; LrcParser keeps a word's trailing
            // space but expects none after the stamp, so drop it (else lines read "Và  em").
            val rich = entry.optString("richSyncLyrics").takeIf { it.isNotBlank() && it != "null" }
                ?.replace(WORD_STAMP_SPACE, "$1")
            val synced = entry.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            val plain = entry.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }

            val parsed = sequenceOf(rich, synced).filterNotNull()
                .map { LrcParser.parse(it, LyricsProvider.SIMPMUSIC) }
                .firstOrNull { it.lines.isNotEmpty() }
                ?: plain?.let { text ->
                    val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }.map { LyricLine(time = 0L, text = it) }
                    if (lines.isEmpty()) null
                    else LyricsData(syncType = SyncType.PLAIN, lines = lines, plainLyrics = text, provider = LyricsProvider.SIMPMUSIC)
                }
                ?: return null

            return parsed.copy(
                trackName = entry.optString("title").takeIf { it.isNotBlank() && it != "null" },
                artistName = entry.optString("artist").takeIf { it.isNotBlank() && it != "null" },
                durationMs = entry.optLong("duration", 0L).takeIf { it > 0L }?.let { it * 1000L }
            )
        }
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val videoId = query.videoId?.takeIf { it.isNotBlank() && !it.startsWith("sp_") && !it.startsWith("spotify:") }
            ?: return@withContext null
        val durationSec = query.durationSec?.takeIf { it > 0 } ?: query.durationMs?.let { it / 1000L }?.takeIf { it > 0 }
        val req = Request.Builder()
            .url(BASE_URL + URLEncoder.encode(videoId, "UTF-8"))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val data = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "SimpMusic lookup for $videoId -> HTTP ${resp.code}")
                    return@withContext null
                }
                parse(resp.body?.string() ?: return@withContext null, durationSec)
            }
        } catch (e: Exception) {
            Log.d(TAG, "SimpMusic lookup failed for $videoId: ${e.message}")
            null
        } ?: return@withContext null

        LyricsCandidate(
            lyricsData = data,
            // Keyed by the exact video that is playing, so the match itself is certain.
            confidence = 90,
            syncType = data.syncType,
            // Not flagged isExactVideoMatch even though it is keyed by video: the race treats exact
            // matches as final (built for Unison), and community timing shouldn't pre-empt Apple
            // Music TTML from Better Lyrics / YouLy+. It competes on quality like any source.
            provider = LyricsProvider.SIMPMUSIC
        )
    }
}
