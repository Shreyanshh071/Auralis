package com.auralis.music

import com.auralis.music.domain.model.HistoryEntry
import com.auralis.music.domain.model.PlayCountEntry
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.NewUserSeedProvider
import com.auralis.music.domain.recommendations.TasteProfiler
import com.auralis.music.domain.recommendations.TrackDeduplicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewUserRecommendationsTest {

    @Test
    fun `seed artists pool contains all 9 required artists`() {
        val expectedArtists = listOf(
            "Tame Impala",
            "Kanye West",
            "Karan Aujla",
            "Radiohead",
            "KR\$NA",
            "Arijit Singh",
            "KK",
            "Shreya Ghoshal",
            "Atif Aslam"
        )

        assertEquals(9, NewUserSeedProvider.SEED_ARTISTS.size)
        for (artist in expectedArtists) {
            assertTrue("Seed pool must include $artist", NewUserSeedProvider.SEED_ARTISTS.contains(artist))
        }
    }

    @Test
    fun `initial seed tracks cover all 9 seed artists and contain no duplicates`() {
        val seedTracks = NewUserSeedProvider.getInitialSeedTracks()

        assertTrue(seedTracks.isNotEmpty())
        assertEquals(27, seedTracks.size)

        // Verify that all 9 artists are represented
        val artistsInTracks = seedTracks.map { it.artist }.distinct()
        assertEquals(9, artistsInTracks.size)

        // Verify no duplicate IDs or songs
        val deduplicated = TrackDeduplicator.deduplicateTracks(seedTracks)
        assertEquals(seedTracks.size, deduplicated.size)
    }

    @Test
    fun `tasteProfiler with zero history yields new-user seed artists and welcome vibe`() {
        val emptyProfile = TasteProfiler.computeTasteProfile(emptyList(), emptyList())

        assertTrue(emptyProfile.topArtists.isEmpty())
        assertEquals(0, emptyProfile.totalPlaysLogged)
        assertEquals(NewUserSeedProvider.SEED_ARTISTS, emptyProfile.recommendedSeeds)
        assertEquals("Welcome to Auralis", emptyProfile.primaryVibe)
    }

    @Test
    fun `transition test - listening history takes over taste profile without seed bias`() {
        val now = System.currentTimeMillis()
        val userTrack = Track(id = "user1", title = "Starboy", artist = "The Weeknd", duration = 230, thumbnail = "https://thumb")

        val history = listOf(HistoryEntry(track = userTrack, playedAt = now))
        val playCounts = listOf(PlayCountEntry(trackId = "user1", count = 5, lastPlayed = now, track = userTrack))

        val personalizedProfile = TasteProfiler.computeTasteProfile(history, playCounts, now)

        assertEquals(5, personalizedProfile.totalPlaysLogged)
        assertEquals(1, personalizedProfile.topArtists.size)
        assertEquals("The Weeknd", personalizedProfile.topArtists.first().artistName)
        assertTrue(personalizedProfile.recommendedSeeds.any { it.contains("The Weeknd") })
        assertFalse(personalizedProfile.recommendedSeeds.contains("Tame Impala"))
    }

    @Test
    fun `interleaveTracks distributes tracks evenly across seed artists without consecutive clumps`() {
        val groups = mapOf(
            "Tame Impala" to listOf(
                Track(id = "t1", title = "Song T1", artist = "Tame Impala"),
                Track(id = "t2", title = "Song T2", artist = "Tame Impala")
            ),
            "Kanye West" to listOf(
                Track(id = "k1", title = "Song K1", artist = "Kanye West"),
                Track(id = "k2", title = "Song K2", artist = "Kanye West")
            ),
            "Karan Aujla" to listOf(
                Track(id = "a1", title = "Song A1", artist = "Karan Aujla"),
                Track(id = "a2", title = "Song A2", artist = "Karan Aujla")
            )
        )

        val interleaved = NewUserSeedProvider.interleaveTracks(groups, maxTotal = 6)
        assertEquals(6, interleaved.size)

        // First 3 tracks should all be distinct artists (round-robin)
        val firstThreeArtists = interleaved.take(3).map { it.artist }.distinct()
        assertEquals(3, firstThreeArtists.size)
    }
}
