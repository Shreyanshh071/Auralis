package com.auralis.music

import com.auralis.music.data.network.YouTubeMusicLibrary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picking playlists from the signed-in YouTube Music library (FEmusic_liked_playlists). */
class YouTubeMusicLibraryTest {

    private fun card(browseId: String?, title: String, subtitle: String, thumb: String? = "https://i/$title.jpg"): String {
        val nav = if (browseId != null) """"navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId"}},"""
            else """"navigationEndpoint":{"createPlaylistEndpoint":{}},"""
        val thumbs = if (thumb != null) """"thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"small"},{"url":"$thumb"}]}}},""" else ""
        return """{"musicTwoRowItemRenderer":{$thumbs"title":{"runs":[{"text":"$title"}]},$nav"subtitle":{"runs":[{"text":"$subtitle"}]}}}"""
    }

    // Shape of the real response: single-column tabs -> section list -> grid of two-row cards.
    private val page = JSONObject(
        """{"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"gridRenderer":{"items":[
            ${card(null, "New playlist", "", null)},
            ${card("VLLM", "Liked Music", "Auto playlist")},
            ${card("VLPLabc123", "gili gili", "Shreyanshh • 97 tracks")},
            ${card("VLSE", "Episodes for later", "Auto playlist")},
            ${card("VLPLprivate9", "Late night", "Private • 12 tracks")}
        ],"continuations":[{"nextContinuationData":{"continuation":"NEXTPAGE"}}]}}]}}}}]}}}"""
    )

    @Test
    fun `lists playlists and liked music, skips the new-playlist button and podcasts`() {
        val (playlists, next) = YouTubeMusicLibrary.parsePage(page)
        assertEquals(listOf("LM", "PLabc123", "PLprivate9"), playlists.map { it.id })
        assertEquals(listOf("Liked Music", "gili gili", "Late night"), playlists.map { it.title })
        assertTrue(playlists.first().isLikedMusic)
        assertEquals("Private • 12 tracks", playlists.last().subtitle)
        assertEquals("https://i/gili gili.jpg", playlists[1].thumbnail)
        assertEquals("NEXTPAGE", next)
    }

    @Test
    fun `last page has no continuation`() {
        val last = JSONObject("""{"continuationContents":{"gridContinuation":{"items":[${card("VLPLz", "Gym", "5 tracks")}]}}}""")
        val (playlists, next) = YouTubeMusicLibrary.parsePage(last)
        assertEquals(listOf("PLz"), playlists.map { it.id })
        assertEquals(null, next)
    }

    @Test
    fun `grid continuation key is followed too`() {
        val grid = JSONObject("""{"gridRenderer":{"items":[${card("VLPLq", "Q", "")}],"continuations":[{"nextGridContinuationData":{"continuation":"GRIDNEXT"}}]}}""")
        assertEquals("GRIDNEXT", YouTubeMusicLibrary.parsePage(grid).second)
    }
}
