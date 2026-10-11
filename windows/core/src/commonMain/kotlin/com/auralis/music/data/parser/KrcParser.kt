package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType

object KrcParser {

    private val KRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)\](.*)""")
    private val KRC_WORD_REGEX = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
    private val CREDIT_LINE_REGEX = Regex(
        """(?i)^(?:作词|作曲|编曲|制作|监制|录音|混音|吉他|贝斯|鼓|键盘|弦乐|合音|企划|OP|SP|Lyricist|Composer|Producer|Written\s*by|Music\s*by|Words\s*by)\s*[:：]"""
    )

    fun parse(
        krcContent: String,
        provider: LyricsProvider = LyricsProvider.KUGOU,
        trackName: String = "",
        artistName: String = ""
    ): LyricsData? {
        if (krcContent.isBlank()) return null

        val lines = mutableListOf<LyricLine>()
        var offsetMs = 0L

        for (rawLine in krcContent.lines()) {
            val lineTrimmed = rawLine.trim()
            if (lineTrimmed.isBlank()) continue

            if (lineTrimmed.startsWith("[offset:", ignoreCase = true)) {
                val offStr = lineTrimmed.substringAfter(":").substringBefore("]").trim()
                offsetMs = offStr.toLongOrNull() ?: 0L
                continue
            }
            if (lineTrimmed.startsWith("[ti:") || lineTrimmed.startsWith("[ar:") ||
                lineTrimmed.startsWith("[al:") || lineTrimmed.startsWith("[by:") ||
                lineTrimmed.startsWith("[hash:") || lineTrimmed.startsWith("[sign:") ||
                lineTrimmed.startsWith("[qq:") || lineTrimmed.startsWith("[total:") ||
                lineTrimmed.startsWith("[language:")
            ) {
                continue
            }

            val lineMatch = KRC_LINE_REGEX.find(lineTrimmed)
            if (lineMatch != null) {
                val rawStart = lineMatch.groupValues[1].toLongOrNull() ?: continue
                val lineDurMs = lineMatch.groupValues[2].toLongOrNull() ?: 0L
                val body = lineMatch.groupValues[3]

                val lineStartMs = (rawStart + offsetMs).coerceAtLeast(0L)
                val wordMatches = KRC_WORD_REGEX.findAll(body).toList()
                val words = mutableListOf<LyricWord>()
                val lineSb = StringBuilder()

                for (w in wordMatches) {
                    val wOffsetMs = w.groupValues[1].toLongOrNull() ?: 0L
                    val wDurMs = w.groupValues[2].toLongOrNull()?.takeIf { it > 0L }
                    val wText = w.groupValues[3]

                    val wStartMs = lineStartMs + wOffsetMs
                    words.add(LyricWord(word = wText, time = wStartMs, duration = wDurMs))
                    lineSb.append(wText)
                }

                val lineEndMs = if (lineDurMs > 0L) lineStartMs + lineDurMs else null
                val fullText = if (words.isNotEmpty()) lineSb.toString().trim() else body.trim()
                if (fullText.isNotEmpty()) {
                    if (lineStartMs < 8000L && CREDIT_LINE_REGEX.containsMatchIn(fullText)) {
                        continue
                    }
                    lines.add(
                        LyricLine(
                            time = lineStartMs,
                            text = fullText,
                            words = if (words.isNotEmpty()) words else null,
                            endTime = lineEndMs
                        )
                    )
                }
            }
        }

        if (lines.isEmpty()) return null
        val sorted = lines.sortedBy { it.time }

        return LyricsData(
            syncType = if (WordTiming.hasGenuineWordStarts(sorted)) SyncType.RICHSYNC else SyncType.LINE_SYNC,
            lines = sorted,
            plainLyrics = sorted.joinToString("\n") { it.text },
            provider = provider,
            trackName = trackName,
            artistName = artistName
        )
    }
}
