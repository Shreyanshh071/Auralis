package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType

object QrcParser {

    private val QRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)\](.*)""")
    private val CREDIT_LINE_REGEX = Regex(
        """(?i)^(?:作词|作曲|编曲|制作|监制|录音|混音|吉他|贝斯|鼓|键盘|弦乐|合音|企划|OP|SP|Lyricist|Composer|Producer|Written\s*by|Music\s*by|Words\s*by)\s*[:：]"""
    )

    fun parse(
        qrcContent: String,
        provider: LyricsProvider = LyricsProvider.QQMUSIC,
        trackName: String = "",
        artistName: String = ""
    ): LyricsData? {
        if (qrcContent.isBlank()) return null

        val rawText = extractLyricContent(qrcContent.trim())
        val lines = mutableListOf<LyricLine>()
        var offsetMs = 0L

        for (rawLine in rawText.lines()) {
            val lineTrimmed = rawLine.trim()
            if (lineTrimmed.isBlank()) continue

            if (lineTrimmed.startsWith("[offset:", ignoreCase = true)) {
                val offStr = lineTrimmed.substringAfter(":").substringBefore("]").trim()
                offsetMs = offStr.toLongOrNull() ?: 0L
                continue
            }

            if (lineTrimmed.startsWith("[ti:") || lineTrimmed.startsWith("[ar:") ||
                lineTrimmed.startsWith("[al:") || lineTrimmed.startsWith("[by:")
            ) {
                continue
            }

            val lineMatch = QRC_LINE_REGEX.find(lineTrimmed)
            if (lineMatch != null) {
                val rawStart = lineMatch.groupValues[1].toLongOrNull() ?: continue
                val lineDurMs = lineMatch.groupValues[2].toLongOrNull() ?: 0L
                val body = lineMatch.groupValues[3]

                val lineStartMs = (rawStart + offsetMs).coerceAtLeast(0L)
                val words = parseQrcWords(body, offsetMs)
                val lineEndMs = if (lineDurMs > 0L) lineStartMs + lineDurMs else null
                val fullText = if (words.isNotEmpty()) words.joinToString("") { it.word }.trim() else body.trim()

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

    private fun parseQrcWords(body: String, offsetMs: Long): List<LyricWord> {
        val words = mutableListOf<LyricWord>()
        val wordSb = StringBuilder()
        var i = 0

        while (i < body.length) {
            if (body[i] == '(') {
                // Check if current position starts with "(start,duration)"
                var commaIdx = -1
                var closeIdx = -1
                var j = i + 1
                while (j < body.length && j < i + 32) {
                    val c = body[j]
                    if (c == ',') {
                        commaIdx = j
                    } else if (c == ')') {
                        closeIdx = j
                        break
                    } else if (!c.isDigit()) {
                        break
                    }
                    j++
                }

                if (commaIdx != -1 && closeIdx != -1 && commaIdx > i + 1 && closeIdx > commaIdx + 1) {
                    val startStr = body.substring(i + 1, commaIdx)
                    val durStr = body.substring(commaIdx + 1, closeIdx)
                    val wStart = startStr.toLongOrNull()
                    val wDur = durStr.toLongOrNull()

                    if (wStart != null && wDur != null) {
                        val wordText = wordSb.toString()
                        wordSb.clear()
                        if (wordText.isNotEmpty()) {
                            val adjStart = (wStart + offsetMs).coerceAtLeast(0L)
                            val validDur = wDur.takeIf { it > 0L }
                            words.add(LyricWord(word = wordText, time = adjStart, duration = validDur))
                        }
                        i = closeIdx + 1
                        continue
                    }
                }
            }
            wordSb.append(body[i])
            i++
        }

        return words
    }

    private fun extractLyricContent(raw: String): String {
        if (!raw.contains("LyricContent=\"")) return raw
        val startIdx = raw.indexOf("LyricContent=\"") + "LyricContent=\"".length
        val endIdx = raw.indexOf("\"", startIdx)
        val content = if (endIdx != -1) raw.substring(startIdx, endIdx) else raw.substring(startIdx)
        return unescapeXml(content)
    }

    private fun unescapeXml(str: String): String {
        return str
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&amp;", "&")
            .replace("&#13;&#10;", "\n")
            .replace("&#10;", "\n")
            .replace("&#13;", "\n")
    }
}
