package com.auralis.music.data.playlistsync

import com.auralis.music.data.network.*
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.util.Base64

/**
 * Spotify playlist sync through the web player's own endpoints, which accept the signed-in
 * web-player session (the public /v1 API answers that token with 429). Playlist contents, the
 * revision and edit rights come from spclient's playlist service; song details come from the
 * Pathfinder query imports use. Edits are index-based playlist operations checked against the
 * revision that was read, so one copy of a duplicate song never takes its siblings with it.
 */
class SpotifyPlaylistSync(
    private val token: suspend () -> String? = { SpotifySession.getValidAccessToken() },
    private val http: OkHttpClient = NetworkClientProvider.okHttpClient,
    private val pathfinder: (token: String, operation: String, variables: JSONObject) -> JSONObject =
        { t, operation, variables -> SpotifyWebApi.query(t, operation, variables) },
    /** Spacing between song searches, so matching a long playlist doesn't trip Spotify's rate limit. */
    private val searchGapMs: Long = 400
) : PlaylistSyncRemote {
    override val service = SyncService.SPOTIFY

    /** One playlist entry: the URI Spotify stores and its occurrence ID. */
    private data class Entry(val uri: String, val itemId: String)
    private class Contents(val revision: String, val name: String, val description: String,
        val owner: String, val editable: Boolean, val entries: List<Entry>)

    @Volatile private var lastRead: List<Pair<String, Track>> = emptyList()
    private val searchPace = kotlinx.coroutines.sync.Mutex()
    private var lastSearchAt = 0L

    private suspend fun access(): String = token()?.takeIf { it.isNotBlank() } ?: error("Connect Spotify before enabling playlist sync.")

    private suspend fun spclient(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$SPCLIENT/$path")
            .header("Authorization", "Bearer ${access()}")
            .header("User-Agent", SpotifySession.USER_AGENT)
            .header("app-platform", "WebPlayer")
            .header("Accept", "application/json")
            .apply { if (body != null) post(body.toString().toRequestBody("application/json".toMediaType())) }
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { when (response.code) {
                401 -> "Spotify sign-in expired. Reconnect your Spotify account."
                403 -> "Spotify did not allow this playlist change. Sync is paused; your local playlist is kept."
                404 -> throw PlaylistGoneException("This Spotify playlist no longer exists or isn't available to your account.")
                409, 412 -> throw PlaylistSyncStalePreviewException("Spotify changed before uploading. Checking the latest changes.")
                429 -> "Spotify is rate-limiting requests. Sync is pending; try again later."
                else -> "Spotify sync failed (HTTP ${response.code}). Your local playlist is kept."
            } }
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private suspend fun query(operation: String, variables: JSONObject): JSONObject {
        val t = access()
        return withContext(Dispatchers.IO) { pathfinder(t, operation, variables) }
    }

    /** The signed-in account's username, straight from its profile (works with an empty library). */
    override suspend fun account(): String =
        query("profileAttributes", JSONObject()).optJSONObject("data")?.optJSONObject("me")
            ?.optJSONObject("profile")?.optString("username")?.takeIf { it.isNotBlank() }
            ?: error("Spotify did not return the signed-in account. Reconnect your Spotify account.")

    private fun playlistId(value: String): String {
        val id = value.substringBefore('?').trimEnd('/').substringAfterLast('/').removePrefix("spotify:playlist:")
        require(Regex("[A-Za-z0-9]{22}").matches(id)) { "Enter a valid Spotify playlist link or ID." }; return id
    }

    /** Every entry, paging with from/length so large playlists are never cut short. */
    private suspend fun contents(key: String): Contents {
        val entries = mutableListOf<Entry>()
        var first: JSONObject? = null
        while (true) {
            val page = spclient("playlist/v2/playlist/$key?decorate=revision,length,attributes,owner,capabilities" +
                "&from=${entries.size}&length=$PAGE")
            if (first == null) first = page
            check(page.optString("revision") == first.optString("revision")) { "Spotify changed while loading. Review again." }
            val items = page.optJSONObject("contents")?.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                entries += Entry(item.optString("uri"), item.optJSONObject("attributes")?.optString("itemId").orEmpty())
            }
            val total = first.optInt("length", entries.size)
            if (entries.size >= total || items.length() == 0) {
                check(entries.size == total) { "Spotify returned part of the playlist. Nothing was changed." }
                break
            }
        }
        val page = first!!
        val attributes = page.optJSONObject("attributes") ?: JSONObject()
        val capabilities = page.optJSONObject("capabilities") ?: JSONObject()
        return Contents(page.getString("revision"), attributes.optString("name"), attributes.optString("description"),
            page.optString("ownerUsername"), capabilities.optBoolean("canEditItems"), entries)
    }

    override suspend fun read(id: String): RemoteSyncPlaylist {
        val key = playlistId(id)
        val me = account()
        val playlist = contents(key)
        // Nothing may be dropped silently: anything sync can't carry pauses it instead.
        for (entry in playlist.entries) {
            check(!entry.uri.startsWith("spotify:local:")) { "This Spotify playlist contains local files, which cannot be synced safely." }
            check(!entry.uri.startsWith("spotify:episode:")) { "Episodes and other non-song items are not supported in playlist sync yet." }
            check(SpicyRecordingMatcher.spotifyId(entry.uri) != null && entry.uri.startsWith("spotify:track:")) {
                "Spotify returned an unavailable playlist item. Sync is paused to preserve it." }
        }
        val details = details(key, playlist.entries)
        check(contents(key).revision == playlist.revision) { "Spotify changed while loading. Review again." }
        val ids = playlist.entries.map { it.uri.removePrefix("spotify:track:") }
        lastRead = ids.zip(details)
        return RemoteSyncPlaylist(key, me, SyncSnapshot(playlist.name, playlist.description, ids), details,
            playlist.revision, playlist.editable)
    }

    /** Song details for each entry, matched by occurrence ID (the Pathfinder uid) and checked against the URI. */
    private suspend fun details(key: String, entries: List<Entry>): List<Track> {
        val byUid = mutableMapOf<String, Track>()
        val inOrder = mutableListOf<Pair<String, Track?>>()
        var offset = 0
        while (offset < entries.size) {
            val content = query("fetchPlaylist", SpotifyWebApi.playlistVariables(key, offset, 100))
                .optJSONObject("data")?.optJSONObject("playlistV2")?.optJSONObject("content")
                ?: error("Spotify did not return this playlist's songs. Nothing was changed.")
            val items = content.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val track = decodeTrack(item.optJSONObject("itemV2")?.optJSONObject("data"))
                inOrder += item.optString("uid") to track
                if (track != null) item.optString("uid").takeIf(String::isNotBlank)?.let { byUid[it] = track }
            }
            offset += items.length()
        }
        return entries.mapIndexed { index, entry ->
            val uid = runCatching { String(Base64.getDecoder().decode(entry.itemId)) }.getOrDefault("")
            val track = byUid[uid] ?: inOrder.getOrNull(index)?.second?.takeIf { it.id == "sp_${entry.uri.removePrefix("spotify:track:")}" }
            checkNotNull(track) { "Spotify returned an unavailable playlist item. Sync is paused to preserve it." }
            // Keep the stored entry's ID; relinked releases report a different nested URI.
            track.copy(id = "sp_${entry.uri.removePrefix("spotify:track:")}")
        }
    }

    /**
     * Playlists in the signed-in library with this name that the account can edit: when someone
     * else's playlist was imported, the engine links to the account's own copy with the same songs.
     */
    override suspend fun editablePlaylistsNamed(title: String): List<String> {
        val wanted = title.trim()
        val named = mutableListOf<String>()
        var offset = 0
        while (true) {
            val library = query("libraryV3", SpotifyWebApi.libraryVariables(offset, 50))
                .optJSONObject("data")?.optJSONObject("me")?.optJSONObject("libraryV3")
                ?: error("Spotify did not return your library. Sync retries automatically.")
            val items = library.optJSONArray("items") ?: JSONArray()
            SpotifyLibrary.parseGqlPlaylists(items).filter { it.title.trim().equals(wanted, ignoreCase = true) }.forEach { named += it.id }
            offset += items.length()
            if (!SpotifyLibrary.hasMorePages(items.length(), offset, library.optInt("totalCount", -1))) break
        }
        return named.distinct().filter { id -> runCatching { contents(id).editable }.getOrDefault(false) }
    }

    override suspend fun create(title: String, description: String): String {
        val values = JSONObject().put("name", title)
        if (description.isNotBlank()) values.put("description", description)
        val created = spclient("playlist/v2/playlist", JSONObject().put("ops", JSONArray().put(
            JSONObject().put("kind", KIND_UPDATE_LIST_ATTRIBUTES).put("updateListAttributes",
                JSONObject().put("newAttributes", JSONObject().put("values", values))))))
        val uri = created.optString("uri")
        check(uri.startsWith("spotify:playlist:")) { "Spotify did not create the playlist." }
        // Private: kept in the library but not published on the profile.
        spclient("playlist/v2/user/${account()}/rootlist/changes", changes(null, listOf(JSONObject().put("kind", KIND_ADD)
            .put("add", JSONObject().put("fromIndex", 0).put("items", JSONArray().put(JSONObject().put("uri", uri)
                .put("attributes", itemAttributes().put("public", false))))))))
        return uri.removePrefix("spotify:playlist:")
    }

    override suspend fun identify(track: Track): String? = (match(track) as? SyncMatch.Matched)?.remoteId

    override suspend fun match(track: Track): SyncMatch {
        // Already a Spotify song, or a YouTube recording Auralis matched from exactly one Spotify song.
        SpicyRecordingMatcher.spotifyId(track.id)?.let { return SyncMatch.Matched(it) }
        RecordingMatches.matchedVideoIds.entries.filter { it.value == track.id }
            .mapNotNull { SpicyRecordingMatcher.spotifyId(it.key) }.distinct().singleOrNull()?.let { return SyncMatch.Matched(it) }
        val wanted = LyricsSearchQuery(track.title, track.artist, durationMs = track.duration * 1000, album = track.album)
        fun accepted(candidates: List<Pair<String, Track>>): String? {
            val matches = candidates.filter { (id, song) -> SpicyRecordingMatcher.matches(wanted,
                SpicyRecording(id, song.title, song.artist, song.duration * 1000, song.album)) }
            if (matches.map { it.first }.distinct().size == 1) return matches.first().first
            // Same title, artist, version and length on several releases: the same album wins,
            // otherwise Spotify's own top-ranked release.
            return matches.firstOrNull { it.second.album != null && it.second.album.equals(track.album, ignoreCase = true) }?.first
                ?: matches.firstOrNull()?.first
        }
        // The linked playlist usually already holds the song (an import being connected).
        accepted(lastRead)?.let { return SyncMatch.Matched(it) }
        val response = searchPace.withLock {
            val wait = lastSearchAt + searchGapMs - System.currentTimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            try {
                // Errors (HTTP, rate limit, GraphQL) throw from the query and are retried, never "not found".
                query("searchDesktop", JSONObject().put("searchTerm", "${track.title} ${track.artist}")
                    .put("offset", 0).put("limit", 10).put("numberOfTopResults", 5).put("includeAudiobooks", false)
                    .put("includeArtistHasConcertsField", false).put("includePreReleases", false)
                    .put("includeLocalConcertsField", false).put("includeAuthors", false))
            } finally { lastSearchAt = System.currentTimeMillis() }
        }
        val items = response.optJSONObject("data")?.optJSONObject("searchV2")?.optJSONObject("tracksV2")?.optJSONArray("items")
            ?: error("Spotify search returned no song results for ‘${track.title}’. Sync retries automatically.")
        val candidates = (0 until items.length()).mapNotNull { i ->
            val data = items.getJSONObject(i).optJSONObject("item")?.optJSONObject("data")
            val song = decodeTrack(data) ?: return@mapNotNull null
            song.id.removePrefix("sp_") to song
        }
        return accepted(candidates)?.let { SyncMatch.Matched(it) } ?: SyncMatch.NotFound
    }

    override suspend fun write(before: RemoteSyncPlaylist, target: SyncSnapshot) {
        val key = playlistId(before.id)
        val me = account()
        val current = contents(key)
        if (me != before.account || !current.editable || current.revision != before.revision)
            throw PlaylistSyncStalePreviewException("Spotify changed before uploading. Checking the latest changes.")
        val ops = plan(current.entries, target.ids.map { "spotify:track:$it" })
        var revision = current.revision
        for (batch in ops.chunked(OPS_PER_REQUEST))
            revision = spclient("playlist/v2/playlist/$key/changes", changes(revision, batch)).optString("revision").ifBlank { revision }
        val values = JSONObject()
        if (target.title != current.name) values.put("name", target.title)
        if (target.description != current.description) values.put("description", target.description)
        if (values.length() > 0) spclient("playlist/v2/playlist/$key/changes", changes(null, listOf(JSONObject()
            .put("kind", KIND_UPDATE_LIST_ATTRIBUTES).put("updateListAttributes",
                JSONObject().put("newAttributes", JSONObject().put("values", values))))))
    }

    private fun decodeTrack(data: JSONObject?): Track? {
        if (data == null || data.optString("__typename", "Track") != "Track") return null
        val uri = data.optString("uri").takeIf { it.startsWith("spotify:track:") } ?: return null
        val name = data.optString("name").takeIf(String::isNotBlank) ?: return null
        val artists = data.optJSONObject("artists")?.optJSONArray("items")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("profile")?.optString("name")?.takeIf(String::isNotBlank) }
        }.orEmpty()
        val album = data.optJSONObject("albumOfTrack")
        val duration = data.optJSONObject("trackDuration")?.optLong("totalMilliseconds")?.takeIf { it > 0 }
            ?: data.optJSONObject("duration")?.optLong("totalMilliseconds") ?: 0L
        return Track("sp_${uri.removePrefix("spotify:track:")}", name, artists.joinToString(", "),
            album = album?.optString("name")?.takeIf(String::isNotBlank), duration = duration / 1000,
            thumbnail = album?.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url").orEmpty())
    }

    companion object {
        private const val SPCLIENT = "https://spclient.wg.spotify.com"
        private const val PAGE = 100
        private const val OPS_PER_REQUEST = 100
        private const val KIND_ADD = 2
        private const val KIND_REM = 3
        private const val KIND_MOV = 4
        private const val KIND_UPDATE_LIST_ATTRIBUTES = 6

        private fun itemAttributes() = JSONObject().put("timestamp", System.currentTimeMillis().toString())
            .put("formatAttributes", JSONArray()).put("availableSignals", JSONArray())

        private fun changes(baseRevision: String?, ops: List<JSONObject>) = JSONObject().apply {
            baseRevision?.let { put("baseRevision", it) }
            put("deltas", JSONArray().put(JSONObject().put("ops", JSONArray(ops))
                .put("info", JSONObject().put("source", JSONObject().put("client", 5)))))
            put("wantResultingRevisions", true); put("wantSyncResult", false); put("nonces", JSONArray())
        }

        /**
         * Index-based edits that turn [current] into [target]. Extra copies are removed by
         * position (last first, so earlier indices stay valid) and named by occurrence ID;
         * surviving entries are moved, never re-added, so they keep their added date.
         */
        private fun plan(current: List<Entry>, target: List<String>): List<JSONObject> {
            val ops = mutableListOf<JSONObject>()
            val keep = target.groupingBy { it }.eachCount().toMutableMap()
            val removed = mutableListOf<Int>()
            current.forEachIndexed { index, entry ->
                val left = keep.getOrDefault(entry.uri, 0)
                if (left > 0) keep[entry.uri] = left - 1 else removed += index
            }
            for (index in removed.asReversed()) ops += JSONObject().put("kind", KIND_REM).put("rem", JSONObject()
                .put("fromIndex", index).put("length", 1).put("itemsAsKey", false)
                .put("items", JSONArray().put(JSONObject().put("uri", current[index].uri)
                    .put("attributes", JSONObject().put("itemId", current[index].itemId)))))
            val gone = removed.toSet()
            val order = current.filterIndexed { index, _ -> index !in gone }.map { it.uri }.toMutableList()
            for (index in target.indices) {
                val wanted = target[index]
                if (order.getOrNull(index) == wanted) continue
                val from = (index + 1 until order.size).firstOrNull { order[it] == wanted }
                if (from != null) {
                    // Moving earlier: Spotify's toIndex is the slot before the item is lifted out.
                    ops += JSONObject().put("kind", KIND_MOV).put("mov", JSONObject()
                        .put("fromIndex", from).put("length", 1).put("toIndex", index))
                    order.add(index, order.removeAt(from))
                } else {
                    ops += JSONObject().put("kind", KIND_ADD).put("add", JSONObject().put("fromIndex", index)
                        .put("items", JSONArray().put(JSONObject().put("uri", wanted).put("attributes", itemAttributes()))))
                    order.add(index, wanted)
                }
            }
            check(order == target) { "Could not plan the Spotify playlist update. Nothing was changed." }
            return ops
        }
    }
}
