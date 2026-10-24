package com.auralis.music.data.network

import com.auralis.music.data.parser.SpicyLyricsParser
import com.auralis.music.desktop.lyrics.LyricsDiskCache
import com.auralis.music.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SpicyLyricsCacheTest {
    @Test fun diskEntriesExpireWithoutRenewalWhenRewritten() {
        val dir = java.nio.file.Files.createTempDirectory("spicy-cache-test").toFile()
        var now = 1_000_000L
        try {
            val cache = LyricsDiskCache(dir) { now }
            val lyrics = LyricsData(SyncType.LINE_SYNC, listOf(LyricLine(10000, "A line")),
                provider = LyricsProvider.SPICY_LYRICS, fetchedAtMs = now)
            cache.write("video", lyrics)
            assertNotNull(cache.read("video"))
            now += 86_400_000L
            cache.write("video", cache.read("video")!!.copy(appliedOffsetMs = 10))
            now = lyrics.fetchedAtMs!! + LyricsCachePolicy.SPICY_MAX_AGE_MS
            assertNull(cache.read("video"))
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    @Test fun startupDiscardsExpiredAndLegacySpicyEntries() {
        val dir = java.nio.file.Files.createTempDirectory("spicy-cache-startup-test").toFile()
        var now = 1_000_000L
        try {
            val cache = LyricsDiskCache(dir) { now }
            val lyrics = LyricsData(SyncType.PLAIN, listOf(LyricLine(0, "A line")),
                provider = LyricsProvider.SPICY_LYRICS, fetchedAtMs = now)
            cache.write("expired", lyrics)
            java.io.File(dir, "legacy.json").writeText(LyricsDiskCache.encode(lyrics).apply { remove("fetchedAtMs") }.toString())
            now += LyricsCachePolicy.SPICY_MAX_AGE_MS
            LyricsDiskCache(dir) { now }
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    @Test fun diskCacheRetainsAttributionAndSpotifyIdentity() {
        val lyrics = LyricsData(SyncType.LINE_SYNC,
            listOf(LyricLine(10000, "A line", endTime = 12000)), provider = LyricsProvider.SPICY_LYRICS,
            credits = listOf(LyricsCredit("Uploaded by author", "https://spicylyrics.org/uid/123")),
            spotifyTrackId = "1QV6tiMFM6fSOKOGLMHYYg")
        val decoded = LyricsDiskCache.decode(LyricsDiskCache.encode(lyrics))
        assertEquals(lyrics, decoded)
        assertEquals(lyrics.credits, SpicyLyricsParser.decodeCredits(SpicyLyricsParser.encodeCredits(lyrics.credits)))
    }

    @Test fun olderProvidersCanStillLoadWithoutAttributionFields() {
        val lyrics = LyricsData(SyncType.PLAIN, listOf(LyricLine(0, "Plain line")), provider = LyricsProvider.LRCLIB)
        val json = LyricsDiskCache.encode(lyrics).apply { remove("credits"); remove("spotifyTrackId") }
        assertEquals(lyrics, LyricsDiskCache.decode(json))
    }
}
