package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSuggestedSongsTest {
    private fun column(text: String) =
        """{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$text"}]}}}"""
    private val answer = """{"contents":[
        {"searchSuggestionsSectionRenderer":{"contents":[
            {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"less "},{"text":"than zero"}]}}},
            {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"less"}]}}}
        ]}},
        {"searchSuggestionsSectionRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
                "flexColumns":[${column("Less Than Zero")},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"},{"text":" • "},
                        {"text":"The Weeknd","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}},
                        {"text":" • "},{"text":"124M plays"}]}}},${column("Dawn FM")}],
                "navigationEndpoint":{"watchEndpoint":{"videoId":"JVQxUFuvLSg"}},
                "playlistItemData":{"videoId":"JVQxUFuvLSg"}}},
            {"musicResponsiveListItemRenderer":{
                "flexColumns":[${column("The Weeknd")},${column("Artist")}],
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UC123"}}}}
        ]}}
    ]}"""

    @Test fun `typed suggestions read as whole phrases, in YouTube Music's order`() {
        assertEquals(listOf("less than zero", "less"), SearchSuggestionsClient().parseYtMusicSuggestionsJson(answer))
    }

    @Test fun `only songs from the suggested rows reach the list`() {
        val rows = SearchSuggestionsClient().parseYtMusicSuggestedRows(answer)
        assertEquals(2, rows.size)
        val songs = InnerTubeClient().parseSuggestedSongs(rows)
        assertEquals(listOf("JVQxUFuvLSg"), songs.map { it.id })
        assertEquals("Less Than Zero", songs.single().title)
        assertTrue(songs.single().artist.contains("The Weeknd"))
    }

    @Test fun `an answer without suggestions is simply empty`() {
        assertTrue(SearchSuggestionsClient().parseYtMusicSuggestedRows("""{"responseContext":{}}""").isEmpty())
    }
}
