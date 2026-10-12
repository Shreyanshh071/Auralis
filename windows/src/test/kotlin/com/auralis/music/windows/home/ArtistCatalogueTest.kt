package com.auralis.music.windows.home

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.domain.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.*
import kotlin.test.*

class ArtistCatalogueTest {
    private fun song(id: String, title: String = id) = JSONObject("""{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$title"}]},"subtitle":{"runs":[{"text":"Guest & Example Artist"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"$id"}}}}""")
    private fun release(id: String) = JSONObject("""{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$id"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"$id"}}}}""")
    private fun page(sections: JSONArray) = JSONObject("""{"header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"Example Artist"}]}}},"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[]}}}}]}}}""").apply {
        getJSONObject("contents").getJSONObject("singleColumnBrowseResultsRenderer").getJSONArray("tabs").getJSONObject(0)
            .getJSONObject("tabRenderer").getJSONObject("content").getJSONObject("sectionListRenderer").put("contents", sections)
    }
    private fun shelf(title: String, items: JSONArray, endpoint: String? = null) = JSONObject().put("musicCarouselShelfRenderer",
        JSONObject().put("header",JSONObject().put("musicCarouselShelfBasicHeaderRenderer",JSONObject().put("title",JSONObject().put("runs",JSONArray().put(JSONObject().put("text",title))))))
            .put("contents",items).apply { if(endpoint != null) put("bottomEndpoint",JSONObject().put("browseEndpoint",JSONObject().put("browseId",endpoint).put("params","opaque-params"))) })

    @Test fun expandsOwnCatalogueKeepsCollaborationsAndSeparatesVideosAndPlaylists() = runBlocking {
        val requested = mutableListOf<String>()
        val root = page(JSONArray().put(shelf("Top songs",JSONArray().put(song("preview")),"VLsongs"))
            .put(shelf("Albums",JSONArray().put(release("MPREpreview")),"MPADalbums"))
            .put(shelf("Videos",JSONArray().put(song("video"))))
            .put(shelf("Playlists by Example Artist",JSONArray().put(release("VLplaylist")))))
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8()); val id = body.getString("browseId"); requested.add(id)
            if(id != "UCexample") assertEquals("opaque-params",body.getString("params"))
            val response = when(id) {
                "UCexample" -> root
                "VLsongs" -> JSONObject().put("contents",JSONObject().put("musicPlaylistShelfRenderer",JSONObject().put("contents",JSONArray().put(song("preview")).put(song("official","Official Instrumental")))))
                "MPADalbums" -> JSONObject().put("contents",JSONObject().put("gridRenderer",JSONObject().put("items",JSONArray().put(release("MPREfull")))))
                else -> error("Unexpected target $id")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(response.toString().toResponseBody()).build()
        }.build()
        val result = assertNotNull(InnerTubeClient(http).getArtistPage(Artist("UCexample","Example Artist",thumbnail="portrait")))
        assertEquals(listOf("preview","official"),result.topSongs.map { it.id })
        assertEquals(listOf("MPREpreview","MPREfull"),result.albums.map { it.id })
        assertEquals(listOf("video"),result.videos.map { it.id })
        assertEquals(listOf("VLplaylist"),result.playlists.map { it.id })
        assertEquals(listOf("UCexample","VLsongs","MPADalbums"),requested)
    }
}
