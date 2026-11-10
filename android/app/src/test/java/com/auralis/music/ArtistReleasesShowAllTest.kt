package com.auralis.music

import com.auralis.music.data.network.artistCatalogueContinuation
import com.auralis.music.data.network.artistCatalogueItems
import com.auralis.music.data.network.artistMoreEndpoint
import com.auralis.music.data.network.artistReleaseFromItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Albums / Singles rows' "Show all": the link the row carries, and the full grid behind it. */
class ArtistReleasesShowAllTest {

    private fun release(id: String, title: String, subtitle: String, pageType: String = "MUSIC_PAGE_TYPE_ALBUM") = """
        {"musicTwoRowItemRenderer":{
          "title":{"runs":[{"text":"$title"}]},
          "subtitle":{"runs":[{"text":"${subtitle.substringBefore(" • ")}"},{"text":" • "},{"text":"${subtitle.substringAfter(" • ")}"}]},
          "navigationEndpoint":{"browseEndpoint":{"browseId":"$id",
            "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$pageType"}}}},
          "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://img/$id"}]}}}}}
    """

    @Test
    fun `albums row keeps the discography link from its title`() {
        // As YouTube Music sends it now (Taylor Swift, 2026-11): the title run carries the link.
        val shelf = JSONObject("""
            {"header":{"musicCarouselShelfBasicHeaderRenderer":{
              "title":{"runs":[{"text":"Albums","navigationEndpoint":{"browseEndpoint":{
                "browseId":"MPADUCPC0L1d253x-KuMNwa05TpA","params":"ggMIegYIARoCAQI%3D"}}}]},
              "moreContentButton":{"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{
                "browseId":"MPADUCPC0L1d253x-KuMNwa05TpA","params":"ggMIegYIARoCAQI%3D"}}}}}},
             "contents":[${release("MPREb_a", "Midnights", "Album • 2022")}]}
        """)
        val endpoint = artistMoreEndpoint(shelf)!!
        assertEquals("MPADUCPC0L1d253x-KuMNwa05TpA", endpoint.browseId)
        assertEquals("ggMIegYIARoCAQI%3D", endpoint.params)
    }

    @Test
    fun `a row without a more link offers no show all`() {
        val shelf = JSONObject("""{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Albums"}]}}},"contents":[]}""")
        assertNull(artistMoreEndpoint(shelf))
    }

    @Test
    fun `full grid lists every release and skips the sort chips`() {
        val page = JSONObject("""
            {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{
              "header":{"chipCloudRenderer":{"chips":[{"chipCloudChipRenderer":{"text":{"runs":[{"text":"Albums"}]}}}]}},
              "contents":[{"gridRenderer":{"items":[
                ${release("MPREb_1", "The Life of a Showgirl", "Album • 2025")},
                ${release("MPREb_2", "Midnights", "Album • 2022")},
                ${release("UCnotanalbum", "Some Artist", "Artist • x", "MUSIC_PAGE_TYPE_ARTIST")}
              ],"continuations":[{"nextContinuationData":{"continuation":"NEXT_PAGE"}}]}}]}}}}]}}}
        """)
        val items = artistCatalogueItems(page)
        assertEquals(3, items.length())
        val releases = (0 until items.length()).mapNotNull { artistReleaseFromItem(items.getJSONObject(it), "Taylor Swift") { a -> a?.getJSONObject(0)?.getString("url").orEmpty() } }
        assertEquals(listOf("MPREb_1", "MPREb_2"), releases.map { it.id })
        assertEquals("Taylor Swift", releases.first().author)
        assertEquals("album", releases.first().releaseType)
        assertEquals("https://img/MPREb_1", releases.first().thumbnail)
        assertEquals("NEXT_PAGE", artistCatalogueContinuation(page))
    }

    @Test
    fun `last page has no continuation`() {
        val page = JSONObject("""{"continuationContents":{"gridContinuation":{"items":[${release("MPREb_9", "Fearless", "Album • 2008")}]}}}""")
        assertEquals(1, artistCatalogueItems(page).length())
        assertNull(artistCatalogueContinuation(page))
    }
}
