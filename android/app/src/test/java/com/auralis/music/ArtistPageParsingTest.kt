package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistPageParsingTest {
    @Test
    fun readsExistingSingleColumnArtistLayout() {
        val json = JSONObject("""{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"title":"Songs"}}]}}}}]}}}""")
        val sections = InnerTubeClient().artistSections(json)
        assertEquals(1, sections.length())
        assertEquals("Songs", sections.getJSONObject(0).getJSONObject("musicShelfRenderer").getString("title"))
    }

    @Test
    fun readsBothColumnsOfNewArtistLayout() {
        val json = JSONObject("""{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":{}}]}}}}],"secondaryContents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"title":"Songs"}},{"musicCarouselShelfRenderer":{"title":"Albums"}}]}}}}}""")
        val sections = InnerTubeClient().artistSections(json)
        assertEquals(3, sections.length())
        assertEquals("Songs", sections.getJSONObject(1).getJSONObject("musicShelfRenderer").getString("title"))
        assertEquals("Albums", sections.getJSONObject(2).getJSONObject("musicCarouselShelfRenderer").getString("title"))
    }
}
