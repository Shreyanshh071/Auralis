package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType

object LrcParser {

    private val LINE_TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIMESTAMP_REGEX = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>([^<]*)""")
    private val AGENT_REGEX = Regex("""\{agent:([^}]+)\}""")
    private val BACKGROUND_REGEX = Regex("""^\{bg\}""")
    private val METADATA_PREFIX_REGEX = Regex(
        """^(作词|作曲|编曲|制作人|制作|监制|混音|母带|吉他|贝斯|鼓|键盘|录音|和音|合音|企划|统筹|出品|发行|封面|弦乐|长笛|萨克斯|演唱|原唱|词|曲|OP|SP|Written\s+by|Composed\s+by|Produced\s+by|Lyrics\s+by|Music\s+by|Arranged\s+by|Mixed\s+by|Mastered\s+by|Recorded\s+by|Vocals\s+by|Vocal\s+by|Performed\s+by|Credits|Publisher|Release|Source|Transcribed\s+by|Translated\s+by)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )

    fun isMetadataOrCreditLine(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return true
        if (trimmed.matches(Regex("""^[\s\-=_~*#\/\\|♪♫♩♬….]+$"""))) return true
        if (METADATA_PREFIX_REGEX.containsMatchIn(trimmed)) return true
        if (trimmed.startsWith("by:", ignoreCase = true) || trimmed.startsWith("by :", ignoreCase = true)) return true
        return false
    }

    /**
     * Parses LRC text (standard line sync or enhanced rich sync) into [LyricsData].
     */
    fun parse(lrcContent: String, provider: LyricsProvider = LyricsProvider.LRCLIB): LyricsData {
        if (lrcContent.isBlank()) {
            return LyricsData(syncType = SyncType.PLAIN, lines = emptyList(), provider = provider)
        }

        val lines = mutableListOf<LyricLine>()
        var globalLrcOffsetMs = 0L
        var parsedDurationMs: Long? = null

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
            if (trimmed.startsWith("[length:", ignoreCase = true)) {
                val numStr = trimmed.substringAfter(":").substringBefore("]").trim()
                val parsed = TtmlParser.parseTimestamp(numStr)
                if (parsed > 0L) {
                    parsedDurationMs = parsed
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
            var textPart = trimmed.substring(lastMatch.range.last + 1).trim()

            // Parse optional provider agent {agent:v1} and background {bg} tags if present
            val agentMatch = AGENT_REGEX.find(textPart)
            val agent = agentMatch?.groupValues?.get(1)
            if (agentMatch != null) {
                textPart = textPart.replaceFirst(AGENT_REGEX, "").trim()
            }
            val isBackground = BACKGROUND_REGEX.containsMatchIn(textPart) || textPart.startsWith("{bg}") || textPart.startsWith("(bg)")
            if (isBackground) {
                textPart = textPart.replaceFirst(BACKGROUND_REGEX, "").removePrefix("{bg}").removePrefix("(bg)").trim()
            }

            // Check for enhanced word-level sync: <00:12.34>Word <00:13.00>Word2
            //
            // Enhanced LRC states word STARTS only — the format has no per-word end.
            // The next tag's time is a segment boundary, not a measured vocal end, so
            // no duration is recorded here: an assumed end would paint the highlight
            // straight through any rest between the two words. With `duration = null`
            // the renderer flips each word at its genuine start instead of sweeping
            // across time it has no data for (see LyricWord / LyricsEngine).
            val wordMatches = WORD_TIMESTAMP_REGEX.findAll(textPart).toList()
            val words = if (wordMatches.isNotEmpty()) {
                val list = mutableListOf<LyricWord>()
                for (wIdx in wordMatches.indices) {
                    val w = wordMatches[wIdx]
                    val wMin = w.groupValues[1].toLongOrNull() ?: 0
                    val wSec = w.groupValues[2].toLongOrNull() ?: 0
                    val wMsRaw = w.groupValues[3]
                    val wMs = parseMs(wMsRaw)
                    val wTime = (wMin * 60_000 + wSec * 1_000 + wMs - globalLrcOffsetMs).coerceAtLeast(0)
                    var wordText = w.groupValues[4]
                    if (wIdx < wordMatches.size - 1) {
                        val nextW = wordMatches[wIdx + 1]
                        val nextText = nextW.groupValues[4]
                        val prevLast = wordText.lastOrNull()
                        val nextFirst = nextText.firstOrNull()
                        if (!wordText.endsWith(" ") && !nextText.startsWith(" ") &&
                            prevLast != null && nextFirst != null &&
                            prevLast.isLetterOrDigit() && nextFirst.isLetterOrDigit()
                        ) {
                            wordText = "$wordText "
                        }
                    }
                    list.add(LyricWord(word = wordText, time = wTime, duration = null, isBackground = isBackground))
                }
                list
            } else null

            val cleanLineText = if (words != null) {
                words.joinToString("") { it.word }.trim()
            } else {
                textPart
            }

            if (isMetadataOrCreditLine(cleanLineText)) {
                continue
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
                        words = words,
                        isBackground = isBackground,
                        agent = agent
                    )
                )
            }
        }

        val sorted = lines.sortedBy { it.time }

        // Enhanced LRC states word starts and no ends, so it is word-synced in the
        // step sense but not the sweep sense — [WordTiming.hasGenuineWordStarts] is
        // the right question. A word list whose entries all share one timestamp
        // (a token split of the line text) answers false and stays line-synced.
        val syncType = when {
            WordTiming.hasGenuineWordStarts(sorted) -> SyncType.RICHSYNC
            sorted.isNotEmpty() -> SyncType.LINE_SYNC
            else -> SyncType.PLAIN
        }

        return LyricsData(
            syncType = syncType,
            lines = sorted,
            plainLyrics = sorted.joinToString("\n") { it.text },
            provider = provider,
            durationMs = parsedDurationMs
        )
    }

    /**
     * Intelligently groups rapid 1-3 word phrase fragments into natural, complete poetic lines
     * with word-level timing preserved, matching Metrolist and Apple Music display style.
     *
     * Word timing survives a merge only when **every** fragment folded into a line
     * supplied its own. A fragment that arrived with `words == null` states one
     * timestamp for its whole text; splitting that text and stamping the line's
     * time onto each token would manufacture word starts the source never gave,
     * so such a merged line is emitted with `words = null` and stays line-synced.
     */
    fun mergeMicroFragments(rawLines: List<LyricLine>): List<LyricLine> {
        if (rawLines.size <= 2) return rawLines

        val result = mutableListOf<LyricLine>()
        var currentMergedTime = rawLines[0].time
        val currentWords = mutableListOf<LyricWord>()
        val currentTokens = mutableListOf<String>()
        var currentWordsComplete = true
        // Gap measurement used to read the last fabricated word's timestamp, which
        // was just the previous fragment's line time. Tracked directly now that no
        // words are fabricated, so the merge heuristics behave identically.
        var lastFragmentTime = rawLines[0].time

        fun flush() {
            if (currentTokens.isNotEmpty()) {
                val fullText = currentTokens.joinToString(" ")
                val wordsList = if (currentWordsComplete && currentWords.isNotEmpty()) {
                    currentWords.toList()
                } else {
                    null
                }
                result.add(LyricLine(time = currentMergedTime, text = fullText, words = wordsList))
                currentTokens.clear()
                currentWords.clear()
                currentWordsComplete = true
            }
        }

        for (i in rawLines.indices) {
            val line = rawLines[i]
            val text = line.text.trim()
            if (text.isBlank() || isMetadataOrCreditLine(text)) continue

            val lineWords = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
            val prevTime = if (currentWords.isNotEmpty()) currentWords.last().time else lastFragmentTime
            val timeGap = line.time - prevTime

            val firstWord = lineWords.firstOrNull() ?: ""
            val isContinuationWord = firstWord.isNotEmpty() && (
                firstWord[0].isLowerCase() ||
                firstWord.lowercase() in listOf("to", "the", "if", "for", "and", "or", "in", "on", "at", "of", "with", "you", "me", "we", "my", "your")
            )
            val isRepeatPhrase = currentTokens.size in 1..3 && lineWords.size in 1..3 &&
                    currentTokens.joinToString(" ").lowercase().trimEnd(',', '.', '!', '?') == lineWords.joinToString(" ").lowercase().trimEnd(',', '.', '!', '?')

            val shouldMerge = currentTokens.isNotEmpty() &&
                    (
                        // 1. Exact phrase repetition (e.g. "Slow down" -> "Slow down" or "Hold on" -> "Hold on")
                        (isRepeatPhrase && timeGap in 100..1600) ||
                        // 2. Intra-sentence short phrase fragment (< 1.45s gap)
                        (timeGap in 100..1450 && currentTokens.size + lineWords.size <= 7) ||
                        // 3. Grammatical continuation (starts with "to", "if", "for", lowercase word, etc.)
                        (isContinuationWord && timeGap in 100..1650 && currentTokens.size + lineWords.size <= 8)
                    ) &&
                    (line.time - currentMergedTime) <= 4500L &&
                    !currentTokens.last().endsWith("?") &&
                    !currentTokens.last().endsWith("!") &&
                    !currentTokens.last().endsWith(".")

            if (shouldMerge) {
                val lastToken = currentTokens.last()
                if (currentTokens.size <= 3 && !lastToken.endsWith(",") && !lastToken.endsWith("?") && !lastToken.endsWith("!")) {
                    currentTokens[currentTokens.size - 1] = "$lastToken,"
                }
                currentTokens.addAll(lineWords)
                if (line.words != null) {
                    currentWords.addAll(line.words)
                } else {
                    currentWordsComplete = false
                }
            } else {
                flush()
                currentMergedTime = line.time
                currentTokens.addAll(lineWords)
                if (line.words != null) {
                    currentWords.addAll(line.words)
                } else {
                    currentWordsComplete = false
                }
            }
            lastFragmentTime = line.time
        }
        flush()
        return result
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
