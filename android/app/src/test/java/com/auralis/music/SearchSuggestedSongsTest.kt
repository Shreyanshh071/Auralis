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

    private fun song(id: String, title: String, artist: String, plays: String) =
        com.auralis.music.domain.model.Track(id = id, title = title, artist = artist, views = plays)

    @Test fun `a far more popular near spelling is the song the user means`() {
        val (ranked, _) = com.auralis.music.domain.search.SearchQueryMatcher.partitionResults(listOf(
            song("lime", "Imposter Syndrome", "Lime Cordiale", "363K plays"),
            song("gish", "Impostor Syndrome", "Sidney Gish", "45M plays"),
            song("whores", "Imposter Syndrome", "Whores", "78K plays"),
        ), "imposter syndrome")
        assertEquals("gish", ranked.first().id)
    }

    @Test fun `the exact spelling still wins when the typo is not far more played`() {
        val (ranked, _) = com.auralis.music.domain.search.SearchQueryMatcher.partitionResults(listOf(
            song("typo", "Impostor Syndrome", "Someone", "500K plays"),
            song("exact", "Imposter Syndrome", "Lime Cordiale", "363K plays"),
        ), "imposter syndrome")
        assertEquals("exact", ranked.first().id)
    }

    private fun firstOfMix(songPlays: String, albumPlays: Long) = com.auralis.music.data.repository.rankMixedSearchResults(
        "imposter syndrome",
        listOf(song("gish", "Impostor Syndrome", "Sidney Gish", songPlays)),
        listOf(com.auralis.music.domain.model.PlaylistResult(id = "single", title = "Imposter Syndrome", thumbnail = null, author = "Connor Price")),
        mapOf("single" to albumPlays)
    ).first()

    @Test fun `a far more popular near spelling beats a small album spelled like the query`() {
        assertTrue(firstOfMix("86M plays", 358_000L) is com.auralis.music.domain.model.SearchTopResult.SongResult)
    }

    @Test fun `an exact album stays above a near spelling without 10x the plays`() {
        assertTrue(firstOfMix("2M plays", 358_000L) is com.auralis.music.domain.model.SearchTopResult.AlbumResult)
    }

    @Test fun `a hit typed without its small words beats tiny exact uploads`() {
        val (ranked, _) = com.auralis.music.domain.search.SearchQueryMatcher.partitionResults(listOf(
            song("dj", "The Less I Know Better", "Dj Dancer", "781 plays"),
            song("marcin", "The Less I Know Better", "Marcin Gasiewicz", "48 plays"),
            song("tame", "The Less I Know The Better", "Tame Impala", "1B plays"),
            song("onerepublic", "The Less I Know", "OneRepublic", "358K plays"),
        ), "less i know better")
        assertEquals("tame", ranked.first().id)
    }

    @Test fun `a small-word match still needs 100x the plays of the exact title`() {
        val (ranked, _) = com.auralis.music.domain.search.SearchQueryMatcher.partitionResults(listOf(
            song("close", "Story of My Life", "Someone", "50M plays"),
            song("exact", "Story My Life", "Band", "2M plays"),
        ), "story my life")
        assertEquals("exact", ranked.first().id)
    }

    @Test fun `a title with another real word is not a small-word match`() {
        val (ranked, _) = com.auralis.music.domain.search.SearchQueryMatcher.partitionResults(listOf(
            song("exact", "Less Better", "Tiny", "100 plays"),
            song("other", "Less Is Much Better", "Huge", "1B plays"),
        ), "less better")
        assertEquals("exact", ranked.first().id)
    }

    @Test fun `the album cut counts even when YouTube Music leaves the feature uncredited`() {
        val spotify = com.auralis.music.domain.model.Track(id = "sp_x", title = "Like Him (feat. Lola Young)",
            artist = "Tyler, The Creator, Lola Young", album = "CHROMAKOPIA", duration = 279)
        val albumCut = com.auralis.music.domain.model.Track(id = "yt", title = "Like Him",
            artist = "Tyler, The Creator", album = "CHROMAKOPIA", duration = 279)
        assertEquals(null, com.auralis.music.domain.search.SearchQueryMatcher.recordingMismatch(spotify, albumCut))
    }

    @Test fun `a version without the feature on another release is still rejected`() {
        val spotify = com.auralis.music.domain.model.Track(id = "sp_x", title = "Song (feat. Guest)",
            artist = "Main, Guest", album = "Album", duration = 200)
        val other = com.auralis.music.domain.model.Track(id = "yt", title = "Song",
            artist = "Main", album = "Different", duration = 200)
        assertTrue(com.auralis.music.domain.search.SearchQueryMatcher.recordingMismatch(spotify, other) != null)
    }

    @Test fun `an answer without suggestions is simply empty`() {
        assertTrue(SearchSuggestionsClient().parseYtMusicSuggestedRows("""{"responseContext":{}}""").isEmpty())
    }
}
