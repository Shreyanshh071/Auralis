package com.auralis.music

import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.data.network.LyricsCachePolicy
import com.auralis.music.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SpicyAndroidCacheTest {
    @Test fun sourceSyllablesAttributionAndOriginalAgeSurviveRoomRoundTrip() {
        val fetched = System.currentTimeMillis() - 60000
        val source = LyricsData(SyncType.RICHSYNC, listOf(LyricLine(1000, "Hello world", words = listOf(
            LyricWord("Hel", 1000, 200), LyricWord("lo ", 1200, 400), LyricWord("world", 2000, 800)),
            endTime = 2800, agent = "v2")), provider = LyricsProvider.SPICY_LYRICS,
            trackName = "Test Song", artistName = "Artist", durationMs = 100000,
            credits = listOf(LyricsCredit("Spicy Lyrics", "https://spicylyrics.org"),
                LyricsCredit("Made by Maker", "https://spicylyrics.org/uid/123"), LyricsCredit("Written by Writer", "")),
            fetchedAtMs = fetched, spotifyTrackId = "1QV6tiMFM6fSOKOGLMHYYg")
        val entity = LyricsRepositoryImpl.domainToEntity("video", source, "Test Song", "Artist")
        val restored = LyricsRepositoryImpl.entityToDomain(entity, "Test Song", "Artist")!!
        assertEquals(source.lines, restored.lines)
        assertEquals(source.credits, restored.credits)
        assertEquals(source.spotifyTrackId, restored.spotifyTrackId)
        assertEquals(fetched, restored.fetchedAtMs)
        assertNull(LyricsRepositoryImpl.entityToDomain(entity.copy(fetchedAtMs = fetched - LyricsCachePolicy.SPICY_MAX_AGE_MS), "Test Song", "Artist"))
        assertNull(LyricsRepositoryImpl.entityToDomain(entity.copy(fetchedAtMs = null), "Test Song", "Artist"))
    }
}
