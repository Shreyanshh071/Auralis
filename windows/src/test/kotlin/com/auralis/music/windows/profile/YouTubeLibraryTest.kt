package com.auralis.music.windows.profile

import com.auralis.music.domain.model.PlaylistResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.nio.file.Files
import kotlin.test.*

class YouTubeLibraryTest {
    private val cookie = "LOGIN_INFO=fake-login; SAPISID=fake-secret"
    private fun grid(id: String, name: String) = """{"musicTwoRowItemRenderer":{
        "title":{"runs":[{"text":"$name"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"$id"}}}}"""
    private fun track(id: String) = """{"musicResponsiveListItemRenderer":{
        "playlistItemData":{"videoId":"$id"},"flexColumns":[
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"A song"}]}}},
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Tame Impala"}]}}}
        ]}}"""

    @Test fun createsPrivatePlaylistInSelectedAccountAndReturnsBrowseId() = runBlocking {
        var writes = 0
        val api = YouTubeMusicClient(cookie) { url, body, headers ->
            if (body == null) """{"LOGGED_IN":true,"DATASYNC_ID":"channel||user","DELEGATED_SESSION_ID":"brand","SESSION_INDEX":2}"""
            else {
                writes++
                assertTrue(url.contains("/playlist/create?"))
                assertEquals("My playlist", body.getString("title"))
                assertEquals("PRIVATE", body.getString("privacyStatus"))
                assertEquals("2", headers["X-Goog-AuthUser"])
                assertEquals("brand", headers["X-Goog-PageId"])
                assertEquals(cookie, headers["Cookie"])
                """{"playlistId":"PLcreated"}"""
            }
        }
        api.resolveScope()
        assertEquals(PlaylistResult("VLPLcreated", "My playlist"), api.createPlaylist("  My playlist  "))
        assertEquals(1, writes, "Creation must not retry and produce duplicate playlists")
    }
    @Test fun rejectsBlankNamesAndUnconfirmedCreation() = runBlocking {
        var writes = 0
        val api = YouTubeMusicClient(cookie) { _, _, _ -> writes++; "{}" }
        assertFailsWith<IllegalArgumentException> { api.createPlaylist("   ") }
        assertEquals(0, writes)
        assertFailsWith<IllegalStateException> { api.createPlaylist("Name") }
        assertEquals(1, writes)
    }

    @Test fun accountScopeComesFromSignedInShellAndUsesFreshAuthenticatedRequests() = runBlocking {
        val client = YouTubeMusicClient(cookie) { url, body, headers ->
            assertEquals(cookie, headers["Cookie"])
            assertTrue(headers.getValue("Authorization").startsWith("SAPISIDHASH "))
            if (body == null) """{"LOGGED_IN":true,"DATASYNC_ID":"channel||user","DELEGATED_SESSION_ID":"brand","SESSION_INDEX":2,"INNERTUBE_CLIENT_VERSION":"live-version","VISITOR_DATA":"visitor"}"""
            else {
                assertTrue(url.contains("account/account_menu"))
                assertEquals("brand", headers["X-Goog-PageId"])
                assertEquals("2", headers["X-Goog-AuthUser"])
                assertEquals("brand", body.getJSONObject("context").getJSONObject("user").getString("onBehalfOfUser"))
                """{"popup":{"activeAccountHeaderRenderer":{"accountName":{"simpleText":"Test name"},"channelHandle":{"runs":[{"text":"@test"}]},"accountPhoto":{"thumbnails":[{"url":"https://example.com/small","width":16},{"url":"https://example.com/avatar","width":88}]}}}}"""
            }
        }
        client.resolveScope()
        assertEquals("live-version", client.scope.version)
        assertEquals(YouTubeIdentity("Test name", "@test", "https://example.com/avatar"), client.identity())
        assertNull(parseYouTubeScope("""{"LOGGED_IN":false,"DATASYNC_ID":"wrong||user"}""").dataSyncId)
    }

    @Test fun playlistsPublishFirstPageThenFollowContinuationsAndStopRepeatedTokens() = runBlocking {
        val observed = mutableListOf<Int>()
        var requests = 0
        val api = YouTubeMusicClient(cookie) { _, body, _ ->
            requests++
            if (body!!.has("browseId")) """{"contents":[${grid("VLone", "First playlist")}],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}"""
            else """{"contents":[${grid("VLone", "First playlist")},${grid("VLtwo", "Second playlist")}],"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next"}}}}"""
        }
        val playlists = api.playlists { observed += it.size }
        assertEquals(listOf(2, 3), observed)
        assertEquals(listOf("VLLM", "VLone", "VLtwo"), playlists.map { it.id })
        assertEquals(2, requests)
    }

    @Test fun privatePlaylistLoadsEveryPageAndExcludesSuggestedTracksAndTheirToken() = runBlocking {
        var requests = 0
        val api = YouTubeMusicClient(cookie) { _, body, _ ->
            requests++
            if (body!!.has("browseId")) """{"contents":{"musicPlaylistShelfRenderer":{"contents":[${track("one")}],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}},"suggestions":{"musicShelfRenderer":{"contents":[${track("unrelated")}],"continuations":[{"nextContinuationData":{"continuation":"suggestions"}}]}}}"""
            else """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[${track("one")},${track("two")}]}},"suggestions":{"nextContinuationData":{"continuation":"suggestions"}}}"""
        }
        val pages = mutableListOf<List<String>>()
        val tracks = api.tracks(PlaylistResult("VLprivate", "Private playlist")) { pages += it.map { song -> song.id } }
        assertEquals(listOf(listOf("one"), listOf("one", "two")), pages)
        assertEquals(listOf("one", "two"), tracks.map { it.id })
        assertEquals(2, requests)
    }

    @Test fun libraryListLayoutParsesPlaylistLinksInsideTitleRuns() {
        val json = JSONObject("""{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"My playlist","navigationEndpoint":{"browseEndpoint":{"browseId":"VLlist"}}}]}}}]}}""")
        assertEquals("VLlist", parseYouTubePlaylists(json).single().id)
        assertEquals("My playlist", parseYouTubePlaylists(json).single().title)
    }

    @Test fun savedAccountsAreProtectedAndTheirPlaylistCachesStaySeparate() {
        val directory = Files.createTempDirectory("auralis-youtube-accounts-test").toFile()
        val file = java.io.File(directory, "accounts.properties")
        try {
            val store = YouTubeAccountsStore(ProtectedYouTubeStore(file))
            val first = store.save(YouTubeIdentity("First", "@first", null), cookie, YouTubeScope(), listOf(PlaylistResult("VLone", "One")))
            val second = store.save(YouTubeIdentity("Second", "@second", null), "$cookie-2", YouTubeScope(), listOf(PlaylistResult("VLtwo", "Two")))
            // Settings writes are asynchronous; wait for the second snapshot to reach disk.
            val deadline = System.nanoTime() + 5_000_000_000L
            while (runCatching { YouTubeAccountsStore(ProtectedYouTubeStore(file)).summaries().size }.getOrDefault(0) != 2 && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertFalse(file.readText().contains("fake-secret"))
            val restored = YouTubeAccountsStore(ProtectedYouTubeStore(file))
            assertEquals(2, restored.summaries().size)
            assertEquals(cookie, restored.session(first)!!.getString("session"))
            assertEquals("VLone", restored.playlists(first).single().id)
            assertEquals("VLtwo", restored.playlists(second).single().id)
            restored.remove(first)
            assertNull(restored.session(first))
            assertEquals(second, restored.summaries().single().id)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
