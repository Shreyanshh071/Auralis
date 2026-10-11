package com.auralis.music.windows.home

import org.json.JSONObject
import kotlin.test.*

class DesktopRemoteHomeTest {
    @Test fun serviceShelvesPreserveMixedItemOrderAndSubtitle() {
        val root = JSONObject("""{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Listen again"}]},"strapline":{"runs":[{"text":"ACCOUNT"}]}}},"contents":[
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Playlist"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLprivate"}}}},
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Song"}]},"subtitle":{"runs":[{"text":"Artist"}]},"navigationEndpoint":{"watchEndpoint":{"videoId":"song"}}}},
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Album"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREalbum"}}}}
        ]}}""")
        val shelf = parseRemoteHome(root).single()
        assertEquals("Listen again", shelf.title)
        assertEquals("ACCOUNT", shelf.subtitle)
        assertEquals(listOf("album:VLprivate", "track:song", "album:MPREalbum"), shelf.order)
        assertEquals("Artist", shelf.tracks.single().artist)
    }
    @Test fun feedTokenExcludesCarouselTokens() {
        val root = JSONObject("""{"sectionListRenderer":{"contents":[{"musicCarouselShelfRenderer":{"continuations":[{"nextContinuationData":{"continuation":"wrong"}}]}}],"continuations":[{"nextContinuationData":{"continuation":"page"}}]}}""")
        assertEquals("page", remoteHomeContinuation(root))
        root.getJSONObject("sectionListRenderer").remove("continuations")
        assertNull(remoteHomeContinuation(root))
    }
    @Test fun plainAndGridShelvesParseAlongsideCarousels() {
        val root = JSONObject("""{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"title":{"simpleText":"Albums & singles"},"contents":[{"musicTwoRowItemRenderer":{"title":{"simpleText":"Album"},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE1"}}}}]}},{"gridRenderer":{"header":{"gridHeaderRenderer":{"title":{"simpleText":"Library"}}},"items":[{"musicTwoRowItemRenderer":{"title":{"simpleText":"Playlist"},"navigationEndpoint":{"browseEndpoint":{"browseId":"VL2"}}}}]}}]}}""")
        assertEquals(listOf("Albums & singles", "Library"), parseRemoteHome(root).map { it.title })
    }
}
