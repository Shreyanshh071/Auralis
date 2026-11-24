package com.auralis.music.desktop.playback

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.network.searchBody
import com.auralis.music.domain.model.Track
import com.auralis.music.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** The desktop counterpart of Android's JioSaavnAudio adapter. Public for the Discord bot. */
object DesktopJioSaavnAudio {
    private const val TAG = "JioSaavnAudio"
    private const val LOOKUP_TIMEOUT_MS = 3_000L
    private val api = "https://www.jiosaavn.com/api.php".toHttpUrl()
    private val rendition = Regex("_(48|96|160|320)\\.(mp4|aac|mp3)$", RegexOption.IGNORE_CASE)

    internal data class Recording(val track: Track, val explicit: Boolean, val has320: Boolean, val encryptedUrl: String)

    private sealed interface Lookup {
        class Found(val url: String, val row: Recording) : Lookup
        class Missed(val why: String) : Lookup
    }

    /**
     * The same recording's stream on JioSaavn (320 kbps when it has one), or null. Never throws
     * except when the caller is canceled: a slow or failing JioSaavn is just a miss.
     */
    suspend fun find320(track: Track): String? {
        if (track.id.startsWith("unavailable:") || track.title.isBlank()) return null
        val started = System.currentTimeMillis()
        val result: Lookup = try {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS * 2) { lookUp(track) } ?: Lookup.Missed("timed out")
        } catch (e: CancellationException) {
            // A request's own timeout surfaces as a cancellation; only the caller's cancel propagates.
            currentCoroutineContext().ensureActive()
            Lookup.Missed("timed out")
        } catch (e: Exception) {
            Lookup.Missed("failed (${e.javaClass.simpleName})")
        }
        val elapsed = System.currentTimeMillis() - started
        when (result) {
            is Lookup.Found -> Log.i(TAG, "Matched '${track.title}' by '${track.artist}' (${track.duration}s) to ${result.row.track.id} " +
                "'${result.row.track.title}' by '${result.row.track.artist}' (${result.row.track.duration}s${if (result.row.has320) ", 320kbps" else ""}) in ${elapsed}ms")
            is Lookup.Missed -> Log.i(TAG, "No JioSaavn stream for '${track.title}' by '${track.artist}' (${track.duration}s): ${result.why} after ${elapsed}ms")
        }
        return (result as? Lookup.Found)?.url
    }

    private suspend fun lookUp(track: Track): Lookup {
        val variants = lookupVariants(track).filter { it.artist.isNotBlank() && !it.artist.equals("Spotify Artist", ignoreCase = true) }
        if (variants.isEmpty()) return Lookup.Missed("no artist to search with")
        var rows = 0
        for (variant in variants) {
            val query = "${TitleCleaner.cleanTitle(variant.title)} ${TitleCleaner.cleanArtist(variant.artist)}".trim()
            val results = recordings(call("search.getResults", "q" to query, "p" to "1", "n" to "20").optJSONArray("results"))
            rows += results.size
            val match = pick(variant, results) ?: continue
            val encrypted = match.encryptedUrl.ifBlank {
                val details = call("song.getDetails", "pids" to match.track.id)
                val recording = details.optJSONObject(match.track.id)?.let(::recording)
                    ?: recordings(details.optJSONArray("songs")).firstOrNull { it.track.id == match.track.id }
                recording?.encryptedUrl.orEmpty()
            }
            if (encrypted.isBlank()) return Lookup.Missed("no media address for ${match.track.id}")
            val url = streamUrl(decrypt(encrypted), match.has320) ?: return Lookup.Missed("unreadable media address for ${match.track.id}")
            return Lookup.Found(url, match)
        }
        return Lookup.Missed("no exact recording among $rows results")
    }

    private fun decrypt(encrypted: String): String {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES"))
        return String(cipher.doFinal(Base64.getMimeDecoder().decode(encrypted)), Charsets.UTF_8).trim()
    }

    /**
     * The song as listed, then (for re-uploads like "Robbie Williams - So This Is Christmas" by
     * channel "aia22") with the artist read from the title instead of the uploader.
     */
    internal fun lookupVariants(track: Track): List<Track> {
        val parts = track.title.split(" - ", limit = 2).map(String::trim)
        if (parts.size < 2 || parts.any(String::isBlank)) return listOf(track)
        val titleArtist = TitleCleaner.cleanArtist(parts[0])
        if (titleArtist.isBlank() || titleArtist.equals(TitleCleaner.cleanArtist(track.artist), ignoreCase = true)) return listOf(track)
        return listOf(track, track.copy(title = parts[1], artist = titleArtist, album = null))
    }

    /** The exact recording among [candidates]: a 320 kbps row first, the uncensored cut first. */
    internal fun pick(track: Track, candidates: List<Recording>): Recording? {
        val rows = candidates.sortedByDescending { it.explicit }
        return RecordingMatch.pick(track, rows.filter { it.has320 }) { it.track }
            ?: RecordingMatch.pick(track, rows) { it.track }
    }

    /**
     * The playable address on JioSaavn's media host: the 320 kbps rendition when the row has one,
     * otherwise the rendition JioSaavn handed out. Null for any other host or file name.
     */
    internal fun streamUrl(value: String, has320: Boolean): String? {
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.host != "saavncdn.com" && !url.host.endsWith(".saavncdn.com")) return null
        val file = url.pathSegments.last()
        val match = rendition.find(file) ?: return null
        val name = if (has320) file.replaceRange(match.range, "_320.${match.groupValues[2]}") else file
        return url.newBuilder().scheme("https").setPathSegment(url.pathSegments.lastIndex, name).build().toString()
    }

    private fun recordings(rows: JSONArray?): List<Recording> =
        (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it)?.let(::recording) }

    private fun recording(song: JSONObject): Recording? {
        val id = song.optString("id").takeIf(String::isNotBlank) ?: return null
        val info = song.optJSONObject("more_info") ?: return null
        // Features ("Like Him" with Lola Young) are listed apart from the primary artists; without
        // them the matcher reads a credited feature as missing and rejects the right recording.
        val names = listOf("primary_artists", "featured_artists").flatMap { role ->
            val artists = info.optJSONObject("artistMap")?.optJSONArray(role)
            (0 until (artists?.length() ?: 0)).mapNotNull {
                artists?.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank)
            }
        }.distinct()
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
