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
            createTrack("4", "Levitating (Live)", "Dua Lipa"),
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

        // Recommendations MUST be empty or capped at 3, never unlimited
        assertTrue("Recommendations must not exceed 3", recommendations.size <= 3)
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

    @Test
    fun `testStudioTrackIsPrioritizedOverLongMusicVideo`() {
        val candidates = listOf(
            // Music video with bloated duration (5:42 = 342s)
            Track(
                id = "sBzrzS1Ag_g",
                title = "The Less I Know The Better (Official Video)",
                artist = "Tame Impala",
                album = null,
                duration = 342L,
                thumbnail = "https://example.com/video.jpg"
            ),
            // Official studio track from album "Currents" (3:36 = 216s)
            Track(
                id = "PvM79DJ2PmM",
                title = "The Less I Know The Better",
                artist = "Tame Impala",
                album = "Currents",
                duration = 216L,
                thumbnail = "https://example.com/album.jpg"
            ),
            // Related song for recommendation
            Track(
                id = "NMRhx71bGo4",
                title = "Let It Happen",
                artist = "Tame Impala",
                album = "Currents",
                duration = 468L,
                thumbnail = "https://example.com/album.jpg"
            )
        )

        val (matches, recommendations) = SearchQueryMatcher.partitionResults(candidates, "The Less I Know The Better")

        // 1. Studio track (PvM79DJ2PmM) must be selected as #1 match
        assertTrue("Matches must not be empty", matches.isNotEmpty())
        val topMatch = matches.first()
        assertEquals("PvM79DJ2PmM", topMatch.id)
        assertEquals("Currents", topMatch.album)
        assertEquals(216L, topMatch.duration)

        // 2. Music video must NOT be in recommendations as a duplicate
        assertFalse("Music video must not be recommended when song is matched", recommendations.any { it.id == "sBzrzS1Ag_g" })
        assertTrue("Different song by same artist should be recommended", recommendations.any { it.id == "NMRhx71bGo4" })
    }

    @Test
    fun `testScoreTrackCandidatePrefersStudioAlbumTrackOverMusicVideo`() {
        val target = Track(
            id = "target1",
            title = "The Less I Know The Better",
            artist = "Tame Impala",
            duration = 216L
        )

        val studioCand = Track(
            id = "PvM79DJ2PmM",
            title = "The Less I Know The Better",
            artist = "Tame Impala",
            album = "Currents",
            duration = 216L
        )

        val videoCand = Track(
            id = "sBzrzS1Ag_g",
            title = "The Less I Know The Better (Official Video)",
            artist = "Tame Impala",
            album = null,
            duration = 342L
        )

        val studioScore = SearchQueryMatcher.scoreTrackCandidate(target, studioCand)
        val videoScore = SearchQueryMatcher.scoreTrackCandidate(target, videoCand)

        assertTrue("Studio track score ($studioScore) must be higher than video score ($videoScore)", studioScore > videoScore)
    }

    @Test
    fun `testOriginalStudioArtistPrioritizedOverOrchestraCovers`() {
        val originalTameImpala = Track(
            id = "tEXYfT_G0W0",
            title = "New Person, Same Old Mistakes",
            artist = "Tame Impala",
            album = "Currents",
            duration = 364L
        )

        val orchestraCover = Track(
            id = "e0cUvAIL3Ps",
            title = "New Person, Same Old Mistakes",
            artist = "Roma Symphony Orchestra",
            album = "RSO Performs Tame Impala",
            duration = 269L
        )

        val rihannaCover = Track(
            id = "y3OBsTTUsjk",
            title = "Same Ol' Mistakes",
            artist = "Rihanna",
            album = "ANTI",
            duration = 398L
        )

        val candidates = listOf(orchestraCover, originalTameImpala, rihannaCover)
        val (matches, recommendations) = SearchQueryMatcher.partitionResults(candidates, "new person same old mistakes")

        assertTrue("Matches must not be empty", matches.isNotEmpty())
        assertEquals("Original artist Tame Impala must be ranked #1", "tEXYfT_G0W0", matches.first().id)
        assertEquals("Tame Impala", matches.first().artist)
        assertEquals("Currents", matches.first().album)
    }

    @Test
    fun `testExactMatchWithHighestViewsRanksAtTop`() {
        val loversSumika = Track(
            id = "lovers_sumika",
            title = "Lovers",
            artist = "sumika",
            album = "Answer Parade",
            views = "127M plays",
            duration = 230L
        )
        val loversBahramji = Track(
            id = "lovers_bahramji",
            title = "Lovers",
            artist = "Bahramji & Mashti",
            album = "Buddha Bar XII",
            views = "178K plays",
            duration = 300L
        )
        val loversRock = Track(
            id = "lovers_rock",
            title = "Lovers Rock",
            artist = "TV Girl",
            album = "French Exit",
            views = "302M plays",
            duration = 213L
        )

        val candidates = listOf(loversBahramji, loversSumika, loversRock)
        val (matches, _) = SearchQueryMatcher.partitionResults(candidates, "lovers")

        assertTrue("Matches must not be empty", matches.isNotEmpty())
        assertEquals("Exact title match with 127M plays must be ranked #1", "lovers_sumika", matches.first().id)
        assertEquals("Lovers", matches.first().title)
    }

    @Test
    fun `testDraculaExactSongPrioritizedOverTypoDragulaAndRankedByViews`() {
        val dragulaRobZombie = Track(
            id = "dragula_rob_zombie",
            title = "Dragula",
            artist = "Rob Zombie",
            album = "Hellbilly Deluxe",
            views = "514M plays",
            duration = 222L
        )
        val draculaTameImpala = Track(
            id = "dracula_tame_impala",
            title = "Dracula (feat. JENNIE)",
            artist = "Tame Impala",
            album = "Dracula",
            views = "232M plays",
            duration = 210L
        )
        val draculaJokiKana = Track(
            id = "dracula_jokikana",
            title = "Dracula",
            artist = "JokiKana",
            album = "Dracula",
            views = "14M views",
            duration = 195L
        )

        val candidates = listOf(dragulaRobZombie, draculaJokiKana, draculaTameImpala)
        val (matches, _) = SearchQueryMatcher.partitionResults(candidates, "dracula")

        assertEquals(3, matches.size)
        // 1. Tame Impala's exact title match with 232M plays MUST be #1
        assertEquals("Tame Impala - Dracula must be #1", "dracula_tame_impala", matches[0].id)
        assertEquals("Dracula", matches[0].title)

        // 2. JokiKana's exact title match with 14M views MUST be #2
        assertEquals("JokiKana - Dracula must be #2", "dracula_jokikana", matches[1].id)
        assertEquals("Dracula", matches[1].title)

        // 3. Rob Zombie's typo match ("Dragula") with 514M plays MUST be ranked after exact matches
        assertEquals("Rob Zombie - Dragula must be #3", "dragula_rob_zombie", matches[2].id)
        assertEquals("Dragula", matches[2].title)
    }

    @Test
    fun `testPlayCountParsingWithAllFormats`() {
        assertEquals(160_000_000L, SearchQueryMatcher.parsePlayCount("160M+ plays"))
        assertEquals(160_000_000L, SearchQueryMatcher.parsePlayCount("160 million views"))
        assertEquals(160_000_000L, SearchQueryMatcher.parsePlayCount("160M"))
        assertEquals(160_000_000L, SearchQueryMatcher.parsePlayCount("160 mn"))
        assertEquals(1_300_000_000L, SearchQueryMatcher.parsePlayCount("1.3B plays"))
        assertEquals(1_300_000_000L, SearchQueryMatcher.parsePlayCount("1.3 billion views"))
        assertEquals(500_000L, SearchQueryMatcher.parsePlayCount("500k+"))
        assertEquals(500_000L, SearchQueryMatcher.parsePlayCount("500 thousand plays"))
        assertEquals(160_000_000L, SearchQueryMatcher.parsePlayCount("16 crore plays"))
        assertEquals(16_000_000L, SearchQueryMatcher.parsePlayCount("160 lakh views"))
        assertEquals(268_000_000L, SearchQueryMatcher.parsePlayCount("268M plays"))
    }

    @Test
    fun `testRecommendationsPrioritizedStrictlyByViews`() {
        val fashionBritney = Track(
            id = "fashion_britney",
            title = "FASHION",
            artist = "Britney Manson",
            views = "160M+ plays",
            duration = 160L
        )
        val fashionRajat = Track(
            id = "fashion_rajat",
            title = "FASHION",
            artist = "Rajat Nagpal",
            views = "268M plays",
            duration = 180L
        )
        val tntCortis = Track(
            id = "tnt_cortis",
            title = "TNT",
            artist = "CORTIS",
            views = "41M plays",
            duration = 200L
        )
        val redRedCortis = Track(
            id = "redred_cortis",
            title = "REDRED",
            artist = "CORTIS",
            views = "224M plays",
            duration = 210L
        )
        val agoraHillsDoja = Track(
            id = "agora_hills",
            title = "Agora Hills",
            artist = "Doja Cat",
            views = "449M plays",
            duration = 260L
        )

        val candidates = listOf(tntCortis, redRedCortis, fashionRajat, fashionBritney)
        val (matches, recommendations) = SearchQueryMatcher.partitionResults(candidates, "fashion", maxRecommendations = 3)

        // Matches should contain exact title songs sorted by views descending
        assertEquals(2, matches.size)
        assertEquals("fashion_rajat", matches[0].id)
        assertEquals("fashion_britney", matches[1].id)

        // Recommendations should contain related tracks by matched artists sorted by views descending (224M REDRED > 41M TNT)
        assertEquals(2, recommendations.size)
        assertEquals("redred_cortis", recommendations[0].id)
        assertEquals("tnt_cortis", recommendations[1].id)
    }

    @Test
    fun `testLiveSearchGraduation`() = kotlinx.coroutines.runBlocking {
        val client = com.auralis.music.data.network.InnerTubeClient()
        val generalRes = client.search("graduation")
        val albumsRes = client.search("graduation", com.auralis.music.data.network.InnerTubeClient.FILTER_ALBUMS)
        val artistsRes = client.search("graduation", com.auralis.music.data.network.InnerTubeClient.FILTER_ARTISTS)
        val songsRes = client.search("graduation", com.auralis.music.data.network.InnerTubeClient.FILTER_SONGS)

        println("=== GENERAL TOP RESULT ===")
        println(generalRes.topResult)
        println("=== GENERAL ALBUMS ===")
        generalRes.albums.forEach { println("Album: ${it.title} by ${it.author} (id=${it.id})") }
        println("=== FILTER_ALBUMS ===")
        albumsRes.albums.forEach { println("Filter Album: ${it.title} by ${it.author} (id=${it.id})") }
        println("=== FILTER_ARTISTS ===")
        artistsRes.artists.forEach { println("Filter Artist: ${it.name} (id=${it.id})") }
        println("=== SONGS ===")
        songsRes.songs.take(10).forEach { println("Song: ${it.title} by ${it.artist} (${it.views})") }
    }
}
