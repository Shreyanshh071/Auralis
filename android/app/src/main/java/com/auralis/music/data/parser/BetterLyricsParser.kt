package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType

object BetterLyricsParser {

    private val TTML_P_REGEX = Regex("""<p\s+[^>]*begin="([^"]+)"(?:\s+end="([^"]+)")?[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val TTML_SPAN_REGEX = Regex("""<span\s+[^>]*begin="([^"]+)"(?:\s+end="([^"]+)")?[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)

    private val QRC_LINE_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\](.*)""")
    private val QRC_WORD_REGEX = Regex("""\((\d+),(\d+)\)([^(]*)""")

    /**
     * Parses Better Lyrics TTML XML or QRC format into syllable-synchronized [LyricsData].
     */
    fun parse(
        content: String,
        provider: LyricsProvider = LyricsProvider.BETTER_LYRICS,
        trackName: String? = null,
        artistName: String? = null
    ): LyricsData? {
        if (content.isBlank()) return null

        val trimmed = content.trim()
        return when {
            trimmed.contains("<tt") || trimmed.contains("<p ") || trimmed.contains("<span ") -> {
                parseTtml(trimmed, provider, trackName, artistName)
            }
            trimmed.contains("(") && QRC_WORD_REGEX.containsMatchIn(trimmed) -> {
                parseQrc(trimmed, provider, trackName, artistName)
            }
            else -> {
                // Fallback to standard LRC parsing
                val parsedLrc = LrcParser.parse(trimmed, provider)
                parsedLrc.copy(trackName = trackName, artistName = artistName)
            }
        }
    }

    /**
     * Parses Timed Text Markup Language (TTML) syllable-level lyrics.
     * Example: <p begin="00:12.340" end="00:15.600"><span begin="00:12.340" end="00:12.800">Hello </span><span begin="00:12.800" end="00:13.200">World</span></p>
     */
    fun parseTtml(
        ttmlContent: String,
        provider: LyricsProvider = LyricsProvider.BETTER_LYRICS,
        trackName: String? = null,
        artistName: String? = null
    ): LyricsData? {
        val lines = mutableListOf<LyricLine>()
        val pMatches = TTML_P_REGEX.findAll(ttmlContent).toList()

        for (pMatch in pMatches) {
            val pBeginStr = pMatch.groupValues[1]
            val pLineTime = parseTimestampToMs(pBeginStr)
            val innerContent = pMatch.groupValues[3]

            val spanMatches = TTML_SPAN_REGEX.findAll(innerContent).toList()
            val words = if (spanMatches.isNotEmpty()) {
                spanMatches.mapNotNull { sMatch ->
                    val sBeginStr = sMatch.groupValues[1]
                    val sEndStr = sMatch.groupValues[2]
                    val rawWord = sMatch.groupValues[3].replace(Regex("<[^>]*>"), "")
                    if (rawWord.isEmpty()) return@mapNotNull null

                    val sBegin = parseTimestampToMs(sBeginStr)
                    val sEnd = if (sEndStr.isNotBlank()) parseTimestampToMs(sEndStr) else sBegin + 300L
                    val duration = (sEnd - sBegin).coerceAtLeast(50L)

                    LyricWord(
                        word = rawWord,
                        time = sBegin,
                        duration = duration
                    )
                }
            } else {
                null
            }

            val fullText = if (!words.isNullOrEmpty()) {
                words.joinToString("") { it.word }.trim()
            } else {
                innerContent.replace(Regex("<[^>]*>"), "").trim()
            }

            if (fullText.isNotBlank()) {
                lines.add(
                    LyricLine(
                        time = pLineTime,
                        text = fullText,
                        words = words
                    )
                )
            }
        }

        if (lines.isEmpty()) return null

        val sorted = lines.sortedBy { it.time }
        val hasRichWords = sorted.any { !it.words.isNullOrEmpty() }

        return LyricsData(
            syncType = if (hasRichWords) SyncType.RICHSYNC else SyncType.LINE_SYNC,
            lines = sorted,
            plainLyrics = sorted.joinToString("\n") { it.text },
            provider = provider,
            trackName = trackName,
            artistName = artistName
        )
    }

    /**
     * Parses QQ Music QRC format.
     * Example: [00:12.34](12340,460)Hello (12800,400)World
     */
    fun parseQrc(
        qrcContent: String,
        provider: LyricsProvider = LyricsProvider.BETTER_LYRICS,
        trackName: String? = null,
        artistName: String? = null
    ): LyricsData? {
        val lines = mutableListOf<LyricLine>()
        val rawLines = qrcContent.lines()

        for (rawLine in rawLines) {
            val trimmed = rawLine.trim()
            if (trimmed.isBlank() || trimmed.startsWith("[ar:") || trimmed.startsWith("[ti:") || trimmed.startsWith("[offset:")) {
                continue
            }

            val lineMatch = QRC_LINE_REGEX.find(trimmed) ?: continue
            val min = lineMatch.groupValues[1].toLongOrNull() ?: 0L
            val sec = lineMatch.groupValues[2].toLongOrNull() ?: 0L
            val ms = parseMs(lineMatch.groupValues[3])
            val lineTime = min * 60_000L + sec * 1_000L + ms

            val rest = lineMatch.groupValues[4]
            val wordMatches = QRC_WORD_REGEX.findAll(rest).toList()
            val words = if (wordMatches.isNotEmpty()) {
                wordMatches.mapNotNull { wm ->
                    val wStart = wm.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                    val wDur = wm.groupValues[2].toLongOrNull() ?: 300L
                    val wText = wm.groupValues[3]
                    if (wText.isEmpty()) return@mapNotNull null

                    LyricWord(
                        word = wText,
                        time = wStart,
                        duration = wDur.coerceAtLeast(50L)
                    )
                }
            } else null

            val text = if (!words.isNullOrEmpty()) {
                words.joinToString("") { it.word }.trim()
            } else {
                rest.replace(Regex("""\(\d+,\d+\)"""), "").trim()
            }

            if (text.isNotBlank()) {
                lines.add(
                    LyricLine(
                        time = lineTime,
                        text = text,
                        words = words
                    )
                )
            }
        }

        if (lines.isEmpty()) return null

        val sorted = lines.sortedBy { it.time }
        val hasRichWords = sorted.any { !it.words.isNullOrEmpty() }

        return LyricsData(
            syncType = if (hasRichWords) SyncType.RICHSYNC else SyncType.LINE_SYNC,
            lines = sorted,
            plainLyrics = sorted.joinToString("\n") { it.text },
            provider = provider,
            trackName = trackName,
            artistName = artistName
        )
    }

    private fun parseTimestampToMs(timeStr: String): Long {
        val trimmed = timeStr.trim()
        // Format 1: "00:12.340" or "01:23:45.670" or "12.340s" or "12340ms"
        if (trimmed.endsWith("ms", ignoreCase = true)) {
            return trimmed.removeSuffix("ms").removeSuffix("MS").trim().toLongOrNull() ?: 0L
        }
        if (trimmed.endsWith("s", ignoreCase = true)) {
            val sec = trimmed.removeSuffix("s").removeSuffix("S").trim().toDoubleOrNull() ?: 0.0
            return (sec * 1000).toLong()
        }

        val parts = trimmed.split(":")
        return when (parts.size) {
            2 -> {
                val min = parts[0].toLongOrNull() ?: 0L
                val secParts = parts[1].split(".")
                val sec = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) parseMs(secParts[1]) else 0L
                min * 60_000L + sec * 1_000L + ms
            }
            3 -> {
                val hr = parts[0].toLongOrNull() ?: 0L
                val min = parts[1].toLongOrNull() ?: 0L
                val secParts = parts[2].split(".")
                val sec = secParts[0].toLongOrNull() ?: 0L
                val ms = if (secParts.size > 1) parseMs(secParts[1]) else 0L
                hr * 3600_000L + min * 60_000L + sec * 1_000L + ms
            }
            else -> {
                val sec = trimmed.toDoubleOrNull() ?: 0.0
                (sec * 1000).toLong()
            }
        }
    }

    private fun parseMs(msRaw: String?): Long {
        if (msRaw.isNullOrBlank()) return 0L
        return when (msRaw.length) {
            1 -> msRaw.toLong() * 100L
            2 -> msRaw.toLong() * 10L
            else -> msRaw.take(3).toLong()
        }
    }
}
