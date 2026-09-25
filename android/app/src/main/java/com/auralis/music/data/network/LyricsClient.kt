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
import com.auralis.music.data.network.provider.SimpMusicLyricsSource
import com.auralis.music.data.network.provider.UnisonLyricsSource
import com.auralis.music.data.network.provider.YouLyPlusLyricsSource
import com.auralis.music.data.network.provider.YouTubeCaptionsLyricsSource
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
    // Not raced: since 2026-09 Musixmatch hands anonymous clients an all-zero token and serves the
    // same decoy tracks ("Casual" by Doja Cat, cat music) for every search. Kept for callers/tests.
    @Suppress("unused") private val musixmatchSource: MusixmatchLyricsSource = MusixmatchLyricsSource(),
    private val geniusSource: GeniusLyricsSource = GeniusLyricsSource(),
    private val ytMusicSource: YouTubeInnerTubeLyricsSource = YouTubeInnerTubeLyricsSource(),
    private val youLyPlusSource: YouLyPlusLyricsSource = YouLyPlusLyricsSource(),
    private val captionsSource: YouTubeCaptionsLyricsSource = YouTubeCaptionsLyricsSource(),
    private val simpMusicSource: SimpMusicLyricsSource = SimpMusicLyricsSource()
) {
    companion object {
        private const val TAG = "LyricsCascade"
        private const val PROVIDER_TIMEOUT_MS = 6500L

        /** A synced candidate with fewer sung lines than this is a credit or stub, not lyrics. */
        internal const val MIN_SUNG_LINES = 3

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

        /**
         * Extra time a usable result *without* line-level speaker metadata waits for a
         * speaker-capable provider that is still running. Bounded per arrival and by
         * [ACTIVE_WORD_PROVIDER_TIMEOUT_MS] from race start, so it can never stall lyrics.
         */
        internal const val SPEAKER_ENRICHMENT_GRACE_MS = 1000L

        /**
         * How far below a speaker-less rival a speaker-tagged candidate of the same sync
         * tier may score and still be preferred. Equivalent copies of one Apple TTML from
         * two providers differ by only a few points of source bonus.
         */
        internal const val SPEAKER_SCORE_TOLERANCE = 15.0

        /**
         * Providers whose TTML can carry `ttm:agent` and that normally answer within the
         * race budget. Measured on live traffic: BetterLyrics, Paxsenix and Unison return
         * agent-tagged Apple/AMLL TTML; LRC-based providers never carry line speakers.
         */
        internal val SPEAKER_CAPABLE_PROVIDERS = setOf(
            LyricsProvider.BETTER_LYRICS,
            LyricsProvider.PAXSENIX,
            LyricsProvider.UNISON,
            LyricsProvider.YOULYPLUS
        )

        /**
         * True when the lyrics name at least two distinct individual vocalists on lead
         * lines (the ensemble id `v1000` excluded) — the same threshold MetroLyrics uses
         * to engage its speaker-aware layout. A file tagging every line `v1` does not count.
         */
        fun hasSpeakerMetadata(data: LyricsData?): Boolean {
            if (data == null) return false
            val speakers = HashSet<String>()
            for (line in data.lines) {
                if (line.isBackground) continue
                val agent = line.agent?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
                if (agent == "v1000") continue
                speakers.add(agent)
                if (speakers.size >= 2) return true
            }
            return false
        }

        /**
         * Speaker metadata as a same-tier tie-break: `true`/`false` when it decides the
         * comparison, `null` when it does not (both or neither tagged, or the tagged one
         * scores too far below). Sync tier is always compared before this.
         */
        internal fun speakerPreference(
            hasSpeakers: Boolean,
            score: Double,
            bestHasSpeakers: Boolean,
            bestScore: Double
        ): Boolean? = when {
            hasSpeakers && !bestHasSpeakers && score >= bestScore - SPEAKER_SCORE_TOLERANCE -> true
            !hasSpeakers && bestHasSpeakers && bestScore >= score - SPEAKER_SCORE_TOLERANCE -> false
            else -> null
        }

        /**
         * When the race may settle after a new best word-synced candidate. The first four
         * rules are the pre-existing ones; the last adds a bounded wait for speaker
         * metadata only when the new best has none and a speaker-capable provider is
         * still running.
         */
        internal fun wordSettleDeadline(
            currentDeadlineMs: Long,
            raceStartMs: Long,
            nowMs: Long,
            provider: LyricsProvider,
            isExactVideoMatch: Boolean,
            maxGapMs: Long,
            betterLyricsOrAmllActive: Boolean,
            hasSpeakers: Boolean = false,
            speakerSourcePending: Boolean = false
        ): Long {
            val base = when {
                // When an exact-video word candidate arrives, settle immediately without waiting for studio sources
                isExactVideoMatch -> nowMs
                // Candidate has a massive internal gap; keep race open for complete providers
                maxGapMs > 45_000L -> raceStartMs + ACTIVE_WORD_PROVIDER_TIMEOUT_MS
                provider != LyricsProvider.BETTER_LYRICS && provider != LyricsProvider.AMLL && betterLyricsOrAmllActive ->
                    minOf(currentDeadlineMs, raceStartMs + WORD_SYNC_GRACE_MS)
                else -> nowMs
            }
            if (hasSpeakers || !speakerSourcePending || isExactVideoMatch) return base
            val speakerDeadline = minOf(nowMs + SPEAKER_ENRICHMENT_GRACE_MS, raceStartMs + ACTIVE_WORD_PROVIDER_TIMEOUT_MS)
            return maxOf(base, speakerDeadline)
        }

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
        /**
         * True when the playing track and a lyrics result are different versions of the song:
         * one is a remix / live / slowed / acoustic take (a timing-altering tag) and the other
         * isn't the same version. A result with no title counts as untagged, since nothing
         * vouches that it's the remix.
         */
        internal fun isVersionClash(playbackTitle: String, candidateTitle: String?): Boolean {
            val qVersion = TitleCleaner.extractVersion(playbackTitle)
            val cVersion = candidateTitle?.takeIf { it.isNotBlank() }?.let { TitleCleaner.extractVersion(it) }
            val altering = com.auralis.music.domain.lyrics.LyricsAlignmentEngine
            if (!altering.isTimingAlteringVersion(qVersion) && !altering.isTimingAlteringVersion(cVersion)) return false
            return qVersion == null || cVersion == null || !qVersion.equals(cVersion, ignoreCase = true)
        }

        internal fun providerWordPriority(provider: LyricsProvider): Int = when (provider) {
            LyricsProvider.BETTER_LYRICS -> 5
            LyricsProvider.AMLL -> 5
            LyricsProvider.PAXSENIX -> 4
            LyricsProvider.YOULYPLUS -> 4
            LyricsProvider.UNISON -> 3
            LyricsProvider.NETEASE -> 2
            LyricsProvider.SIMPMUSIC -> 2
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
            bestMaxGapMs: Long = 0L,
            hasSpeakers: Boolean = false,
            bestHasSpeakers: Boolean = false
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
                    // Same timing tier: line-level speaker metadata beats none, within a score tolerance
                    else speakerPreference(hasSpeakers, score, bestHasSpeakers, bestScore) ?: run {
                        val p = providerWordPriority(provider)
                        val bp = providerWordPriority(bestProvider)
                        if (p > bp) true
                        else if (p < bp) false
                        else score > bestScore
                    }
                }
                else -> speakerPreference(hasSpeakers, score, bestHasSpeakers, bestScore) ?: (score > bestScore)
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

        /** Character-bigram Dice similarity of two normalized line texts (0..1). */
        internal fun bigramSimilarity(a: String, b: String): Double {
            if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
            val grams = HashMap<String, Int>()
            for (i in 0 until a.length - 1) grams.merge(a.substring(i, i + 2), 1, Int::plus)
            var shared = 0
            for (i in 0 until b.length - 1) {
                val g = b.substring(i, i + 2)
                val c = grams[g] ?: 0
                if (c > 0) { shared++; grams[g] = c - 1 }
            }
            return 2.0 * shared / ((a.length - 1) + (b.length - 1))
        }

        internal fun fillLyricsGaps(
            primary: LyricsData,
            secondaryCandidates: List<LyricsData>
        ): LyricsData {
            if (primary.lines.isEmpty() || secondaryCandidates.isEmpty()) return primary

            // Master compatibility filter: reject candidates with duration delta > 3.5s
            val compatibleSecondary = secondaryCandidates.filter { sec ->
                val pDur = primary.durationMs
                val sDur = sec.durationMs
                if (pDur != null && pDur > 0L && sDur != null && sDur > 0L) {
                    kotlin.math.abs(pDur - sDur) <= 3500L
                } else true
            }
            if (compatibleSecondary.isEmpty()) return primary

            val existingNormalizedTexts = primary.lines
                .map { it.text.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "") }
                .filter { it.isNotBlank() }
                .toSet()

            fun isEquivalentToExisting(text: String): Boolean {
                val norm = text.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
                if (norm.isBlank()) return true
                if (existingNormalizedTexts.contains(norm)) return true
                if (existingNormalizedTexts.any { it.length >= 6 && norm.length >= 6 && (it.contains(norm) || norm.contains(it)) }) return true
                // Another transcription of the same line ("thahar" vs "thehar") is not a missing line.
                return norm.length >= 8 && existingNormalizedTexts.any {
                    it.length >= 8 && bigramSimilarity(it, norm) >= 0.8
                }
            }

            val missingLinesToInsert = mutableListOf<LyricLine>()

            // 1. Check intro void: only inspect genuine long voids (>= 20s), not standard instrumental intros.
            // Never inject if equivalent text already exists in primary lyrics.
            val primaryFirstTime = primary.lines.firstOrNull { !it.isInstrumental }?.time ?: 0L
            if (primaryFirstTime >= 20_000L) {
                for (sec in compatibleSecondary) {
                    val introLines = sec.lines.filter {
                        it.time in 1_500L..(primaryFirstTime - 2_000L) &&
                            it.text.isNotBlank() &&
                            !it.isInstrumental &&
                            !isEquivalentToExisting(it.text)
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
                    for (sec in compatibleSecondary) {
                        val fillingLines = sec.lines.filter {
                            it.time in (gapStart + 1200L)..(gapEnd - 1200L) &&
                                it.text.isNotBlank() &&
                                !it.isInstrumental &&
                                !isEquivalentToExisting(it.text)
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
                for (sec in compatibleSecondary) {
                    val outroLines = sec.lines.filter {
                        it.time >= (primaryLastTime + 2_000L) &&
                            it.text.isNotBlank() &&
                            !it.isInstrumental &&
                            !isEquivalentToExisting(it.text)
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
            maxGapMs: Long = 0L,
            hasSpeakers: Boolean = false,
            speakerSourcePending: Boolean = false
        ): Boolean =
            tier == TIER_WORD &&
            // A speaker-less winner does not end the race while a speaker-capable provider is
            // still answering; the bounded wait is applied by [wordSettleDeadline].
            (hasSpeakers || !speakerSourcePending) &&
            score >= INSTANT_WIN_SCORE &&
            maxGapMs <= 18_000L &&
            masterMatch != com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH &&
            (provider == LyricsProvider.BETTER_LYRICS || provider == LyricsProvider.AMLL || (provider == LyricsProvider.UNISON && isExactVideoMatch))

        internal fun calculateQualityScore(
            cand: LyricsCandidate,
            queryDurationSec: Long?,
            queryDurationMs: Long? = null,
            queryTitle: String? = null,
            queryChannelTitle: String? = null,
            queryVideoId: String? = null,
            audioLeadingSilenceMs: Long? = null,
            queryArtist: String? = null
        ): Double {
            if (com.auralis.music.data.parser.LyricsValidator.isCorruptOrInvalid(cand.lyricsData) ||
                com.auralis.music.data.parser.LyricsValidator.hasCorruptIntroTiming(cand.lyricsData, queryDurationSec)) {
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

            // 5. Source bonuses: Word providers ranked BetterLyrics = AMLL > Paxsenix > Unison > NetEase > Musixmatch
            // Exact-video-match genuine word-sync receives a strong ranking bonus
            when (cand.provider) {
                LyricsProvider.BETTER_LYRICS -> if (tier == TIER_WORD) score += 30.0
                LyricsProvider.AMLL -> if (tier == TIER_WORD) score += 30.0
                LyricsProvider.PAXSENIX -> if (tier == TIER_WORD) score += 28.0
                // Same Apple Music word timing as Paxsenix, served as KPoe JSON.
                LyricsProvider.YOULYPLUS -> if (tier == TIER_WORD) score += 28.0
                LyricsProvider.UNISON -> if (tier == TIER_WORD) {
                    score += if (cand.isExactVideoMatch) 45.0 else 25.0
                }
                LyricsProvider.NETEASE -> if (tier == TIER_WORD) score += 20.0 else if (firstLineTime > 350L) score += 6.0
                LyricsProvider.MUSIXMATCH -> if (tier == TIER_WORD) score += 10.0 else if (firstLineTime > 350L) score += 8.0
                LyricsProvider.LRCLIB -> if (firstLineTime > 350L) score += 10.0
                // Community entries keyed by the playing video: below Apple Music TTML, level with lrclib.
                LyricsProvider.SIMPMUSIC -> if (tier == TIER_WORD) score += 22.0 else if (firstLineTime > 350L) score += 10.0
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
                playbackVideoId = queryVideoId,
                playbackArtist = queryArtist,
                candidateArtist = cand.lyricsData.artistName
            )
            val isAcceptable = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                lyrics = cand.lyricsData,
                playbackDurationMs = playbackMs,
                playbackTitle = queryTitle,
                candidateTitle = cand.lyricsData.trackName,
                playbackChannelTitle = queryChannelTitle,
                playbackVideoId = queryVideoId,
                audioLeadingSilenceMs = audioLeadingSilenceMs,
                playbackArtist = queryArtist,
                candidateArtist = cand.lyricsData.artistName
            )
            if (!isAcceptable) {
                score -= 50.0
            } else if (masterMatch == com.auralis.music.domain.lyrics.MasterMatchStatus.EXACT_MATCH && (playbackMs > 0L || cand.isExactVideoMatch)) {
                score += 10.0
            }

            // Silence alignment evaluation: if audioLeadingSilenceMs is known and candidate provides leadingSilenceMs,
            // reward candidate whose leading silence matches the audio, and penalize large silence discrepancies.
            val candSilence = cand.lyricsData.leadingSilenceMs
            if (audioLeadingSilenceMs != null && candSilence != null) {
                val silenceDelta = kotlin.math.abs(audioLeadingSilenceMs - candSilence)
                if (silenceDelta <= 300L) {
                    score += 25.0 // Strong match with playback stream pre-roll
                } else if (silenceDelta > 1000L) {
                    score -= 25.0 // Candidate belongs to master with incompatible pre-roll
                }
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
        audioLeadingSilenceMs: Long? = null,
        /** Displayable results while the race keeps waiting for better; see LyricsRepository.getLyricsWithInterim. */
        onInterim: ((LyricsData) -> Unit)? = null
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
            durationMs = durationMs,
            audioLeadingSilenceMs = audioLeadingSilenceMs
        )

        val t0 = System.currentTimeMillis()
        Log.d(TAG, "Starting ultra-fast synced lyrics search for: '$coreTitle' by '${query.artist}' (${durationSec ?: 0}s)")

        // ── PARALLEL MULTI-PROVIDER RACE, RANKED BY TIMING FORMAT FIRST ──
        // Better Lyrics and AMLL TTML DB lead: they serve genuine syllable-level TTML.
        val primaryProviders: List<LyricsSource> = listOf(
            betterLyricsSource,
            amllSource,
            unisonSource,
            paxsenixSource,
            youLyPlusSource,
            simpMusicSource,
            lrcLibSource,
            kuGouSource,
            netEaseSource,
            jioSaavnSource
        )

        // Providers run in their own detached scope, not as children of the race. Most make
        // blocking OkHttp calls that cancellation can't interrupt, and coroutineScope waits for
        // every child to finish: a winner found in 0.8s was held back until the slowest source
        // (AMLL, 10-18s) returned, so lyrics took 11-15s to appear and a skip before then meant
        // the synced version never showed. Stragglers now finish in the background, ignored.
        val raceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val syncedWinner: LyricsData? = try { coroutineScope {
            val resultChannel = Channel<LyricsCandidate>(capacity = primaryProviders.size * 2)
            val providerJobMap = mutableMapOf<LyricsProvider, kotlinx.coroutines.Job>()
            val providerJobs = primaryProviders.map { source ->
                val job = raceScope.launch {
                    try {
                        // YouLy+ needs longer on a song its backend hasn't served lately (cold fetch).
                        val budgetMs = if (source.provider == LyricsProvider.YOULYPLUS) {
                            YouLyPlusLyricsSource.RACE_BUDGET_MS
                        } else PROVIDER_TIMEOUT_MS
                        val cand = withTimeoutOrNull(budgetMs) {
                            source.search(query)
                        }
                        if (cand != null) {
                            Log.d(TAG, "[Provider: ${source.provider}] found: ${cand.syncType}, confidence=${cand.confidence}%, lines=${cand.lyricsData.lines.size}")
                            resultChannel.trySend(cand)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[Provider: ${source.provider}] exception: ${e.message}")
                    } finally {
                        // trySend: never suspends (the buffer holds two messages per provider), so it
                        // still lands after the race has moved on and cancelled this job.
                        resultChannel.trySend(
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

            // A speaker-capable provider other than [except] is still answering.
            fun speakerSourcePending(except: LyricsProvider): Boolean =
                SPEAKER_CAPABLE_PROVIDERS.any { it != except && providerJobMap[it]?.isActive == true }

            while (completedCount < primaryProviders.size) {
                val betterLyricsActive = providerJobMap[LyricsProvider.BETTER_LYRICS]?.isActive == true
                val amllActive = providerJobMap[LyricsProvider.AMLL]?.isActive == true
                val unisonActive = providerJobMap[LyricsProvider.UNISON]?.isActive == true
                val paxsenixActive = providerJobMap[LyricsProvider.PAXSENIX]?.isActive == true
                val netEaseActive = providerJobMap[LyricsProvider.NETEASE]?.isActive == true
                val youLyPlusActive = providerJobMap[LyricsProvider.YOULYPLUS]?.isActive == true
                val simpMusicActive = providerJobMap[LyricsProvider.SIMPMUSIC]?.isActive == true
                val anyWordProviderActive = betterLyricsActive || amllActive || unisonActive || paxsenixActive || netEaseActive || youLyPlusActive || simpMusicActive

                val candidate = if (bestCandidate != null) {
                    if (bestTier == TIER_WORD) {
                        val bestHasGap = maxInternalGapMs(bestCandidate.lyricsData.lines) > 45_000L
                        val canBeBeaten = bestHasGap || ((bestCandidate.provider != LyricsProvider.BETTER_LYRICS && bestCandidate.provider != LyricsProvider.AMLL && !bestCandidate.isExactVideoMatch) &&
                            (betterLyricsActive || amllActive || (bestCandidate.provider != LyricsProvider.PAXSENIX && paxsenixActive))) ||
                            // Bounded speaker-metadata enrichment window (see wordSettleDeadline)
                            (!bestCandidate.isExactVideoMatch && !hasSpeakerMetadata(bestCandidate.lyricsData) &&
                                speakerSourcePending(bestCandidate.provider))
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
                    // Nothing usable yet. Per-provider timeouts can't interrupt blocking calls, so
                    // bound the whole wait here instead of waiting on the slowest source.
                    // While YouLy+ is still on a cold fetch it's often the only source with the song,
                    // so keep waiting for it; otherwise stop at the normal per-source budget.
                    val deadlineMs = if (youLyPlusActive) YouLyPlusLyricsSource.RACE_BUDGET_MS + 500L else PROVIDER_TIMEOUT_MS + 500L
                    val remainingMs = (t0 + deadlineMs) - System.currentTimeMillis()
                    if (remainingMs <= 0L) break
                    withTimeoutOrNull(remainingMs) { resultChannel.receive() } ?: break
                }

                if (candidate.confidence == -1) {
                    completedCount++
                    continue
                }

                val isCandSynced = (candidate.syncType != SyncType.PLAIN || candidate.lyricsData.syncType != SyncType.PLAIN || candidate.lyricsData.lines.any { it.time > 0L })
                // Clean first (credits, symbol-only marker lines, source headers, CJK annotations),
                // then count: a result that was only credits must not survive as empty lyrics.
                val cleanedData = com.auralis.music.data.parser.LyricsContentFilter.cleanForDisplay(candidate.lyricsData, coreTitle)
                // Fewer than 3 sung lines isn't a song's lyrics: seen live, NetEase answered
                // "Jadoo Ki Jhappi" with a single line (a credit) and won because nothing else had.
                val sungLineCount = cleanedData.lines.count { !it.isInstrumental && it.text.isNotBlank() }
                if (isCandSynced && candidate.confidence >= 50 && sungLineCount >= MIN_SUNG_LINES) {
                    val tier = tierOf(cleanedData)
                    if (tier == TIER_NONE) continue

                    // Label from the data, not from the provider's claim: candidates
                    // with genuine word timing (durations or starts) retain RICHSYNC,
                    // while line-level text remains LINE_SYNC.
                    val resolvedSyncType =
                        if (tier == TIER_WORD || com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(cleanedData.lines)) {
                            SyncType.RICHSYNC
                        } else {
                            SyncType.LINE_SYNC
                        }
                    val correctedCand = candidate.copy(
                        syncType = resolvedSyncType,
                        // Fan-made CJK annotations (film headers, 女/男 labels, inline Chinese
                        // translations) are stripped before the candidate can win or fill gaps.
                        lyricsData = cleanedData.copy(syncType = resolvedSyncType)
                    )
                    allValidCandidates.add(correctedCand)

                    val queryDurationMs = durationMs?.takeIf { it > 0L } ?: ((durationSec ?: 0L) * 1000L)
                    val masterMatch = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.evaluateMasterMatch(
                        lyrics = correctedCand.lyricsData,
                        playbackDurationMs = queryDurationMs,
                        playbackTitle = title,
                        candidateTitle = correctedCand.lyricsData.trackName,
                        playbackChannelTitle = channelTitle,
                        playbackVideoId = videoId,
                        playbackArtist = artist,
                        candidateArtist = correctedCand.lyricsData.artistName
                    )

                    val lyricDurMs = correctedCand.lyricsData.effectiveDurationMs
                    val isAcceptable = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isAcceptableMasterMatch(
                        lyrics = correctedCand.lyricsData,
                        playbackDurationMs = queryDurationMs,
                        playbackTitle = title,
                        candidateTitle = correctedCand.lyricsData.trackName,
                        playbackChannelTitle = channelTitle,
                        playbackVideoId = videoId,
                        audioLeadingSilenceMs = audioLeadingSilenceMs,
                        playbackArtist = artist,
                        candidateArtist = correctedCand.lyricsData.artistName
                    )

                    // Rejection gate:
                    // 1. Definite master mismatch (MASTER_MISMATCH) for ALL candidates (both word-sync and line-sync)
                    // 2. Unverified compatible offset (COMPATIBLE_OFFSET when audioLeadingSilenceMs is null and candidate is not a genuine exact video match)
                    if (!isAcceptable) {
                        val durDelta = if (queryDurationMs > 0L && lyricDurMs > 0L) kotlin.math.abs(queryDurationMs - lyricDurMs) else -1L
                        Log.w(TAG, "[LyricsDiagnostic] Track='$title' Provider=${correctedCand.provider} Tier=$tier WordTiming=${tier == TIER_WORD} DurDelta=${durDelta}ms Accepted=false Reason='MASTER_MISMATCH_OR_INCOMPATIBLE_OFFSET' (masterMatch=$masterMatch, acceptable=false, playback=${queryDurationMs}ms, lyric=${lyricDurMs}ms)")
                        continue
                    }

                    val score = calculateQualityScore(correctedCand, durationSec, queryDurationMs, title, channelTitle, videoId, audioLeadingSilenceMs, artist)
                    val durDelta = if (queryDurationMs > 0L && lyricDurMs > 0L) kotlin.math.abs(queryDurationMs - lyricDurMs) else -1L
                    Log.d(TAG, "[LyricsDiagnostic] Track='$title' Provider=${correctedCand.provider} Tier=$tier WordTiming=${tier == TIER_WORD} DurDelta=${durDelta}ms Score=$score Accepted=true lines=${correctedCand.lyricsData.lines.size} exactVideo=${correctedCand.isExactVideoMatch}")
                    if (score < 0) continue

                    // Alignment-aware comparison: matching line sync beats mismatched word sync; BetterLyrics / AMLL > NetEase > Musixmatch RichSync
                    val currentBestProvider = bestCandidate?.provider ?: LyricsProvider.LRCLIB
                    val currentBestIsExactVideo = bestCandidate?.isExactVideoMatch == true
                    val candMaxGap = maxInternalGapMs(correctedCand.lyricsData.lines)
                    val bestCandMaxGap = bestCandidate?.let { maxInternalGapMs(it.lyricsData.lines) } ?: 0L
                    val candHasSpeakers = hasSpeakerMetadata(correctedCand.lyricsData)
                    val bestHasSpeakers = hasSpeakerMetadata(bestCandidate?.lyricsData)
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
                            bestMaxGapMs = bestCandMaxGap,
                            hasSpeakers = candHasSpeakers,
                            bestHasSpeakers = bestHasSpeakers
                        )) {
                        bestTier = tier
                        bestScore = score
                        bestMasterMatch = masterMatch
                        bestCandidate = correctedCand
                        // Show it now; the race may still wait for a better tier (word sync, speakers).
                        onInterim?.invoke(correctedCand.lyricsData)
                        if (tier == TIER_LINE) {
                            val wordProviderActive = providerJobMap[LyricsProvider.BETTER_LYRICS]?.isActive == true ||
                                providerJobMap[LyricsProvider.AMLL]?.isActive == true ||
                                providerJobMap[LyricsProvider.UNISON]?.isActive == true ||
                                providerJobMap[LyricsProvider.PAXSENIX]?.isActive == true ||
                                providerJobMap[LyricsProvider.NETEASE]?.isActive == true ||
                                providerJobMap[LyricsProvider.YOULYPLUS]?.isActive == true ||
                                providerJobMap[LyricsProvider.SIMPMUSIC]?.isActive == true
                            if (wordProviderActive) {
                                // Give actively running word providers sufficient time to complete genuine
                                // word sync. YouLy+ alone needs longer on a cold fetch (~9-13s vs its
                                // typical <1s): the old flat 5.5s cut it off before it could answer,
                                // settling for line sync moments before word sync would have arrived.
                                val youLyPlusStillActive = providerJobMap[LyricsProvider.YOULYPLUS]?.isActive == true
                                graceDeadlineMs = t0 + (if (youLyPlusStillActive) YouLyPlusLyricsSource.RACE_BUDGET_MS else ACTIVE_WORD_PROVIDER_TIMEOUT_MS)
                            } else {
                                // No word provider running; settle immediately
                                graceDeadlineMs = System.currentTimeMillis()
                            }
                        } else if (tier == TIER_WORD) {
                            graceDeadlineMs = wordSettleDeadline(
                                currentDeadlineMs = graceDeadlineMs,
                                raceStartMs = t0,
                                nowMs = System.currentTimeMillis(),
                                provider = correctedCand.provider,
                                isExactVideoMatch = correctedCand.isExactVideoMatch,
                                maxGapMs = candMaxGap,
                                betterLyricsOrAmllActive = providerJobMap[LyricsProvider.BETTER_LYRICS]?.isActive == true ||
                                    providerJobMap[LyricsProvider.AMLL]?.isActive == true,
                                hasSpeakers = candHasSpeakers,
                                speakerSourcePending = speakerSourcePending(correctedCand.provider)
                            )
                        }
                    }

                    // Only the current best may end the race: a candidate that just lost the
                    // ranking (e.g. an untagged copy of an already speaker-tagged best) must not.
                    if (bestCandidate === correctedCand && isInstantWinner(
                            tier, score, masterMatch, correctedCand.provider, correctedCand.isExactVideoMatch, candMaxGap,
                            hasSpeakers = candHasSpeakers,
                            speakerSourcePending = speakerSourcePending(correctedCand.provider)
                        )) {
                        Log.d(TAG, "[INSTANT QUALITY WINNER] ${correctedCand.provider} tier=$tier masterMatch=$masterMatch in ${System.currentTimeMillis() - t0}ms (Score: $score)")
                        providerJobs.forEach { it.cancel() }
                        return@coroutineScope correctedCand.lyricsData
                    }
                }
            }
            providerJobs.forEach { it.cancel() }
            val settledBest = bestCandidate ?: run {
                val queryMs = durationMs?.takeIf { it > 0L } ?: ((durationSec ?: 0L) * 1000L)
                allValidCandidates
                    .filter { cand ->
                        val lines = cand.lyricsData.lines
                        if (lines.isEmpty() || cand.confidence < 50) return@filter false
                        if (com.auralis.music.data.parser.LyricsValidator.hasCorruptIntroTiming(cand.lyricsData, durationSec)) return@filter false
                        if (com.auralis.music.data.parser.LyricsValidator.hasNonMonotonicWordTimestamps(cand.lyricsData)) return@filter false

                        val qVersion = com.auralis.music.data.network.TitleCleaner.extractVersion(title)
                        val cVersion = com.auralis.music.data.network.TitleCleaner.extractVersion(cand.lyricsData.trackName ?: "")
                        if (com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isTimingAlteringVersion(qVersion) ||
                            com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isTimingAlteringVersion(cVersion)) {
                            if (qVersion == null || cVersion == null || !qVersion.equals(cVersion, ignoreCase = true)) {
                                return@filter false
                            }
                        }

                        if (queryMs > 0L) {
                            val lastVocalEnd = lines.lastOrNull { !it.isInstrumental }?.let { it.wordTimingEndMs ?: it.time }
                                ?: lines.lastOrNull()?.time
                                ?: 0L
                            if (lastVocalEnd > queryMs + 2000L) return@filter false

                            val lyricDur = cand.lyricsData.effectiveDurationMs
                            if (lyricDur > 0L && kotlin.math.abs(queryMs - lyricDur) > 10_000L) return@filter false
                        }
                        true
                    }
                    .maxWithOrNull(
                        compareBy<LyricsCandidate> { tierOf(it.lyricsData) }
                            .thenBy { it.confidence }
                            .thenByDescending {
                                if (queryMs > 0L && it.lyricsData.effectiveDurationMs > 0L) {
                                    -kotlin.math.abs(queryMs - it.lyricsData.effectiveDurationMs)
                                } else 0L
                            }
                    )?.also {
                        Log.d(TAG, "[SAFE RESCUE WINNER] Rescued candidate from ${it.provider} for '$title' (${it.syncType})")
                    }
            }
            settledBest?.let {
                Log.d(TAG, "[RACE SETTLED] ${it.provider} tier=${tierOf(it.lyricsData)} score=$bestScore in ${System.currentTimeMillis() - t0}ms")
            }
            settledBest?.let { best ->
                val otherCandidates = allValidCandidates.filter { it.provider != best.provider }.map { it.lyricsData }
                fillLyricsGaps(best.lyricsData, otherCandidates)
            }
        } } finally {
            // Also covers the caller being cancelled (song skipped): stop sources still in flight.
            raceScope.cancel()
        }

        if (syncedWinner != null && syncedWinner.lines.isNotEmpty()) {
            Log.d(TAG, "[SYNCED WINNER] ${syncedWinner.provider} syncType=${syncedWinner.syncType} tier=${tierOf(syncedWinner)} selected in ${System.currentTimeMillis() - t0}ms")
            return@withContext syncedWinner
        }

        // ── TIER 2: RAW TITLE / FALLBACK ON LRCLIB ──
        if (title != coreTitle || syncedWinner == null) {
            try {
                val fallbackTitle = if (title != coreTitle) TitleCleaner.cleanTitle(title) else coreTitle
                val fallbackQuery = LyricsSearchQuery(
                    title = fallbackTitle,
                    artist = TitleCleaner.cleanArtist(artist),
                    durationSec = durationSec,
                    videoId = videoId,
                    album = album,
                    channelTitle = channelTitle,
                    durationMs = durationMs
                )
                val lrcFallback = withTimeoutOrNull(2500L) { lrcLibSource.search(fallbackQuery) }
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
                    val isSafeMatch = if (queryDurationMs > 0L && lrcFallback.lyricsData.effectiveDurationMs > 0L) {
                        kotlin.math.abs(queryDurationMs - lrcFallback.lyricsData.effectiveDurationMs) <= 10_000L
                    } else true
                    // A close length may excuse a loose timing match, never a different version:
                    // the album cut of a song is often within 10s of its remix.
                    val isVersionClash = isVersionClash(title, lrcFallback.lyricsData.trackName)

                    if (!isVersionClash && (isAcceptable || isSafeMatch)) {
                        Log.d(TAG, "[RAW TITLE WINNER] LRCLIB in ${System.currentTimeMillis() - t0}ms (acceptable=$isAcceptable, safeMatch=$isSafeMatch)")
                        val fallbackSyncType = if (com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(lrcFallback.lyricsData.lines)) {
                            SyncType.RICHSYNC
                        } else {
                            SyncType.LINE_SYNC
                        }
                        return@withContext com.auralis.music.data.parser.LyricsContentFilter
                            .cleanForDisplay(lrcFallback.lyricsData, fallbackTitle).copy(syncType = fallbackSyncType)
                    } else {
                        Log.w(TAG, "[RAW TITLE REJECTED] LRCLIB master mismatch for '$title' (playback=${queryDurationMs}ms, lyric=${lrcFallback.lyricsData.durationMs}ms)")
                    }
                }
            } catch (_: Exception) {}
        }

        // ── TIER 2b: VERSION-TAGGED TITLE ──
        // "Jadoo Ki Jhappi (Jhankar)", "Song [Unplugged]": no lyrics site lists the tagged title,
        // but a same-length version sings the same lines at the same times. Retry with the tag
        // stripped, and accept only when the playing length is known and the lyrics' length
        // matches it closely — a remix or edit of a different length is never given its timing.
        // Remixes, live takes, slowed/sped edits are excluded outright: a similar length proves
        // nothing there (lrclib lists the album cut of "Anarkali Disco Chali" at 285s, and its
        // Hyper Mix runs 283s with different vocals).
        val baseTitle = TitleCleaner.withoutBracketedTags(coreTitle)
        val playbackMsForBase = durationMs?.takeIf { it > 0L } ?: ((durationSec ?: 0L) * 1000L)
        val isRearrangedVersion = com.auralis.music.domain.lyrics.LyricsAlignmentEngine
            .isTimingAlteringVersion(TitleCleaner.extractVersion(title))
        if (!isRearrangedVersion && baseTitle.isNotBlank() && !baseTitle.equals(coreTitle, ignoreCase = true) && playbackMsForBase > 0L) {
            try {
                val baseQuery = LyricsSearchQuery(
                    title = baseTitle,
                    artist = TitleCleaner.cleanArtist(artist),
                    durationSec = durationSec,
                    videoId = videoId,
                    channelTitle = channelTitle,
                    durationMs = durationMs
                )
                val baseCand = withTimeoutOrNull(3000L) { lrcLibSource.search(baseQuery) }
                if (baseCand != null && baseCand.confidence >= 50) {
                    val cleaned = com.auralis.music.data.parser.LyricsContentFilter.cleanForDisplay(baseCand.lyricsData, baseTitle)
                    val sung = cleaned.lines.count { !it.isInstrumental && it.text.isNotBlank() }
                    val lyricMs = cleaned.effectiveDurationMs
                    val synced = cleaned.lines.any { it.time > 0L }
                    if (synced && sung >= MIN_SUNG_LINES && lyricMs > 0L && kotlin.math.abs(playbackMsForBase - lyricMs) <= 5_000L) {
                        Log.d(TAG, "[BASE TITLE WINNER] '$baseTitle' for '$coreTitle' (playback=${playbackMsForBase}ms, lyric=${lyricMs}ms)")
                        val syncType = if (com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(cleaned.lines)) SyncType.RICHSYNC else SyncType.LINE_SYNC
                        return@withContext cleaned.copy(syncType = syncType)
                    } else {
                        Log.d(TAG, "[BASE TITLE REJECTED] '$baseTitle' (playback=${playbackMsForBase}ms, lyric=${lyricMs}ms, synced=$synced, lines=$sung)")
                    }
                }
            } catch (_: Exception) {}
        }

        // ── TIER 3: PLAIN TEXT FALLBACK (Genius & YouTube Music) ──
        val plainWinner = coroutineScope {
            val geniusDeferred = async {
                try { withTimeoutOrNull(2000L) { geniusSource.search(query) } } catch (_: Exception) { null }
            }
            val ytDeferred = async {
                try { withTimeoutOrNull(2000L) { ytMusicSource.search(query) } } catch (_: Exception) { null }
            }
            listOfNotNull(geniusDeferred.await(), ytDeferred.await()).firstOrNull { it.lyricsData.lines.isNotEmpty() }
        }

        if (plainWinner != null) {
            // Last chance at sync: time the plain text from the playing video's own captions.
            val captionVideoId = videoId?.takeIf { it.isNotBlank() && !it.startsWith("sp_") && !it.startsWith("spotify:") }
            if (captionVideoId != null) {
                val timed = try {
                    withTimeoutOrNull(4500L) { captionsSource.timeFromCaptions(captionVideoId, plainWinner.lyricsData) }
                } catch (_: Exception) { null }
                if (timed != null) {
                    Log.d(TAG, "[CAPTIONS WINNER] timed ${plainWinner.provider} text from video captions in ${System.currentTimeMillis() - t0}ms")
                    return@withContext timed
                }
            }
            Log.d(TAG, "[PLAIN WINNER] ${plainWinner.provider} in ${System.currentTimeMillis() - t0}ms")
            return@withContext plainWinner.lyricsData
        }

        Log.d(TAG, "No lyrics found after ${System.currentTimeMillis() - t0}ms for '$coreTitle'")
        null
    }
}

