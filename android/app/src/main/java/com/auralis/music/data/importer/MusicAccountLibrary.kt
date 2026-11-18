package com.auralis.music.data.importer

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Metadata only: credentials stay in the service's WebView cookie store or this client's memory. */
enum class MusicAccountService(val source: String, val label: String, val libraryUrl: String) {
    DEEZER("deezer", "Deezer", "https://www.deezer.com/en/login"),
    TIDAL("tidal", "Tidal", "https://listen.tidal.com/collection/playlists"),
    AMAZON("amazon", "Amazon Music", "https://music.amazon.com/my/playlists");
}

data class MusicAccountPlaylist(
    val id: String,
    val title: String,
    val coverUrl: String? = null,
    val trackCount: Int = -1,
    val isPrivate: Boolean? = null,
    val url: String? = null,
    val remoteId: String = "playlist:$id"
)

open class MusicAccountException(message: String) : Exception(message)
private class MusicAccountAuthenticationException(message: String) : MusicAccountException(message)

/** Fails closed rather than saving the first page of a private playlist as a complete import. */
internal object AccountImportIntegrity {
    fun requireCount(expected: Int, actual: Int) {
        if (expected >= 0 && expected != actual) {
            throw MusicAccountException("Read $actual of $expected songs. Nothing was saved for this playlist. Retry after it finishes loading.")
        }
    }

    fun nextCursor(pageInfo: JSONObject, seen: MutableSet<String>): String? {
        if (!pageInfo.getBoolean("hasNextPage")) return null
        val cursor = pageInfo.optString("endCursor").takeUnless { it.isBlank() || it == "null" }
        if (cursor == null || !seen.add(cursor)) throw MusicAccountException("The service repeated a playlist page. Please retry.")
        return cursor
    }
}

/** Deezer and Tidal's signed-in web APIs. No borrowed client keys or public-token fallback. */
class MusicAccountLibrary(private val service: MusicAccountService, private val session: String) {
    private var deezerJwt: String? = null
    private var tidalCountry = "US"
    private val cookieJar = object : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                this.cookies.add(cookie)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }
    private val client = OkHttpClient.Builder().cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).build()

    suspend fun playlists(): List<MusicAccountPlaylist> = withContext(Dispatchers.IO) {
        when (service) {
            MusicAccountService.DEEZER -> deezerPlaylists()
            MusicAccountService.TIDAL -> tidalPlaylists()
            MusicAccountService.AMAZON -> error("Amazon uses its signed-in browser library")
        }
    }

    suspend fun readPlaylist(item: MusicAccountPlaylist, progress: (Int) -> Unit): ExternalPlaylist = withContext(Dispatchers.IO) {
        val tracks = when (service) {
            MusicAccountService.DEEZER -> deezerTracks(item.id, progress)
            MusicAccountService.TIDAL -> tidalTracks(item.id, progress)
            MusicAccountService.AMAZON -> error("Amazon uses its signed-in browser library")
        }
        ExternalPlaylist(when (service) {
            MusicAccountService.DEEZER -> ExternalService.DEEZER
            MusicAccountService.TIDAL -> ExternalService.TIDAL
            MusicAccountService.AMAZON -> ExternalService.AMAZON_MUSIC
        }, item.remoteId, item.title, null, item.coverUrl, tracks)
    }

    private suspend fun json(request: Request): JSONObject {
        currentCoroutineContext().ensureActive()
        val result = suspendCancellableCoroutine<JSONObject> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(MusicAccountException("Could not connect to ${service.label}. Check your connection and retry."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            when (it.code) {
                                401, 403 -> throw MusicAccountAuthenticationException("Your ${service.label} session needs to be renewed. Sign in again, then reload playlists.")
                                429 -> throw MusicAccountException("${service.label} is limiting requests. Wait a little and retry.")
                            }
                            if (!it.isSuccessful) throw MusicAccountException("${service.label} could not load this library (HTTP ${it.code}).")
                            JSONObject(it.body?.string().orEmpty())
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is MusicAccountException) e else MusicAccountException("${service.label} returned an unreadable response. Reload and try again."))
                    }
                }
            })
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun deezer(query: String, variables: JSONObject = JSONObject(), retryAuth: Boolean = true): JSONObject {
        if (deezerJwt == null) {
            if (session.isBlank() || session.any { it == '\r' || it == '\n' || it == ';' })
                throw MusicAccountException("Sign in to Deezer in the page below first.")
            val auth = json(Request.Builder().url("https://auth.deezer.com/login/arl?jo=p&rto=c&i=c")
                .header("Cookie", "arl=$session").post("".toRequestBody(null)).build())
            deezerJwt = auth.optString("jwt").takeIf { it.isNotBlank() && it != "null" }
                ?: throw MusicAccountException("Deezer login was not accepted. Sign in again.")
        }
        val body = JSONObject().put("query", query).put("variables", variables)
        val response = try {
            json(Request.Builder().url("https://pipe.deezer.com/api")
                .header("Authorization", "Bearer $deezerJwt")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build())
        } catch (e: MusicAccountAuthenticationException) {
            if (!retryAuth) throw e
            deezerJwt = null
            return deezer(query, variables, retryAuth = false)
        }
        // Even partial GraphQL errors must not silently truncate an import.
        if (response.optJSONArray("errors")?.length()?.let { it > 0 } == true)
            throw MusicAccountException("Deezer could not read the complete library. Check your login and retry.")
        return response.optJSONObject("data") ?: throw MusicAccountException("Deezer did not return library data.")
    }

    private suspend fun deezerPlaylists(): List<MusicAccountPlaylist> {
        val result = linkedMapOf<String, MusicAccountPlaylist>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val data = deezer(DEEZER_LIBRARY, JSONObject().put("after", cursor ?: JSONObject.NULL))
            val page = data.getJSONObject("me").getJSONObject("playlists")
            page.getJSONArray("edges").objects().forEach { edge ->
                val p = edge.getJSONObject("node")
                val id = p.getString("id")
                result[id] = MusicAccountPlaylist(id, p.getString("title"), picture(p.optJSONObject("picture")),
                    p.optInt("estimatedTracksCount", -1), p.optBoolean("isPrivate"))
            }
            cursor = AccountImportIntegrity.nextCursor(page.getJSONObject("pageInfo"), seen)
            if (seen.size > 1000) throw MusicAccountException("Too many library pages. Nothing was imported.")
        } while (cursor != null)
        return result.values.toList()
    }

    private suspend fun deezerTracks(id: String, progress: (Int) -> Unit): List<Track> {
        val tracks = mutableListOf<Track>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        var expected: Int
        do {
            val data = deezer(DEEZER_TRACKS, JSONObject().put("id", id).put("after", cursor ?: JSONObject.NULL))
            val playlist = data.getJSONObject("playlist")
            expected = playlist.getInt("estimatedTracksCount")
            val page = playlist.getJSONObject("tracks")
            page.getJSONArray("edges").objects().forEach { edge ->
                val t = edge.getJSONObject("node")
                val album = t.optJSONObject("album")
                val artist = t.getJSONObject("contributors").getJSONArray("edges").objects()
                    .map { it.getJSONObject("node").getString("name") }.joinToString(", ")
                tracks += accountTrack(service, t.getString("id"), t.getString("title"), artist,
                    album?.optString("displayTitle"), t.optLong("duration"), picture(album?.optJSONObject("cover")))
            }
            progress(tracks.size)
            cursor = AccountImportIntegrity.nextCursor(page.getJSONObject("pageInfo"), seen)
            if (seen.size > 1000) throw MusicAccountException("Too many track pages. Nothing was saved for this playlist.")
        } while (cursor != null)
        AccountImportIntegrity.requireCount(expected, tracks.size)
        return tracks
    }

    private suspend fun tidal(path: String): JSONObject {
        if (session.isBlank() || session.any { it == '\r' || it == '\n' })
            throw MusicAccountException("Sign in to Tidal, then open the web player before loading playlists.")
        return json(Request.Builder().url("https://api.tidal.com/$path")
            .header("Authorization", "Bearer $session").build())
    }

    private suspend fun tidalPlaylists(): List<MusicAccountPlaylist> {
        val identity = tidal("v1/sessions")
        tidalCountry = identity.getString("countryCode")
        val result = linkedMapOf<String, MusicAccountPlaylist>()
        val folders = ArrayDeque<String>().apply { add("root") }
        val visited = mutableSetOf<String>()
        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            if (!visited.add(folder)) continue
            var cursor: String? = null
            val seen = mutableSetOf<String>()
            var entryCount = 0
            var expectedEntries = -1
            do {
                val url = "https://api.tidal.com/v2/my-collection/playlists/folders".let { base ->
                    base.toHttpUrl().newBuilder()
                        .addQueryParameter("folderId", folder).addQueryParameter("limit", "50")
                        .addQueryParameter("countryCode", tidalCountry).addQueryParameter("includeOnly", "")
                        .apply { cursor?.let { addQueryParameter("cursor", it) } }.build()
                }
                val page = json(Request.Builder().url(url).header("Authorization", "Bearer $session").build())
                expectedEntries = page.optInt("totalNumberOfItems", expectedEntries)
                page.getJSONArray("items").objects().forEach { wrapper ->
                    val p = wrapper.optJSONObject("data") ?: wrapper.optJSONObject("item") ?: wrapper
                    if (wrapper.optString("itemType") == "FOLDER" || wrapper.optString("trn").startsWith("trn:folder:")) {
                        val folderId = p.optString("id").ifBlank { wrapper.optString("trn").substringAfterLast(':') }
                        if (folderId.isBlank()) throw MusicAccountException("Tidal returned an unreadable playlist folder. Please retry.")
                        folders.add(folderId)
                    } else {
                        val id = p.optString("uuid")
                        if (id.isBlank()) throw MusicAccountException("Tidal returned an unreadable playlist entry. Please retry.")
                        result[id] = MusicAccountPlaylist(id, p.getString("title"),
                            tidalCover(p.optString("squareImage").ifBlank { p.optString("image") }), p.optInt("numberOfTracks", -1),
                            if (p.has("publicPlaylist")) !p.getBoolean("publicPlaylist") else null)
                    }
                    entryCount++
                }
                cursor = page.optString("cursor").takeUnless { it.isBlank() || it == "null" }
                if (cursor != null && !seen.add(cursor)) throw MusicAccountException("Tidal repeated a library page. Please retry.")
                if (seen.size > 1000 || visited.size > 1000) throw MusicAccountException("Too many library pages. Please retry.")
            } while (cursor != null)
            if (expectedEntries >= 0 && entryCount != expectedEntries)
                throw MusicAccountException("Tidal returned an incomplete playlist folder. Reload your library and retry.")
        }
        return result.values.toList()
    }

    private suspend fun tidalTracks(id: String, progress: (Int) -> Unit): List<Track> {
        val meta = tidal("v1/playlists/$id?countryCode=$tidalCountry")
        val tracks = mutableListOf<Track>()
        var offset = 0
        val seenPages = mutableSetOf<String>()
        val expected = meta.getInt("numberOfTracks")
        while (true) {
            val page = tidal("v1/playlists/$id/items?countryCode=$tidalCountry&limit=100&offset=$offset")
            val items = page.getJSONArray("items")
            if (items.length() == 0) break
            if (!seenPages.add(items.toString())) throw MusicAccountException("Tidal repeated a track page. Nothing was saved for this playlist.")
            items.objects().forEach { wrapper ->
                if (wrapper.optString("type", "track") != "track")
                    throw MusicAccountException("This Tidal playlist contains video items. It was not imported as an incomplete music playlist.")
                val t = wrapper.optJSONObject("item") ?: wrapper
                val artists = t.optJSONArray("artists")?.objects()?.map { it.getString("name") }?.joinToString(", ")
                    ?: t.optJSONObject("artist")?.optString("name").orEmpty()
                val album = t.optJSONObject("album")
                tracks += accountTrack(service, t.getString("id"), t.getString("title"), artists,
                    album?.optString("title"), t.optLong("duration"), tidalCover(album?.optString("cover").orEmpty()))
            }
            offset += items.length()
            progress(tracks.size)
            if (offset >= page.getInt("totalNumberOfItems")) break
            if (seenPages.size > 1000) throw MusicAccountException("Too many track pages. Please retry.")
        }
        AccountImportIntegrity.requireCount(expected, tracks.size)
        return tracks
    }

    private fun picture(obj: JSONObject?): String? = obj?.optJSONArray("urls")?.optString(0)?.takeIf { it.startsWith("https://") }
    private fun tidalCover(id: String): String? = id.takeIf { it.isNotBlank() }
        ?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/320x320.jpg" }

    companion object {
        // Query only the metadata needed for import; no media URLs, playback or DRM interaction.
        private val DEEZER_LIBRARY = """
            query Library(${'$'}after: String) {
              me { playlists(first: 50, after: ${'$'}after) {
                edges { node { id title isPrivate estimatedTracksCount picture { urls(pictureRequest: {width: 300, height: 300}) } } }
                pageInfo { hasNextPage endCursor }
              } }
            }
        """.trimIndent()
        private val DEEZER_TRACKS = """
            query Playlist(${'$'}id: String!, ${'$'}after: String) {
              playlist(playlistId: ${'$'}id) { estimatedTracksCount tracks(first: 50, after: ${'$'}after) {
                edges { node { id title duration album { displayTitle cover { urls(pictureRequest: {width: 300, height: 300}) } }
                  contributors(first: 20, roles: [MAIN, FEATURED]) { edges { node { ... on Artist { name } } } }
                } }
                pageInfo { hasNextPage endCursor }
              } }
            }
        """.trimIndent()
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal fun accountTrack(service: MusicAccountService, id: String, title: String, artist: String,
    album: String?, duration: Long, cover: String?): Track {
    if (id.isBlank() || title.isBlank() || artist.isBlank())
        throw MusicAccountException("A song is missing its title or artist. Nothing was saved for this playlist.")
    return Track(id = "sp_${service.source}_$id", title = title, artist = artist, album = album,
        duration = duration, thumbnail = cover.orEmpty(), source = TrackSource.YOUTUBE)
}
