package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType

object BetterLyricsParser {

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
     * Parses Timed Text Markup Language (TTML) word/syllable-level lyrics.
     *
     * Apple Music exports (`itunes:timing="Word"`) give every word an explicit
     * `begin` and `end`, and the space between one word's `end` and the next
     * word's `begin` is a real rest in the vocal. Parsing is delegated to
     * [TtmlParser] so those values survive untouched: nothing here invents an end
     * timestamp, stretches a word across silence, or caps a genuinely held note.
     */
    fun parseTtml(
        ttmlContent: String,
        provider: LyricsProvider = LyricsProvider.BETTER_LYRICS,
        trackName: String? = null,
        artistName: String? = null
    ): LyricsData? {
        val parsed = TtmlParser.parse(ttmlContent, provider)
        if (parsed.lines.isEmpty()) return null

        return parsed.copy(
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
                wordMatches.mapIndexedNotNull { wIdx, wm ->
                    val wStart = wm.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
                    val wDur = wm.groupValues[2].toLongOrNull() ?: 300L
                    var wText = wm.groupValues[3]
                    if (wText.isEmpty()) return@mapIndexedNotNull null

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

    private fun parseMs(msRaw: String?): Long {
        if (msRaw.isNullOrBlank()) return 0L
        return when (msRaw.length) {
            1 -> msRaw.toLong() * 100L
            2 -> msRaw.toLong() * 10L
            else -> msRaw.take(3).toLong()
        }
    }
}
