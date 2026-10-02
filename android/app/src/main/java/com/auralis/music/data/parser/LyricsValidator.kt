package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricsData

object LyricsValidator {

    private val CORRUPT_QUESTION_MARK_REGEX = Regex("""\?{2,}|\?\s+\?""")
    private val PLACEHOLDER_REGEX = Regex(
        """(?i)\b(lyrics\s*(not\s*available|coming\s*soon|to\s*be\s*added)|add\s*lyrics|lorem\s*ipsum|text\s*not\s*found)\b"""
    )
    private val CONTRIBUTOR_STUB_REGEX = Regex(
        """(?i)\b(lyrics\s*uploaded\s*by|synced\s*by|transcribed\s*by)\b"""
    )

    /**
     * Checks if a lyrics candidate contains corrupt encoding (e.g. "??? ?? ???"),
     * placeholder mock text, or broken character data.
     */
    fun isCorruptOrInvalid(lyricsData: LyricsData?): Boolean {
        if (lyricsData == null || lyricsData.lines.isEmpty()) return true

        val nonInstLines = lyricsData.lines.filter { !it.isInstrumental && it.text.isNotBlank() }
        if (nonInstLines.isEmpty()) return false // purely instrumental is fine

        val fullText = nonInstLines.joinToString("\n") { it.text }

        // 1. Multiple consecutive question marks (e.g. "??? ?? ????? ??..." from failed UTF-8 Devanagari uploads)
        if (CORRUPT_QUESTION_MARK_REGEX.containsMatchIn(fullText)) {
            return true
        }

        // 2. High ratio of question marks or replacement characters (indicates Unicode decoding failure)
        val questionMarkCount = fullText.count { it == '?' || it == '\uFFFD' }
        val letterOrDigitCount = fullText.count { it.isLetter() || it.isDigit() }
        if (letterOrDigitCount > 0) {
            val ratio = questionMarkCount.toDouble() / (letterOrDigitCount + questionMarkCount)
            if (ratio > 0.04) {
                return true
            }
        }

        // 3. Known placeholder / spam lines
        if (PLACEHOLDER_REGEX.containsMatchIn(fullText)) {
            return true
        }

        // Entire payload consists only of contributor attribution stubs without actual lyrics
        if (nonInstLines.all { CONTRIBUTOR_STUB_REGEX.containsMatchIn(it.text) || it.text.trim().length < 5 }) {
            return true
        }

        // 4. Incomplete 1-line or 2-line synced lyrics for standard length tracks
        if (lyricsData.lines.size <= 2 && nonInstLines.size <= 2) {
            val text = nonInstLines.firstOrNull()?.text?.lowercase() ?: ""
            if (text.contains("instrumental") || text.contains("music") || text.length < 5) {
                // allow short markers if intended
            } else {
                return true
            }
        }

        // 5. Non-monotonic broken word timestamps
        if (hasNonMonotonicWordTimestamps(lyricsData)) {
            return true
        }

        // 6. Defective / synthetic micro-stutter lines (e.g. consecutive identical lines spaced < 350ms apart,
        // or multiple lines with impossibly short duration < 200ms indicating corrupt machine-generated sync)
        if (hasDefectiveMicroTiming(lyricsData)) {
            return true
        }

        return false
    }

    /**
     * Detects defective, corrupted, or synthetic machine-generated lyrics where timestamps
     * stutter or collapse into physically impossible micro-durations.
     *
     * Example: Corrupted crowdsourced NetEase YRC for "All I Need" contains identical lines
     * ("S'alright") spaced 180ms apart with 180ms line durations and truncated shorthand wording.
     */
    fun hasDefectiveMicroTiming(lyricsData: LyricsData?): Boolean {
        if (lyricsData == null || lyricsData.lines.isEmpty()) return false
        val lines = lyricsData.lines.filter { !it.isInstrumental && it.text.isNotBlank() }
        if (lines.size < 2) return false

        for (i in 0 until lines.size - 1) {
            val curr = lines[i]
            val next = lines[i + 1]
            if (curr.isBackground || next.isBackground) continue

            val normCurr = curr.text.lowercase().filter { it.isLetterOrDigit() }
            val normNext = next.text.lowercase().filter { it.isLetterOrDigit() }
            if (normCurr.isEmpty() || normNext.isEmpty()) continue

            val timeDelta = next.time - curr.time

            // Consecutive identical lines stamped with physically impossible vocal delta (< 350ms)
            // or impossible micro-duration (< 250ms) indicating machine-generated stutter timestamps.
            val currDuration = curr.endTime?.let { it - curr.time }
                ?: curr.words?.sumOf { it.duration ?: 0L }?.takeIf { it > 0L }

            if (normCurr == normNext && (timeDelta in 0 until 350L || (currDuration != null && currDuration in 1 until 250L))) {
                return true
            }
        }
        return false
    }

    /**
     * Detects crowdsourced or malformed lyrics where the first vocal line starts impossibly early
     * (e.g. at [00:00.00] or <= 100ms) on a track of substantial length (>= 45s or >= 12 lines)
     * without an explicit intro or acapella marker.
     *
     * Example: LRCLIB crowdsourced submission for "Love Me Not" (Ravyn Lenae) has stated duration 213s,
     * but line 1 begins at [00:00.00], omitting the 16.8s instrumental intro entirely.
     */
    fun hasCorruptIntroTiming(lyricsData: LyricsData?, playbackDurationSec: Long? = null): Boolean {
        if (lyricsData == null || lyricsData.lines.isEmpty()) return false
        val nonInstLines = lyricsData.lines.filter { !it.isInstrumental && it.text.isNotBlank() }
        if (nonInstLines.isEmpty()) return false

        val firstLine = nonInstLines.firstOrNull() ?: return false
        val durSec = playbackDurationSec ?: (lyricsData.durationMs?.let { it / 1000L } ?: 0L)
        val isSubstantialTrack = durSec >= 45L || lyricsData.lines.size >= 12
        if (!isSubstantialTrack) return false

        val firstWordTime = firstLine.words?.firstOrNull { it.word.isNotBlank() }?.time
        val isZeroOrNearZeroIntro = firstLine.time <= 100L || (firstWordTime != null && firstWordTime <= 100L)
        if (isZeroOrNearZeroIntro) {
            val text = firstLine.text.lowercase().trim()
            val isExplicitIntroMarker = text.startsWith("(intro") || text.startsWith("[intro") ||
                text.contains("instrumental") || text.startsWith("intro:") || text == "♪"
            if (!isExplicitIntroMarker) {
                return true
            }
        }
        return false
    }

    /**
     * Detects broken word-level timestamps where timestamps within lines or across lead lines jump backwards in time.
     * Concurrent background ad-libs (x-bg) are ignored during cross-line checks since they overlap with lead vocals.
     */
    fun hasNonMonotonicWordTimestamps(lyricsData: LyricsData?): Boolean {
        if (lyricsData == null || lyricsData.lines.isEmpty()) return false
        var lastLeadWordTime = -1L
        for (line in lyricsData.lines) {
            if (line.isInstrumental) continue
            val words = line.words ?: continue
            var lastWordTimeInLine = -1L
            for (word in words) {
                if (word.word.isBlank()) continue
                // Within a line, syllables/words must progress monotonically forward
                if (lastWordTimeInLine >= 0L && word.time < lastWordTimeInLine - 100L) {
                    return true
                }
                if (word.time > lastWordTimeInLine) {
                    lastWordTimeInLine = word.time
                }
            }

            // Across lead vocal lines, verify general forward progress (ignoring concurrent background ad-libs)
            if (!line.isBackground) {
                val leadWords = words.filter { !it.isBackground && it.word.isNotBlank() }
                for (word in leadWords) {
                    if (lastLeadWordTime >= 0L && word.time < lastLeadWordTime - 1200L) {
                        return true
                    }
                    if (word.time > lastLeadWordTime) {
                        lastLeadWordTime = word.time
                    }
                }
            }
        }
        return false
    }

    /**
     * Checks if the candidate lyrics' last vocal timestamp overruns the actual playback audio length
     * by more than the allowed tolerance (default 3500ms).
     */
    fun hasVocalOverrun(lyricsData: LyricsData?, playbackDurationMs: Long, toleranceMs: Long = 3500L): Boolean {
        if (lyricsData == null || playbackDurationMs <= 0L) return false
        val lastVocalEndMs = lyricsData.lines.lastOrNull { !it.isInstrumental }?.let { it.wordTimingEndMs ?: it.time }
            ?: lyricsData.lines.lastOrNull()?.time
            ?: 0L
        return lastVocalEndMs > playbackDurationMs + toleranceMs
    }
}

