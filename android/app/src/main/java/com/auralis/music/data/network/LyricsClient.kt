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
import com.auralis.music.data.network.provider.PaxsenixLyricsSource
import com.auralis.music.data.network.provider.UnisonLyricsSource
import com.auralis.music.data.network.provider.YouTubeInnerTubeLyricsSource
import com.auralis.music.domain.model.LyricLine
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
    private val unisonSource: UnisonLyricsSource = UnisonLyricsSource(),
    private val paxsenixSource: PaxsenixLyricsSource = PaxsenixLyricsSource(),
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
        private const val PROVIDER_TIMEOUT_MS = 6500L

        /**
         * How long a usable line-synced candidate is held back to give a
         * word-synced one a chance to answer.
         *
         * Better Lyrics returns syllable-level TTML in roughly 0.9-1.4 s, while
         * LRCLIB often answers line-sync in under 300 ms. Settling on the first
         * usable result therefore guaranteed the *worse* timing format won every
         * race. This window bounds the wait: once anything usable has landed we
         * wait at most this long for a better tier, then commit.
         */
        private const val WORD_SYNC_GRACE_MS = 2200L

        /**
         * Upper limit for waiting on BetterLyrics when it is actively processing
         * in parallel with an early line-synced result (e.g. from LRCLIB).
         */
        private const val ACTIVE_WORD_PROVIDER_TIMEOUT_MS = 5500L

        /** Genuine word timing outranks line timing outranks nothing. */
        internal const val TIER_NONE = 0
        internal const val TIER_LINE = 1
        internal const val TIER_WORD = 2

        /** Score at which a word-synced candidate is good enough to end the race. */
        internal const val INSTANT_WIN_SCORE = 145.0

        /**
         * Preference hierarchy among word-synced providers:
         * BetterLyrics (Apple Music studio TTML) > NetEase (AMLL TTML / YRC) > Musixmatch (RichSync).
         */
        internal fun providerWordPriority(provider: LyricsProvider): Int = when (provider) {
            LyricsProvider.BETTER_LYRICS -> 5
            LyricsProvider.PAXSENIX -> 4
            LyricsProvider.UNISON -> 3
            LyricsProvider.NETEASE -> 2
            LyricsProvider.MUSIXMATCH -> 1
            else -> 0
        }

        /**
         * Which timing format a candidate actually carries — judged from the
         * data, never from the label the provider attached to it.
         *
         * Mirrors braccato's `ProviderChain` priority ordering
         * (`{syllable, word, line, unsynced}`): sync quality dominates every
         * other quality signal, because no amount of line-count or confidence
         * bonus makes a line-synced lyric able to highlight a syllable.
         *
         * A word tier requires at least two words on some line to carry a real
         * duration. One timed word is a parser artefact, not word sync.
         */
        internal fun tierOf(data: LyricsData): Int = when {
            data.lines.any { line ->
                !line.isInstrumental && (line.words?.count { it.duration != null } ?: 0) >= 2
            } -> TIER_WORD
            data.lines.any { it.time > 0L } -> TIER_LINE
            else -> TIER_NONE
        }

        /**
         * Alignment-aware lexicographic `(tier, score)` comparison with word provider priority.
         *
         * Requirements:
         * - A candidate with [com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH]
         *   must NOT beat an aligned candidate (even if the candidate has word-level sync and the
         *   aligned one has line-level sync).
         * - Between two aligned candidates (or between two candidates with identical alignment status),
         *   genuine word timing outranks line timing outranks nothing.
         * - Between two aligned word-synced candidates, provider hierarchy applies:
         *   BetterLyrics > NetEase > Musixmatch RichSync.
         */
        internal fun outranks(
            tier: Int,
            score: Double,
            bestTier: Int,
            bestScore: Double,
            masterMatch: com.auralis.music.domain.lyrics.MasterMatchStatus = com.auralis.music.domain.lyrics.MasterMatchStatus.EXACT_MATCH,
            bestMasterMatch: com.auralis.music.domain.lyrics.MasterMatchStatus = com.auralis.music.domain.lyrics.MasterMatchStatus.EXACT_MATCH,
            provider: LyricsProvider = LyricsProvider.LRCLIB,
            bestProvider: LyricsProvider = LyricsProvider.LRCLIB,
            isExactVideoMatch: Boolean = false,
            bestIsExactVideoMatch: Boolean = false,
            maxGapMs: Long = 0L,
            bestMaxGapMs: Long = 0L
        ): Boolean {
            val isAligned = masterMatch != com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH
            val bestIsAligned = bestMasterMatch != com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH

            return when {
                isAligned && !bestIsAligned -> true
                !isAligned && bestIsAligned -> false
                !isAligned && !bestIsAligned -> false
                // Completeness priority: candidate with significantly smaller void outranks candidate with missing void (>= 18s gap difference)
                (bestMaxGapMs - maxGapMs) >= 18_000L && (tier >= bestTier || score >= bestScore - 15.0) -> true
                (maxGapMs - bestMaxGapMs) >= 18_000L && (bestTier >= tier || bestScore >= score - 15.0) -> false
                tier > bestTier -> true
                tier < bestTier -> false
                tier == TIER_WORD -> {
                    // Exact-video-match genuine word sync takes precedence over metadata-only word sync
                    if (isExactVideoMatch && !bestIsExactVideoMatch) true
                    else if (!isExactVideoMatch && bestIsExactVideoMatch) false
                    else {
                        val p = providerWordPriority(provider)
                        val bp = providerWordPriority(bestProvider)
                        if (p > bp) true
                        else if (p < bp) false
                        else score > bestScore
                    }
                }
                else -> score > bestScore
            }
        }

        internal fun maxInternalGapMs(lines: List<LyricLine>): Long {
            if (lines.size < 2) return 0L
            var maxGap = 0L
            for (i in 0 until lines.size - 1) {
                val current = lines[i]
                val next = lines[i + 1]
                val effEnd = current.effectiveEndTime ?: (current.time + 3000L)
                val gap = next.time - effEnd
                if (gap > maxGap) {
                    maxGap = gap
                }
            }
            return maxGap
        }

        internal fun fillLyricsGaps(
            primary: LyricsData,
            secondaryCandidates: List<LyricsData>
        ): LyricsData {
            if (primary.lines.isEmpty() || secondaryCandidates.isEmpty()) return primary
            val missingLinesToInsert = mutableListOf<LyricLine>()

            // 1. Check intro void: primary first line starts after a significant void (>= 8s)
            val primaryFirstTime = primary.lines.firstOrNull { !it.isInstrumental }?.time ?: 0L
            if (primaryFirstTime >= 8_000L) {
                for (sec in secondaryCandidates) {
                    val introLines = sec.lines.filter {
                        it.time in 1_500L..(primaryFirstTime - 1_500L) &&
                            it.text.isNotBlank() &&
                            !it.isInstrumental
                    }
                    if (introLines.isNotEmpty()) {
                        missingLinesToInsert.addAll(introLines)
                        break
                    }
                }
            }

            // 2. Check internal gaps between consecutive lines (>= 8s)
            for (i in 0 until primary.lines.size - 1) {
                val current = primary.lines[i]
                val next = primary.lines[i + 1]
                val gapStart = current.effectiveEndTime ?: (current.time + 3000L)
                val gapEnd = next.time
                if (gapEnd - gapStart >= 8_000L) {
                    for (sec in secondaryCandidates) {
                        val fillingLines = sec.lines.filter {
                            it.time in (gapStart + 1200L)..(gapEnd - 1200L) &&
                                it.text.isNotBlank() &&
                                !it.isInstrumental
                        }
                        if (fillingLines.isNotEmpty()) {
                            missingLinesToInsert.addAll(fillingLines)
                            break
                        }
                    }
                }
            }

            // 3. Check outro void: primary ends early (>= 8s before secondary's last line)
            val primaryLastTime = primary.lines.lastOrNull { !it.isInstrumental }?.let { it.effectiveEndTime ?: it.time } ?: 0L
            if (primaryLastTime > 0L) {
                for (sec in secondaryCandidates) {
                    val outroLines = sec.lines.filter {
                        it.time >= (primaryLastTime + 2_000L) &&
                            it.text.isNotBlank() &&
                            !it.isInstrumental
                    }
                    if (outroLines.isNotEmpty()) {
                        missingLinesToInsert.addAll(outroLines)
                        break
                    }
                }
            }

            if (missingLinesToInsert.isEmpty()) return primary
            val mergedLines = (primary.lines + missingLinesToInsert).sortedBy { it.time }
            return primary.copy(
                lines = mergedLines,
                plainLyrics = mergedLines.joinToString("\n") { it.text }
            )
        }

        /**
         * Whether a candidate is good enough to cancel the remaining providers.
         * Gated on:
         * 1. Word tier.
         * 2. High quality score (>= INSTANT_WIN_SCORE).
         * 3. No internal voids (>18s) indicating dropped sections.
         * 4. Verified audio master alignment (must REJECT MasterMatchStatus.MASTER_MISMATCH).
         * 5. Top provider preference (BetterLyrics, or Unison when exact-video matched).
         */
        internal fun isInstantWinner(
            tier: Int,
            score: Double,
            masterMatch: com.auralis.music.domain.lyrics.MasterMatchStatus = com.auralis.music.domain.lyrics.MasterMatchStatus.EXACT_MATCH,
            provider: LyricsProvider = LyricsProvider.BETTER_LYRICS,
            isExactVideoMatch: Boolean = false,
            maxGapMs: Long = 0L
        ): Boolean =
            tier == TIER_WORD &&
            score >= INSTANT_WIN_SCORE &&
            maxGapMs <= 18_000L &&
            masterMatch != com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH &&
            (provider == LyricsProvider.BETTER_LYRICS || (provider == LyricsProvider.UNISON && isExactVideoMatch))

        internal fun calculateQualityScore(
            cand: LyricsCandidate,
            queryDurationSec: Long?,
            queryDurationMs: Long? = null,
            queryTitle: String? = null,
            queryChannelTitle: String? = null,
            queryVideoId: String? = null,
            audioLeadingSilenceMs: Long? = null
        ): Double {
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

            // 4. Word-timing bonus: genuine word sync outranks line-sync
            val tier = tierOf(cand.lyricsData)
            if (tier == TIER_WORD) {
                score += 20.0
            }

            // 5. Source bonuses: Word providers ranked BetterLyrics > NetEase > Musixmatch RichSync
            // Exact-video-match genuine word-sync receives a strong ranking bonus
            when (cand.provider) {
                LyricsProvider.BETTER_LYRICS -> if (tier == TIER_WORD) score += 30.0
                LyricsProvider.PAXSENIX -> if (tier == TIER_WORD) score += 28.0
                LyricsProvider.UNISON -> if (tier == TIER_WORD) {
                    score += if (cand.isExactVideoMatch) 45.0 else 25.0
                }
                LyricsProvider.NETEASE -> if (tier == TIER_WORD) score += 20.0 else if (firstLineTime > 350L) score += 6.0
                LyricsProvider.MUSIXMATCH -> if (tier == TIER_WORD) score += 10.0 else if (firstLineTime > 350L) score += 8.0
                LyricsProvider.LRCLIB -> if (firstLineTime > 350L) score += 10.0
                LyricsProvider.KUGOU -> if (firstLineTime > 350L) score += 6.0
                LyricsProvider.JIOSAAVN -> if (firstLineTime > 350L) score += 4.0
                else -> {}
            }

            // 6. Master Alignment evaluation against playback duration
            val playbackMs = queryDurationMs?.takeIf { it > 0L } ?: ((queryDurationSec ?: 0L) * 1000L)
            val masterMatch = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.evaluateMasterMatch(
                lyrics = cand.lyricsData,
                playbackDurationMs = playbackMs,
                playbackTitle = queryTitle,
                candidateTitle = cand.lyricsData.trackName,
                playbackChannelTitle = queryChannelTitle,
                playbackVideoId = queryVideoId
            )
            val isAcceptable = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                lyrics = cand.lyricsData,
                playbackDurationMs = playbackMs,
                playbackTitle = queryTitle,
                candidateTitle = cand.lyricsData.trackName,
                playbackChannelTitle = queryChannelTitle,
                playbackVideoId = queryVideoId,
                audioLeadingSilenceMs = audioLeadingSilenceMs
            )
            if (!isAcceptable) {
                score -= 50.0
            } else if (masterMatch == com.auralis.music.domain.lyrics.MasterMatchStatus.EXACT_MATCH && (playbackMs > 0L || cand.isExactVideoMatch)) {
                score += 10.0
            }

            // 7. Internal gap sanity penalty: massive voids (>45s) indicate missing verses or omissions
            val maxGap = maxInternalGapMs(lines)
            if (maxGap > 45_000L && lines.size < 45) {
                val penalty = (((maxGap - 45_000L) / 1000.0) * 0.4).coerceAtMost(50.0)
                score -= penalty
            }

            return score
        }
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
        album: String? = null,
        channelTitle: String? = null,
        durationMs: Long? = null,
        audioLeadingSilenceMs: Long? = null
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
            album = album,
            channelTitle = channelTitle,
            durationMs = durationMs
        )

        val t0 = System.currentTimeMillis()
        Log.d(TAG, "Starting ultra-fast synced lyrics search for: '$coreTitle' by '${query.artist}' (${durationSec ?: 0}s)")

        // ── PARALLEL MULTI-PROVIDER RACE, RANKED BY TIMING FORMAT FIRST ──
        // Better Lyrics leads: it is the only source that reliably serves
        // syllable-level TTML. AMLL is currently inert (its host answers 404) but
        // costs nothing to race and would resume working on its own if the host
        // returns.
        val primaryProviders: List<LyricsSource> = listOf(
            betterLyricsSource,
            unisonSource,
            paxsenixSource,
            lrcLibSource,
            musixmatchSource,
            kuGouSource,
            netEaseSource,
            jioSaavnSource,
            amllSource
        )

        val syncedWinner: LyricsData? = coroutineScope {
            val resultChannel = Channel<LyricsCandidate>(capacity = primaryProviders.size * 2)
            val providerJobMap = mutableMapOf<LyricsProvider, kotlinx.coroutines.Job>()
            val providerJobs = primaryProviders.map { source ->
                val job = launch {
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
                providerJobMap[source.provider] = job
                job
            }

            var bestCandidate: LyricsCandidate? = null
            var bestTier = TIER_NONE
            var bestScore = 0.0
            var bestMasterMatch = com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH
            var completedCount = 0
            var graceDeadlineMs = Long.MAX_VALUE

            val allValidCandidates = mutableListOf<LyricsCandidate>()

            while (completedCount < primaryProviders.size) {
                val betterLyricsActive = providerJobMap[LyricsProvider.BETTER_LYRICS]?.isActive == true
                val unisonActive = providerJobMap[LyricsProvider.UNISON]?.isActive == true
                val paxsenixActive = providerJobMap[LyricsProvider.PAXSENIX]?.isActive == true
                val netEaseActive = providerJobMap[LyricsProvider.NETEASE]?.isActive == true
                val musixmatchActive = providerJobMap[LyricsProvider.MUSIXMATCH]?.isActive == true
                val anyWordProviderActive = betterLyricsActive || unisonActive || paxsenixActive || netEaseActive || musixmatchActive

                val candidate = if (bestCandidate != null) {
                    if (bestTier == TIER_WORD) {
                        val bestHasGap = maxInternalGapMs(bestCandidate.lyricsData.lines) > 45_000L
                        val canBeBeaten = bestHasGap || ((bestCandidate.provider != LyricsProvider.BETTER_LYRICS && !bestCandidate.isExactVideoMatch) &&
                            (betterLyricsActive || (bestCandidate.provider != LyricsProvider.PAXSENIX && paxsenixActive)))
                        if (!canBeBeaten) {
                            break
                        }
                        val remainingMs = graceDeadlineMs - System.currentTimeMillis()
                        if (remainingMs <= 0L) break
                        withTimeoutOrNull(remainingMs) { resultChannel.receive() } ?: break
                    } else {
                        // We have a LINE_SYNC candidate in hand.
                        if (!anyWordProviderActive) {
                            // None of top word providers are active; settle immediately without waiting.
                            break
                        }
                        val remainingMs = graceDeadlineMs - System.currentTimeMillis()
                        if (remainingMs <= 0L) break
                        withTimeoutOrNull(remainingMs) { resultChannel.receive() } ?: break
                    }
                } else {
                    resultChannel.receive()
                }

                if (candidate.confidence == -1) {
                    completedCount++
                    continue
                }

                val isCandSynced = (candidate.syncType != SyncType.PLAIN || candidate.lyricsData.syncType != SyncType.PLAIN || candidate.lyricsData.lines.any { it.time > 0L })
                if (isCandSynced && candidate.confidence >= 50 && candidate.lyricsData.lines.isNotEmpty()) {
                    val tier = tierOf(candidate.lyricsData)
                    if (tier == TIER_NONE) continue

                    // Label from the data, not from the provider's claim: a
                    // candidate with no per-word durations is line-sync no matter
                    // what it called itself.
                    val resolvedSyncType =
                        if (tier == TIER_WORD) SyncType.RICHSYNC else SyncType.LINE_SYNC
                    val correctedCand = candidate.copy(
                        syncType = resolvedSyncType,
                        lyricsData = candidate.lyricsData.copy(syncType = resolvedSyncType)
                    )
                    allValidCandidates.add(correctedCand)

                    val queryDurationMs = durationMs?.takeIf { it > 0L } ?: ((durationSec ?: 0L) * 1000L)
                    val masterMatch = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.evaluateMasterMatch(
                        lyrics = correctedCand.lyricsData,
                        playbackDurationMs = queryDurationMs,
                        playbackTitle = title,
                        candidateTitle = correctedCand.lyricsData.trackName,
                        playbackChannelTitle = channelTitle,
                        playbackVideoId = videoId
                    )

                    val lyricDurMs = correctedCand.lyricsData.effectiveDurationMs
                    val isAcceptable = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                        lyrics = correctedCand.lyricsData,
                        playbackDurationMs = queryDurationMs,
                        playbackTitle = title,
                        candidateTitle = correctedCand.lyricsData.trackName,
                        playbackChannelTitle = channelTitle,
                        playbackVideoId = videoId,
                        audioLeadingSilenceMs = audioLeadingSilenceMs
                    )

                    // Rejection gate:
                    // 1. Definite master mismatch (MASTER_MISMATCH) for ALL candidates (both word-sync and line-sync)
                    // 2. Unverified compatible offset (COMPATIBLE_OFFSET when audioLeadingSilenceMs is null and candidate is not a genuine exact video match)
                    if (!isAcceptable) {
                        Log.w(TAG, "[Candidate REJECTED: ${correctedCand.provider}] ${correctedCand.syncType} rejected (masterMatch=$masterMatch, acceptable=false, playback=${queryDurationMs}ms, lyric=${lyricDurMs}ms)")
                        continue
                    }

                    val score = calculateQualityScore(correctedCand, durationSec, queryDurationMs, title, channelTitle, videoId, audioLeadingSilenceMs)
                    Log.d(TAG, "[Candidate: ${correctedCand.provider}] tier=$tier, masterMatch=$masterMatch, score=$score, exactVideo=${correctedCand.isExactVideoMatch}, firstLine=${correctedCand.lyricsData.lines.firstOrNull()?.time}ms, lines=${correctedCand.lyricsData.lines.size}")
                    if (score < 0) continue

                    // Alignment-aware comparison: matching line sync beats mismatched word sync; BetterLyrics > NetEase > Musixmatch RichSync
                    val currentBestProvider = bestCandidate?.provider ?: LyricsProvider.LRCLIB
                    val currentBestIsExactVideo = bestCandidate?.isExactVideoMatch == true
                    val candMaxGap = maxInternalGapMs(correctedCand.lyricsData.lines)
                    val bestCandMaxGap = bestCandidate?.let { maxInternalGapMs(it.lyricsData.lines) } ?: 0L
                    if (bestCandidate == null || outranks(
                            tier = tier,
                            score = score,
                            bestTier = bestTier,
                            bestScore = bestScore,
                            masterMatch = masterMatch,
                            bestMasterMatch = bestMasterMatch,
                            provider = correctedCand.provider,
                            bestProvider = currentBestProvider,
                            isExactVideoMatch = correctedCand.isExactVideoMatch,
                            bestIsExactVideoMatch = currentBestIsExactVideo,
                            maxGapMs = candMaxGap,
                            bestMaxGapMs = bestCandMaxGap
                        )) {
                        bestTier = tier
                        bestScore = score
                        bestMasterMatch = masterMatch
                        bestCandidate = correctedCand
                        if (tier == TIER_LINE) {
                            val wordProviderActive = providerJobMap[LyricsProvider.BETTER_LYRICS]?.isActive == true ||
                                providerJobMap[LyricsProvider.UNISON]?.isActive == true ||
                                providerJobMap[LyricsProvider.PAXSENIX]?.isActive == true ||
                                providerJobMap[LyricsProvider.NETEASE]?.isActive == true ||
                                providerJobMap[LyricsProvider.MUSIXMATCH]?.isActive == true
                            if (wordProviderActive) {
                                // Give actively running word providers sufficient time to complete genuine word sync
                                graceDeadlineMs = t0 + ACTIVE_WORD_PROVIDER_TIMEOUT_MS
                            } else {
                                // No word provider running; settle immediately
                                graceDeadlineMs = System.currentTimeMillis()
                            }
                        } else if (tier == TIER_WORD) {
                            // When an exact-video word candidate arrives, settle immediately without waiting for studio sources
                            if (correctedCand.isExactVideoMatch) {
                                graceDeadlineMs = System.currentTimeMillis()
                            } else if (candMaxGap > 45_000L) {
                                // Candidate has a massive internal gap; keep race open for complete providers
                                graceDeadlineMs = t0 + ACTIVE_WORD_PROVIDER_TIMEOUT_MS
                            } else if (correctedCand.provider != LyricsProvider.BETTER_LYRICS && providerJobMap[LyricsProvider.BETTER_LYRICS]?.isActive == true) {
                                graceDeadlineMs = minOf(graceDeadlineMs, t0 + WORD_SYNC_GRACE_MS)
                            } else {
                                graceDeadlineMs = System.currentTimeMillis()
                            }
                        }
                    }

                    if (isInstantWinner(tier, score, masterMatch, correctedCand.provider, correctedCand.isExactVideoMatch, candMaxGap)) {
                        Log.d(TAG, "[INSTANT QUALITY WINNER] ${correctedCand.provider} tier=$tier masterMatch=$masterMatch in ${System.currentTimeMillis() - t0}ms (Score: $score)")
                        providerJobs.forEach { it.cancel() }
                        return@coroutineScope correctedCand.lyricsData
                    }
                }
            }
            providerJobs.forEach { it.cancel() }
            bestCandidate?.let {
                Log.d(TAG, "[RACE SETTLED] ${it.provider} tier=$bestTier masterMatch=$bestMasterMatch score=$bestScore in ${System.currentTimeMillis() - t0}ms")
            }
            bestCandidate?.let { best ->
                val otherCandidates = allValidCandidates.filter { it.provider != best.provider }.map { it.lyricsData }
                fillLyricsGaps(best.lyricsData, otherCandidates)
            }
        }

        if (syncedWinner != null && syncedWinner.lines.isNotEmpty()) {
            Log.d(TAG, "[SYNCED WINNER] ${syncedWinner.provider} syncType=${syncedWinner.syncType} tier=${tierOf(syncedWinner)} selected in ${System.currentTimeMillis() - t0}ms")
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
                    album = album,
                    channelTitle = channelTitle,
                    durationMs = durationMs
                )
                val lrcFallback = withTimeoutOrNull(2000L) { lrcLibSource.search(fallbackQuery) }
                if (lrcFallback != null && lrcFallback.confidence >= 50 && lrcFallback.lyricsData.lines.isNotEmpty()) {
                    val queryDurationMs = durationMs?.takeIf { it > 0L } ?: ((durationSec ?: 0L) * 1000L)
                    val isAcceptable = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                        lyrics = lrcFallback.lyricsData,
                        playbackDurationMs = queryDurationMs,
                        playbackTitle = title,
                        candidateTitle = lrcFallback.lyricsData.trackName,
                        playbackChannelTitle = channelTitle,
                        playbackVideoId = videoId,
                        audioLeadingSilenceMs = audioLeadingSilenceMs
                    )
                    if (isAcceptable) {
                        Log.d(TAG, "[RAW TITLE WINNER] LRCLIB in ${System.currentTimeMillis() - t0}ms")
                        return@withContext lrcFallback.lyricsData.copy(syncType = SyncType.LINE_SYNC)
                    } else {
                        Log.w(TAG, "[RAW TITLE REJECTED] LRCLIB master mismatch for '$title' (playback=${queryDurationMs}ms, lyric=${lrcFallback.lyricsData.durationMs}ms)")
                    }
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
}

