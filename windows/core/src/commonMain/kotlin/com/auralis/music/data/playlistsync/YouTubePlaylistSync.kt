package com.auralis.music.data.playlistsync

import com.auralis.music.data.network.*
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.domain.model.Track
import org.json.*
import java.security.MessageDigest

/** Transport is supplied by the platform's selected YouTube Music session. */
class YouTubePlaylistSync(private val identity: suspend () -> String,
    private val post: suspend (String, JSONObject) -> JSONObject,
    private val search: suspend (String) -> List<Track> = { InnerTubeClient().search(it, InnerTubeClient.FILTER_SONGS).songs },
    private val libraryPlaylists: suspend () -> List<Pair<String, String>> = {
        YouTubeMusicLibrary.fetchPlaylists().orEmpty().filterNot { it.isLikedMusic }.map { it.id to it.title }
    }
) : PlaylistSyncRemote {
    override val service = SyncService.YOUTUBE
    override suspend fun account(): String = identity().also { check(it.isNotBlank()) { "Sign in to YouTube Music to sync this playlist." } }
    private fun playlistId(value: String): String {
        val id = if (value.contains("list=")) value.substringAfter("list=").substringBefore('&').substringBefore('#') else value.removePrefix("VL")
        require(Regex("[A-Za-z0-9_-]{12,100}").matches(id) && !id.startsWith("OLAK") && !id.startsWith("RD")) {
            "Enter an editable YouTube Music playlist link or ID, not an album or radio." }
        return id
    }
    private data class Page(val remote: RemoteSyncPlaylist, val handles: List<String>)
    private suspend fun page(id: String): Page {
        val key = playlistId(id); val user = account()
        val root = post("browse", JSONObject().put("browseId", "VL$key"))
        val editable = nodes(root, "musicEditablePlaylistDetailHeaderRenderer").isNotEmpty() ||
            nodes(root, "musicPlaylistEditHeaderRenderer").isNotEmpty()
        val header = (nodes(root, "musicResponsiveHeaderRenderer") + nodes(root, "musicDetailHeaderRenderer")).firstOrNull()
            ?: error("YouTube did not return a complete playlist header. Nothing was changed.")
        val name = text(header.optJSONObject("title")).ifBlank { error("YouTube playlist has no title.") }
        val description = text(nodes(header, "musicDescriptionShelfRenderer").firstOrNull()?.optJSONObject("description"))
        val tracks = mutableListOf<Track>(); val ids = mutableListOf<String>(); val handles = mutableListOf<String>()
        val seen = mutableSetOf<String>(); var response = root
        while (true) {
            val shelf = (nodes(response, "musicPlaylistShelfRenderer") + nodes(response, "musicPlaylistShelfContinuation")).firstOrNull()
                ?: (if (response !== root) nodes(response, "appendContinuationItemsAction").singleOrNull()
                    ?.optJSONArray("continuationItems")?.let { JSONObject().put("contents", it) } else null)
                ?: error("YouTube did not return the complete playlist. Sync is paused.")
            val contents = shelf.optJSONArray("contents") ?: JSONArray()
            for (i in 0 until contents.length()) {
                val item = contents.getJSONObject(i)
                val row = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                val data = row.optJSONObject("playlistItemData") ?: error("An unavailable playlist entry cannot be synced safely.")
                val video = data.optString("videoId").takeIf { it.isNotBlank() }
                    ?: error("An unavailable playlist entry cannot be synced safely.")
                val handle = data.optString("playlistSetVideoId").ifBlank {
                    nodes(row, "playlistEditEndpoint").flatMap { endpoint -> endpoint.optJSONArray("actions")?.let { a ->
                        (0 until a.length()).mapNotNull(a::optJSONObject) }.orEmpty() }.firstOrNull {
                            it.optString("action") == "ACTION_REMOVE_VIDEO" }?.optString("setVideoId").orEmpty()
                }
                val songs = mutableListOf<Track>()
                InnerTubeClient().parseMusicListItem(row, songs, mutableListOf(), mutableListOf(), mutableListOf())
                val track = songs.firstOrNull { it.id == video } ?: error("Could not read a playlist entry. Nothing was changed.")
                ids += video; handles += handle; tracks += track
            }
            val token = nodes(shelf, "nextContinuationData").firstOrNull()?.optString("continuation")
                ?: nodes(shelf, "continuationCommand").firstOrNull()?.optString("token")
            if (token.isNullOrBlank()) break
            check(seen.add(token) && seen.size <= 500) { "YouTube pagination did not finish. Nothing was changed." }
            response = post("browse", JSONObject().put("continuation", token))
        }
        if (editable) check(handles.all(String::isNotBlank)) { "YouTube did not return playlist item IDs. Reconnect your account." }
        val snapshot = SyncSnapshot(name, description, ids)
        val revision = MessageDigest.getInstance("SHA-256").digest(
            (PlaylistSyncStore.encode(snapshot).toString() + handles.joinToString("|")).toByteArray()).joinToString("") { "%02x".format(it) }
        return Page(RemoteSyncPlaylist(key, user, snapshot, tracks, revision, editable), handles)
    }
    override suspend fun read(id: String): RemoteSyncPlaylist = page(id).remote
    override suspend fun editablePlaylistsNamed(title: String): List<String> = libraryPlaylists()
        .filter { (_, name) -> name.trim().equals(title.trim(), ignoreCase = true) }
        .filter { (id, _) -> runCatching { page(id).remote.editable }.getOrDefault(false) }
        .map { it.first }
    override suspend fun create(title: String, description: String): String {
        account()
        return post("playlist/create", JSONObject().put("title", title).put("description", description).put("privacyStatus", "PRIVATE"))
            .optString("playlistId").takeIf(String::isNotBlank) ?: error("YouTube did not confirm creation. Check your library before retrying.")
    }
    override suspend fun identify(track: Track): String? {
        if (Regex("[A-Za-z0-9_-]{11}").matches(track.id)) return track.id
        RecordingMatches.matchedVideoIds[track.id]?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }?.let { return it }
        val matches = search("${track.title} ${track.artist}").filter { candidate ->
            SpicyRecordingMatcher.matches(LyricsSearchQuery(track.title, track.artist, durationMs = track.duration * 1000),
                SpicyRecording("abcdefghijklmnopqrstuv", candidate.title, candidate.artist, candidate.duration * 1000))
        }.distinctBy { it.id }
        return matches.singleOrNull()?.id
    }
    private suspend fun edit(id: String, actions: List<JSONObject>): JSONObject {
        val result = post("browse/edit_playlist", JSONObject().put("playlistId", id).put("actions", JSONArray(actions)))
        check(result.optString("status") == "STATUS_SUCCEEDED") { "YouTube did not confirm the playlist edit. Review sync before retrying." }
        return result
    }
    override suspend fun write(before: RemoteSyncPlaylist, target: SyncSnapshot) {
        var current = page(before.id)
        if (current.remote.account != before.account || !current.remote.editable || current.remote.revision != before.revision)
            throw PlaylistSyncStalePreviewException("YouTube Music changed before uploading. Checking the latest changes.")
        // Match occurrences, so deleting one duplicate never deletes its siblings.
        val available = target.ids.groupingBy { it }.eachCount().toMutableMap()
        val remove = mutableListOf<JSONObject>()
        current.remote.snapshot.ids.forEachIndexed { index, id ->
            val remaining = available.getOrDefault(id, 0)
            if (remaining > 0) available[id] = remaining - 1
            else remove += JSONObject().put("action", "ACTION_REMOVE_VIDEO").put("removedVideoId", id).put("setVideoId", current.handles[index])
        }
        for (batch in remove.chunked(50)) edit(before.id, batch)
        if (remove.isNotEmpty()) current = page(before.id)
        val counts = current.remote.snapshot.ids.groupingBy { it }.eachCount().toMutableMap()
        val additions = target.ids.mapNotNull { id ->
            val count = counts.getOrDefault(id, 0)
            if (count > 0) { counts[id] = count - 1; null }
            else JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", id).put("dedupeOption", "DEDUPE_OPTION_SKIP")
        }
        for (batch in additions.chunked(50)) edit(before.id, batch)
        if (additions.isNotEmpty()) current = page(before.id)
        val order = current.remote.snapshot.ids.zip(current.handles).toMutableList()
        // Move by occurrence handle, preserving existing playlist entries and their metadata.
        for (index in target.ids.indices) {
            val wanted = target.ids[index]
            if (order.getOrNull(index)?.first == wanted) continue
            val from = (index until order.size).firstOrNull { order[it].first == wanted } ?: error("Playlist changed during sync.")
            val item = order.removeAt(from)
            val successor = order.getOrNull(index)?.second
            val action = JSONObject().put("action", "ACTION_MOVE_VIDEO_BEFORE").put("setVideoId", item.second)
            successor?.let { action.put("movedSetVideoIdSuccessor", it) }
            edit(before.id, listOf(action)); order.add(index, item)
        }
        val details = mutableListOf<JSONObject>()
        if (target.title != before.snapshot.title) details += JSONObject().put("action", "ACTION_SET_PLAYLIST_NAME").put("playlistName", target.title)
        if (target.description != before.snapshot.description) details += JSONObject().put("action", "ACTION_SET_PLAYLIST_DESCRIPTION").put("playlistDescription", target.description)
        if (details.isNotEmpty()) edit(before.id, details)
    }
    companion object {
        private fun text(value: JSONObject?): String = value?.optString("simpleText")?.takeIf(String::isNotBlank)
            ?: value?.optJSONArray("runs")?.let { a -> (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("text") } }.orEmpty()
        fun nodes(value: Any?, key: String): List<JSONObject> = buildList {
            when (value) {
                is JSONObject -> { value.optJSONObject(key)?.let(::add); value.keys().forEach { addAll(nodes(value.opt(it), key)) } }
                is JSONArray -> for (i in 0 until value.length()) addAll(nodes(value.opt(i), key))
            }
        }
    }
}
