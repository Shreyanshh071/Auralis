package com.auralis.music.data.network

import com.auralis.music.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LyricsResponseCacheTest {
    private val fetched = 1_000_000L
    private fun lyrics(provider: LyricsProvider = LyricsProvider.SPICY_LYRICS) = LyricsData(
        SyncType.LINE_SYNC, listOf(LyricLine(10000, "A sung line")), provider = provider, fetchedAtMs = fetched)

    @Test fun expiresAtThirtyDaysIncludingInMemory() {
        var now = fetched
        val cache = LyricsResponseCache { now }
        cache["video"] = lyrics()
        now = fetched + LyricsCachePolicy.SPICY_MAX_AGE_MS - 1
        assertNotNull(cache["video"])
        now++
        assertNull(cache["video"])
        assertTrue(cache.isEmpty())
    }

    @Test fun copyingAndReinsertingDoesNotExtendResponseLifetime() {
        var now = fetched
        val cache = LyricsResponseCache { now }
        cache["video"] = lyrics()
        now += 86_400_000L
        cache["copy"] = cache["video"]!!.copy(appliedOffsetMs = 150)
        now = fetched + LyricsCachePolicy.SPICY_MAX_AGE_MS
        assertNull(cache["copy"])
        assertNull(cache["video"])
        cache["late"] = lyrics()
        assertNull(cache["late"])
    }

    @Test fun missingAndFutureRetrievalTimesAreNotTrusted() {
        assertTrue(LyricsCachePolicy.isExpired(lyrics().copy(fetchedAtMs = null), fetched))
        assertTrue(LyricsCachePolicy.isExpired(lyrics().copy(fetchedAtMs = fetched + 1), fetched))
        assertTrue(LyricsCachePolicy.isExpired(lyrics().copy(fetchedAtMs = 0), fetched))
    }

    @Test fun otherProvidersKeepTheirExistingCacheBehavior() {
        val cache = LyricsResponseCache { fetched + LyricsCachePolicy.SPICY_MAX_AGE_MS * 2 }
        cache["lrclib"] = lyrics(LyricsProvider.LRCLIB).copy(fetchedAtMs = null)
        assertNotNull(cache["lrclib"])
    }
}
