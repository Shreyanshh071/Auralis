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
     * Parses Musixmatch richsync_body JSON into [LyricsData] with [SyncType.RICHSYNC].
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
                val lineEndMs = if (teSec > tsSec) (teSec * 1000.0).toLong() else lineStartMs + 3000L
                val lineDurationMs = (lineEndMs - lineStartMs).coerceAtLeast(300L)
                val fullText = lineObj.optString("x").trim()

                val lArray = lineObj.optJSONArray("l")
                val words = mutableListOf<LyricWord>()

                if (lArray != null && lArray.length() > 0) {
                    for (j in 0 until lArray.length()) {
                        val tokenObj = lArray.optJSONObject(j) ?: continue
                        val cText = tokenObj.optString("c", "")
                        val offsetSec = tokenObj.optDouble("o", 0.0)

                        val wordStartMs = lineStartMs + (offsetSec * 1000.0).toLong()

                        // Calculate duration based on next token or line end
                        val nextOffsetSec = if (j + 1 < lArray.length()) {
                            lArray.optJSONObject(j + 1)?.optDouble("o", offsetSec) ?: offsetSec
                        } else null

                        val wordDurMs = if (nextOffsetSec != null && nextOffsetSec > offsetSec) {
                            ((nextOffsetSec - offsetSec) * 1000.0).toLong()
                        } else {
                            (lineEndMs - wordStartMs).coerceIn(100L, 2000L)
                        }

                        words.add(
                            LyricWord(
                                word = cText,
                                time = wordStartMs,
                                duration = wordDurMs.coerceAtLeast(50L)
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

            val hasWordSync = lines.any { !it.words.isNullOrEmpty() }

            return LyricsData(
                syncType = if (hasWordSync) SyncType.RICHSYNC else SyncType.LINE_SYNC,
                lines = lines.sortedBy { it.time },
                plainLyrics = lines.joinToString("\n") { it.text },
                provider = provider,
                trackName = trackName,
                artistName = artistName
            )
        } catch (_: Exception) {
            return null
        }
    }
}
