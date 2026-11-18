package com.auralis.music.desktop.playback

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.network.searchBody
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** The desktop counterpart of Android's JioSaavnAudio adapter. */
internal object DesktopJioSaavnAudio {
    private const val LOOKUP_TIMEOUT_MS = 3_000L
    private val api = "https://www.jiosaavn.com/api.php".toHttpUrl()
    private val rendition = Regex("_(48|96|160|320)\\.(mp4|aac|mp3)$", RegexOption.IGNORE_CASE)

    internal data class Recording(val track: Track, val explicit: Boolean, val has320: Boolean, val encryptedUrl: String)

    suspend fun find320(track: Track): String? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
        if (track.id.startsWith("unavailable:") || track.duration <= 0 || track.title.isBlank() ||
            track.artist.isBlank() || track.artist.equals("Spotify Artist", ignoreCase = true)) return@withTimeoutOrNull null
        val query = "${TitleCleaner.cleanTitle(track.title)} ${TitleCleaner.cleanArtist(track.artist)}".trim()
        val results = call("search.getResults", "q" to query, "p" to "1", "n" to "10").optJSONArray("results")
        val match = pick(track, recordings(results)) ?: return@withTimeoutOrNull null
        val encrypted = match.encryptedUrl.ifBlank {
            val details = call("song.getDetails", "pids" to match.track.id)
            val recording = details.optJSONObject(match.track.id)?.let(::recording)
                ?: recordings(details.optJSONArray("songs")).firstOrNull { it.track.id == match.track.id }
            recording?.encryptedUrl.orEmpty()
        }
        if (encrypted.isBlank()) return@withTimeoutOrNull null
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES"))
        stream320(String(cipher.doFinal(Base64.getMimeDecoder().decode(encrypted)), Charsets.UTF_8).trim())
    }

    internal fun pick(track: Track, candidates: List<Recording>): Recording? {
        val title = SearchQueryMatcher.baseTitle(TitleCleaner.cleanTitle(track.title))
        if (track.duration <= 0 || title.isBlank()) return null
        return candidates.filter { it.has320 }.sortedByDescending { it.explicit }.firstOrNull { row ->
            val match = row.track
            match.duration > 0 && match.artist.isNotBlank() && abs(match.duration - track.duration) <= 2 &&
                SearchQueryMatcher.baseTitle(TitleCleaner.cleanTitle(match.title)) == title &&
                SearchQueryMatcher.scoreTrackCandidate(track, match) >= 40.0
        }
    }

    internal fun stream320(value: String): String? {
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.host != "saavncdn.com" && !url.host.endsWith(".saavncdn.com")) return null
        val file = url.pathSegments.last()
        val match = rendition.find(file) ?: return null
        return url.newBuilder().scheme("https")
            .setPathSegment(url.pathSegments.lastIndex, file.replaceRange(match.range, "_320.${match.groupValues[2]}"))
            .build().toString()
    }

    private fun recordings(rows: JSONArray?): List<Recording> =
        (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it)?.let(::recording) }

    private fun recording(song: JSONObject): Recording? {
        val id = song.optString("id").takeIf(String::isNotBlank) ?: return null
        val info = song.optJSONObject("more_info") ?: return null
        val artists = info.optJSONObject("artistMap")?.optJSONArray("primary_artists")
        val names = (0 until (artists?.length() ?: 0)).mapNotNull {
            artists?.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank)
        }
        return Recording(
            Track(id = id, title = unescape(song.optString("title")), artist = unescape(names.joinToString(", ")),
                album = unescape(info.optString("album")).takeIf(String::isNotBlank), duration = info.optLong("duration")),
            explicit = song.optString("explicit_content") == "1",
            has320 = info.optString("320kbps").equals("true", ignoreCase = true),
            encryptedUrl = info.optString("encrypted_media_url"),
        )
    }

    private fun unescape(text: String) = text.replace("&quot;", "\"").replace("&amp;", "&")
        .replace("&#039;", "'").replace("&#39;", "'")

    private suspend fun call(method: String, vararg parameters: Pair<String, String>): JSONObject {
        val url = api.newBuilder()
            .addQueryParameter("__call", method)
            .addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0")
            .addQueryParameter("api_version", "4")
            .addQueryParameter("ctx", "android")
            .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("Cookie", "explicit_content=1").build()
        return JSONObject(NetworkClientProvider.okHttpClient.searchBody(request, LOOKUP_TIMEOUT_MS + 500L))
    }
}
