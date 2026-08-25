package com.auralis.music

import com.auralis.music.domain.model.HistoryEntry
import com.auralis.music.domain.model.PlayCountEntry
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.TasteProfiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteProfilerTest {

    private val trackA1 = Track(id = "a1", title = "Blinding Lights", artist = "The Weeknd", duration = 200, thumbnail = "https://a1")
    private val trackA2 = Track(id = "a2", title = "Starboy", artist = "The Weeknd", duration = 230, thumbnail = "https://a2")
    private val trackB1 = Track(id = "b1", title = "Levitating", artist = "Dua Lipa", duration = 210, thumbnail = "https://b1")

    @Test
    fun `tasteProfiler returns default seeds for empty listening history`() {
        val profile = TasteProfiler.computeTasteProfile(emptyList(), emptyList())

        assertTrue(profile.topArtists.isEmpty())
        assertEquals(0, profile.totalPlaysLogged)
        assertTrue(profile.recommendedSeeds.isNotEmpty())
    }

    @Test
    fun `tasteProfiler ranks artists by play count and recent history weighting`() {
        val now = System.currentTimeMillis()

        val playCounts = listOf(
            PlayCountEntry(trackId = "a1", count = 10, lastPlayed = now, track = trackA1),
            PlayCountEntry(trackId = "b1", count = 3, lastPlayed = now, track = trackB1)
        )

        val history = listOf(
            HistoryEntry(track = trackA1, playedAt = now - 1000L),
            HistoryEntry(track = trackA2, playedAt = now - 2000L),
            HistoryEntry(track = trackB1, playedAt = now - 3000L)
        )

        val profile = TasteProfiler.computeTasteProfile(history, playCounts, now)

        assertEquals(13, profile.totalPlaysLogged)
        assertTrue(profile.topArtists.isNotEmpty())

        val topArtist = profile.topArtists.first()
        assertEquals("The Weeknd", topArtist.artistName)
        assertEquals(10, topArtist.totalPlays)
        assertEquals(2, topArtist.recentPlaysCount)
        assertTrue(topArtist.affinityScore > 0f)

        // Seed recommendation generation
        assertTrue(profile.recommendedSeeds.any { it.contains("The Weeknd") })
        assertTrue(profile.primaryVibe.contains("The Weeknd"))
    }
}
