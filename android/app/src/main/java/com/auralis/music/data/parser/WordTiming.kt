package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord

/**
 * The single place that answers "does this lyric actually carry word timing?".
 *
 * Every parser, the provider race and the Room cache used to answer it their own
 * way, and the loosest of those answers — "any words present at all" — let
 * fabricated timing be labelled word-synced. These two predicates replace all of
 * them.
 *
 * The distinction between them matters because sources differ in *how much* they
 * state:
 *  - TTML / YRC / QRC / Musixmatch state a start **and** an end per token, so a
 *    karaoke sweep can be drawn. [hasGenuineWordTiming] is that case.
 *  - Enhanced LRC (`<00:12.50>word`) states starts only. That is still real
 *    per-word timing — it just cannot be swept, only stepped.
 *    [hasGenuineWordStarts] is that case.
 *  - A line whose "words" all share one timestamp states nothing: it is a token
 *    split of the line text with the line's own time stamped onto each piece.
 *    Both predicates reject it, which is the point.
 */
object WordTiming {

    /**
     * True when some non-instrumental line can drive a **sweep** — at least two
     * of its words carry a provider-supplied duration.
     *
     * Two, not one: a lone timed word among untimed ones is a parser artefact
     * (a stray `end` attribute, a single `d` field), not word sync.
     *
     * Mirrors braccato's `parts?.some(p => p.durationMs > 0)` and Metrolist's
     * TTML rule of recording a span only when both `begin` and `end` are present.
     */
    fun hasGenuineWordTiming(lines: List<LyricLine>): Boolean =
        lines.any { line ->
            !line.isInstrumental && (line.words?.count { it.duration != null } ?: 0) >= 2
        }

    /**
     * True when some non-instrumental line can drive a **step** — at least two of
     * its words start at distinct, provider-supplied times.
     *
     * The distinct-time requirement is what rejects fabricated word lists: a
     * splitter that stamps `line.time` onto every token produces N words with one
     * timestamp, which carries no more information than the line already did.
     */
    fun hasGenuineWordStarts(lines: List<LyricLine>): Boolean =
        lines.any { line ->
            !line.isInstrumental &&
                (line.words?.size ?: 0) >= 2 &&
                (line.words?.distinctBy { it.time }?.size ?: 0) >= 2
        }

    /**
     * The keep-or-strip question: does the word list say anything the line
     * timestamps do not?
     *
     * Deliberately more permissive than the two predicates above, because both of
     * those ask "can this drive an animation across a line", and this one asks
     * only "is there information here worth keeping". A `<p>` whose single span
     * carries a real `end` states where the vocal stops — the line time alone
     * never does — so it survives, while a token split sharing one timestamp does
     * not.
     */
    fun statesMoreThanLineTime(lines: List<LyricLine>): Boolean =
        lines.any { line ->
            !line.isInstrumental &&
                (
                    line.words?.any { it.duration != null } == true ||
                        (line.words?.distinctBy { it.time }?.size ?: 0) >= 2
                    )
        }

    /**
     * The only derivation this codebase permits: split a span that **already has
     * a genuine duration** into its whitespace-separated words, proportionally by
     * character count, strictly inside `[time, time + duration]`.
     *
     * Legitimate because it invents no new interval — it subdivides one the
     * provider measured. Apple Music and AMLL TTML exports do emit multi-word
     * spans (`<span begin="16s" end="17.5s">can try</span>`), and braccato
     * subdivides them the same way (`inject.ts:231-244`), as does Metrolist for
     * hyphenated words (`LyricsLine.kt:474+`).
     *
     * Returns the input unchanged when there is nothing to split or no genuine
     * duration to split — never widens the span, never emits a zero-length piece.
     */
    fun subdivideByCharCount(span: LyricWord): List<LyricWord> {
        val duration = span.duration ?: return listOf(span)
        if (duration <= 0L) return listOf(span)

        val tokens = splitKeepingTrailingSpace(span.word)
        if (tokens.size < 2) return listOf(span)

        val weights = tokens.map { it.trim().length.coerceAtLeast(1) }
        val totalWeight = weights.sum()
        if (totalWeight <= 0) return listOf(span)

        val out = ArrayList<LyricWord>(tokens.size)
        var consumedWeight = 0
        var cursor = span.time
        for (i in tokens.indices) {
            consumedWeight += weights[i]
            // Accumulate against the span end rather than summing per-piece
            // lengths, so rounding can never push the last piece past it.
            val pieceEnd = if (i == tokens.lastIndex) {
                span.time + duration
            } else {
                span.time + (duration * consumedWeight) / totalWeight
            }
            val pieceDur = pieceEnd - cursor
            if (pieceDur <= 0L) return listOf(span)
            out.add(
                LyricWord(
                    word = tokens[i],
                    time = cursor,
                    duration = pieceDur,
                    isBackground = span.isBackground
                )
            )
            cursor = pieceEnd
        }
        return out
    }

    /** Applies [subdivideByCharCount] to every span of every line. */
    fun subdivideLines(lines: List<LyricLine>): List<LyricLine> = lines.map { line ->
        val words = line.words ?: return@map line
        if (words.none { it.word.trim().contains(' ') }) return@map line
        line.copy(words = words.flatMap { subdivideByCharCount(it) })
    }

    /**
     * Splits on whitespace but keeps each separator attached to the token before
     * it, so the renderer can still print the pieces verbatim and reassemble the
     * original text exactly.
     */
    private fun splitKeepingTrailingSpace(text: String): List<String> {
        if (text.isBlank()) return listOf(text)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var tokenHasLetters = false
        for (ch in text) {
            if (!ch.isWhitespace() && tokenHasLetters && sb.isNotEmpty() && sb.last().isWhitespace()) {
                // A non-space after a space run closes the previous token.
                out.add(sb.toString())
                sb.setLength(0)
                tokenHasLetters = false
            }
            sb.append(ch)
            if (!ch.isWhitespace()) tokenHasLetters = true
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return if (out.isEmpty()) listOf(text) else out
    }

    /**
     * Merges contiguous syllable spans belonging to the same visual word into a single
     * [LyricWord], matching Metrolist, Echo, and NomaTune behavior.
     *
     * In TTML and similar providers, multi-syllable words (such as "beauti" + "ful") are
     * emitted as separate spans without whitespace between them. Rendering these syllables
     * as independent visual words causes rapid, disproportionate sweep speeds across short
     * syllables.
     *
     * Contiguous spans are merged when:
     * 1. The previous span does not end with whitespace.
     * 2. Neither span contains CJK characters (where character-level timing is authentic and expected).
     * 3. Both spans share the same background-vocal status.
     *
     * The merged word spans the exact provider interval: from the start of the first syllable
     * to the end of the final syllable, preserving genuine provider timing accuracy.
     */
    fun mergeContiguousSyllables(words: List<LyricWord>?): List<LyricWord>? {
        if (words.isNullOrEmpty() || words.size < 2) return words

        val merged = ArrayList<LyricWord>(words.size)
        var acc = words[0]

        for (i in 1 until words.size) {
            val curr = words[i]
            val prevWord = acc.word
            val hasTrailingSpace = prevWord.isNotEmpty() && prevWord.last().isWhitespace()
            val canMerge = !hasTrailingSpace &&
                !isCjk(prevWord) &&
                !isCjk(curr.word) &&
                acc.isBackground == curr.isBackground

            if (canMerge) {
                val combinedText = prevWord + curr.word
                val startTime = acc.time
                val currEnd = curr.duration?.let { curr.time + it }
                val accEnd = acc.duration?.let { acc.time + it }
                val effectiveEnd = currEnd ?: accEnd
                val combinedDuration = if (effectiveEnd != null && effectiveEnd > startTime) {
                    effectiveEnd - startTime
                } else null

                acc = acc.copy(
                    word = combinedText,
                    time = startTime,
                    duration = combinedDuration
                )
            } else {
                merged.add(acc)
                acc = curr
            }
        }
        merged.add(acc)
        return merged
    }

    /**
     * Identifies characters belonging to CJK (Chinese, Japanese, Korean) Unicode blocks,
     * where each character typically represents an independent syllable/morpheme without
     * word-separating spaces. Syllable merging is bypassed for CJK to preserve natural
     * character-by-character karaoke progression.
     */
    fun isCjk(text: String): Boolean = text.any { c ->
        Character.UnicodeBlock.of(c) in CJK_UNICODE_BLOCKS
    }

    private val CJK_UNICODE_BLOCKS = setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HANGUL_JAMO,
        Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    )
}
