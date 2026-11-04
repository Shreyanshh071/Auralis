package com.auralis.music.data.network

import android.util.Log
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * 320kbps audio from JioSaavn, used by the High Quality setting.
 *
 * Approach credited to Vivi Music (GPL-3.0): https://github.com/vivizzz007/vivi-music
 * Adapted from BitChord's JioSaavnService (GPL-3.0): https://github.com/kushagrasinghx/BitChord
 *
 * YouTube stays the source of the song: JioSaavn only supplies its audio when it has a 320kbps file
 * of the same recording. Anything less is left to YouTube's own 160kbps stream. The length has to
 * match closely because synced lyrics are timed to it.
 */
object JioSaavnAudio {
    private const val TAG = "JioSaavn"
    private const val API = "https://www.jiosaavn.com/api.php"
    private const val URL_KEY = "38346591"
    private const val MAX_LENGTH_GAP_SEC = 2L
    private const val MIN_MATCH_SCORE = 40.0

    private val client: OkHttpClient get() = NetworkClientProvider.okHttpClient

    internal data class Candidate(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationSec: Long,
        val explicit: Boolean,
        val has320: Boolean,
        val encryptedUrl: String
    )

    private fun unescape(text: String) = text.replace("&quot;", "\"").replace("&amp;", "&")
        .replace("&#039;", "'").replace("&#39;", "'")

    internal fun candidate(song: JSONObject): Candidate? {
        val id = song.optString("id").takeIf { it.isNotBlank() } ?: return null
        val info = song.optJSONObject("more_info") ?: return null
        val artists = info.optJSONObject("artistMap")?.optJSONArray("primary_artists")
        val names = (0 until (artists?.length() ?: 0)).mapNotNull { i ->
            artists?.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
        }
        return Candidate(
            id = id,
            title = unescape(song.optString("title")),
            artist = unescape(names.joinToString(", ")),
            album = unescape(info.optString("album")).takeIf { it.isNotBlank() },
            durationSec = info.optString("duration").toLongOrNull() ?: 0L,
            explicit = song.optString("explicit_content") == "1",
            has320 = info.optString("320kbps").equals("true", ignoreCase = true),
            encryptedUrl = info.optString("encrypted_media_url")
        )
    }

    /** The search results' rows, in JioSaavn's order. */
    internal fun parseSearch(body: String): List<Candidate> {
        val results: JSONArray = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i -> results.optJSONObject(i)?.let(::candidate) }
    }

    /** `song.getDetails` answers with a map keyed by the song id, or sometimes a `songs` list. */
    internal fun parseDetails(body: String): Candidate? {
        val root = JSONObject(body)
        val song = root.optJSONArray("songs")?.optJSONObject(0)
            ?: root.keys().asSequence().mapNotNull { root.optJSONObject(it) }.firstOrNull()
        return song?.let(::candidate)
    }

    internal fun decrypt(encrypted: String): String {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(URL_KEY.toByteArray(Charsets.UTF_8), "DES"))
        return String(cipher.doFinal(Base64.getMimeDecoder().decode(encrypted)), Charsets.UTF_8).trim()
    }

    /** The 320kbps file's URL, or null when [url] doesn't name a rendition we can read. */
    internal fun stream320(url: String): String? {
        val rendition = Regex("_(48|96|160|320)\\.(mp4|aac|mp3)(?=[?#]|$)", RegexOption.IGNORE_CASE).find(url)
            ?: return null
        return url.replaceRange(rendition.range, "_320.${rendition.groupValues[2]}").replace(Regex("^http://"), "https://")
    }

    /** The first row that is the same recording, uncensored rows first. Null rather than a near miss. */
    internal fun pick(target: Track, candidates: List<Candidate>): Candidate? {
        if (target.duration <= 0L) return null
        return candidates.filter { it.has320 }.sortedByDescending { it.explicit }.firstOrNull { row ->
            val track = Track(id = "saavn:${row.id}", title = row.title, artist = row.artist, album = row.album, duration = row.durationSec)
            abs(row.durationSec - target.duration) <= MAX_LENGTH_GAP_SEC &&
                AudioStreamResolver.isSameSongTitle(target.title, row.title) &&
                SearchQueryMatcher.recordingMismatch(target, track) == null &&
                SearchQueryMatcher.scoreTrackCandidate(target, track) >= MIN_MATCH_SCORE
        }
    }

    private suspend fun call(method: String, vararg parameters: Pair<String, String>): String {
        val url = API.toHttpUrl().newBuilder()
            .addQueryParameter("__call", method)
            .addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0")
            .addQueryParameter("api_version", "4")
            .addQueryParameter("ctx", "android")
            .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("Cookie", "explicit_content=1").build()
        return client.searchBody(request, 2_500L)
    }

    /** The 320kbps URL for this recording, or null when it should come from YouTube. */
    suspend fun find320(target: Track): String? = withContext(Dispatchers.IO) {
        val query = "${TitleCleaner.cleanTitle(target.title)} ${TitleCleaner.cleanArtist(target.artist)}".trim()
        val match = pick(target, parseSearch(call("search.getResults", "q" to query, "p" to "1", "n" to "10")))
            ?: return@withContext null
        val encrypted = match.encryptedUrl.ifBlank {
            parseDetails(call("song.getDetails", "pids" to match.id))?.encryptedUrl.orEmpty()
        }
        if (encrypted.isBlank()) return@withContext null
        stream320(decrypt(encrypted)).also { Log.d(TAG, "320kbps for '${target.title}': ${it != null}") }
    }
}
