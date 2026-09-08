package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.json.JSONArray

object YrcParser {

    private val YRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.*)""")
    private val YRC_WORD_REGEX = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")

    /**
     * Parses NetEase Cloud Music YRC content into [LyricsData].
     *
     * YRC states a start **and** a length per token — `(start,duration,?)` in the
     * bracket dialect, `{"t":…,"d":…}` in the JSON dialect — so both are used
     * verbatim. When a token omits its length the duration stays `null`: the old
     * `300 ms` default made every such token sweep for a third of a second it had
     * no data for, which reads as a highlight running on through silence.
     */
    fun parse(
        yrcContent: String,
        provider: LyricsProvider = LyricsProvider.NETEASE,
        trackName: String = "",
        artistName: String = ""
    ): LyricsData? {
        if (yrcContent.isBlank()) return null

        val trimmed = yrcContent.trim()
        if (trimmed.startsWith("[") && trimmed.contains("\"t\"") && trimmed.contains("\"c\"")) {
            return parseJsonYrc(trimmed, provider, trackName, artistName)
        }

        return parseBracketYrc(trimmed, provider, trackName, artistName)
    }

    private fun parseBracketYrc(
        content: String,
        provider: LyricsProvider,
        trackName: String,
        artistName: String
    ): LyricsData? {
        val lines = mutableListOf<LyricLine>()

        val rawLines = content.lines()
        for (rawLine in rawLines) {
            val lineTrimmed = rawLine.trim()
            if (lineTrimmed.isBlank() || lineTrimmed.startsWith("[ti:") || lineTrimmed.startsWith("[ar:") ||
                lineTrimmed.startsWith("[al:") || lineTrimmed.startsWith("[by:") || lineTrimmed.startsWith("[offset:")
            ) {
                continue
            }

            val lineMatch = YRC_LINE_REGEX.find(lineTrimmed)
            if (lineMatch != null) {
                val lineStartMs = lineMatch.groupValues[1].toLongOrNull() ?: continue
                val lineDurMs = lineMatch.groupValues[2].toLongOrNull() ?: 0L
                val body = lineMatch.groupValues[3]

                val wordMatches = YRC_WORD_REGEX.findAll(body).toList()
                val words = mutableListOf<LyricWord>()
                val lineSb = StringBuilder()

                for (w in wordMatches) {
                    val wStartMs = w.groupValues[1].toLongOrNull() ?: lineStartMs
                    val wDurMs = w.groupValues[2].toLongOrNull()?.takeIf { it > 0L }
                    val wText = w.groupValues[3]

                    words.add(LyricWord(word = wText, time = wStartMs, duration = wDurMs))
                    lineSb.append(wText)
                }

                val lineEndMs = if (lineDurMs > 0L) lineStartMs + lineDurMs else null
                val fullText = lineSb.toString().trim()
                if (fullText.isNotEmpty()) {
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

    private fun parseJsonYrc(
        jsonStr: String,
        provider: LyricsProvider,
        trackName: String,
        artistName: String
    ): LyricsData? {
        try {
            val jsonArray = JSONArray(jsonStr)
            val lines = mutableListOf<LyricLine>()

            for (i in 0 until jsonArray.length()) {
                val lineObj = jsonArray.optJSONObject(i) ?: continue
                val lineStartMs = lineObj.optLong("t", 0L)
                val lineDurMs = if (lineObj.has("d")) lineObj.optLong("d", 0L).takeIf { it > 0L } else null
                val lineEndMs = lineDurMs?.let { lineStartMs + it }
                val cArray = lineObj.optJSONArray("c") ?: continue

                val words = mutableListOf<LyricWord>()
                val lineSb = StringBuilder()

                for (j in 0 until cArray.length()) {
                    val tokenObj = cArray.optJSONObject(j) ?: continue
                    val tx = tokenObj.optString("tx", "")
                    val offset = tokenObj.optLong("t", 0L)
                    // "d" absent means this token's length was never stated. It stays
                    // null; the previous default of 300ms was invented.
                    val dur = if (tokenObj.has("d")) tokenObj.optLong("d", 0L).takeIf { it > 0L } else null

                    val wordStartMs = lineStartMs + offset
                    words.add(LyricWord(word = tx, time = wordStartMs, duration = dur))
                    lineSb.append(tx)
                }

                val fullText = lineSb.toString().trim()
                if (fullText.isNotEmpty()) {
                    lines.add(
                        LyricLine(
                            time = lineStartMs,
                            text = fullText,
                            words = words,
                            endTime = lineEndMs
                        )
                    )
                }
            }

            if (lines.isEmpty()) return null

            val sorted = lines.sortedBy { it.time }

            return LyricsData(
                syncType = if (WordTiming.hasGenuineWordStarts(sorted)) {
                    SyncType.RICHSYNC
                } else {
                    SyncType.LINE_SYNC
                },
                lines = sorted,
                plainLyrics = sorted.joinToString("\n") { it.text },
                provider = provider,
                trackName = trackName,
                artistName = artistName
            )
        } catch (_: Exception) {
            return null
        }
    }
}
