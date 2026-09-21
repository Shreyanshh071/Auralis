package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.NewUserSeedProvider
import com.auralis.music.domain.recommendations.TrackDeduplicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPicksSlideCountTest {

    @Test
    fun `quick picks chunked by 4 yields at least 4 slides when 16 tracks present`() {
        val tracks = (1..16).map { i ->
            Track(id = "trk_$i", title = "Title $i", artist = "Artist $i")
        }

        val pages = tracks.chunked(4)
        assertEquals(4, pages.size)
        pages.forEach { page ->
            assertEquals(4, page.size)
        }
    }

    @Test
    fun `sparse user history with 4 artists and 1 song each guarantees at least 16 tracks for 4 slides`() {
        // Simulates the exact user scenario from the report:
        // 4 user-listened artists (Billy Idol, Tame Impala, Radiohead, The Smiths) with 1 song each
        val userTracks = listOf(
            Track(id = "u1", title = "Eyes Without A Face", artist = "Billy Idol"),
            Track(id = "u2", title = "New Person, Same Old Mistakes", artist = "Tame Impala"),
            Track(id = "u3", title = "Creep", artist = "Radiohead"),
            Track(id = "u4", title = "Heaven Knows I'm Miserable Now", artist = "The Smiths")
        )

        val artistPools = userTracks.map { mutableListOf(it) }.toMutableList()

        // Fallback tracks (from seed tracks / curated list)
        val fallbackList = NewUserSeedProvider.getInitialSeedTracks().toMutableList()

        // Simulate fetchQuickPicks round-robin logic
        val finalQuickPicks = mutableListOf<Track>()
        val seenTrackIds = mutableSetOf<String>()
        val artistAppearanceCount = mutableMapOf<String, Int>()

        var round = 0
        val maxRounds = 6
        while (finalQuickPicks.size < 28 && round < maxRounds && artistPools.any { it.isNotEmpty() }) {
            var anyAdded = false
            for (pool in artistPools) {
                if (pool.isNotEmpty()) {
                    val track = pool.removeAt(0)
                    val count = artistAppearanceCount.getOrDefault(track.artist, 0)
                    if (count < 4 && seenTrackIds.add(track.id) && finalQuickPicks.none { TrackDeduplicator.isDuplicateTrack(it, track) }) {
                        finalQuickPicks.add(track)
                        artistAppearanceCount[track.artist] = count + 1
                        anyAdded = true
                    }
                }
            }
            if (!anyAdded) break
            round++
        }

        // Fill from fallback
        if (finalQuickPicks.size < 24 && fallbackList.isNotEmpty()) {
            for (t in fallbackList) {
                val count = artistAppearanceCount.getOrDefault(t.artist, 0)
                if (count < 3 && seenTrackIds.add(t.id) && finalQuickPicks.none { TrackDeduplicator.isDuplicateTrack(it, t) }) {
                    finalQuickPicks.add(t)
                    artistAppearanceCount[t.artist] = count + 1
                    if (finalQuickPicks.size >= 24) break
                }
            }
        }

        // Absolute floor guarantee
        if (finalQuickPicks.size < 16) {
            for (seed in NewUserSeedProvider.getInitialSeedTracks()) {
                if (seenTrackIds.add(seed.id) && finalQuickPicks.none { TrackDeduplicator.isDuplicateTrack(it, seed) }) {
                    finalQuickPicks.add(seed)
                    if (finalQuickPicks.size >= 16) break
                }
            }
        }

        // Verify that we have at least 16 tracks (which creates at least 4 slides)
        assertTrue("Quick picks must have >= 16 tracks, was ${finalQuickPicks.size}", finalQuickPicks.size >= 16)

        val pages = finalQuickPicks.chunked(4)
        assertTrue("Quick picks must have >= 4 slides, was ${pages.size}", pages.size >= 4)

        // Verify all 4 user tracks are present on Slide 0
        val page0Ids = pages[0].map { it.id }
        assertTrue(page0Ids.contains("u1"))
        assertTrue(page0Ids.contains("u2"))
        assertTrue(page0Ids.contains("u3"))
        assertTrue(page0Ids.contains("u4"))
    }

    @Test
    fun `quick picks deduplication prevents duplicates in candidate pools`() {
        val dupTrack1 = Track(id = "trk1", title = "Creep", artist = "Radiohead")
        val dupTrack2 = Track(id = "trk1_alt", title = "Creep", artist = "Radiohead")
        val diffTrack = Track(id = "trk2", title = "Karma Police", artist = "Radiohead")

        val list = listOf(dupTrack1, dupTrack2, diffTrack)
        val deduplicated = TrackDeduplicator.deduplicateTracks(list)

        assertEquals(2, deduplicated.size)
    }
}
