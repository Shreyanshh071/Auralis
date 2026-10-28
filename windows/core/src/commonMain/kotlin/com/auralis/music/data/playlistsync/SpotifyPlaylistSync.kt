package com.auralis.music.data.playlistsync

import com.auralis.music.data.network.*
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*

class SpotifyPlaylistSync(
    private val token: suspend () -> String? = { SpotifySession.getValidAccessToken() },
    private val http: OkHttpClient = NetworkClientProvider.okHttpClient
) : PlaylistSyncRemote {
    override val service = SyncService.SPOTIFY
    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("https://")) path.toHttpUrl() else "https://api.spotify.com/v1/$path".toHttpUrl()
        require(url.host == "api.spotify.com" && url.encodedPath.startsWith("/v1/")) { "Invalid Spotify pagination URL." }
        val access = token()?.takeIf { it.isNotBlank() } ?: error("Connect Spotify before enabling playlist sync.")
        val request = Request.Builder().url(url).header("Authorization", "Bearer $access")
            .method(method, if (method == "GET") null else (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { when (response.code) {
                401 -> "Spotify sign-in expired. Reconnect your Spotify account."
                403 -> "Spotify did not grant playlist access. Connect an account with playlist read/write permissions; this app's Spotify access may also be restricted."
                429 -> "Spotify is rate-limiting requests. Sync is pending; try again later."
                else -> "Spotify sync failed (HTTP ${response.code}). Your local playlist is kept."
            } }
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
    override suspend fun account(): String = request("me").getString("id")
    private fun playlistId(value: String): String {
        val id = value.substringBefore('?').trimEnd('/').substringAfterLast('/').removePrefix("spotify:playlist:")
        require(Regex("[A-Za-z0-9]{22}").matches(id)) { "Enter a valid Spotify playlist link or ID." }; return id
    }
    override suspend fun read(id: String): RemoteSyncPlaylist {
        val key = playlistId(id)
        val me = account()
        val info = request("playlists/$key")
        val snapshot = info.getString("snapshot_id")
        val tracks = mutableListOf<Track>(); val ids = mutableListOf<String>(); val seen = mutableSetOf<String>()
        var next: String? = "playlists/$key/items?limit=50"
        while (next != null) {
            check(seen.add(next)) { "Spotify pagination did not finish. Nothing was changed." }
            val page = request(next)
            val items = page.getJSONArray("items")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                check(!item.optBoolean("is_local")) { "This Spotify playlist contains local files, which cannot be synced safely." }
                val song = item.optJSONObject("item") ?: item.optJSONObject("track")
                    ?: error("Spotify returned an unavailable playlist item. Sync is paused to preserve it.")
                check(song.optString("type", "track") == "track") { "Episodes and other non-song items are not supported in playlist sync yet." }
                val trackId = song.getString("id"); check(trackId.isNotBlank())
                ids += trackId; tracks += decodeTrack(song)
            }
            next = page.optString("next").takeIf { !page.isNull("next") && it.isNotBlank() }
        }
        check(request("playlists/$key").getString("snapshot_id") == snapshot) { "Spotify changed while loading. Review again." }
        return RemoteSyncPlaylist(key, me, SyncSnapshot(info.getString("name"), info.optString("description"), ids), tracks,
            snapshot, info.optJSONObject("owner")?.optString("id") == me || info.optBoolean("collaborative"))
    }
    override suspend fun create(title: String, description: String): String =
        request("me/playlists", "POST", JSONObject().put("name", title).put("description", description).put("public", false)).getString("id")
    override suspend fun identify(track: Track): String? {
        SpicyRecordingMatcher.spotifyId(track.id)?.let { return it }
        val q = "https://api.spotify.com/v1/search".toHttpUrl().newBuilder().addQueryParameter("q", "${track.title} ${track.artist}")
            .addQueryParameter("type", "track").addQueryParameter("limit", "10").build()
        val items = request(q.toString()).optJSONObject("tracks")?.optJSONArray("items") ?: return null
        val matches = (0 until items.length()).map { items.getJSONObject(it) }.filter { song ->
            SpicyRecordingMatcher.matches(LyricsSearchQuery(track.title, track.artist, durationMs = track.duration * 1000,
                album = track.album), SpicyRecording(song.getString("id"), song.getString("name"),
                artists(song), song.optLong("duration_ms"), song.optJSONObject("album")?.optString("name")))
        }
        if (matches.size == 1) return matches.single().getString("id")
        val isrcs = matches.mapNotNull { it.optJSONObject("external_ids")?.optString("isrc")?.takeIf(String::isNotBlank) }.distinct()
        return matches.firstOrNull()?.takeIf { matches.isNotEmpty() && isrcs.size == 1 &&
            matches.all { it.optJSONObject("external_ids")?.optString("isrc") == isrcs.single() } }?.getString("id")
    }
    override suspend fun write(before: RemoteSyncPlaylist, target: SyncSnapshot) {
        val current = read(before.id)
        if (current.account != before.account || !current.editable || current.revision != before.revision)
            throw PlaylistSyncStalePreviewException("Spotify changed before uploading. Checking the latest changes.")
        val path = "playlists/${playlistId(before.id)}"
        if (target.ids != current.snapshot.ids) {
            val batches = target.ids.chunked(100)
            request("$path/items", "PUT", JSONObject().put("uris", JSONArray(batches.firstOrNull().orEmpty().map { "spotify:track:$it" })))
            for (batch in batches.drop(1)) request("$path/items", "POST", JSONObject().put("uris", JSONArray(batch.map { "spotify:track:$it" })))
        }
        if (target.title != current.snapshot.title || target.description != current.snapshot.description)
            request(path, "PUT", JSONObject().put("name", target.title).put("description", target.description))
    }
    private fun artists(song: JSONObject): String = song.optJSONArray("artists")?.let { a ->
        (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }.orEmpty()
    private fun decodeTrack(song: JSONObject): Track = Track("sp_${song.getString("id")}", song.getString("name"), artists(song),
        album = song.optJSONObject("album")?.optString("name"), duration = song.optLong("duration_ms") / 1000,
        thumbnail = song.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty())
}
