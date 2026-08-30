package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType

object LrcParser {

    private val LINE_TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIMESTAMP_REGEX = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>([^<]*)""")

    /**
     * Parses LRC text (standard line sync or enhanced rich sync) into [LyricsData].
     */
    fun parse(lrcContent: String, provider: LyricsProvider = LyricsProvider.LRCLIB): LyricsData {
        if (lrcContent.isBlank()) {
            return LyricsData(syncType = SyncType.PLAIN, lines = emptyList(), provider = provider)
        }

        val lines = mutableListOf<LyricLine>()
        var hasWordSync = false
        var globalLrcOffsetMs = 0L

        val rawLines = lrcContent.lines()
        for (rawLine in rawLines) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("[offset:", ignoreCase = true)) {
                val numStr = trimmed.substringAfter(":").substringBefore("]").trim()
                val parsedOffset = numStr.toLongOrNull()
                if (parsedOffset != null) {
                    globalLrcOffsetMs = parsedOffset
                }
                continue
            }
            if (trimmed.isBlank() || trimmed.startsWith("[ar:") || trimmed.startsWith("[ti:") ||
                trimmed.startsWith("[al:") || trimmed.startsWith("[by:")
            ) {
                continue
            }

            val lineMatches = LINE_TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (lineMatches.isEmpty()) continue

            // The text comes after all leading [mm:ss.xx] timestamps
            val lastMatch = lineMatches.last()
            val textPart = trimmed.substring(lastMatch.range.last + 1).trim()

            // Check for enhanced word-level sync: <00:12.34>Word <00:13.00>Word2
            val wordMatches = WORD_TIMESTAMP_REGEX.findAll(textPart).toList()
            val words = if (wordMatches.isNotEmpty()) {
                hasWordSync = true
                val list = mutableListOf<LyricWord>()
                for (w in wordMatches) {
                    val wMin = w.groupValues[1].toLongOrNull() ?: 0
                    val wSec = w.groupValues[2].toLongOrNull() ?: 0
                    val wMsRaw = w.groupValues[3]
                    val wMs = parseMs(wMsRaw)
                    val wTime = (wMin * 60_000 + wSec * 1_000 + wMs - globalLrcOffsetMs).coerceAtLeast(0)
                    val wordText = w.groupValues[4]
                    list.add(LyricWord(word = wordText, time = wTime))
                }
                list
            } else null

            val cleanLineText = if (words != null) {
                words.joinToString("") { it.word }.trim()
            } else {
                textPart
            }

            // A line could have multiple timestamps, e.g. [00:10.00][00:20.00]Repeated lyric
            for (match in lineMatches) {
                val min = match.groupValues[1].toLongOrNull() ?: 0
                val sec = match.groupValues[2].toLongOrNull() ?: 0
                val msRaw = match.groupValues[3]
                val ms = parseMs(msRaw)
                val lineTime = (min * 60_000 + sec * 1_000 + ms - globalLrcOffsetMs).coerceAtLeast(0)

                lines.add(
                    LyricLine(
                        time = lineTime,
                        text = cleanLineText,
                        words = words
                    )
                )
            }
        }

        val sorted = lines.sortedBy { it.time }

        // Check if timestamps are fake robotic linear steps (e.g. 0.0s, 5.2s, 10.4s, 15.6s)
        val isAuthentic = if (sorted.size >= 4) {
            val diffs = sorted.zipWithNext { a, b -> b.time - a.time }
            val distinctDiffs = diffs.distinct()
            // If all lines have the exact same robotic interval (e.g., 5200ms everywhere), it's fake
            !(distinctDiffs.size == 1 && distinctDiffs[0] > 1000L)
        } else {
            true
        }

        val syncType = when {
            !isAuthentic -> SyncType.PLAIN
            hasWordSync -> SyncType.RICHSYNC
            sorted.isNotEmpty() -> SyncType.LINE_SYNC
            else -> SyncType.PLAIN
        }

        val linesWithWords = sorted

        return LyricsData(
            syncType = syncType,
            lines = linesWithWords,
            plainLyrics = sorted.joinToString("\n") { it.text },
            provider = provider
        )
    }

    /**
     * Synthesizes natural human vocal-paced word-level timestamps for standard line-synced lyrics,
     * ensuring words highlight at authentic singing speed without bleeding into instrumental gaps.
     */
    fun synthesizeWordTimestamps(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return lines
        return lines.mapIndexed { index, line ->
            if (!line.words.isNullOrEmpty()) return@mapIndexed line
            val text = line.text.trim()
            if (text.isBlank()) return@mapIndexed line

            val rawWords = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (rawWords.isEmpty()) return@mapIndexed line

            val nextTime = lines.getOrNull(index + 1)?.time
            val timeGapToNext = if (nextTime != null && nextTime > line.time) nextTime - line.time else 3500L

            // 1. Natural human vocal singing cadence per word
            val naturalWordDurations = rawWords.map { w ->
                val base = 250L + (w.length * 60L)
                val punctBonus = when {
                    w.endsWith(",") || w.endsWith(";") -> 160L
                    w.endsWith(".") || w.endsWith("?") || w.endsWith("!") -> 260L
                    else -> 0L
                }
                base + punctBonus
            }
            val totalNaturalTime = naturalWordDurations.sum().coerceAtLeast(350L)

            // 2. Bound vocal line duration so it finishes before the next line begins (leaving breathing gap)
            val maxAllowedDuration = if (timeGapToNext > 500L) timeGapToNext - 300L else timeGapToNext
            val effectiveDuration = if (totalNaturalTime <= maxAllowedDuration) {
                totalNaturalTime
            } else {
                maxAllowedDuration.coerceAtLeast(300L)
            }

            // 3. Proportionally distribute across words
            val scaleFactor = effectiveDuration.toFloat() / totalNaturalTime
            var accumulatedTime = line.time
            val words = rawWords.mapIndexed { wIdx, wordStr ->
                val isLast = wIdx == rawWords.size - 1
                val wordWithSpace = if (isLast) wordStr else "$wordStr "
                val calculatedDur = (naturalWordDurations[wIdx] * scaleFactor).toLong().coerceIn(70L, 2500L)
                val lyricWord = LyricWord(
                    word = wordWithSpace,
                    time = accumulatedTime,
                    duration = calculatedDur
                )
                accumulatedTime += calculatedDur
                lyricWord
            }

            line.copy(words = words)
        }
    }

    private fun parseMs(msRaw: String?): Long {
        if (msRaw.isNullOrBlank()) return 0
        return when (msRaw.length) {
            1 -> msRaw.toLong() * 100
            2 -> msRaw.toLong() * 10
            else -> msRaw.take(3).toLong()
        }
    }
}
