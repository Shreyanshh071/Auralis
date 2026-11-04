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

    private fun presentationEnd(lines: List<LyricLine>, index: Int): Long? {
        val line = lines[index]
        line.effectiveEndTime?.let { return it }
        // LRC supplies starts, not ends. Highlight the whole line until the next
        // distinct timestamp; never manufacture word timing or extend a timed vocal rest.
        if (!line.words.isNullOrEmpty()) return null
        return lines.asSequence().drop(index + 1).firstOrNull { it.time > line.time }?.time ?: Long.MAX_VALUE
    }

    /** Lines being sung, plus the last line held across a short rest until the next one starts. */
    fun activeIndices(lines: List<LyricLine>, positionMs: Long): Set<Int> = lines.indices.filterTo(linkedSetOf()) {
        val end = presentationEnd(lines, it)
        positionMs >= lines[it].time && end != null && positionMs < end
    } + com.auralis.music.ui.screens.lyrics.LyricsEngine.findVisualActiveLineIndices(lines, positionMs)

    fun scrollAnchor(lines: List<LyricLine>, positionMs: Long, fallback: Int): Int {
        val active = activeIndices(lines, positionMs).toList()
        if (active.isEmpty()) return leadIndex(lines, fallback)
        val front = active.maxOf { leadIndex(lines, it) }
        val leads = active.filter { !lines[it].isBackground || leadIndex(lines, it) == front }
            .map { leadIndex(lines, it) }.distinct()
        val first = leads.first()
        val following = (first + 1 until lines.size).filter { !lines[it].isBackground }.getOrNull(1)
        var groupEnd = lines[first].effectiveEndTime ?: lines[first].time
        for (index in first + 1 until lines.size) {
            if (!lines[index].isBackground) break
            groupEnd = maxOf(groupEnd, lines[index].effectiveEndTime ?: lines[index].time)
        }
        return if (following == null || groupEnd <= lines[following].time || leads.last() - first <= 1) first else leads.last()
    }
}
