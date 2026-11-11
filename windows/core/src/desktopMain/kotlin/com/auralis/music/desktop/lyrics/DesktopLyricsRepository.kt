package com.auralis.music.desktop.lyrics

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.data.network.provider.LyricsSearchQuery
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import com.auralis.music.util.Log

/**
 * Lyrics for the desktop app: the phone's provider race (LyricsClient) and its playback
 * alignment, cached in memory and on disk ([LyricsDiskCache]; the phone uses Room). Like the
 * phone's cache it keeps the provider's answer and aligns it to the stream on every read, and a
 * word-synced entry is never replaced by a lower tier.
 */
class DesktopLyricsRepository(
    private val disk: LyricsDiskCache? = null,
    private val client: LyricsClient = LyricsClient(),
    private val spicyEnabled: () -> Boolean = { LyricsClient.isProviderEnabled(LyricsProvider.SPICY_LYRICS) },
    private val spicyLookup: suspend (LyricsSearchQuery) -> LyricsData? = { q ->
        client.searchProvider(LyricsProvider.SPICY_LYRICS, q.title, q.artist, q.durationSec,
            q.videoId, q.album, q.channelTitle, q.durationMs, q.audioLeadingSilenceMs, q.spotifyId)
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val providerLookup: suspend (LyricsProvider, LyricsSearchQuery) -> LyricsData? = { provider, q ->
        client.searchProvider(provider, q.title, q.artist, q.durationSec,
            q.videoId, q.album, q.channelTitle, q.durationMs, q.audioLeadingSilenceMs, q.spotifyId,
            reportFailures = true)
    },
    private val fallbackLookup: suspend (LyricsSearchQuery, Boolean, (LyricsData) -> Unit) -> LyricsData? = { q, skipSpicy, interim ->
        client.getLyrics(q.title, q.artist, q.durationSec, q.videoId, q.album, q.channelTitle,
            durationMs = q.durationMs, audioLeadingSilenceMs = q.audioLeadingSilenceMs,
            skipSpicyProvider = skipSpicy, onInterim = interim)
    },
    private val knownTracks: () -> Sequence<com.auralis.music.domain.model.Track> = { emptySequence() }
) {
    companion object {
        @Volatile var preferredProviders: List<com.auralis.music.domain.model.LyricsProvider> = emptyList()

        /** Timing quality wins first; Spicy style then prefers the matched Spicy response. */
        fun choose(current: LyricsData?, candidate: LyricsData?, preferSpicy: Boolean): LyricsData? {
            if (candidate == null || candidate.lines.isEmpty()) return current
            if (current == null) return candidate
            val oldTier = LyricsClient.tierOf(current)
            val newTier = LyricsClient.tierOf(candidate)
            if (oldTier > newTier) return current
            if (newTier > oldTier) return candidate
            return if (preferSpicy && current.provider == LyricsProvider.SPICY_LYRICS &&
                candidate.provider != LyricsProvider.SPICY_LYRICS) current else candidate
        }
    }
    private val memory = com.auralis.music.data.network.LyricsResponseCache()
    private val spicyAttempts = mutableMapOf<String, Long>()
    private val spicyLocks = Array(32) { kotlinx.coroutines.sync.Mutex() }
    private val storageLock = Any()

    fun selectableProviders(): List<LyricsProvider> = client.selectableProviders()

    /** Reuse imported recording identities after playback has become a YouTube ID. */
    internal fun withKnownRecording(query: LyricsSearchQuery): LyricsSearchQuery {
        if (com.auralis.music.data.network.SpicyRecordingMatcher.spotifyId(query.spotifyId) != null) return query
        val matcher = com.auralis.music.data.network.SpicyRecordingMatcher
        val cached = query.videoId?.lowercase()?.let { key -> synchronized(storageLock) {
            memory[key] ?: disk?.read(key)
        } }
        if (cached != null && cached.spotifyTrackId != null && cached.trackName != null && cached.artistName != null &&
            cached.durationMs != null && matcher.matches(query, com.auralis.music.data.network.SpicyRecording(
                cached.spotifyTrackId, cached.trackName, cached.artistName, cached.durationMs)))
            return query.copy(spotifyId = cached.spotifyTrackId)
        val candidates = knownTracks().mapNotNull { track ->
            matcher.spotifyId(track.id)?.let { id -> com.auralis.music.data.network.SpicyRecording(
                id, track.title, track.artist, track.duration * 1000L, track.album) }
        }.distinctBy { it.id }.filter { matcher.matchesIdentity(query, it) }.toList()
        val verified = candidates.filter { matcher.matches(query, it) }
        val match = verified.firstOrNull { !query.album.isNullOrBlank() && query.album.equals(it.album, true) }
            ?: verified.firstOrNull()
        if (match != null) return query.copy(spotifyId = match.id)
        // Imported Spotify entries often omit duration. They identify a metadata lookup,
        // not a verified recording: SpicyLyricsSource still checks the public metadata's
        // measured duration, title, artist and version before requesting any lyrics.
        val unknownLength = candidates.filter { it.durationMs <= 0 }
        val sameAlbum = unknownLength.filter { !query.album.isNullOrBlank() && query.album.equals(it.album, true) }
        val hint = (sameAlbum.ifEmpty { unknownLength }).singleOrNull() ?: return query
        return query.copy(spotifyId = hint.id)
    }

    /** Explicit user choice may intentionally select plain/line lyrics over word sync. */
    suspend fun fetchProvider(provider: LyricsProvider, query: LyricsSearchQuery): LyricsData? {
        val cacheKey = query.videoId?.lowercase()?.let { "$it-provider-${provider.name}" }
        if (provider == LyricsProvider.SPICY_LYRICS && cacheKey != null) synchronized(storageLock) {
            memory[cacheKey] ?: disk?.read(cacheKey)?.also { memory[cacheKey] = it }
        }?.let { cached ->
            if (cached.trackName != null && cached.artistName != null && cached.spotifyTrackId != null &&
                cached.durationMs != null && com.auralis.music.data.network.SpicyRecordingMatcher.matches(query,
                    com.auralis.music.data.network.SpicyRecording(cached.spotifyTrackId, cached.trackName,
                        cached.artistName, cached.durationMs))) return align(cached, query.durationMs ?: 0L)
        }
        val response = withTimeoutOrNull(if (provider == LyricsProvider.SPICY_LYRICS) 45_000L else 16_000L) {
            Result.success(providerLookup(provider, withKnownRecording(query)))
        } ?: throw java.io.IOException("Lyrics lookup timed out")
        val result = response.getOrNull() ?: return null
        if (provider == LyricsProvider.SPICY_LYRICS && cacheKey != null && result.lines.isNotEmpty())
            synchronized(storageLock) { memory[cacheKey] = result; disk?.write(cacheKey, result) }
        return result.takeIf { it.lines.isNotEmpty() || !it.plainLyrics.isNullOrBlank() }
            ?.let { align(it, query.durationMs ?: 0L) }
    }

    /** Independent of the provider race and of its word-cache early exit. */
    suspend fun upgradeSpicy(query: LyricsSearchQuery): LyricsData? {
        val key = query.videoId ?: return null
        // A foreground request waits for (or takes over from) next-song preparation,
        // rather than mistaking an in-flight request for a recent provider failure.
        return spicyLocks[(key.hashCode() and Int.MAX_VALUE) % spicyLocks.size].withLock { upgradeSpicyOnce(query) }
    }

    private suspend fun upgradeSpicyOnce(query: LyricsSearchQuery): LyricsData? {
        if (!spicyEnabled()) return null
        val key = query.videoId?.lowercase() ?: return null
        val existing = synchronized(storageLock) {
            val providerKey = "$key-provider-${LyricsProvider.SPICY_LYRICS.name}"
            val best = memory[key] ?: disk?.read(key)
            best?.takeIf { it.provider == LyricsProvider.SPICY_LYRICS }
                ?: memory[providerKey] ?: disk?.read(providerKey)
        }
        if (existing != null && !com.auralis.music.data.network.LyricsCachePolicy.isExpired(existing, clock()))
            return align(existing, query.durationMs ?: 0L)
        val attemptKey = listOf(key, query.spotifyId, query.title, query.artist, query.durationMs).joinToString("|")
        synchronized(spicyAttempts) {
            val last = spicyAttempts[attemptKey]
            if (last != null && clock() - last < 15_000L) return null
            // Bound session bookkeeping as well as repeated offline/rate-limit requests.
            if (spicyAttempts.size >= 512) spicyAttempts.clear()
            spicyAttempts[attemptKey] = clock()
        }
        val result = try {
            withTimeoutOrNull(45_000L) { spicyLookup(withKnownRecording(query)) }
        } catch (e: CancellationException) {
            synchronized(spicyAttempts) { spicyAttempts.remove(attemptKey) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Spicy upgrade unavailable (${e.javaClass.simpleName}); keeping existing lyrics")
            null
        } ?: return null
        if (result.provider != LyricsProvider.SPICY_LYRICS || LyricsClient.tierOf(result) < LyricsClient.TIER_LINE) return null
        synchronized(storageLock) {
            val providerKey = "$key-provider-${LyricsProvider.SPICY_LYRICS.name}"
            memory[providerKey] = result; disk?.write(providerKey, result)
        }
        val selected = storeBest(key, result, preferSpicy = true)
        Log.i(TAG, "Spicy upgrade for '${query.title}': selected ${selected.provider} ${selected.syncType}")
        return align(selected, query.durationMs ?: 0L)
    }

    private fun storeBest(key: String, result: LyricsData, preferSpicy: Boolean): LyricsData = synchronized(storageLock) {
        val existing = memory[key] ?: disk?.read(key)
        val selected = choose(existing, result, preferSpicy)!!
        if (selected !== existing) {
            memory[key] = selected
            disk?.write(key, selected)
        }
        selected
    }

    /** Cached lyrics for [videoId], aligned to [durationMs]; null when there are none. */
    fun cached(videoId: String, durationMs: Long): LyricsData? {
        val key = videoId.lowercase()
        val raw = memory[key] ?: disk?.read(key)?.also { memory[key] = it } ?: return null
        return align(raw, durationMs)
    }

    /**
     * Same request the phone makes. [onInterim] gets displayable synced lyrics while the race
     * still waits for a better source; the return value is the final answer (or the cached copy
     * when the network has nothing better).
     */
    suspend fun fetch(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        channelTitle: String?,
        durationSec: Long,
        durationMs: Long,
        onInterim: (LyricsData) -> Unit,
        preferSpicy: Boolean = false
    ): LyricsData? {
        val key = videoId.lowercase()
        val started = System.currentTimeMillis()
        var preferred: LyricsData? = null
        val deadline = System.currentTimeMillis() + 15_000
        for (provider in preferredProviders.filter { LyricsClient.isProviderEnabled(it) &&
            (!preferSpicy || it != LyricsProvider.SPICY_LYRICS) }) {
            if (System.currentTimeMillis() >= deadline) break
            preferred = client.searchProvider(provider,title,artist,durationSec,videoId,album,channelTitle,durationMs)
            if (preferred != null && preferred.lines.isNotEmpty()) break
        }
        val result = preferred ?: fallbackLookup(
            LyricsSearchQuery(title, artist, durationSec = durationSec, videoId = videoId,
                album = album, channelTitle = channelTitle, durationMs = durationMs),
            preferSpicy, { interim -> onInterim(align(interim, durationMs)) })
        val existing = memory[key] ?: disk?.read(key)
        if (result == null || result.lines.isEmpty()) {
            Log.w(TAG, "No lyrics from the network for '$title' by '$artist' (${System.currentTimeMillis() - started}ms)")
            return existing?.let { align(it, durationMs) }
        }
        val aligned = align(storeBest(key, result, preferSpicy), durationMs)
        Log.i(TAG, "Lyrics for '$title': ${aligned.provider} ${aligned.syncType}, ${aligned.lines.size} lines in ${System.currentTimeMillis() - started}ms")
        return aligned
    }

    /** Prepare only the next queued recording; no decoder, UI updates or unbounded queue crawl. */
    suspend fun prefetch(track: com.auralis.music.domain.model.Track, preferSpicy: Boolean) = kotlinx.coroutines.coroutineScope {
        val videoId = com.auralis.music.data.network.RecordingMatches.getMatchedVideoId(track.id) ?: track.id
        val durationSec = com.auralis.music.data.network.RecordingMatches.getEffectiveDurationSec(videoId, track.duration)
        val query = LyricsSearchQuery(track.title, track.artist, durationSec = durationSec, videoId = videoId,
            album = track.album, channelTitle = track.channelTitle, durationMs = durationSec * 1000L,
            spotifyId = com.auralis.music.data.network.SpicyRecordingMatcher.spotifyId(track.id))
        if (preferSpicy) launch { upgradeSpicy(query) }
        val existing = cached(videoId, durationSec * 1000L)
        if (existing == null || LyricsClient.tierOf(existing) < LyricsClient.TIER_WORD) {
            fetch(videoId, track.title, track.artist, track.album, track.channelTitle,
                durationSec, durationSec * 1000L, onInterim = {}, preferSpicy = preferSpicy)
        }
        Unit
    }

    private fun align(lyrics: LyricsData, durationMs: Long): LyricsData =
        if (durationMs > 0L) LyricsAlignmentEngine.alignToPlayback(lyrics, durationMs, null) else lyrics

    private val TAG = "DesktopLyrics"
}
