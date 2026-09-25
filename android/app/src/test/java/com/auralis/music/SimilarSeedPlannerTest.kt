package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.SimilarSeedPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home "Similar to [Artist]" shelves are seeded by individual artists, not credit lines. */
class SimilarSeedPlannerTest {

    private fun track(id: String, artist: String) = Track(id = id, title = id, artist = artist, duration = 200)

    // Credit lines exactly as YouTube Music returned them for the user's recent plays.
    private val history = listOf(
        track("agar", "Arijit Singh & Alka Yagnik"),
        track("gerua", "Pritam, Arijit Singh & Antara Mitra"),
        track("samjhawan", "Jawad Ahmed, Sharib Toshi, Arijit Singh & Shreya Ghoshal"),
        track("tere_liye_prince", "Atif Aslam, Shreya Ghoshal, Sachin Gupta, Sameer Anjaan"),
        track("tere_liye", "Lata Mangeshkar & Roop Kumar Rathod")
    )

    @Test
    fun `credit lines split into individual artists`() {
        assertEquals(listOf("Pritam", "Arijit Singh", "Antara Mitra"), SimilarSeedPlanner.splitArtistCredit("Pritam, Arijit Singh & Antara Mitra"))
        assertEquals(listOf("Gorillaz", "De La Soul"), SimilarSeedPlanner.splitArtistCredit("Gorillaz feat. De La Soul"))
        assertEquals(listOf("Billy Idol"), SimilarSeedPlanner.splitArtistCredit("Billy Idol"))
        assertEquals(emptyList<String>(), SimilarSeedPlanner.splitArtistCredit("  "))
    }

    @Test
    fun `the artist the user plays most becomes the first artist shelf`() {
        val seeds = SimilarSeedPlanner.rankArtistSeeds(history, limit = 4)
        assertEquals("Arijit Singh", seeds.first())       // credited on 3 of 5 plays
        assertEquals("Shreya Ghoshal", seeds[1])          // credited on 2
        assertEquals(4, seeds.size)
        assertTrue(seeds.none { it.contains(",") || it.contains("&") })
    }

    @Test
    fun `top played tracks add weight`() {
        val seeds = SimilarSeedPlanner.rankArtistSeeds(
            history = listOf(track("a", "Billy Idol"), track("b", "Tame Impala")),
            topPlayed = listOf(track("c", "Tame Impala")),
            limit = 2
        )
        assertEquals(listOf("Tame Impala", "Billy Idol"), seeds)
    }

    @Test
    fun `artist names match regardless of punctuation and case`() {
        assertTrue(SimilarSeedPlanner.isSameArtist("Shankar-Ehsaan-Loy", "Shankar Ehsaan Loy"))
        assertTrue(SimilarSeedPlanner.isSameArtist("ARIJIT SINGH", "Arijit Singh"))
        assertFalse(SimilarSeedPlanner.isSameArtist("Arijit Singh", "Atif Aslam"))
        assertFalse(SimilarSeedPlanner.isSameArtist("", ""))
    }

    @Test
    fun `artist and song shelves alternate so artist shelves are not buried`() {
        assertEquals(
            listOf("artist:Arijit", "song:Gerua", "artist:Shreya", "song:Samjhawan", "song:Agar"),
            SimilarSeedPlanner.interleave(listOf("artist:Arijit", "artist:Shreya"), listOf("song:Gerua", "song:Samjhawan", "song:Agar"))
        )
    }
}
