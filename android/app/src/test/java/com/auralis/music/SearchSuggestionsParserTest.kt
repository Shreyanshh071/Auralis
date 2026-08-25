package com.auralis.music

import com.auralis.music.data.network.SearchSuggestionsClient
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSuggestionsParserTest {

    @Test
    fun `parseSuggestionsJson parses Google Suggest JSON array correctly`() {
        val client = SearchSuggestionsClient()
        val mockJson = """["the weeknd", ["the weeknd", "the weeknd blinding lights", "the weeknd starboy", "the weeknd songs"]]"""

        val suggestions = client.parseSuggestionsJson(mockJson)
        assertEquals(4, suggestions.size)
        assertEquals("the weeknd", suggestions[0])
        assertEquals("the weeknd blinding lights", suggestions[1])
        assertEquals("the weeknd starboy", suggestions[2])
    }

    @Test
    fun `parseSuggestionsJson handles malformed or empty responses gracefully`() {
        val client = SearchSuggestionsClient()
        val empty = client.parseSuggestionsJson("")
        assertEquals(0, empty.size)

        val invalid = client.parseSuggestionsJson("{ \"error\": \"not an array\" }")
        assertEquals(0, invalid.size)
    }
}
