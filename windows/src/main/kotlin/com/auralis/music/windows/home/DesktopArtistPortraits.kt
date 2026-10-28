package com.auralis.music.windows.home

import com.auralis.music.data.network.NetworkClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Only exact music-entity pages; never the first fuzzy search result or an album cover. */
internal fun portraitPageMatches(name: String, page: JSONObject): Boolean {
    if (page.has("missing") || page.optJSONObject("pageprops")?.has("disambiguation") == true) return false
    fun normalized(value: String) = com.auralis.music.domain.search.SearchQueryMatcher.normalize(value.substringBefore(" ("))
    val expected = if (name.equals("Ye", true)) "Kanye West" else name
    val description = page.optString("description").lowercase()
    return normalized(page.optString("title")) == normalized(expected) &&
        listOf("singer", "musician", "band", "rapper", "musical", "songwriter", "duo", "producer", "music project").any { it in description }
}

internal class DesktopArtistPortraits(private val directory: File) {
    private val requests = Semaphore(2)
    private fun file(key: String) = File(directory, java.security.MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".png")

    suspend fun musicArtists(name: String): List<com.auralis.music.domain.model.Artist> = requests.withPermit {
        val headers = com.auralis.music.data.network.YouTubeSession.authHeaders(com.auralis.music.windows.profile.YouTubeMusicClient.ORIGIN)
        val cookie = headers["Cookie"].orEmpty()
        val api = com.auralis.music.windows.profile.YouTubeMusicClient(cookie, headers["X-Goog-AuthUser"] ?: "0")
        if (com.auralis.music.data.network.YouTubeSession.hasAuthCookies(cookie)) api.resolveScope() else api.resolvePublicScope()
        api.searchArtists(name)
    }

    suspend fun localPhoto(url: String): String? = withContext(Dispatchers.IO) {
        if (url.startsWith("file:")) return@withContext url.takeIf { runCatching { File(java.net.URI(it)).isFile }.getOrDefault(false) }
        val output = file(url)
        if (output.isFile && output.length() > 0) return@withContext output.toURI().toString()
        try {
            val bytes = NetworkClientProvider.okHttpClient.newCall(Request.Builder().url(url)
                .header("User-Agent", "AuralisMusicApp/1.0 (contact@auralis.app)").build()).execute().use {
                if (!it.isSuccessful) return@withContext null
                it.body?.bytes() ?: return@withContext null
            }
            org.jetbrains.skia.Image.makeFromEncoded(bytes).use { image ->
                if (image.width < 100 || image.height < 100) return@withContext null
                image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.use { png ->
                    directory.mkdirs()
                    val temp = File(directory, output.name + ".tmp")
                    temp.writeBytes(png.bytes)
                    java.nio.file.Files.move(temp.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } ?: return@withContext null
            }
            output.toURI().toString()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    suspend fun wikipedia(name: String): String? = requests.withPermit { withContext(Dispatchers.IO) {
        val title = if (name.equals("Ye", true)) "Kanye West" else name
        val titles = listOf(title, "$title (band)", "$title (singer)", "$title (musician)", "$title (rapper)")
        val encoded = java.net.URLEncoder.encode(titles.joinToString("|"), "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&redirects=1&titles=$encoded&prop=pageimages%7Cdescription%7Cpageprops&format=json&pithumbsize=640"
        try {
            val body = NetworkClientProvider.okHttpClient.newCall(Request.Builder().url(url)
                .header("User-Agent", "AuralisMusicApp/1.0 (contact@auralis.app)").build()).execute().use {
                if (!it.isSuccessful) return@withContext null
                it.body?.string() ?: return@withContext null
            }
            val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return@withContext null
            for (key in pages.keys()) {
                val page = pages.optJSONObject(key) ?: continue
                if (!portraitPageMatches(name, page)) continue
                val source = page.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() } ?: continue
                localPhoto(source)?.let { return@withContext it }
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    } }
}
