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

        val rawLines = lrcContent.lines()
        for (rawLine in rawLines) {
            val trimmed = rawLine.trim()
            if (trimmed.isBlank() || trimmed.startsWith("[ar:") || trimmed.startsWith("[ti:") ||
                trimmed.startsWith("[al:") || trimmed.startsWith("[by:") || trimmed.startsWith("[offset:")
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
                    val wTime = wMin * 60_000 + wSec * 1_000 + wMs
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
                val lineTime = min * 60_000 + sec * 1_000 + ms

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

        return LyricsData(
            syncType = syncType,
            lines = sorted,
            plainLyrics = sorted.joinToString("\n") { it.text },
            provider = provider
        )
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
