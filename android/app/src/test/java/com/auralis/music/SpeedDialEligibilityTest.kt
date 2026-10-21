package com.auralis.music

import com.auralis.music.domain.recommendations.SpeedDialEligibility
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.SpeedDialItem
import com.auralis.music.domain.model.SpeedDialType
import com.auralis.music.ui.viewmodel.HomeUiState
import com.auralis.music.ui.viewmodel.forDisplay
import org.junit.Assert.*
import org.junit.Test

class SpeedDialEligibilityTest {
    @Test fun sixDistinctThirtySecondListensUnlockTheRow() {
        val times = (1..6).associate { "song$it" to 30_000L }
        assertTrue(SpeedDialEligibility.ready(times))
        assertFalse(SpeedDialEligibility.ready(times - "song6"))
        assertFalse(SpeedDialEligibility.ready(times + ("song6" to 29_999L)))
    }

    @Test fun repeatPlaysOfOneSongDoNotCountAsSixSongs() {
        assertFalse(SpeedDialEligibility.ready(mapOf("one" to 1_000_000L)))
        assertFalse(SpeedDialEligibility.ready(emptyMap()))
    }

    @Test fun canonicalAliasesCombineListeningButDoNotInflateDistinctCount() {
        val times = mapOf("one" to 15_000L, "track-one-0" to 15_000L,
            "playlist-x" to 999_999L, "surprise-0" to 999_999L, "negative" to -1L)
        assertEquals(setOf("one"), SpeedDialEligibility.qualifiedTracks(times))
        assertFalse(SpeedDialEligibility.ready(times))
    }

    @Test fun cachedAndPinnedUnplayedSongsCannotBypassTheVisibilityPolicy() {
        val item = SpeedDialItem("track-cached", "Cached song", SpeedDialType.TRACK,
            track = Track("cached", "Cached song", "Someone"), isPinned = true)
        val state = HomeUiState(speedDialPages = listOf(listOf(item)))
        assertTrue(state.forDisplay().speedDialPages.isEmpty())
        assertTrue(state.copy(speedDialReady = true,
            qualifiedSpeedDialTrackIds = (1..6).map { "actual$it" }.toSet())
            .forDisplay().speedDialPages.isEmpty())
    }

    @Test fun filteringAnOldCacheRepacksAllEligibleSongsWithoutGridHoles() {
        val stale = (1..10).map { SpeedDialItem("track-stale$it", "Stale $it", SpeedDialType.TRACK,
            track = Track("stale$it", "Stale $it", "Old artist")) }
        val songs = (1..26).map { SpeedDialItem("track-song$it", "Song $it", SpeedDialType.TRACK,
            track = Track("song$it", "Song $it", "Artist $it")) }
        val pages = (stale + songs).chunked(9)
        val state = HomeUiState(speedDialPages = pages, speedDialReady = true,
            qualifiedSpeedDialTrackIds = songs.map { it.track!!.id }.toSet())
        val result = state.forDisplay().speedDialPages
        assertEquals(3, result.size)
        assertTrue(result.all { it.size == 9 })
        assertEquals(songs.map { it.track!!.id }, result.flatten().mapNotNull { it.track?.id })
        assertTrue(result.flatten().none { it.type == SpeedDialType.PLACEHOLDER })
    }
}
