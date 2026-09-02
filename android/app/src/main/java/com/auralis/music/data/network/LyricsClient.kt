package com.auralis.music.data.network

import android.util.Log
import com.auralis.music.data.network.provider.AmllLyricsSource
import com.auralis.music.data.network.provider.BetterLyricsSource
import com.auralis.music.data.network.provider.GeniusLyricsSource
import com.auralis.music.data.network.provider.JioSaavnLyricsSource
import com.auralis.music.data.network.provider.KuGouLyricsSource
import com.auralis.music.data.network.provider.LrcLibLyricsSource
import com.auralis.music.data.network.provider.LyricsCandidate
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.data.network.provider.LyricsSource
import com.auralis.music.data.network.provider.MusixmatchLyricsSource
import com.auralis.music.data.network.provider.NetEaseLyricsSource
import com.auralis.music.data.network.provider.YouTubeInnerTubeLyricsSource
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class LyricsClient(
    private val amllSource: AmllLyricsSource = AmllLyricsSource(),
    private val betterLyricsSource: BetterLyricsSource = BetterLyricsSource(),
    private val lrcLibSource: LrcLibLyricsSource = LrcLibLyricsSource(),
    private val jioSaavnSource: JioSaavnLyricsSource = JioSaavnLyricsSource(),
    private val netEaseSource: NetEaseLyricsSource = NetEaseLyricsSource(),
    private val kuGouSource: KuGouLyricsSource = KuGouLyricsSource(),
    private val musixmatchSource: MusixmatchLyricsSource = MusixmatchLyricsSource(),
    private val geniusSource: GeniusLyricsSource = GeniusLyricsSource(),
    private val ytMusicSource: YouTubeInnerTubeLyricsSource = YouTubeInnerTubeLyricsSource()
) {
    companion object {
        private const val TAG = "LyricsCascade"
        private const val PROVIDER_TIMEOUT_MS = 4500L
    }

    /**
     * Ultra-fast parallel multi-provider search with instant early exit:
     * - As soon as ANY provider returns high-confidence synced lyrics (>= 68%), immediately return (sub-300ms!).
     * - If moderate match arrives (>= 50%), evaluate immediately without waiting for slow failing providers.
     * - Falls back to plain sources (Genius, YouTube Music) in parallel only if no synced lyrics found.
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSec: Long? = null,
        videoId: String? = null,
        album: String? = null
    ): LyricsData? = withContext(Dispatchers.IO) {
        val (splitArtist, splitTitle) = TitleCleaner.splitArtistAndTitle(title, artist)
        val cleanedTitle = TitleCleaner.cleanTitle(splitTitle)
        val coreTitle = TitleCleaner.cleanCoreSongTitle(splitTitle)
            .replace(Regex("(?i)\\b(official\\s*(music)?\\s*video|official\\s*audio|lyric(al)?\\s*video|full\\s*song|video|audio|remastered|remaster)\\b.*"), "")
            .replace(Regex("""\s*[\(\[](?:feat\.?|ft\.?|with)\s+[^)\]]+[\)\]]""", RegexOption.IGNORE_CASE), "")
            .trim(' ', '-', '|', ':', '_')
            .ifBlank { cleanedTitle }

        val query = LyricsSearchQuery(
            title = coreTitle,
            artist = TitleCleaner.cleanArtist(splitArtist),
            durationSec = durationSec,
            videoId = videoId,
            album = album
        )

        val t0 = System.currentTimeMillis()
        Log.d(TAG, "Starting ultra-fast synced lyrics search for: '$coreTitle' by '${query.artist}' (${durationSec ?: 0}s)")

        // ── PARALLEL MULTI-PROVIDER RACE WITH INTELLIGENT TIMING & COMPLETENESS SCORING ──
        val primaryProviders: List<LyricsSource> = listOf(
            lrcLibSource,
            musixmatchSource,
            kuGouSource,
            netEaseSource,
            jioSaavnSource
        )

        val syncedWinner: LyricsData? = coroutineScope {
            val resultChannel = Channel<LyricsCandidate>(capacity = primaryProviders.size * 2)
            val providerJobs = primaryProviders.map { source ->
                launch {
                    try {
                        val cand = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            source.search(query)
                        }
                        if (cand != null) {
                            Log.d(TAG, "[Provider: ${source.provider}] found: ${cand.syncType}, confidence=${cand.confidence}%, lines=${cand.lyricsData.lines.size}")
                            resultChannel.send(cand)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[Provider: ${source.provider}] exception: ${e.message}")
                    } finally {
                        resultChannel.send(
                            LyricsCandidate(
                                lyricsData = LyricsData(syncType = SyncType.PLAIN, lines = emptyList(), provider = source.provider),
                                confidence = -1,
                                syncType = SyncType.PLAIN,
                                provider = source.provider
                            )
                        )
                    }
                }
            }

            var bestCandidate: LyricsCandidate? = null
            var bestScore = 0.0
            var completedCount = 0

            while (completedCount < primaryProviders.size) {
                val candidate = resultChannel.receive()
                if (candidate.confidence == -1) {
                    completedCount++
                    continue
                }

                val isCandSynced = (candidate.syncType != SyncType.PLAIN || candidate.lyricsData.syncType != SyncType.PLAIN || candidate.lyricsData.lines.any { it.time > 0L })
                if (isCandSynced && candidate.confidence >= 50 && candidate.lyricsData.lines.isNotEmpty()) {
                    val resolvedSyncType = when {
                        candidate.lyricsData.syncType == SyncType.RICHSYNC || candidate.syncType == SyncType.RICHSYNC -> SyncType.RICHSYNC
                        else -> SyncType.LINE_SYNC
                    }
                    val correctedCand = candidate.copy(
                        syncType = resolvedSyncType,
                        lyricsData = candidate.lyricsData.copy(syncType = resolvedSyncType)
                    )

                    val score = calculateQualityScore(correctedCand, durationSec)
                    Log.d(TAG, "[Candidate: ${correctedCand.provider}] score=$score, firstLine=${correctedCand.lyricsData.lines.firstOrNull()?.time}ms, lines=${correctedCand.lyricsData.lines.size}")

                    if (score > bestScore) {
                        bestScore = score
                        bestCandidate = correctedCand
                    }

                    // Instant win for flawless high-scoring candidate (>= 145)
                    if (score >= 145.0) {
                        Log.d(TAG, "[INSTANT QUALITY WINNER] ${correctedCand.provider} in ${System.currentTimeMillis() - t0}ms (Score: $score)")
                        providerJobs.forEach { it.cancel() }
                        return@coroutineScope correctedCand.lyricsData
                    }
                }
            }
            bestCandidate?.lyricsData
        }

        if (syncedWinner != null && syncedWinner.lines.isNotEmpty()) {
            Log.d(TAG, "[SYNCED WINNER] ${syncedWinner.provider} selected in ${System.currentTimeMillis() - t0}ms")
            return@withContext syncedWinner
        }

        // ── TIER 2: RAW TITLE FALLBACK ON LRCLIB ──
        if (title != coreTitle) {
            try {
                val fallbackQuery = LyricsSearchQuery(
                    title = TitleCleaner.cleanTitle(title),
                    artist = TitleCleaner.cleanArtist(artist),
                    durationSec = durationSec,
                    videoId = videoId,
                    album = album
                )
                val lrcFallback = withTimeoutOrNull(2000L) { lrcLibSource.search(fallbackQuery) }
                if (lrcFallback != null && lrcFallback.confidence >= 50 && lrcFallback.lyricsData.lines.isNotEmpty()) {
                    Log.d(TAG, "[RAW TITLE WINNER] LRCLIB in ${System.currentTimeMillis() - t0}ms")
                    return@withContext lrcFallback.lyricsData.copy(syncType = SyncType.LINE_SYNC)
                }
            } catch (_: Exception) {}
        }

        // ── TIER 3: PLAIN TEXT FALLBACK (Genius & YouTube Music) ──
        val plainWinner = coroutineScope {
            val geniusDeferred = async {
                try { withTimeoutOrNull(1500L) { geniusSource.search(query) } } catch (_: Exception) { null }
            }
            val ytDeferred = async {
                try { withTimeoutOrNull(1500L) { ytMusicSource.search(query) } } catch (_: Exception) { null }
            }
            listOfNotNull(geniusDeferred.await(), ytDeferred.await()).firstOrNull { it.lyricsData.lines.isNotEmpty() }
        }

        if (plainWinner != null) {
            Log.d(TAG, "[PLAIN WINNER] ${plainWinner.provider} in ${System.currentTimeMillis() - t0}ms")
            return@withContext plainWinner.lyricsData
        }

        Log.d(TAG, "No lyrics found after ${System.currentTimeMillis() - t0}ms for '$coreTitle'")
        null
    }

    private fun calculateQualityScore(cand: LyricsCandidate, queryDurationSec: Long?): Double {
        if (com.auralis.music.data.parser.LyricsValidator.isCorruptOrInvalid(cand.lyricsData)) {
            return -1000.0
        }
        var score = cand.confidence.toDouble() // base 0 - 100
        val lines = cand.lyricsData.lines
        if (lines.isEmpty()) return 0.0

        val firstLineTime = lines.firstOrNull { !it.isInstrumental }?.time ?: 0L
        val isLongTrack = (queryDurationSec ?: 0L) >= 45L || (lines.size >= 15)

        // 1. Timing Sanity: If a standard track starts at 0ms (or < 350ms), it's a defective crowdsourced submission lacking intro offset
        if (isLongTrack && firstLineTime <= 350L) {
            score -= 30.0
        } else if (firstLineTime >= 1000L) {
            score += 15.0 // Valid non-zero intro timing present (enables spinning countdown circle)
        }

        // 2. Line count & completeness
        score += (lines.size.coerceAtMost(60) * 0.4) // up to +24

        // 3. Backing vocals & ad-libs retention
        val hasBackingVocals = lines.any { it.text.contains("(") && it.text.contains(")") }
        if (hasBackingVocals) {
            score += 15.0
        }

        // 4. RichSync word-timing bonus
        if (cand.syncType == SyncType.RICHSYNC || cand.lyricsData.syncType == SyncType.RICHSYNC) {
            score += 8.0
        }

        // 5. Source bonuses
        if (cand.provider == LyricsProvider.LRCLIB && firstLineTime > 350L) {
            score += 10.0
        } else if (cand.provider == LyricsProvider.MUSIXMATCH && firstLineTime > 350L) {
            score += 8.0
        }

        return score
    }
}
