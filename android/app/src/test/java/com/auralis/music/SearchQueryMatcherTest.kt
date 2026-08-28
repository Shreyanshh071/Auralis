package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import org.junit.Assert.*
import org.junit.Test

class SearchQueryMatcherTest {

    private fun createTrack(id: String, title: String, artist: String, album: String? = null): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = 200L,
            thumbnail = "https://example.com/$id.jpg"
        )
    }

    @Test
    fun `testExactSongSearchPrioritizesCorrectSongAtTop`() {
        val candidates = listOf(
            createTrack("1", "Starboy", "The Weeknd"),
            createTrack("2", "Blinding Lights (Major Lazer Remix)", "The Weeknd"),
            createTrack("3", "Blinding Lights", "The Weeknd"),
            createTrack("4", "Save Your Tears", "The Weeknd"),
            createTrack("5", "Blinding Lights (Official Audio)", "The Weeknd")
        )

        val (matches, recommendations) = SearchQueryMatcher.partitionResults(candidates, "Blinding Lights")

        // Exact match "Blinding Lights" must be #1
        assertTrue("Matches must not be empty", matches.isNotEmpty())
        assertEquals("Blinding Lights", matches.first().title)
        assertEquals("3", matches.first().id)

        // Non-matching songs ("Starboy", "Save Your Tears") must be in recommendations, not in matches
        assertTrue(recommendations.any { it.title == "Starboy" })
        assertTrue(recommendations.any { it.title == "Save Your Tears" })
        assertFalse(matches.any { it.title == "Starboy" })
        assertFalse(matches.any { it.title == "Save Your Tears" })
    }

    @Test
    fun `testRecommendationsAreCappedAtThreeMaximum`() {
        val candidates = listOf(
            createTrack("match1", "Blinding Lights", "The Weeknd"),
            createTrack("rec1", "Save Your Tears", "The Weeknd"),
            createTrack("rec2", "In Your Eyes", "The Weeknd"),
            createTrack("rec3", "Starboy", "The Weeknd"),
            createTrack("rec4", "The Hills", "The Weeknd"),
            createTrack("rec5", "Can't Feel My Face", "The Weeknd")
        )

        val (matches, recommendations) = SearchQueryMatcher.partitionResults(candidates, "Blinding Lights", maxRecommendations = 3)

        assertEquals("Recommendations must be strictly capped at 3", 3, recommendations.size)
        assertEquals(1, matches.size)
        assertEquals("Blinding Lights", matches.first().title)
    }

    @Test
    fun `testResultsAfterRecommendationSectionAreOnlyQueryMatchedResults`() {
        val candidates = listOf(
            createTrack("1", "Levitating", "Dua Lipa"),
            createTrack("2", "Don't Start Now", "Dua Lipa"),
            createTrack("3", "New Rules", "Dua Lipa"),
            createTrack("4", "Levitating (feat. DaBaby)", "Dua Lipa"),
            createTrack("5", "Cold Heart", "Elton John, Dua Lipa")
        )

        val (matches, recommendations) = SearchQueryMatcher.partitionResults(candidates, "Levitating")

        // Matches should only contain songs with "Levitating" in title/metadata
        for (m in matches) {
            val eval = SearchQueryMatcher.evaluateMatch(m, "Levitating")
            assertNotNull("Every song in matches must match the query", eval)
        }

        assertTrue(matches.any { it.id == "1" })
        assertTrue(matches.any { it.id == "4" })
        assertFalse(matches.any { it.id == "2" }) // "Don't Start Now" is not in matches
        assertFalse(matches.any { it.id == "3" }) // "New Rules" is not in matches
    }

    @Test
    fun `testArtistSearchReturnsArtistSongs`() {
        val candidates = listOf(
            createTrack("1", "Shape of You", "Ed Sheeran"),
            createTrack("2", "Perfect", "Ed Sheeran"),
            createTrack("3", "Bad Habits", "Ed Sheeran"),
            createTrack("4", "Thinking Out Loud", "Ed Sheeran"),
            createTrack("5", "Someone Like You", "Adele")
        )

        val (matches, recommendations) = SearchQueryMatcher.partitionResults(candidates, "Ed Sheeran")

        // All Ed Sheeran songs should match
        assertEquals(4, matches.size)
        assertTrue(matches.all { it.artist.contains("Ed Sheeran", ignoreCase = true) })

        // Adele song is not by Ed Sheeran and should be in recommendations
        assertEquals(1, recommendations.size)
        assertEquals("Someone Like You", recommendations.first().title)
    }

    @Test
    fun `testPartialSearchesReturnRelevantMatches`() {
        val candidates = listOf(
            createTrack("1", "Chasing Cars", "Snow Patrol"),
            createTrack("2", "Fast Car", "Tracy Chapman"),
            createTrack("3", "Drive", "The Cars"),
            createTrack("4", "Yellow", "Coldplay")
        )

        val (matches, recommendations) = SearchQueryMatcher.partitionResults(candidates, "Car")

        // Songs containing "Car" / "Cars"
        assertTrue(matches.any { it.id == "1" })
        assertTrue(matches.any { it.id == "2" })
        assertTrue(matches.any { it.id == "3" })

        // "Yellow" by Coldplay has no "Car" and goes to recommendations
        assertFalse(matches.any { it.id == "4" })
        assertTrue(recommendations.any { it.id == "4" })
    }

    @Test
    fun `testUnrelatedRecommendationsDoNotReplaceSearchMatches`() {
        val candidates = listOf(
            createTrack("1", "Believer", "Imagine Dragons"),
            createTrack("2", "Thunder", "Imagine Dragons"),
            createTrack("3", "Radioactive", "Imagine Dragons"),
            createTrack("4", "Demons", "Imagine Dragons"),
            createTrack("5", "Natural", "Imagine Dragons")
        )

        val (matches, recommendations) = SearchQueryMatcher.partitionResults(candidates, "Believer")

        // Only "Believer" is a match
        assertEquals(1, matches.size)
        assertEquals("Believer", matches.first().title)

        // The remaining 4 songs are candidate recommendations, capped at 3
        assertEquals(3, recommendations.size)
        assertFalse(recommendations.any { it.title == "Believer" })
    }

    @Test
    fun `testNoDuplicateAppearsInBothSections`() {
        val candidates = listOf(
            createTrack("1", "Flowers", "Miley Cyrus"),
            createTrack("2", "Flowers (Demo)", "Miley Cyrus"),
            createTrack("3", "Wrecking Ball", "Miley Cyrus"),
            createTrack("4", "Midnight Sky", "Miley Cyrus")
        )

        val (matches, recommendations) = SearchQueryMatcher.partitionResults(candidates, "Flowers")

        val matchIds = matches.map { it.id }.toSet()
        val recIds = recommendations.map { it.id }.toSet()

        val duplicates = matchIds.intersect(recIds)
        assertTrue("There must be zero duplicate track IDs between matches and recommendations", duplicates.isEmpty())
    }

    @Test
    fun `testNoResultSearchesDoNotGetFilledWithUnlimitedRecommendations`() {
        val candidates = listOf(
            createTrack("1", "Song A", "Artist A"),
            createTrack("2", "Song B", "Artist B"),
            createTrack("3", "Song C", "Artist C"),
            createTrack("4", "Song D", "Artist D"),
            createTrack("5", "Song E", "Artist E"),
            createTrack("6", "Song F", "Artist F")
        )

        // Gibberish search query
        val (matches, recommendations) = SearchQueryMatcher.partitionResults(candidates, "xyzqwertynoresult")

        // Matches MUST be empty!
        assertTrue("No-result query must have 0 matching songs", matches.isEmpty())

        // Recommendations MUST be capped at 3, NOT unlimited!
        assertEquals("Recommendations must be capped at 3", 3, recommendations.size)
    }

    @Test
    fun `testFewerThanThreeRecommendationsDoesNotInventDuplicates`() {
        val candidates = listOf(
            createTrack("1", "Espresso", "Sabrina Carpenter"),
            createTrack("2", "Please Please Please", "Sabrina Carpenter")
        )

        val (matches, recommendations) = SearchQueryMatcher.partitionResults(candidates, "Espresso")

        assertEquals(1, matches.size)
        assertEquals("Espresso", matches.first().title)

        // Only 1 non-matching track exists, so recommendations should have exactly 1 (not duplicated to 3)
        assertEquals(1, recommendations.size)
        assertEquals("Please Please Please", recommendations.first().title)
    }
}
