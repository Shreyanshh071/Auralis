package com.auralis.music.data.network.provider

import android.util.Log
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Line timing from the caption tracks of the exact video being played.
 *
 * Captions are never trusted as lyrics on their own: music videos often carry translated
 * subtitles (a Hindi song's first track can be Arabic), and auto-generated tracks of singing
 * are mostly noise. So this only *times* plain lyrics another source already supplied: it picks
 * the uploaded (non-auto) track whose words match that text, and gives up when none does.
 *
 * Tracks come from the IOS client's player response, which still links caption files directly;
 * the web `get_transcript` endpoint now answers "Precondition check failed" to non-browsers.
 */
class YouTubeCaptionsLyricsSource(
    client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) {
    private val client = client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()

    companion object {
        private const val TAG = "YouTubeCaptionsSource"
        private const val IOS_VERSION = "20.10.4"
        private const val IOS_UA = "com.google.ios.youtube/$IOS_VERSION (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        private const val MAX_TRACKS_TRIED = 5

        /** Share of a track's words that must also appear in the reference lyrics. */
        internal const val MIN_PRECISION = 0.6

        /** Share of the reference lyrics' words the track must cover. */
        internal const val MIN_COVERAGE = 0.4

        private val DIRECTION_MARKS = Regex("[‎‏‪-‮]")

        internal fun tokens(text: String): Set<String> =
            Regex("""[\p{L}\p{Nd}']+""").findAll(text.lowercase()).map { it.value.trim('\'') }
                .filter { it.length > 1 }.toSet()

        internal fun isBracketed(text: String): Boolean {
            val t = text.trim()
            return (t.startsWith("(") && t.endsWith(")")) || (t.startsWith("[") && t.endsWith("]"))
        }

        /** Lowercase letters/digits only, so `("Blinding Lights")` and `Blinding Lights` compare equal. */
        internal fun titleKey(text: String): String = text.lowercase().replace(Regex("""[^\p{L}\p{Nd}]"""), "")

        /** Precision and coverage of [candidate] against [reference], or null when either is empty. */
        internal fun overlap(candidate: Set<String>, reference: Set<String>): Pair<Double, Double>? {
            if (candidate.isEmpty() || reference.isEmpty()) return null
            val shared = candidate.count { it in reference }.toDouble()
            return shared / candidate.size to shared / reference.size
        }

        /** Parses a json3 caption file into timed lines, dropping blank and music-note-only cues. */
        internal fun parseJson3(body: String): List<LyricLine> {
            val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
            val lines = mutableListOf<LyricLine>()
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                val segs = ev.optJSONArray("segs") ?: continue
                val start = ev.optLong("tStartMs", -1L).takeIf { it >= 0L } ?: continue
                val raw = buildString {
                    for (j in 0 until segs.length()) append(segs.optJSONObject(j)?.optString("utf8").orEmpty())
                }
                val text = raw.replace(DIRECTION_MARKS, "").replace('\n', ' ')
                    .replace(Regex("""\s+"""), " ").trim().trim('♪', '♫', ' ').trim()
                if (text.isBlank()) continue
                val dur = ev.optLong("dDurationMs", 0L)
                lines += LyricLine(time = start, text = text, endTime = if (dur > 0L) start + dur else null)
            }
            return lines.sortedBy { it.time }
        }
    }

    /**
     * Returns [reference] re-timed from a caption track of [videoId] that carries the same words,
     * or null when the video has no matching uploaded track.
     */
    suspend fun timeFromCaptions(videoId: String, reference: LyricsData): LyricsData? = withContext(Dispatchers.IO) {
        val referenceTokens = tokens(reference.lines.joinToString(" ") { it.text })
        if (referenceTokens.size < 8) return@withContext null

        val tracks = captionTrackUrls(videoId)
        var best: Pair<List<LyricLine>, Double>? = null
        for (url in tracks.take(MAX_TRACKS_TRIED)) {
            val lines = try {
                client.newCall(Request.Builder().url("$url&fmt=json3").header("User-Agent", IOS_UA).build())
                    .execute().use { resp -> if (resp.isSuccessful) resp.body?.string()?.let(::parseJson3) else null }
            } catch (e: Exception) {
                Log.d(TAG, "Caption track fetch failed for $videoId: ${e.message}")
                null
            } ?: continue
            if (lines.size < 4) continue
            val (precision, coverage) = overlap(tokens(lines.joinToString(" ") { it.text }), referenceTokens) ?: continue
            Log.d(TAG, "Track for $videoId: ${lines.size} cues, precision=%.2f coverage=%.2f".format(precision, coverage))
            if (precision >= MIN_PRECISION && coverage >= MIN_COVERAGE && precision > (best?.second ?: 0.0)) {
                best = lines to precision
            }
        }
        val titleKey = reference.trackName?.let(::titleKey)
        val chosen = best?.first?.filterNot { line ->
            // Title cards like ("Blinding Lights") and sound descriptions like (upbeat music) or
            // [Music] are captions, not lyrics.
            val isTitleCard = titleKey != null && titleKey(line.text) == titleKey
            val isSoundCue = isBracketed(line.text) && tokens(line.text).none { it in referenceTokens }
            isTitleCard || isSoundCue
        }?.takeIf { it.isNotEmpty() } ?: return@withContext null
        LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = chosen,
            plainLyrics = chosen.joinToString("\n") { it.text },
            provider = LyricsProvider.YOUTUBE_CAPTIONS,
            trackName = reference.trackName,
            artistName = reference.artistName,
            // Timed against this exact video, so it is in sync with what is playing.
            isExactVideoMatch = true,
            matchedVideoId = videoId
        )
    }

    /** Uploaded (non auto-generated) caption track URLs for [videoId], in YouTube's order. */
    private fun captionTrackUrls(videoId: String): List<String> {
        val payload = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "IOS")
                .put("clientVersion", IOS_VERSION)
                .put("hl", "en")
                .put("gl", "US")))
            .put("videoId", videoId)
            .toString()
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", IOS_UA)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val tracks = JSONObject(resp.body?.string() ?: return emptyList())
                    .optJSONObject("captions")
                    ?.optJSONObject("playerCaptionsTracklistRenderer")
                    ?.optJSONArray("captionTracks") ?: return emptyList()
                (0 until tracks.length()).mapNotNull { tracks.optJSONObject(it) }
                    .filter { it.optString("kind") != "asr" }
                    .mapNotNull { it.optString("baseUrl").takeIf { u -> u.isNotBlank() } }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Player caption lookup failed for $videoId: ${e.message}")
            emptyList()
        }
    }
}
