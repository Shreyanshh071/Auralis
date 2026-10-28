package com.auralis.music.ui.lyrics

import com.auralis.music.domain.model.LyricLine
import kotlin.math.abs

/** Presentation measurements from Spicy's regular (non-simple) renderer.
 * CSS shadow blur is a diameter-like radius; the native blur effect takes sigma.
 * Kept separate from playback timing and other animation styles. */
object SpicyLyricsPresentation {
    const val BACKGROUND_SIZE = .70f
    const val LINE_HEIGHT = 1.1818182f
    const val LEAD_ALPHA = .85f
    const val BACKGROUND_ALPHA = 1f
    const val UNSUNG_ALPHA = .35f

    fun blurSigmaPx(index: Int, activeIndex: Int): Float =
        if (activeIndex < 0) 0f else minOf(abs(index - activeIndex) * 1.25f, 6.83125f) / 2f

    /** Backing vocals stay attached to their lead instead of pulling the scroll anchor down. */
    fun leadIndex(lines: List<LyricLine>, index: Int): Int {
        if (index !in lines.indices) return index
        var result = index
        while (result > 0 && lines[result].isBackground) result--
        return result
    }

    internal fun presentationEnd(lines: List<LyricLine>, index: Int): Long? {
        val line = lines[index]
        // A provider's paragraph end can precede a held harmony/syllable end.
        // Keep every genuine timed vocal alive until its own final word finishes.
        val wordEnd = line.words?.mapNotNull { word ->
            listOfNotNull(word.endTime, word.syllables?.mapNotNull { it.endTime }?.maxOrNull()).maxOrNull()
        }?.maxOrNull()
        listOfNotNull(line.endTime, wordEnd).maxOrNull()?.let { return it }
        // LRC supplies starts, not ends. Highlight the whole line until the next
        // distinct timestamp; never manufacture word timing or extend a timed vocal rest.
        if (!line.words.isNullOrEmpty()) return null
        return lines.asSequence().drop(index + 1).firstOrNull { it.time > line.time }?.time ?: Long.MAX_VALUE
    }

    fun activeIndices(lines: List<LyricLine>, positionMs: Long): Set<Int> = SpicyVocalTimeline(lines).activeIndices(positionMs)

    fun scrollAnchor(lines: List<LyricLine>, positionMs: Long, fallback: Int): Int {
        // Follow the newest started lead, even when an earlier harmony outlasts
        // it. Ending one of several active lines must never scroll backwards.
        // A seek still resolves entirely from its requested playback timestamp.
        val latest = lines.indexOfLast { it.time <= positionMs && !it.isBackground }
        return if (latest >= 0) latest else leadIndex(lines, fallback)
    }
}

/** Resolve nested syllable ends once per lyric delivery, never once per frame
 * for each simultaneously displayed panel. */
class SpicyVocalTimeline(private val lines: List<LyricLine>) {
    private val ends = lines.indices.map { SpicyLyricsPresentation.presentationEnd(lines, it) }

    fun activeIndices(positionMs: Long): Set<Int> = lines.indices.filterTo(linkedSetOf()) {
        val end = ends[it]
        positionMs >= lines[it].time && end != null && positionMs < end
    }
}
