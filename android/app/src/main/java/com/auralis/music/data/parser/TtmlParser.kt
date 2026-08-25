package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

object TtmlParser {

    /**
     * Parses TTML XML content into domain [LyricsData].
     */
    fun parse(ttmlXml: String, provider: LyricsProvider = LyricsProvider.AMLL): LyricsData {
        if (ttmlXml.isBlank()) {
            return LyricsData(syncType = SyncType.PLAIN, lines = emptyList(), provider = provider)
        }

        val lines = mutableListOf<LyricLine>()
        var hasWordSync = false

        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(ttmlXml.toByteArray(Charsets.UTF_8)))

            val pNodes = doc.getElementsByTagName("p")
            for (i in 0 until pNodes.length) {
                val pElem = pNodes.item(i) as? Element ?: continue
                val pBegin = pElem.getAttribute("begin")
                val lineTime = parseTimestamp(pBegin)

                val words = mutableListOf<LyricWord>()
                val spanNodes = pElem.getElementsByTagName("span")

                if (spanNodes.length > 0) {
                    val lineSb = StringBuilder()
                    for (j in 0 until spanNodes.length) {
                        val spanElem = spanNodes.item(j) as? Element ?: continue
                        val spanBegin = spanElem.getAttribute("begin")
                        val spanEnd = spanElem.getAttribute("end")
                        val spanText = spanElem.textContent ?: ""

                        val wordStart = if (spanBegin.isNotBlank()) parseTimestamp(spanBegin) else lineTime
                        val wordEnd = if (spanEnd.isNotBlank()) parseTimestamp(spanEnd) else null
                        val dur = if (wordEnd != null && wordEnd > wordStart) wordEnd - wordStart else null

                        words.add(LyricWord(word = spanText, time = wordStart, duration = dur))
                        lineSb.append(spanText)
                        hasWordSync = true
                    }
                    val fullText = lineSb.toString().trim()
                    if (fullText.isNotEmpty()) {
                        lines.add(LyricLine(time = lineTime, text = fullText, words = words.toList()))
                    }
                } else {
                    val fullText = pElem.textContent.trim()
                    if (fullText.isNotEmpty()) {
                        lines.add(LyricLine(time = lineTime, text = fullText, words = null))
                    }
                }
            }
        } catch (e: Exception) {
            // Return collected lines
        }

        val syncType = when {
            hasWordSync -> SyncType.RICHSYNC
            lines.isNotEmpty() -> SyncType.LINE_SYNC
            else -> SyncType.PLAIN
        }

        return LyricsData(
            syncType = syncType,
            lines = lines.sortedBy { it.time },
            provider = provider
        )
    }

    fun parseTimestamp(timestamp: String?): Long {
        if (timestamp.isNullOrBlank()) return 0
        val trimmed = timestamp.trim()

        if (trimmed.endsWith("ms", ignoreCase = true)) {
            return trimmed.removeSuffix("ms").removeSuffix("MS").trim().toLongOrNull() ?: 0
        }
        if (trimmed.endsWith("s", ignoreCase = true)) {
            val sec = trimmed.removeSuffix("s").removeSuffix("S").trim().toDoubleOrNull() ?: 0.0
            return (sec * 1000).toLong()
        }

        val parts = trimmed.split(":")
        return try {
            when (parts.size) {
                1 -> {
                    val sec = parts[0].toDoubleOrNull() ?: 0.0
                    (sec * 1000).toLong()
                }
                2 -> {
                    val min = parts[0].toLongOrNull() ?: 0
                    val sec = parts[1].toDoubleOrNull() ?: 0.0
                    min * 60_000 + (sec * 1000).toLong()
                }
                3 -> {
                    val hr = parts[0].toLongOrNull() ?: 0
                    val min = parts[1].toLongOrNull() ?: 0
                    val sec = parts[2].toDoubleOrNull() ?: 0.0
                    hr * 3_600_000 + min * 60_000 + (sec * 1000).toLong()
                }
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
