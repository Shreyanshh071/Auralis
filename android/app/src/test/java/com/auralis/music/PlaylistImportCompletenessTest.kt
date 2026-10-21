package com.auralis.music

import com.auralis.music.data.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaylistImportCompletenessTest {
    private fun fixture() = javaClass.getResource("/imports/youtube-gili-gili-100.json")!!.readText()
    private fun client(answer: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(answer(chain.request()).toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun `user playlist imports all 100 positions including the repeated recording`() = runBlocking {
        val importer = CompleteYouTubePlaylistImporter(client { fixture() })
        val playlist = importer.importPlaylist("https://music.youtube.com/playlist?list=PLaXJX-8WO2Us&si=example")!!
        assertEquals(100, playlist.tracks.size)
        assertEquals(2, playlist.tracks.count { it.id == "MJoSyNdffGo" })
        assertEquals("PvM79DJ2PmM", playlist.tracks.first().id)
        assertEquals("nEUZPehC6rs", playlist.tracks.last().id)
        assertEquals("gili gili", playlist.title)
    }

    @Test fun `explicit imports retain Shorts markers and short songs`() {
        val importer = CompleteYouTubePlaylistImporter()
        val root = JSONObject(fixture())
        val row = importer.page(root)!!.rows.getJSONObject(0)
        row.getJSONObject("musicResponsiveListItemRenderer").put("reelWatchEndpoint", JSONObject())
        assertEquals(1, importer.parseRows(JSONArray().put(row), "playlist", 0).size)
    }

    @Test fun `continuation pages append in order without deduplicating recordings`() = runBlocking {
        val row = CompleteYouTubePlaylistImporter().page(JSONObject(fixture()))!!.rows.getJSONObject(0)
        var requests = 0
        val importer = CompleteYouTubePlaylistImporter(client { request ->
            requests++
            val payload = request.body!!.let { body -> okio.Buffer().also(body::writeTo).readUtf8() }
            if (requests == 1) """{"header":{"musicResponsiveHeaderRenderer":{"title":{"simpleText":"Mix"},"secondSubtitle":{"simpleText":"2 tracks"}}},"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$row]}}],"continuations":[{"nextContinuationData":{"continuation":"second"}}]}}}"""
            else {
                assertEquals("second", JSONObject(payload).getString("continuation"))
                """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$row]}}]}"""
            }
        })
        assertEquals(2, importer.importPlaylistById("PLtest").tracks.size)
        assertEquals(2, requests)
    }

    @Test fun `failed continuation never produces a successful partial playlist`() = runBlocking {
        val row = CompleteYouTubePlaylistImporter().page(JSONObject(fixture()))!!.rows.getJSONObject(0)
        var requests = 0
        val importer = CompleteYouTubePlaylistImporter(client {
            if (++requests == 1) """{"contents":{"musicPlaylistShelfRenderer":{"contents":[$row],"continuations":[{"nextContinuationData":{"continuation":"second"}}]}}}"""
            else throw IOException("Connection interrupted")
        })
        try { importer.importPlaylistById("PLtest"); fail("Expected incomplete import") }
        catch (e: IncompletePlaylistImport) { assertTrue(e.message!!.contains("Nothing was replaced")) }
    }

    @Test fun `counts cannot declare a partial or skipped Spotify import complete`() {
        for (check in listOf<() -> Unit>(
            { PlaylistImportIntegrity.requireComplete("Spotify", 93, 100) },
            { PlaylistImportIntegrity.requireComplete("Spotify", 100, 100, hasMore = true) },
            { PlaylistImportIntegrity.requirePage("Spotify", 100, 93) }
        )) {
            try { check(); fail("Expected incomplete import") } catch (_: IncompletePlaylistImport) { }
        }
        PlaylistImportIntegrity.requireComplete("Spotify", 100, 100)
    }

    @Test fun `Spotify API pagination failure rejects partial data and repeats keep their positions`() = runBlocking {
        val track = """{"track":{"id":"same","name":"Song","artists":[{"name":"Artist"}],"duration_ms":200000}}"""
        val complete = SpotifyPlaylistImporter(client = client {
            """{"name":"Mix","tracks":{"total":2,"next":null,"items":[$track,$track]}}"""
        }).fetchPlaylistFromApi("playlist", "test-token")!!
        assertEquals(2, complete.tracks.size)
        assertEquals(complete.tracks[0].id, complete.tracks[1].id)
        var requests = 0
        val partial = SpotifyPlaylistImporter(client = client {
            if (++requests == 1) """{"name":"Mix","tracks":{"total":2,"next":"https://api.spotify.com/v1/next","items":[$track]}}"""
            else throw IOException("Connection interrupted")
        })
        try { partial.fetchPlaylistFromApi("playlist", "test-token"); fail("Expected incomplete data") }
        catch (_: IncompletePlaylistImport) { }
    }

    @Test fun `Spotify preview cannot be accepted as a complete playlist`() {
        val entity = """{"title":"Mix","trackCount":100,"trackList":[{"uri":"spotify:track:first","title":"First","subtitle":"Artist"}]}"""
        val html = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":$entity}}}}}</script>"""
        try { SpotifyPlaylistImporter().parseCompleteEmbed(html, "playlist", SpotifyItemType.PLAYLIST); fail("Expected incomplete preview") }
        catch (_: IncompletePlaylistImport) { }
    }

    @Test fun `source-unavailable YouTube entries stay visible and cannot resolve to random songs`() = runBlocking {
        val rows = JSONArray("""[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Deleted video"}}}]}}]""")
        val entry = CompleteYouTubePlaylistImporter().parseRows(rows, "playlist", 3).single()
        assertEquals("Deleted video", entry.title)
        assertTrue(entry.id.startsWith("unavailable:"))
        assertNull(AudioStreamResolver.resolvePlaybackVideoId(entry))
    }
}
