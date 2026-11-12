package com.auralis.music.windows.notch

import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.SyncType
import org.json.JSONArray
import org.json.JSONObject

internal data class NotchLyricPiece(val text: String, val timeMs: Long?, val durationMs: Long?, val background: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("text", text).put("timeMs", timeMs ?: JSONObject.NULL)
        .put("durationMs", durationMs ?: JSONObject.NULL).put("background", background)
}
internal data class NotchLyricLine(val timeMs: Long, val endMs: Long?, val text: String,
    val pieces: List<NotchLyricPiece>, val instrumental: Boolean) {
    fun json(): JSONObject = JSONObject().put("timeMs", timeMs).put("endMs", endMs ?: JSONObject.NULL)
        .put("text", text).put("pieces", JSONArray().apply { pieces.forEach { put(it.json()) } })
        .put("instrumental", instrumental)
}
internal data class NotchLyrics(val status: String, val lines: List<NotchLyricLine> = emptyList()) {
    fun json(): JSONObject = JSONObject().put("status", status)
        .put("lines", JSONArray().apply { lines.forEach { put(it.json()) } })
}

/** A small prefetched window lets the native renderer change lines at frame rate between heartbeats. */
internal fun notchLyrics(state: DesktopLyricsController.State, trackId: String?, positionMs: Long): NotchLyrics {
    if (trackId == null) return NotchLyrics("unavailable")
    if (state.trackId != trackId) return NotchLyrics("loading")
    val data = state.lyrics ?: return NotchLyrics(if (state.isLoading) "loading" else "unavailable")
    if (data.syncType == SyncType.PLAIN) {
        val text = data.plainLyrics?.takeIf { it.isNotBlank() } ?: data.lines.joinToString("\n") { it.text }
        return if (text.isBlank()) NotchLyrics("unavailable") else NotchLyrics("static",
            listOf(NotchLyricLine(0, null, text.take(4096), emptyList(), false)))
    }
    val foreground = data.lines.filterNot { it.isBackground }
    val source = (foreground.ifEmpty { data.lines }).sortedBy { it.time }
    if (source.isEmpty()) return NotchLyrics("unavailable")
    val current = source.indexOfLast { it.time <= positionMs }.coerceAtLeast(0)
    val lines = (current until minOf(current + 3, source.size)).map { index ->
        encodeNotchLine(source[index], source.getOrNull(index + 1)?.time)
    }
    return NotchLyrics("ready", lines)
}

private fun encodeNotchLine(line: LyricLine, nextMs: Long?): NotchLyricLine {
    val pieces = line.words.orEmpty().flatMap { flattenNotchWord(it) }
    val text = if (pieces.isNotEmpty()) pieces.joinToString("") { it.text } else line.text
    val lastSung = pieces.lastOrNull { it.text.isNotBlank() }
    val sungEnd = lastSung?.timeMs?.let { start -> lastSung.durationMs?.let { start + it } }
    val end = (line.endTime ?: line.words?.lastOrNull { it.word.isNotBlank() }?.endTime ?: sungEnd)?.takeIf { it >= line.time }
    val boundary = listOfNotNull(end, nextMs?.takeIf { it > line.time }).minOrNull()
    // Never truncate through a timed syllable or guess replacement timing for pathological input.
    if (text.length > 512 || pieces.size > 128) return NotchLyricLine(line.time, boundary, line.text.take(512), emptyList(), line.isInstrumental)
    return NotchLyricLine(line.time, boundary, text, pieces, line.isInstrumental)
}

/** Syllables come from providers, never spelling. Preserve their spaces, rests and unknown ends. */
internal fun flattenNotchWord(word: LyricWord, depth: Int = 0): List<NotchLyricPiece> {
    val children = word.syllables?.takeIf { it.isNotEmpty() && depth < 8 }?.flatMap { flattenNotchWord(it, depth + 1) }
        ?.map { if (word.isBackground) it.copy(background = true) else it }
    if (children != null && children.size <= 128) {
        val combined = children.joinToString("") { it.text }
        if (word.word == combined) return children
        if (combined.isNotEmpty() && word.word.startsWith(combined)) return children +
            NotchLyricPiece(word.word.removePrefix(combined), null, null, word.isBackground)
    }
    return listOf(NotchLyricPiece(word.word, word.time, word.duration, word.isBackground))
}
