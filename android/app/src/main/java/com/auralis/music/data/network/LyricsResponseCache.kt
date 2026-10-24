package com.auralis.music.data.network

import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import java.util.concurrent.ConcurrentHashMap

/** Spicy Lyrics permits storing a response for at most 30 days, measured from retrieval.
 * Playback alignment, translation and copying a cache entry must not renew this timestamp. */
object LyricsCachePolicy {
    const val SPICY_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    fun isExpired(data: LyricsData, now: Long = System.currentTimeMillis()): Boolean =
        data.provider == LyricsProvider.SPICY_LYRICS && isSpicyTimestampExpired(data.fetchedAtMs, now)

    fun isSpicyTimestampExpired(fetchedAt: Long?, now: Long): Boolean =
        fetchedAt == null || fetchedAt <= 0L || fetchedAt > now || now - fetchedAt >= SPICY_MAX_AGE_MS
}

/** Sweep on cache access so an expired word-sync result cannot beat a fresh fallback. */
class LyricsResponseCache(private val clock: () -> Long = System::currentTimeMillis) : ConcurrentHashMap<String, LyricsData>() {
    private fun purgeExpired() {
        val now = clock()
        entries.forEach { (key, value) -> if (LyricsCachePolicy.isExpired(value, now)) remove(key, value) }
    }

    override fun get(key: String): LyricsData? {
        purgeExpired()
        return super.get(key)
    }

    override fun put(key: String, value: LyricsData): LyricsData? {
        purgeExpired()
        return if (LyricsCachePolicy.isExpired(value, clock())) remove(key) else super.put(key, value)
    }
}
