package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Structure-aware TTML parser for word/syllable synchronized lyrics
 * (Apple Music `itunes:timing="Word"` exports, AMLL TTML DB, NetEase TTML mirrors).
 *
 * Timing rules — these are the point of this parser:
 *  - A syllable is kept only with the `begin` the file states. Its length comes
 *    from the file's own `end`; when `end` is missing the duration stays `null`
 *    rather than being stretched to the next syllable, because that gap may be a
 *    real vocal rest (see [LyricWord]).
 *  - Whitespace between spans lives in the text (`"lights "`), so the renderer
 *    prints words verbatim and syllables of one word stay glued together.
 *  - Only direct children of `<p>` are read. Nested spans are descended into
 *    explicitly, so a background/translation wrapper can never be flattened into
 *    the main line and duplicate its text.
 *  - `ttm:role="x-translation"` becomes the line translation, `x-roman` is
 *    ignored, and `x-bg` (background ad-libs) is skipped — the player has no
 *    separate background lane, and folding ad-libs into the lead line would
 *    corrupt both its text and its timing.
 */
object TtmlParser {

    private const val ROLE_TRANSLATION = "x-translation"
    private const val ROLE_ROMAN = "x-roman"
    private const val ROLE_BACKGROUND = "x-bg"

    private class Syllable(
        val text: String,
        val start: Long,
        val end: Long?
    )

    /**
     * Parses TTML XML content into domain [LyricsData].
     */
    fun parse(ttmlXml: String, provider: LyricsProvider = LyricsProvider.AMLL): LyricsData {
        if (ttmlXml.isBlank()) {
            return LyricsData(syncType = SyncType.PLAIN, lines = emptyList(), provider = provider)
        }

        val lines = mutableListOf<LyricLine>()

        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { factory.isExpandEntityReferences = false }

            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(ttmlXml.toByteArray(Charsets.UTF_8)))

            val pNodes = doc.getElementsByTagName("p")
            for (i in 0 until pNodes.length) {
                val pElem = pNodes.item(i) as? Element ?: continue
                val parsedLines = parseParagraph(pElem)
                lines.addAll(parsedLines)
            }
        } catch (_: Exception) {
            // Keep whatever was collected before the document went bad
        }

        val sorted = lines.sortedBy { it.time }

        // Word timing is only real when the file actually stated at least one word end.
        // Otherwise the words carry starts alone, which cannot drive a karaoke sweep,
        // so the track is presented as line-synchronized instead of pretending.
        val hasGenuineWordTiming = sorted.any { line -> line.words?.any { it.duration != null } == true }

        val resolved = if (hasGenuineWordTiming) sorted else sorted.map { it.copy(words = null) }

        val syncType = when {
            hasGenuineWordTiming -> SyncType.RICHSYNC
            resolved.isNotEmpty() -> SyncType.LINE_SYNC
            else -> SyncType.PLAIN
        }

        return LyricsData(
            syncType = syncType,
            lines = resolved,
            plainLyrics = resolved.joinToString("\n") { it.text }.ifBlank { null },
            provider = provider
        )
    }

    private fun parseParagraph(p: Element): List<LyricLine> {
        val resultLines = mutableListOf<LyricLine>()
        val currentSyllables = mutableListOf<Syllable>()
        val currentTranslation = StringBuilder()
        val currentPlainText = StringBuilder()
        val pBegin = attr(p, "begin").takeIf { it.isNotBlank() }?.let { parseTimestamp(it) }

        fun flushLine() {
            if (currentSyllables.isEmpty() && currentPlainText.isBlank()) return

            val (lineText, normalizedWords) = buildLineTextAndWords(currentSyllables, currentPlainText.toString())
            if (lineText.isBlank()) {
                currentSyllables.clear()
                currentPlainText.clear()
                currentTranslation.clear()
                return
            }

            val lineTime = currentSyllables.minOfOrNull { it.start }
                ?: (if (resultLines.isEmpty()) pBegin else null)
                ?: 0L

            resultLines.add(
                LyricLine(
                    time = lineTime,
                    text = lineText,
                    translatedText = currentTranslation.toString().trim().ifBlank { null },
                    words = normalizedWords
                )
            )

            currentSyllables.clear()
            currentPlainText.clear()
            currentTranslation.clear()
        }

        var child: Node? = p.firstChild
        while (child != null) {
            when {
                child.nodeType == Node.TEXT_NODE -> {
                    val raw = child.textContent ?: ""
                    if (raw.isNotBlank()) {
                        if (raw.contains("\n")) {
                            val subParts = raw.split("\n")
                            for (idx in subParts.indices) {
                                val part = subParts[idx]
                                if (part.isNotBlank()) {
                                    currentPlainText.append(part)
                                    appendToLast(currentSyllables, part)
                                }
                                if (idx < subParts.size - 1) {
                                    flushLine()
                                }
                            }
                        } else {
                            currentPlainText.append(raw)
                            appendToLast(currentSyllables, raw)
                        }
                    } else if (raw.isNotEmpty() && currentSyllables.isNotEmpty()) {
                        // Preserve space between spans if there was spacing
                        appendToLast(currentSyllables, " ")
                    }
                }

                child is Element && (localName(child) == "br" || localName(child) == "break") -> {
                    flushLine()
                }

                child is Element && localName(child) == "span" -> {
                    when (role(child)) {
                        ROLE_TRANSLATION -> currentTranslation.append(child.textContent ?: "")
                        ROLE_ROMAN, ROLE_BACKGROUND -> Unit
                        else -> {
                            collectSyllables(child, currentSyllables)
                            currentPlainText.append(child.textContent ?: "")
                        }
                    }
                }
            }
            child = child.nextSibling
        }

        flushLine()
        return resultLines
    }

    private fun buildLineTextAndWords(
        syllables: List<Syllable>,
        fallbackPlainText: String
    ): Pair<String, List<LyricWord>?> {
        if (syllables.isEmpty()) {
            val text = fallbackPlainText.trim()
            return Pair(text, null)
        }

        val wordList = mutableListOf<LyricWord>()
        val lineSb = StringBuilder()

        for (i in syllables.indices) {
            val syl = syllables[i]
            val sylText = syl.text

            lineSb.append(sylText)
            wordList.add(
                LyricWord(
                    word = sylText,
                    time = syl.start,
                    duration = genuineDuration(syl)
                )
            )
        }

        val finalText = lineSb.toString().trim()
        return Pair(finalText, wordList.takeIf { it.isNotEmpty() })
    }

    private fun isLatinScript(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c.code in 0x00C0..0x024F

    /** A span either carries its own timing, or wraps timed child spans. */
    private fun collectSyllables(span: Element, out: MutableList<Syllable>) {
        val nestedTimedSpans = mutableListOf<Element>()
        var child: Node? = span.firstChild
        while (child != null) {
            if (child is Element && localName(child) == "span" && role(child) != ROLE_TRANSLATION && role(child) != ROLE_ROMAN) {
                nestedTimedSpans.add(child)
            }
            child = child.nextSibling
        }

        if (nestedTimedSpans.isNotEmpty()) {
            var inner: Node? = span.firstChild
            while (inner != null) {
                when {
                    inner.nodeType == Node.TEXT_NODE -> appendToLast(out, inner.textContent ?: "")

                    inner is Element && localName(inner) == "span" -> {
                        when (role(inner)) {
                            ROLE_TRANSLATION, ROLE_ROMAN, ROLE_BACKGROUND -> Unit
                            else -> collectSyllables(inner, out)
                        }
                    }
                }
                inner = inner.nextSibling
            }
            return
        }

        val text = span.textContent ?: ""
        if (text.isEmpty()) return
        val begin = attr(span, "begin")
        if (begin.isBlank()) return

        val start = parseTimestamp(begin)
        val endAttr = attr(span, "end")
        val end = if (endAttr.isBlank()) null else parseTimestamp(endAttr)
        out.add(Syllable(text, start, end))
    }

    /** Attaches loose text (spacing, punctuation) to the syllable it follows. */
    private fun appendToLast(out: MutableList<Syllable>, raw: String) {
        if (raw.isEmpty() || out.isEmpty()) return
        val normalized = if (raw.isBlank()) " " else raw
        val last = out.removeAt(out.size - 1)
        if (last.text.endsWith(normalized)) {
            out.add(last)
        } else {
            out.add(Syllable(last.text + normalized, last.start, last.end))
        }
    }

    private fun genuineDuration(syllable: Syllable): Long? {
        val end = syllable.end ?: return null
        val dur = end - syllable.start
        return if (dur > 0L) dur else null
    }

    private fun localName(el: Element): String =
        (el.localName ?: el.nodeName).substringAfterLast(':')

    /** Reads an attribute regardless of the namespace prefix the export chose. */
    private fun attr(el: Element, name: String): String {
        el.getAttribute(name).let { if (it.isNotEmpty()) return it }
        val attrs = el.attributes ?: return ""
        for (i in 0 until attrs.length) {
            val a = attrs.item(i) ?: continue
            if (a.nodeName.substringAfterLast(':') == name) {
                return a.nodeValue ?: ""
            }
        }
        return ""
    }

    private fun role(el: Element): String = attr(el, "role").trim().lowercase()

    fun parseTimestamp(timestamp: String?): Long {
        if (timestamp.isNullOrBlank()) return 0
        val trimmed = timestamp.trim()

        if (trimmed.endsWith("ms", ignoreCase = true)) {
            return trimmed.dropLast(2).trim().toDoubleOrNull()?.toLong() ?: 0
        }
        if (trimmed.endsWith("s", ignoreCase = true)) {
            val sec = trimmed.dropLast(1).trim().toDoubleOrNull() ?: 0.0
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
