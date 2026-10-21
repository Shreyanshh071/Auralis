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
    fun `no preselected artists or fabricated songs before the picker`() {
        NewUserSeedProvider.setStarter(emptyList(), emptyList())
        assertTrue(NewUserSeedProvider.SEED_ARTISTS.isEmpty())
        assertTrue(NewUserSeedProvider.getInitialSeedTracks().isEmpty())
        assertTrue(NewUserSeedProvider.starterRecommendations().isEmpty())
    }

    @Test
    fun `chosen artist shelves preserve choice order and exclude unrelated songs`() {
        try {
            NewUserSeedProvider.setStarter(listOf("Radiohead", "Tame Impala"), listOf(
                Track("one", "Creep", "Radiohead"), Track("two", "Borderline", "Tame Impala"),
                Track("other", "Other song", "Someone else")))
            val shelves = NewUserSeedProvider.starterRecommendations()
            assertEquals(listOf("Radiohead", "Tame Impala"), shelves.map { it.seedTitle })
            assertEquals(listOf("one", "two"), shelves.flatMap { it.items }.map { it.id })
        } finally { NewUserSeedProvider.setStarter(emptyList(), emptyList()) }
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
