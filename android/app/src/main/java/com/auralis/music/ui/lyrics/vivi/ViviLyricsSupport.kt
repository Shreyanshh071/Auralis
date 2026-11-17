/**
 * Line model and helpers for the lyric styles ported from Vivi Music
 * (https://github.com/vivizzz007/vivi-music, GPL-3.0).
 */
package com.auralis.music.ui.lyrics.vivi

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import kotlinx.coroutines.flow.MutableStateFlow

/** One written word, in seconds, as Vivi's renderers expect. */
data class WordTimestamp(
    val text: String,
    val startTime: Double,
    val endTime: Double
)

class LyricsEntry(
    val time: Long,
    val text: String,
    val words: List<WordTimestamp>? = null,
    val romanizedTextFlow: MutableStateFlow<String?> = MutableStateFlow(null),
    val translatedTextFlow: MutableStateFlow<String?> = MutableStateFlow(null),
    val agent: String? = null,
    val isBackground: Boolean = false,
    /** Index of the source line in the lyrics the player gave us; -1 for the blank head entry. */
    val sourceIndex: Int = -1
) {
    companion object {
        fun head() = LyricsEntry(0L, "")
    }
}

enum class LyricsPosition { LEFT, CENTER, RIGHT;
    companion object {
        fun fromSetting(value: String): LyricsPosition = when (value.lowercase()) {
            "left", "start" -> LEFT
            "right", "end" -> RIGHT
            else -> CENTER
        }
    }
}

/**
 * The raw (un-offset) lyric clock, read every frame by the word renderers that sweep
 * independently of recomposition (Metro).
 */
val LocalViviPlaybackClock = staticCompositionLocalOf<(() -> Long)?> { null }

internal fun isHindiText(text: String): Boolean = text.any { it in 'ऀ'..'ॿ' }

/**
 * Joins source syllables into written words. A span without trailing whitespace belongs to the
 * same word as the next span. A word with no provider end time ends where it starts, so it lights
 * on its start instead of sweeping across a guessed length.
 */
internal fun groupWords(words: List<LyricWord>?): List<WordTimestamp>? {
    if (words.isNullOrEmpty()) return null
    val out = ArrayList<WordTimestamp>()
    val buffer = StringBuilder()
    var start = 0L
    var end = 0L
    fun flush() {
        if (buffer.isNotBlank()) {
            out += WordTimestamp(buffer.toString().trim(), start / 1000.0, end.coerceAtLeast(start) / 1000.0)
        }
        buffer.clear()
    }
    for (word in words) {
        val text = word.word
        if (text.isBlank()) { flush(); continue }
        if (text.first().isWhitespace() && buffer.isNotEmpty()) flush()
        if (buffer.isEmpty()) { start = word.time; end = word.time }
        buffer.append(text.trim())
        end = maxOf(end, word.endTime ?: word.time)
        if (text.last().isWhitespace()) flush()
    }
    flush()
    return out.takeIf { it.isNotEmpty() }
}

private val TranslationNoise = Regex("""[̀-ͯ◌़ऽ]""")

/** Auralis lines to Vivi entries; synced lyrics get Vivi's blank head entry at index 0. */
internal fun toViviEntries(lines: List<LyricLine>, isSynced: Boolean, wordSynced: Boolean): List<LyricsEntry> {
    val entries = lines.mapIndexed { index, line ->
        LyricsEntry(
            time = if (isSynced) line.time else index * 100L,
            text = if (line.isInstrumental) "" else line.text,
            words = if (wordSynced && !line.isInstrumental) groupWords(line.words) else null,
            translatedTextFlow = MutableStateFlow(
                line.translatedText?.takeIf { it.isNotBlank() }?.replace(TranslationNoise, "")?.trim()
            ),
            agent = line.agent,
            isBackground = line.isBackground,
            sourceIndex = index
        )
    }
    return if (isSynced) listOf(LyricsEntry.head()) + entries else entries
}

internal fun findCurrentLineIndex(lines: List<LyricsEntry>, position: Long): Int {
    for (index in lines.indices) {
        if (lines[index].time >= position + 300L) return index - 1
    }
    return lines.lastIndex
}

internal fun findActiveLineIndices(lines: List<LyricsEntry>, position: Long): Set<Int> {
    val active = mutableSetOf<Int>()
    val hasWordTimings = lines.any { !it.words.isNullOrEmpty() }
    for (index in lines.indices) {
        val line = lines[index]
        if (line.time > position) break
        val lineEndMs: Long = if (!line.words.isNullOrEmpty()) {
            (line.words.last().endTime * 1000).toLong()
        } else {
            if (index + 1 < lines.size) lines[index + 1].time else Long.MAX_VALUE
        }
        if (position <= lineEndMs) active.add(index)
    }
    if (!hasWordTimings && active.size > 1) {
        val mainActive = active.filter { !lines[it].isBackground }
        if (mainActive.size > 1) {
            val maxTime = mainActive.maxOf { lines[it].time }
            active.removeAll { it in mainActive && lines[it].time < maxTime }
        }
    }
    return active
}

sealed class LyricsListItem {
    data class Line(val index: Int, val entry: LyricsEntry) : LyricsListItem()
    data class Indicator(
        val afterLineIndex: Int,
        val gapMs: Long,
        val gapStartMs: Long,
        val gapEndMs: Long,
        val nextAgent: String?
    ) : LyricsListItem()
}

internal fun Modifier.viviFadingEdge(top: Dp? = null, bottom: Dp? = null) = graphicsLayer(alpha = 0.99f)
    .drawWithContent {
        drawContent()
        if (top != null) {
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = top.toPx()),
                blendMode = BlendMode.DstIn
            )
        }
        if (bottom != null) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Black, Color.Transparent),
                    startY = size.height - bottom.toPx(),
                    endY = size.height
                ),
                blendMode = BlendMode.DstIn
            )
        }
    }
