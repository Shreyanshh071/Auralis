package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.json.JSONArray
import org.json.JSONObject

object MusixmatchRichsyncParser {

    /**
     * Parses Musixmatch richsync_body JSON into [LyricsData].
     *
     * Typical structure of richsync_body:
     * [
     *   {
     *     "ts": 12.34,
     *     "te": 15.67,
     *     "x": "Hello world",
     *     "l": [
     *       { "c": "Hello", "o": 0.0 },
     *       { "c": " ", "o": 0.5 },
     *       { "c": "world", "o": 0.6 }
     *     ]
     *   }
     * ]
     *
     * Where the word ends come from: `l` is a **dense partition** of `[ts, te]` —
     * the silence between two sung words is itself a token (usually `" "`) with
     * its own offset. So the next token's `o` is a provider-stated boundary, not a
     * guess, and a rest inside a line survives it: the space token ends the word
     * before it, and the interval from that space to the next word is left
     * unassigned. That is why `nextOffset - offset` is used here and nowhere else.
     *
     * What is *not* derived: when `te` is missing or malformed the final token of
     * the line gets `duration = null` rather than an assumed length. The old code
     * used `ts + 3000ms`, which held the last word of every such line visually for
     * up to three seconds of silence.
     */
    fun parse(
        richsyncBody: String,
        provider: LyricsProvider = LyricsProvider.MUSIXMATCH,
        trackName: String = "",
        artistName: String = ""
    ): LyricsData? {
        if (richsyncBody.isBlank()) return null

        try {
            val jsonArray = if (richsyncBody.trim().startsWith("[")) {
                JSONArray(richsyncBody.trim())
            } else {
                return null
            }

            if (jsonArray.length() == 0) return null

            val lines = mutableListOf<LyricLine>()

            for (i in 0 until jsonArray.length()) {
                val lineObj = jsonArray.optJSONObject(i) ?: continue
                val tsSec = lineObj.optDouble("ts", -1.0)
                val teSec = lineObj.optDouble("te", -1.0)
                if (tsSec < 0) continue

                val lineStartMs = (tsSec * 1000.0).toLong()
                // No fallback: a missing "te" means the line end is unknown, and an
                // unknown end must stay unknown (see the KDoc).
                val lineEndMs: Long? = if (teSec > tsSec) (teSec * 1000.0).toLong() else null
                val fullText = lineObj.optString("x").trim()

                val lArray = lineObj.optJSONArray("l")
                val words = mutableListOf<LyricWord>()

                if (lArray != null && lArray.length() > 0) {
                    for (j in 0 until lArray.length()) {
                        val tokenObj = lArray.optJSONObject(j) ?: continue
                        val cText = tokenObj.optString("c", "")
                        val offsetSec = tokenObj.optDouble("o", 0.0)
                        val tokenStartMs = lineStartMs + (offsetSec * 1000.0).toLong()

                        if (cText.isBlank()) {
                            if (words.isNotEmpty()) {
                                val last = words.last()
                                words[words.size - 1] = last.copy(
                                    word = if (last.word.endsWith(" ")) last.word else "${last.word} "
                                )
                            }
                            continue
                        }

                        // The token stream partitions the line, so the next token's
                        // offset is where this one stops being sung — a value the
                        // provider stated. The last token of the line stops at "te".
                        // Neither is capped and neither is padded: a long value is a
                        // genuinely held note, and the gap that follows a folded
                        // space token is a genuine rest that stays unpainted.
                        val nextOffsetSec = if (j + 1 < lArray.length()) {
                            lArray.optJSONObject(j + 1)?.optDouble("o", offsetSec) ?: offsetSec
                        } else null

                        val wordDurMs: Long? = when {
                            nextOffsetSec != null && nextOffsetSec > offsetSec ->
                                ((nextOffsetSec - offsetSec) * 1000.0).toLong()
                            // Final token of the line: ends at the stated line end,
                            // or nowhere at all if the line never stated one.
                            lineEndMs != null && lineEndMs > tokenStartMs -> lineEndMs - tokenStartMs
                            else -> null
                        }

                        words.add(
                            LyricWord(
                                word = cText,
                                time = tokenStartMs,
                                duration = wordDurMs?.takeIf { it > 0L }
                            )
                        )
                    }
                }

                val resolvedText = if (fullText.isNotBlank()) {
                    fullText
                } else {
                    words.joinToString("") { it.word }.trim()
                }

                if (resolvedText.isNotBlank()) {
                    lines.add(
                        LyricLine(
                            time = lineStartMs,
                            text = resolvedText,
                            words = if (words.isNotEmpty()) words else null
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
