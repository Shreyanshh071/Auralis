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
            { PlaylistImportIntegrity.requireComplete("YouTube", 40, 100, allowMissing = true) },
            { PlaylistImportIntegrity.requireReadable("Spotify", 100, 0) }
        )) {
            try { check(); fail("Expected incomplete import") } catch (_: IncompletePlaylistImport) { }
        }
        PlaylistImportIntegrity.requireComplete("Spotify", 100, 100)
    }

    @Test fun `a renamed shelf still yields the song rows`() {
        val song = CompleteYouTubePlaylistImporter().page(JSONObject(fixture()))!!.rows.getJSONObject(0)
        val root = JSONObject().put("shelf", JSONObject().put("somethingNewRenderer", JSONObject().put("contents", JSONArray().put(song))))
        assertEquals(1, CompleteYouTubePlaylistImporter().page(root)!!.rows.length())
    }

    @Test fun `the regular YouTube route takes over when YouTube Music shows no songs`() = runBlocking {
        val hosts = mutableListOf<String>()
        val importer = CompleteYouTubePlaylistImporter(client { request ->
            hosts += request.url.host
            if (request.url.host == "www.youtube.com") fixture() else """{"responseContext":{}}"""
        })
        assertEquals(100, importer.importPlaylistById("PLprivate").tracks.size)
        assertEquals(listOf("music.youtube.com", "www.youtube.com"), hosts)
    }

    @Test fun `regular YouTube rows and their next page marker are read`() {
        val lockup = """{"lockupViewModel":{"contentId":"abc12345678","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
            "contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/a.jpg","width":168,"height":94}]},
            "overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"3:55"}}]}}]}},
            "metadata":{"lockupMetadataViewModel":{"title":{"content":"Song"},"metadata":{"contentMetadataViewModel":{
            "metadataRows":[{"metadataParts":[{"text":{"content":"Artist - Topic"}}]}]}}}}}}"""
        val marker = """{"continuationItemViewModel":{"continuationCommand":{"innertubeCommand":{"continuationCommand":{"token":"next"}}}}}"""
        val root = JSONObject("""{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[$lockup]}},$marker]}}}""")
        val importer = CompleteYouTubePlaylistImporter()
        val page = importer.page(root)!!
        assertEquals("next", page.continuation)
        val track = importer.parseRows(page.rows, "playlist", 0).single()
        assertEquals(listOf("abc12345678", "Song", "Artist"), listOf(track.id, track.title, track.artist))
        assertEquals(235L, track.duration)
    }

    @Test fun `appended rows win over the stale page in a later response`() {
        val row = CompleteYouTubePlaylistImporter().page(JSONObject(fixture()))!!.rows.getJSONObject(0)
        val root = JSONObject("""{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[$row,$row,$row]}}]}},
            "onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$row]}}]}""")
        assertEquals(1, CompleteYouTubePlaylistImporter().page(root)!!.rows.length())
    }

    @Test fun `an empty playlist imports as empty instead of failing`() = runBlocking {
        val importer = CompleteYouTubePlaylistImporter(client {
            """{"header":{"musicResponsiveHeaderRenderer":{"title":{"simpleText":"aaaaa"},"secondSubtitle":{"simpleText":"0 tracks"}}}}"""
        })
        val playlist = importer.importPlaylistById("PLempty")
        assertEquals("aaaaa", playlist.title)
        assertTrue(playlist.tracks.isEmpty())
    }

    @Test fun `a playlist no route can show fails without replacing anything`() = runBlocking {
        val importer = CompleteYouTubePlaylistImporter(client { """{"responseContext":{}}""" })
        try { importer.importPlaylistById("PLgone"); fail("Expected failure") }
        catch (e: IncompletePlaylistImport) { assertTrue(e.message!!.contains("Nothing was replaced")) }
    }

    @Test fun `hidden or unplayable entries no longer fail the whole playlist`() {
        PlaylistImportIntegrity.requireComplete("YouTube", 288, 295, allowMissing = true)
        PlaylistImportIntegrity.requireReadable("Spotify", 100, 93)
    }

    @Test fun `an unknown YouTube row is skipped and the rest of the page survives`() {
        val song = CompleteYouTubePlaylistImporter().page(JSONObject(fixture()))!!.rows.getJSONObject(0)
        val rows = JSONArray().put(song).put(JSONObject("""{"somethingNewRenderer":{}}""")).put(song)
        assertEquals(2, CompleteYouTubePlaylistImporter().parseRows(rows, "playlist", 0).size)
    }

    @Test fun `Spotify local files and podcasts are skipped while songs import`() {
        val rows = JSONArray("""[
            {"itemV2":{"data":{"uri":"spotify:track:real","name":"Real Song","artists":{"items":[{"profile":{"name":"Artist"}}]}}}},
            {"itemV2":{"data":{"uri":"spotify:local:a:b:c:200","name":"Local File"}}},
            {"itemV2":{"data":{"uri":"spotify:episode:show","name":"Podcast"}}},
            {"itemV2":{"data":{"__typename":"NotFound"}}}
        ]""")
        val parsed = mutableListOf<com.auralis.music.domain.model.Track>()
        SpotifyLibrary.parseGqlTracks(rows, likedSongs = false, parsed)
        assertEquals(listOf("sp_real"), parsed.map { it.id })
    }

    private fun song(id: String, title: String = id, artist: String = "Artist") =
        com.auralis.music.domain.model.Track(id = id, title = title, artist = artist)

    @Test fun `reimport brings only songs that are new to the playlist`() {
        val remote = listOf(song("sp_old"), song("sp_new"))
        val added = SpotifyIncrementalImport.newTracks(remote, listOf(song("sp_old")), setOf("sp_old"))
        assertEquals(listOf("sp_new"), added.map { it.id })
    }

    @Test fun `a song removed in Auralis is not brought back by reimport`() {
        val added = SpotifyIncrementalImport.newTracks(listOf(song("sp_removed")), emptyList(), setOf("sp_removed"))
        assertTrue(added.isEmpty())
    }

    @Test fun `songs already matched to YouTube are not imported twice`() {
        val local = listOf(song("yt_matched", "Other Name"), song("yt_same", "Same Song", "Artist"))
        val remote = listOf(song("sp_matched", "Matched"), song("sp_same", "Same Song", "Artist, Guest"))
        val added = SpotifyIncrementalImport.newTracks(remote, local, emptySet()) { id ->
            "yt_matched".takeIf { id == "sp_matched" }
        }
        assertTrue(added.isEmpty())
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
