package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchResponseLayoutTest {
    private fun response(name: String) = JSONObject(javaClass.getResource("/search/$name.json")!!.readText())

    @Test fun newGeneralLayoutIncludesOfficialSongsAndAlbumWhenFilteredSearchFails() {
        val result = InnerTubeClient().parseYtMusicSearchResults(response("graduation-general"))
        assertTrue("Song rows below the album card must be parsed", result.songs.any {
            it.title == "Can't Tell Me Nothing" && it.artist == "Kanye West"
        })
        assertTrue("Kanye's Graduation must survive parsing", result.albums.any {
            it.title == "Graduation" && it.author == "Kanye West"
        })
        assertTrue("The rest of the response must not be dropped", result.songs.size > 5)
    }

    @Test fun newLayoutKeepsStudioTrackInsteadOfOnlyTheMusicVideoCard() {
        val result = InnerTubeClient().parseYtMusicSearchResults(response("we-are-the-people-general"))
        assertTrue(result.songs.any { it.artist.contains("Empire", true) && it.album != null })
        assertTrue(result.songs.size > 5)
    }

    @Test fun originalShelfLayoutStillParsesOfficialSongs() {
        val result = InnerTubeClient().parseYtMusicSearchResults(response("we-are-the-people-songs"))
        assertTrue(result.songs.any { it.title == "We Are The People" && it.artist == "Empire Of The Sun" })
        assertTrue(result.songs.size > 10)
    }

    @Test fun songRowsNestedInsideTopCardAreNotLost() {
        val root = response("we-are-the-people-songs")
        val sections = root.getJSONObject("contents").getJSONObject("tabbedSearchResultsRenderer")
            .getJSONArray("tabs").getJSONObject(0).getJSONObject("tabRenderer")
            .getJSONObject("content").getJSONObject("sectionListRenderer")
        val rows = sections.getJSONArray("contents").getJSONObject(0)
            .getJSONObject("musicShelfRenderer").getJSONArray("contents")
        val card = response("graduation-general").getJSONObject("contents")
            .getJSONObject("tabbedSearchResultsRenderer").getJSONArray("tabs").getJSONObject(0)
            .getJSONObject("tabRenderer").getJSONObject("content").getJSONObject("sectionListRenderer")
            .getJSONArray("contents").getJSONObject(0).getJSONObject("musicCardShelfRenderer")
        card.put("contents", rows)
        sections.put("contents", org.json.JSONArray().put(JSONObject().put("musicCardShelfRenderer", card)))
        val result = InnerTubeClient().parseYtMusicSearchResults(root)
        assertTrue(result.songs.any { it.title == "We Are The People" && it.artist == "Empire Of The Sun" })
        assertTrue(result.songs.size > 10)
    }
}
