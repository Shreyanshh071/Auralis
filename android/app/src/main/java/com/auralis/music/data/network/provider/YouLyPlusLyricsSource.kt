package com.auralis.music.data.network.provider

import android.util.Log
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YouLy+ (LyricsPlus) — the open-source ibratabian17/lyricsplus backend behind the YouLy+
 * extension. Serves word-synced Apple Music lyrics ("qApple") with per-line singers and
 * per-syllable background-vocal flags, as KPoe JSON rather than TTML.
 *
 * The backend is run as several independent community mirrors that come and go (on
 * 2026-09-25 only binimum's answered; the rest were overloaded, rate-limited or disabled), so
 * each lookup walks the mirrors, starting with the one that answered last.
 */
class YouLyPlusLyricsSource(
    client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.YOULYPLUS
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC)

    // A song the backend hasn't served recently is fetched upstream first: measured 8.75s cold vs
    // 0.53s warm. A 6s budget abandoned every cold song, which is most of the long tail. Dead
    // mirrors still fail in < 1.5s, so the long budget only ever waits on a mirror that's working.
    private val client = client.newBuilder().callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()

    companion object {
        private const val TAG = "YouLyPlusLyricsSource"

        /** Covers a cold upstream fetch (~9s measured) with headroom. */
        internal const val REQUEST_TIMEOUT_MS = 12_000L

        /** The race waits this long for YouLy+ when nothing synced has arrived from anyone else. */
        const val RACE_BUDGET_MS = 13_000L

        /** A rate-limit wait longer than this isn't worth holding the lyrics for. */
        private const val MAX_RATE_LIMIT_WAIT_MS = 5_000L

        private val RATE_LIMIT_WAIT = Regex("""wait (\d+) seconds?""", RegexOption.IGNORE_CASE)
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        internal val MIRRORS = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus-seven.vercel.app",
            "https://lyrics-plus-backend.vercel.app"
        )

        @Volatile
        private var lastWorkingMirror: String? = null

        internal fun mirrorOrder(): List<String> {
            val last = lastWorkingMirror ?: return MIRRORS
            return listOf(last) + MIRRORS.filter { it != last }
        }

        /**
         * Converts a KPoe JSON body into [LyricsData]. Background syllables inside a line become
         * their own background [LyricLine], matching how the TTML parser keeps `x-bg` vocals.
         */
        internal fun parse(body: String): LyricsData? {
            val json = JSONObject(body)
            val arr = json.optJSONArray("lyrics") ?: return null
            val isWordSync = json.optString("type").let { it.equals("Word", true) || it.equals("Syllable", true) }
            val metadata = json.optJSONObject("metadata")

            val lines = mutableListOf<LyricLine>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val time = obj.optLong("time", -1L).takeIf { it >= 0L } ?: continue
                val duration = obj.optLong("duration", 0L)
                val singer = obj.optJSONObject("element")?.optString("singer")?.takeIf { it.isNotBlank() }
                val syllabus = obj.optJSONArray("syllabus")

                if (isWordSync && syllabus != null && syllabus.length() > 0) {
                    val lead = mutableListOf<LyricWord>()
                    val background = mutableListOf<LyricWord>()
                    for (j in 0 until syllabus.length()) {
                        val s = syllabus.optJSONObject(j) ?: continue
                        val text = s.optString("text")
                        if (text.isEmpty()) continue
                        val word = LyricWord(
                            word = text,
                            time = s.optLong("time"),
                            duration = s.optLong("duration", 0L).takeIf { it > 0L },
                            isBackground = s.optBoolean("isBackground", false)
                        )
                        if (word.isBackground) background += word else lead += word
                    }
                    if (lead.isNotEmpty()) {
                        lines += LyricLine(
                            time = time,
                            text = lead.joinToString("") { it.word }.trim(),
                            words = lead,
                            endTime = if (duration > 0L) time + duration else lead.last().endTime,
                            agent = singer
                        )
                    }
                    if (background.isNotEmpty()) {
                        lines += LyricLine(
                            time = background.first().time,
                            text = background.joinToString("") { it.word }.trim(),
                            words = background,
                            isBackground = true,
                            endTime = background.last().endTime,
                            agent = singer
                        )
                    }
                } else {
                    val text = obj.optString("text").trim()
                    if (text.isBlank()) continue
                    lines += LyricLine(
                        time = time,
                        text = text,
                        endTime = if (duration > 0L) time + duration else null,
                        agent = singer
                    )
                }
            }
            if (lines.isEmpty()) return null

            val sorted = lines.sortedBy { it.time }
            val hasWords = sorted.any { !it.words.isNullOrEmpty() }
            return LyricsData(
                syncType = if (hasWords) SyncType.RICHSYNC else SyncType.LINE_SYNC,
                lines = sorted,
                plainLyrics = sorted.filter { !it.isBackground }.joinToString("\n") { it.text },
                provider = LyricsProvider.YOULYPLUS,
                trackName = metadata?.optString("title")?.takeIf { it.isNotBlank() }
            )
        }
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanCoreSongTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)
        val primaryArtist = cleanArtist
            .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim()?.ifBlank { null } ?: cleanArtist
        if (cleanTitle.isBlank() || primaryArtist.isBlank()) return@withContext null
        val durationSec = query.durationSec?.takeIf { it > 0 }
            ?: query.durationMs?.let { it / 1000L }?.takeIf { it > 0 }

        for (mirror in mirrorOrder()) {
            val url = "$mirror/v2/lyrics/get".toHttpUrl().newBuilder()
                .addQueryParameter("title", cleanTitle)
                .addQueryParameter("artist", primaryArtist)
                .apply {
                    if (durationSec != null) addQueryParameter("duration", durationSec.toString())
                    if (!query.album.isNullOrBlank()) addQueryParameter("album", query.album)
                    if (!query.isrc.isNullOrBlank()) addQueryParameter("isrc", query.isrc)
                }
                .build()
            val req = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            var body: String? = null
            for (attempt in 0..1) {
                val (code, text) = try {
                    client.newCall(req).execute().use { resp -> resp.code to resp.body?.string() }
                } catch (e: Exception) {
                    Log.d(TAG, "Mirror $mirror failed: ${e.message}")
                    break
                }
                when {
                    code in 200..299 -> { body = text; break }
                    // A healthy mirror saying "not found" is definitive; the others share its upstream.
                    code == 404 -> { lastWorkingMirror = mirror; return@withContext null }
                    // The backend allows ~2 requests per 10s per client; skipping through songs hits
                    // it. A short stated wait is cheaper than losing the lyrics, so wait and retry once.
                    code == 429 && attempt == 0 -> {
                        val waitMs = text?.let { RATE_LIMIT_WAIT.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                            ?.let { it * 1000L + 250L }
                        if (waitMs == null || waitMs > MAX_RATE_LIMIT_WAIT_MS) break
                        Log.d(TAG, "Mirror $mirror rate-limited; retrying in ${waitMs}ms")
                        kotlinx.coroutines.delay(waitMs)
                    }
                    else -> break
                }
            }
            if (body == null) continue

            lastWorkingMirror = mirror
            val parsed = try { parse(body) } catch (e: Exception) {
                Log.d(TAG, "Unparseable response from $mirror: ${e.message}")
                null
            } ?: return@withContext null

            val confidence = LyricsMatcher.calculateConfidence(
                queryTitle = query.title,
                queryArtist = query.artist,
                candidateTitle = parsed.trackName ?: cleanTitle,
                // The backend returns songwriters, not the performing artist, so it can't be checked.
                candidateArtist = query.artist,
                queryDurationSec = durationSec,
                candidateDurationSec = null
            )
            if (confidence < 50) return@withContext null

            // No autoAlignLyrics: it shifts lines by (track length - source length), and this
            // backend reports no source length, so any stand-in would invent an offset.
            val aligned = parsed.copy(artistName = query.artist)
            return@withContext LyricsCandidate(
                lyricsData = aligned,
                confidence = confidence,
                syncType = aligned.syncType,
                provider = LyricsProvider.YOULYPLUS
            )
        }
        null
    }
}
