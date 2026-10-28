package com.auralis.music.playlistsync

import com.auralis.music.data.playlistsync.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import kotlin.test.*

class PlaylistSyncTransportTest {
    private val id = "abcdefghijklmnopqrstuv"
    private fun info(snapshot: String = "v1") = """{"id":"$id","name":"Mix","description":"","snapshot_id":"$snapshot","owner":{"id":"me"}}"""
    private fun song(id: String) = JSONObject().put("item", JSONObject().put("id", id).put("type", "track").put("name", "Song")
        .put("duration_ms", 180000).put("artists", JSONArray().put(JSONObject().put("name", "Artist"))))
    @Test fun spotifyReadsAllPagesAndPreservesDuplicateOccurrences() = runBlocking<Unit> {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val body = when {
                path == "/v1/me" -> """{"id":"me"}"""
                path.endsWith("/items") && chain.request().url.queryParameter("offset") == null -> JSONObject().put("items", JSONArray().put(song(id)).put(song(id)))
                    .put("next", "https://api.spotify.com/v1/playlists/$id/items?offset=2").toString()
                path.endsWith("/items") -> JSONObject().put("items", JSONArray().put(song("zyxwvutsrqponmlkjihgfe"))).put("next", JSONObject.NULL).toString()
                else -> info()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val result = SpotifyPlaylistSync({ "token" }, client).read(id)
        assertTrue(result.editable)
        assertEquals(listOf(id, id, "zyxwvutsrqponmlkjihgfe"), result.snapshot.ids)
        assertEquals(3, result.tracks.size)
    }
    @Test fun spotifyRejectsUnavailableItemsAndMissingWritePermissionsInsteadOfReportingSuccess() = runBlocking<Unit> {
        var code = 200
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val body = when { path == "/v1/me" -> """{"id":"me"}"""
                path.endsWith("/items") -> """{"items":[{"item":null}],"next":null}"""; else -> info() }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("OK").body(body.toResponseBody()).build()
        }.build()
        val api = SpotifyPlaylistSync({ "token" }, client)
        assertTrue(assertFailsWith<IllegalStateException> { api.read(id) }.message!!.contains("unavailable"))
        code = 403
        assertTrue(assertFailsWith<IllegalStateException> { api.create("Mix", "") }.message!!.contains("permissions"))
    }
    private fun youtubeRow(video: String, handle: String) = JSONObject().put("musicResponsiveListItemRenderer", JSONObject()
        .put("playlistItemData", JSONObject().put("videoId", video).put("playlistSetVideoId", handle))
        .put("flexColumns", JSONArray().put(JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text",
            JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Song").put("navigationEndpoint",
                JSONObject().put("watchEndpoint", JSONObject().put("videoId", video))))))))))
    @Test fun youtubeReadsOccurrencesAndNeverTreatsNonEditableListsAsOwned() = runBlocking<Unit> {
        val root = JSONObject().put("header", JSONObject().put("musicResponsiveHeaderRenderer", JSONObject().put("title",
            JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Mix"))))))
            .put("musicPlaylistShelfRenderer", JSONObject().put("contents", JSONArray()
                .put(youtubeRow("abcdefghijk", "one")).put(youtubeRow("abcdefghijk", "two"))))
        val api = YouTubePlaylistSync({ "account" }, { _, _ -> root })
        assertFalse(api.read("PLabcdefghijklmnop").editable)
        root.put("musicPlaylistEditHeaderRenderer", JSONObject())
        val result = api.read("PLabcdefghijklmnop")
        assertTrue(result.editable); assertEquals(listOf("abcdefghijk", "abcdefghijk"), result.snapshot.ids)
        assertEquals(2, result.tracks.size)
    }

    @Test fun youtubeReadsAppendContinuationPagesWithoutLosingOrDeduplicatingSongs() = runBlocking<Unit> {
        val requests = mutableListOf<String>()
        val api = YouTubePlaylistSync({ "account" }, { _, payload ->
            requests += payload.optString("continuation", "first")
            if (!payload.has("continuation")) JSONObject()
                .put("musicPlaylistEditHeaderRenderer", JSONObject())
                .put("musicResponsiveHeaderRenderer", JSONObject().put("title", JSONObject().put("simpleText", "Mix")))
                .put("musicPlaylistShelfRenderer", JSONObject().put("contents", JSONArray().put(youtubeRow("abcdefghijk", "one")))
                    .put("continuations", JSONArray().put(JSONObject().put("nextContinuationData", JSONObject().put("continuation", "page2")))))
            else JSONObject().put("onResponseReceivedActions", JSONArray().put(JSONObject().put("appendContinuationItemsAction",
                JSONObject().put("continuationItems", JSONArray().put(youtubeRow("bcdefghijkl", "two")).put(youtubeRow("abcdefghijk", "three"))))))
        })
        val result = api.read("PLabcdefghijklmnop")
        assertEquals(listOf("first", "page2"), requests)
        assertEquals(listOf("abcdefghijk", "bcdefghijkl", "abcdefghijk"), result.snapshot.ids)
        assertEquals(3, result.tracks.size)
        assertTrue(result.editable)
    }
    @Test fun spotifyWritesBatchesInOrderAndRejectsAStaleRevisionBeforeWriting() = runBlocking<Unit> {
        val writes = mutableListOf<Pair<String, JSONObject>>()
        var version = "v1"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val body = if (req.method != "GET") {
                val buffer = okio.Buffer(); req.body!!.writeTo(buffer)
                writes += req.method to JSONObject(buffer.readUtf8()); "{}"
            } else when {
                req.url.encodedPath == "/v1/me" -> """{"id":"me"}"""
                req.url.encodedPath.endsWith("/items") -> """{"items":[],"next":null}"""
                else -> info(version)
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val api = SpotifyPlaylistSync({ "token" }, client)
        val before = api.read(id)
        val desired = SyncSnapshot("Renamed", "Description", List(205) { id })
        api.write(before, desired)
        assertEquals(listOf("PUT", "POST", "POST", "PUT"), writes.map { it.first })
        assertEquals(listOf(100, 100, 5), writes.take(3).map { it.second.getJSONArray("uris").length() })
        assertEquals("Renamed", writes.last().second.getString("name"))
        writes.clear(); version = "v2"
        assertFailsWith<IllegalStateException> { api.write(before, desired) }
        assertTrue(writes.isEmpty())
    }

    @Test fun youtubeWritesDuplicateOccurrencesByHandleAndReordersWithoutDeletingSiblings() = runBlocking<Unit> {
        val a = "abcdefghijk"; val b = "bcdefghijkl"; val c = "cdefghijklm"
        val entries = mutableListOf(a to "one", a to "two", b to "three")
        var title = "Mix"; var description = ""; var handle = 3
        val actions = mutableListOf<JSONObject>()
        val api = YouTubePlaylistSync({ "account" }, { endpoint, payload ->
            if (endpoint == "browse") JSONObject().put("musicEditablePlaylistDetailHeaderRenderer", JSONObject())
                .put("header", JSONObject().put("musicResponsiveHeaderRenderer", JSONObject()
                    .put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", title))))
                    .put("musicDescriptionShelfRenderer", JSONObject().put("description", JSONObject().put("simpleText", description)))))
                .put("musicPlaylistShelfRenderer", JSONObject().put("contents", JSONArray(entries.map { youtubeRow(it.first, it.second) })))
            else {
                assertEquals("browse/edit_playlist", endpoint)
                val edits = payload.getJSONArray("actions")
                for (i in 0 until edits.length()) {
                    val action = edits.getJSONObject(i); actions += action
                    when (action.getString("action")) {
                        "ACTION_REMOVE_VIDEO" -> entries.removeAll { it.second == action.getString("setVideoId") }
                        "ACTION_ADD_VIDEO" -> { assertEquals("DEDUPE_OPTION_SKIP", action.getString("dedupeOption")); entries += action.getString("addedVideoId") to "new${++handle}" }
                        "ACTION_MOVE_VIDEO_BEFORE" -> {
                            val item = entries.first { it.second == action.getString("setVideoId") }; entries.remove(item)
                            val successor = action.optString("movedSetVideoIdSuccessor")
                            entries.add(entries.indexOfFirst { it.second == successor }.takeIf { it >= 0 } ?: entries.size, item)
                        }
                        "ACTION_SET_PLAYLIST_NAME" -> title = action.getString("playlistName")
                        "ACTION_SET_PLAYLIST_DESCRIPTION" -> description = action.getString("playlistDescription")
                        else -> error("Unexpected action")
                    }
                }
                JSONObject().put("status", "STATUS_SUCCEEDED")
            }
        })
        val target = SyncSnapshot("Renamed", "Description", listOf(b, a, c, c))
        api.write(api.read("PLabcdefghijklmnop"), target)
        assertEquals(target, api.read("PLabcdefghijklmnop").snapshot)
        assertEquals(listOf("two"), actions.filter { it.getString("action") == "ACTION_REMOVE_VIDEO" }.map { it.getString("setVideoId") })
        assertEquals(2, actions.count { it.getString("action") == "ACTION_ADD_VIDEO" })
        assertTrue(entries.contains(a to "one"))
    }
}
