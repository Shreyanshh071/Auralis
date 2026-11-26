package com.auralis.music.windows.library

import com.auralis.music.data.playlistsync.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import kotlin.test.*

class PlaylistSyncTransportTest {
    private val id = "abcdefghijklmnopqrstuv"
    private fun track(n: Int) = "spotify:track:" + n.toString().padStart(22, '0')
    private fun bare(uri: String) = uri.removePrefix("spotify:track:")
    private val a = track(1); private val b = track(2); private val c = track(3)

    /** A stand-in for Spotify's playlist service and Pathfinder that applies index-based edits. */
    private inner class FakeSpotify(vararg uris: String) {
        var nextItem = 0
        fun item(): String = java.util.Base64.getEncoder().encodeToString((nextItem++).toString(16).padStart(16, '0').toByteArray())
        val entries = uris.map { it to item() }.toMutableList()
        var name = "Mix"; var description = ""; var revision = 1; var editable = true
        val missingDetails = mutableSetOf<String>()
        val pages = mutableListOf<String>(); val changes = mutableListOf<JSONObject>(); val creates = mutableListOf<JSONObject>()
        val rootlist = mutableListOf<JSONObject>()
        var search = JSONArray()
        /** The signed-in library (id to name) and which of those playlists this account can't edit. */
        val library = mutableListOf<Pair<String, String>>()
        val readOnly = mutableSetOf<String>()
        fun rev() = "rev$revision"
        fun song(uri: String, seconds: Long = 180): JSONObject = JSONObject().put("__typename", "Track").put("uri", uri).put("name", "Song ${uri.takeLast(2)}")
            .put("artists", JSONObject().put("items", JSONArray().put(JSONObject().put("profile", JSONObject().put("name", "Artist")))))
            .put("trackDuration", JSONObject().put("totalMilliseconds", seconds * 1000)).put("albumOfTrack", JSONObject().put("name", "Album"))
        private fun text(req: Request) = okio.Buffer().also { req.body!!.writeTo(it) }.readUtf8()
        private fun apply(op: JSONObject) { when (op.getInt("kind")) {
            2 -> { val add = op.getJSONObject("add"); val items = add.getJSONArray("items")
                val at = if (add.has("fromIndex")) add.getInt("fromIndex") else entries.size
                for (i in 0 until items.length()) entries.add(at + i, items.getJSONObject(i).getString("uri") to item()) }
            3 -> { val rem = op.getJSONObject("rem"); assertFalse(rem.getBoolean("itemsAsKey"))
                val at = rem.getInt("fromIndex"); assertEquals(1, rem.getInt("length"))
                assertEquals(rem.getJSONArray("items").getJSONObject(0).getJSONObject("attributes").getString("itemId"), entries[at].second)
                entries.removeAt(at) }
            4 -> { val mov = op.getJSONObject("mov"); val from = mov.getInt("fromIndex"); val to = mov.getInt("toIndex")
                val moved = entries.removeAt(from); entries.add(if (to > from) to - 1 else to, moved) }
            6 -> { val values = op.getJSONObject("updateListAttributes").getJSONObject("newAttributes").getJSONObject("values")
                if (values.has("name")) name = values.getString("name")
                if (values.has("description")) description = values.getString("description") }
            else -> error("Unexpected op")
        } }
        val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request(); val path = req.url.encodedPath
            var code = 200
            val body: String = when {
                req.method == "GET" -> {
                    pages += req.url.query.orEmpty()
                    val from = req.url.queryParameter("from")!!.toInt(); val length = req.url.queryParameter("length")!!.toInt()
                    JSONObject().put("revision", rev()).put("length", entries.size).put("ownerUsername", "me")
                        .put("attributes", JSONObject().put("name", name).put("description", description))
                        .put("capabilities", JSONObject().put("canEditItems", editable && path.substringAfterLast('/') !in readOnly))
                        .put("contents", JSONObject().put("items", JSONArray(entries.drop(from).take(length).map { (uri, item) ->
                            JSONObject().put("uri", uri).put("attributes", JSONObject().put("itemId", item)) }))).toString()
                }
                path == "/playlist/v2/playlist" -> { creates += JSONObject(text(req)); """{"uri":"spotify:playlist:zyxwvutsrqponmlkjihgfe"}""" }
                path.endsWith("/rootlist/changes") -> { rootlist += JSONObject(text(req)); "{}" }
                path.endsWith("/changes") -> {
                    val o = JSONObject(text(req))
                    if (o.has("baseRevision") && o.getString("baseRevision") != rev()) { code = 409; "{}" } else {
                        changes += o
                        val ops = o.getJSONArray("deltas").getJSONObject(0).getJSONArray("ops")
                        for (i in 0 until ops.length()) apply(ops.getJSONObject(i))
                        revision++; JSONObject().put("revision", rev()).toString()
                    }
                }
                else -> error("Unexpected $path")
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody()).build()
        }.build()
        val pathfinder: (String, String, JSONObject) -> JSONObject = { _, operation, variables -> when (operation) {
            "profileAttributes" -> JSONObject().put("data", JSONObject().put("me", JSONObject().put("profile", JSONObject().put("username", "me"))))
            "fetchPlaylist" -> {
                val offset = variables.getInt("offset"); val limit = variables.getInt("limit")
                JSONObject().put("data", JSONObject().put("playlistV2", JSONObject().put("content", JSONObject().put("totalCount", entries.size)
                    .put("items", JSONArray(entries.drop(offset).take(limit).map { (uri, item) ->
                        JSONObject().put("uid", String(java.util.Base64.getDecoder().decode(item)))
                            .put("itemV2", JSONObject().put("data", if (uri in missingDetails) JSONObject.NULL else song(uri))) })))))
            }
            "searchDesktop" -> JSONObject().put("data", JSONObject().put("searchV2", JSONObject().put("tracksV2", JSONObject().put("items", search))))
            "libraryV3" -> JSONObject().put("data", JSONObject().put("me", JSONObject().put("libraryV3", JSONObject()
                .put("totalCount", library.size).put("items", JSONArray(library.drop(variables.getInt("offset")).take(variables.getInt("limit")).map { (id, name) ->
                    JSONObject().put("item", JSONObject().put("__typename", "PlaylistResponseWrapper").put("_uri", "spotify:playlist:$id")
                        .put("data", JSONObject().put("__typename", "Playlist").put("name", name))) })))))
            else -> error("Unexpected $operation")
        } }
        val api get() = SpotifyPlaylistSync({ "token" }, http, pathfinder)
    }
    private fun song(id: String, title: String, duration: Long = 0, album: String? = null) =
        com.auralis.music.domain.model.Track(id, title, "Artist", album = album, duration = duration)

    @Test fun spotifyReadsEveryPageAndPreservesDuplicateOccurrences() = runBlocking<Unit> {
        val songs = List(150) { if (it % 50 == 0) a else track(10 + it) }
        val spotify = FakeSpotify(*songs.toTypedArray())
        val result = spotify.api.read(id)
        assertTrue(result.editable)
        assertEquals("me", result.account)
        assertEquals(songs.map(::bare), result.snapshot.ids)
        assertEquals(songs.map { "sp_" + bare(it) }, result.tracks.map { it.id })
        assertTrue(spotify.pages.any { "from=100" in it }, "the second page must be requested")
    }

    @Test fun spotifyPausesOnLocalFilesEpisodesAndUnavailableItemsInsteadOfDroppingThem() = runBlocking<Unit> {
        for ((uri, words) in listOf("spotify:local:Artist:Album:Song:180" to "local files", "spotify:episode:abcdefghijklmnopqrstuv" to "Episodes")) {
            val message = assertFailsWith<IllegalStateException> { FakeSpotify(a, uri).api.read(id) }.message!!
            assertTrue(words in message, message)
        }
        val unavailable = FakeSpotify(a, b).apply { missingDetails += b }
        assertTrue("unavailable" in assertFailsWith<IllegalStateException> { unavailable.api.read(id) }.message!!)
        val noProfile = SpotifyPlaylistSync({ "token" }, FakeSpotify(a).http, pathfinder = { _, _, _ -> JSONObject() })
        assertFailsWith<IllegalStateException> { noProfile.account() }
    }

    @Test fun spotifyCreatesAPrivatePlaylistWithItsDescriptionInTheLibrary() = runBlocking<Unit> {
        val spotify = FakeSpotify()
        assertEquals("zyxwvutsrqponmlkjihgfe", spotify.api.create("Mix", "For the road"))
        val values = spotify.creates.single().getJSONArray("ops").getJSONObject(0)
            .getJSONObject("updateListAttributes").getJSONObject("newAttributes").getJSONObject("values")
        assertEquals("Mix", values.getString("name")); assertEquals("For the road", values.getString("description"))
        assertTrue(spotify.rootlist.single().getJSONArray("deltas").length() == 1)
        val added = spotify.rootlist.single().getJSONArray("deltas").getJSONObject(0).getJSONArray("ops").getJSONObject(0)
            .getJSONObject("add").getJSONArray("items").getJSONObject(0)
        assertEquals("spotify:playlist:zyxwvutsrqponmlkjihgfe", added.getString("uri"))
        assertFalse(added.getJSONObject("attributes").getBoolean("public"))
        spotify.creates.clear(); spotify.api.create("Mix", "")
        assertFalse(spotify.creates.single().toString().contains("description"))
    }

    @Test fun spotifyIdentifiesFromSavedIdsThenTheLinkedPlaylistThenSearch() = runBlocking<Unit> {
        val spotify = FakeSpotify(b)
        val api = spotify.api
        assertEquals(bare(a), api.identify(song("sp_" + bare(a), "x")))
        com.auralis.music.data.network.RecordingMatches.matchedVideoIds["sp_" + bare(c)] = "ytVideoId01"
        try { assertEquals(bare(c), api.identify(song("ytVideoId01", "x"))) }
        finally { com.auralis.music.data.network.RecordingMatches.matchedVideoIds.remove("sp_" + bare(c)) }
        api.read(id)
        assertEquals(bare(b), api.identify(song("ytVideoId02", "Song 02", 180, "Album")))
        spotify.search = JSONArray().put(JSONObject().put("item", JSONObject().put("data", spotify.song(track(7), seconds = 240))))
            .put(JSONObject().put("item", JSONObject().put("data", spotify.song(track(8)))))
        assertEquals(bare(track(8)), api.identify(song("ytVideoId03", "Song 08", 180)))
        assertNull(api.identify(song("ytVideoId04", "Song 09", 180)))
    }

    private fun result(id: Int, title: String, artist: String, seconds: Long) = JSONObject().put("item", JSONObject().put("data",
        JSONObject().put("__typename", "Track").put("uri", track(id)).put("name", title)
            .put("artists", JSONObject().put("items", JSONArray().put(JSONObject().put("profile", JSONObject().put("name", artist)))))
            .put("trackDuration", JSONObject().put("totalMilliseconds", seconds * 1000))
            .put("albumOfTrack", JSONObject().put("name", "The Land Is Inhospitable and So Are We"))))

    @Test fun spotifyFindsOnlyTheAccountsOwnEditablePlaylistsWithTheSameName() = runBlocking<Unit> {
        val spotify = FakeSpotify(a).apply {
            library += "mineAAAAAAAAAAAAAAAAAA" to "Road Trip"
            library += "followedBBBBBBBBBBBBBB" to "road trip "
            library += "otherCCCCCCCCCCCCCCCCC" to "Gym"
            readOnly += "followedBBBBBBBBBBBBBB"
        }
        assertEquals(listOf("mineAAAAAAAAAAAAAAAAAA"), spotify.api.editablePlaylistsNamed(" Road trip"))
        assertEquals(emptyList(), spotify.api.editablePlaylistsNamed("Chill"))
    }

    @Test fun spotifyReportsADeletedPlaylistAsGone() = runBlocking<Unit> {
        val gone = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("x").body("{}".toResponseBody()).build()
        }.build()
        val api = SpotifyPlaylistSync({ "token" }, gone, FakeSpotify().pathfinder)
        assertTrue("no longer exists" in assertFailsWith<PlaylistGoneException> { api.read(id) }.message!!)
    }

    @Test fun spotifyNeverMatchesACoverToTheOriginalAndCallsItNotFound() = runBlocking<Unit> {
        val spotify = FakeSpotify()
        // The original at its real length, and even at the cover's length: artist and title still differ.
        spotify.search = JSONArray().put(result(20, "My Love Mine All Mine", "Mitski", 137))
            .put(result(21, "My Love Mine All Mine", "Mitski", 125))
        val cover = song("ytCoverVid1", "my love mine all mine by mitski - cover", 125).copy(artist = "Jian Ross")
        assertEquals(SyncMatch.NotFound, spotify.api.match(cover))
        assertEquals(SyncMatch.Matched(bare(track(20))), spotify.api.match(song("ytOrigVid01", "My Love Mine All Mine", 137).copy(artist = "Mitski")))
    }

    @Test fun spotifySearchFailuresThrowInsteadOfReportingNotFound() = runBlocking<Unit> {
        val spotify = FakeSpotify()
        val limited = SpotifyPlaylistSync({ "token" }, spotify.http, { _, op, v ->
            if (op == "searchDesktop") throw java.io.IOException("Spotify searchDesktop returned HTTP 429") else spotify.pathfinder("", op, v) })
        assertFailsWith<java.io.IOException> { limited.match(song("ytVideoId05", "Song 05", 180)) }
        val broken = SpotifyPlaylistSync({ "token" }, spotify.http, { _, op, v ->
            if (op == "searchDesktop") JSONObject().put("data", JSONObject()) else spotify.pathfinder("", op, v) })
        assertFailsWith<IllegalStateException> { broken.match(song("ytVideoId06", "Song 06", 180)) }
        // A clean, empty answer is the only "not found".
        assertEquals(SyncMatch.NotFound, spotify.api.match(song("ytVideoId07", "Song 07", 180)))
    }

    @Test fun spotifySpacesOutConsecutiveSearches() = runBlocking<Unit> {
        val spotify = FakeSpotify()
        val api = SpotifyPlaylistSync({ "token" }, spotify.http, spotify.pathfinder, searchGapMs = 250)
        val started = System.nanoTime()
        repeat(3) { api.match(song("ytVideoId1$it", "Song 1$it", 180)) }
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 500, "three searches need at least two gaps")
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
    @Test fun spotifyRemovesAndReordersByOccurrenceWithoutTouchingDuplicateSiblings() = runBlocking<Unit> {
        val spotify = FakeSpotify(a, b, a, c)
        val original = spotify.entries.toList()
        val api = spotify.api
        val target = SyncSnapshot("Renamed", "Description", listOf(b, a, c, c).map(::bare))
        api.write(api.read(id), target)
        assertEquals(target, api.read(id).snapshot)
        // The first copy of a stays with its occurrence ID; only the second copy is removed.
        assertTrue(original[0] in spotify.entries); assertFalse(original[2] in spotify.entries)
        assertTrue(original[1] in spotify.entries && original[3] in spotify.entries)
        val ops = spotify.changes.flatMap { change -> change.getJSONArray("deltas").getJSONObject(0).getJSONArray("ops").let { o -> (0 until o.length()).map(o::getJSONObject) } }
        assertEquals(listOf(original[2].second), ops.filter { it.getInt("kind") == 3 }.map {
            it.getJSONObject("rem").getJSONArray("items").getJSONObject(0).getJSONObject("attributes").getString("itemId") })
        assertEquals(1, ops.count { it.getInt("kind") == 2 })
    }

    @Test fun spotifyRejectsAStaleRevisionOrLostEditAccessBeforeWriting() = runBlocking<Unit> {
        val spotify = FakeSpotify(a, b)
        val api = spotify.api
        val before = api.read(id)
        val desired = SyncSnapshot("Mix", "", listOf(b, a).map(::bare))
        spotify.revision++
        assertFailsWith<PlaylistSyncStalePreviewException> { api.write(before, desired) }
        val fresh = api.read(id); spotify.editable = false
        assertFailsWith<PlaylistSyncStalePreviewException> { api.write(fresh, desired) }
        assertTrue(spotify.changes.isEmpty())
        assertEquals(listOf(a, b), spotify.entries.map { it.first })
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
