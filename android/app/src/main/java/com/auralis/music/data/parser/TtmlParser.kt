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
 *  - `ttm:role="x-translation"` becomes the line translation and `x-roman` is
 *    ignored. `x-bg` (background ad-libs) is emitted as its **own** line flagged
 *    [LyricLine.isBackground], never folded into the lead vocal: an ad-lib runs
 *    concurrently with the lead, so merging the two would corrupt both the text
 *    and the word order of the line the listener is reading.
 */
object TtmlParser {

    private const val ROLE_TRANSLATION = "x-translation"
    private const val ROLE_ROMAN = "x-roman"
    private const val ROLE_BACKGROUND = "x-bg"

    private val NON_LEAD_ROLES = setOf(ROLE_TRANSLATION, ROLE_ROMAN, ROLE_BACKGROUND)

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

        // Keep only what the file actually stated. A word list that says no more
        // than the line timestamp already did (one shared start, no ends) is a
        // token split, not word sync, and is dropped so nothing downstream can
        // mistake it for timing. See [WordTiming].
        val carriesWordInfo = WordTiming.statesMoreThanLineTime(sorted)

        // Apple Music and AMLL exports do emit multi-word spans. Splitting one
        // proportionally by character count stays strictly inside the interval the
        // file measured, so it invents no timing — the only derivation permitted.
        val resolved = if (carriesWordInfo) {
            WordTiming.subdivideLines(sorted)
        } else {
            sorted.map { it.copy(words = null) }
        }

        val syncType = when {
            carriesWordInfo -> SyncType.RICHSYNC
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
        val currentBgSyllables = mutableListOf<Syllable>()
        val currentTranslation = StringBuilder()
        val currentPlainText = StringBuilder()
        val pBegin = attr(p, "begin").takeIf { it.isNotBlank() }?.let { parseTimestamp(it) }

        fun flushLine() {
            val hasLead = currentSyllables.isNotEmpty() || currentPlainText.isNotBlank()
            val hasBackground = currentBgSyllables.isNotEmpty()
            if (!hasLead && !hasBackground) return

            val (lineText, normalizedWords) = buildLineTextAndWords(currentSyllables, currentPlainText.toString())
            if (lineText.isNotBlank()) {
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
            }

            // The ad-lib becomes a sibling line at its own start time, so the lead
            // line above keeps exactly the text and word order the file gave it.
            if (hasBackground) {
                val (bgText, bgWords) = buildLineTextAndWords(currentBgSyllables, "")
                if (bgText.isNotBlank()) {
                    resultLines.add(
                        LyricLine(
                            time = currentBgSyllables.minOfOrNull { it.start } ?: 0L,
                            text = bgText,
                            words = bgWords?.map { it.copy(isBackground = true) },
                            isBackground = true
                        )
                    )
                }
            }

            currentSyllables.clear()
            currentBgSyllables.clear()
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
                        ROLE_ROMAN -> Unit
                        ROLE_BACKGROUND -> collectSyllables(child, currentBgSyllables, currentBgSyllables)
                        else -> {
                            collectSyllables(child, currentSyllables, currentBgSyllables)
                            currentPlainText.append(leadTextContent(child))
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
        for (syl in syllables) {
            wordList.add(
                LyricWord(
                    word = syl.text,
                    time = syl.start,
                    duration = genuineDuration(syl)
                )
            )
        }

        // XML pretty-printing puts a newline between the last span and `</p>`,
        // which reaches the final syllable as a trailing space. The line text is
        // trimmed, so the word list is trimmed to match: concatenating the words
        // has to reproduce the line text exactly, or a renderer measuring words
        // and a renderer measuring the line disagree about where the line ends.
        wordList[0] = wordList[0].let { it.copy(word = it.word.trimStart()) }
        wordList[wordList.lastIndex] = wordList.last().let { it.copy(word = it.word.trimEnd()) }
        wordList.removeAll { it.word.isEmpty() }

        val finalText = wordList.joinToString("") { it.word }
        return Pair(finalText, wordList.takeIf { it.isNotEmpty() })
    }

    /**
     * A span either carries its own timing, or wraps timed child spans.
     *
     * [background] receives any nested `x-bg` group, so an ad-lib written inside a
     * lead span reaches the background lane instead of being dropped.
     */
    private fun collectSyllables(
        span: Element,
        out: MutableList<Syllable>,
        background: MutableList<Syllable>? = null
    ) {
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
                            ROLE_TRANSLATION, ROLE_ROMAN -> Unit
                            ROLE_BACKGROUND ->
                                if (background != null) collectSyllables(inner, background, background) else Unit
                            else -> collectSyllables(inner, out, background)
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

    /**
     * Text of a node excluding nested non-lead roles, so a translation or an
     * ad-lib written inside a lead span cannot leak into the lead line's text.
     */
    private fun leadTextContent(node: Node): String {
        val first = node.firstChild ?: return node.textContent ?: ""
        val sb = StringBuilder()
        var child: Node? = first
        while (child != null) {
            when {
                child.nodeType == Node.TEXT_NODE -> sb.append(child.textContent ?: "")
                child is Element && localName(child) == "span" && role(child) in NON_LEAD_ROLES -> Unit
                else -> sb.append(leadTextContent(child))
            }
            child = child.nextSibling
        }
        return sb.toString()
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
