package com.auralis.music.data.network

import com.auralis.music.domain.model.*
import kotlin.math.abs

/** Cross-source evidence, not a claim of audio/word forced alignment. */
object LyricsTimingConsensus {
    const val VERSION = 1
    const val SETTLE_MS = 900L

    // These services commonly mirror the same Apple/TTML recording.
    private fun family(provider: LyricsProvider): String = when (provider) {
        LyricsProvider.BETTER_LYRICS, LyricsProvider.AMLL, LyricsProvider.PAXSENIX,
        LyricsProvider.UNISON, LyricsProvider.YOULYPLUS -> "apple-ttml"
        else -> provider.name
    }

    private fun anchors(data: LyricsData): Map<String, Long> = data.lines
        .filter { !it.isBackground && !it.isInstrumental }
        .map { line -> line.text.lowercase().filter { it.isLetterOrDigit() } to
            (line.words?.firstOrNull()?.time ?: line.time) }
        .filter { it.first.length >= 8 }
        .groupBy { it.first }
        // Repeated chorus lines cannot identify which occurrence is being sung.
        .filterValues { it.size == 1 }.mapValues { it.value.single().second }

    /** 1 = agreement, -1 = clear offset/drift, 0 = insufficient evidence. */
    fun compare(a: LyricsData, b: LyricsData): Int {
        val left = anchors(a)
        val right = anchors(b)
        val deltas = left.mapNotNull { (text, time) -> right[text]?.let { abs(time - it) } }.sorted()
        if (deltas.size < 4 || deltas.size * 2 < minOf(left.size, right.size)) return 0
        val median = deltas[deltas.size / 2]
        val near = deltas.count { it <= 800L }
        return when {
            median <= 500L && near * 4 >= deltas.size * 3 -> 1
            median >= 1500L && deltas.count { it >= 1200L } * 4 >= deltas.size * 3 -> -1
            else -> 0
        }
    }

    /** Call only with candidates that already passed recording/version and corruption checks. */
    fun select(fallback: LyricsData, candidates: List<LyricsData>): LyricsData {
        val all = candidates.distinctBy { it.provider }
        all.firstOrNull { it.provider == LyricsProvider.SPICY_LYRICS && LyricsClient.tierOf(it) >= LyricsClient.TIER_LINE }
            ?.let { return it.copy(timingSelectionVersion = VERSION) }
        if (fallback.isExactVideoMatch) return fallback.copy(timingSelectionVersion = VERSION)
        val trusted = all.filter { a ->
            all.filter { b -> family(a.provider) != family(b.provider) && compare(a, b) == 1 }
                .map { family(it.provider) }.distinct().size >= 2
        }
        fun rejected(a: LyricsData): Boolean = trusted.filter {
            family(it.provider) != family(a.provider) && compare(a, it) == -1
        }.map { family(it.provider) }.distinct().size >= 2
        var best = fallback
        if (rejected(fallback)) {
            // No evidence is not evidence of accuracy: don't promote an unrelated
            // or sparsely matched word source over the corroborated alternatives.
            val eligible = trusted.filterNot(::rejected)
            best = eligible.firstOrNull() ?: fallback
            for (candidate in eligible.drop(1)) {
                if (LyricsClient.outranks(LyricsClient.tierOf(candidate), 100.0, LyricsClient.tierOf(best), 100.0,
                        provider = candidate.provider, bestProvider = best.provider,
                        hasSpeakers = LyricsClient.hasSpeakerMetadata(candidate), bestHasSpeakers = LyricsClient.hasSpeakerMetadata(best))) best = candidate
            }
        }
        return best.copy(timingConfidence = if (best in trusted) 1 else 0, timingSelectionVersion = VERSION)
    }
}
