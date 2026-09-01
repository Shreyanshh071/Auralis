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

        // ── TIER 1: HIGH-PRECISION STUDIO VERIFIED SYNCED RACE (LRCLIB, Musixmatch, BetterLyrics) ──
        val tier1Providers: List<LyricsSource> = listOf(
            lrcLibSource,
            musixmatchSource,
            betterLyricsSource
        )

        val tier2Providers: List<LyricsSource> = listOf(
            jioSaavnSource,
            netEaseSource,
            kuGouSource,
            amllSource
        )

        // Run Tier 1 first (fast sub-400ms studio synced database)
        val tier1Winner: LyricsData? = coroutineScope {
            val resultChannel = Channel<LyricsCandidate>(capacity = tier1Providers.size * 2)
            val providerJobs = tier1Providers.map { source ->
                launch {
                    try {
                        val cand = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            source.search(query)
                        }
                        if (cand != null) {
                            Log.d(TAG, "[Tier 1 Provider: ${source.provider}] found: ${cand.syncType}, confidence=${cand.confidence}%, lines=${cand.lyricsData.lines.size}")
                            resultChannel.send(cand)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[Tier 1 Provider: ${source.provider}] exception: ${e.message}")
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
            var completedCount = 0

            while (completedCount < tier1Providers.size) {
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

                    if (correctedCand.confidence > (bestCandidate?.confidence ?: 0)) {
                        bestCandidate = correctedCand
                    }

                    // Instant win for authoritative studio source
                    if (correctedCand.confidence >= 65) {
                        Log.d(TAG, "[TIER 1 INSTANT WINNER] ${correctedCand.provider} in ${System.currentTimeMillis() - t0}ms (Confidence: ${correctedCand.confidence}%)")
                        providerJobs.forEach { it.cancel() }
                        return@coroutineScope correctedCand.lyricsData
                    }
                }
            }
            bestCandidate?.lyricsData
        }

        if (tier1Winner != null && tier1Winner.lines.isNotEmpty()) {
            return@withContext tier1Winner
        }

        // ── TIER 2: SECONDARY / REGIONAL / CROWDSOURCED CASCADE ──
        val tier2Winner: LyricsData? = coroutineScope {
            val resultChannel = Channel<LyricsCandidate>(capacity = tier2Providers.size * 2)
            val providerJobs = tier2Providers.map { source ->
                launch {
                    try {
                        val cand = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            source.search(query)
                        }
                        if (cand != null) {
                            resultChannel.send(cand)
                        }
                    } catch (_: Exception) {
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

            var bestSyncedCandidate: LyricsCandidate? = null
            var bestPlainCandidate: LyricsCandidate? = null
            var completedCount = 0

            while (completedCount < tier2Providers.size) {
                val candidate = resultChannel.receive()
                if (candidate.confidence == -1) {
                    completedCount++
                    continue
                }

                val isCandSynced = (candidate.syncType != SyncType.PLAIN || candidate.lyricsData.syncType != SyncType.PLAIN || candidate.lyricsData.lines.any { it.time > 0L })
                if (isCandSynced && candidate.confidence >= 55 && candidate.lyricsData.lines.isNotEmpty()) {
                    if (candidate.confidence > (bestSyncedCandidate?.confidence ?: 0)) {
                        bestSyncedCandidate = candidate
                    }
                    if (candidate.confidence >= 75) {
                        providerJobs.forEach { it.cancel() }
                        return@coroutineScope candidate.lyricsData
                    }
                } else if (candidate.syncType == SyncType.PLAIN && candidate.confidence >= 50 && candidate.lyricsData.lines.isNotEmpty()) {
                    if (candidate.confidence > (bestPlainCandidate?.confidence ?: 0)) {
                        bestPlainCandidate = candidate
                    }
                }
            }
            bestSyncedCandidate?.lyricsData ?: bestPlainCandidate?.lyricsData
        }

        if (tier2Winner != null && tier2Winner.lines.isNotEmpty()) {
            Log.d(TAG, "Lyrics resolved via Tier 2 in ${System.currentTimeMillis() - t0}ms (Provider: ${tier2Winner.provider})")
            return@withContext tier2Winner
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
                    return@withContext lrcFallback.lyricsData
                }
            } catch (_: Exception) {}
        }

        // ── TIER 3: PLAIN LYRICS FALLBACK (Genius & YouTube Music concurrent) ──
        val plainWinner = coroutineScope {
            val geniusDeferred = async {
                try { withTimeoutOrNull(1200L) { geniusSource.search(query) } } catch (_: Exception) { null }
            }
            val ytDeferred = async {
                try { withTimeoutOrNull(1200L) { ytMusicSource.search(query) } } catch (_: Exception) { null }
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
}
