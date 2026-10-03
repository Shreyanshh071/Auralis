package com.auralis.music.ui.lyrics.renderers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.screens.lyrics.LyricsEngine

/*
 * "Auralis (Fluid)" lyrics.
 *
 * The line being sung is two copies of the same text laid out identically: a dim one, and a
 * bright one revealed up to the singing a fraction of a character at a time, with the front edge
 * feathered so it reads as the words lighting up rather than a bar sliding across them. The word
 * being sung lifts a couple of pixels and settles back once it's past, and a held note gets a
 * soft glow. Everything is decided in the draw phase from the playback clock, so a frame is a
 * few clips and redraws of already-measured text; nothing re-lays out while a line plays.
 */

/** How far back anything not yet sung sits: the rest of the playing line, and the lines to come. */
internal const val FluidUnsungAlpha = 0.42f
private const val UNSUNG_ALPHA = FluidUnsungAlpha

/** Width of the soft front edge of the sweep. */
private val FEATHER = 28.dp

/** How far the word being sung lifts off the line. */
private val WORD_RISE = 2.dp

/** Time a word takes to lift, and to settle back once it's past. */
private const val RISE_MS = 650f

/** A word held at least this long glows while it's held. */
private const val GLOW_MIN_HELD_MS = 900L
private const val GLOW_ALPHA = 0.55f
private val GLOW_RADIUS = 6.dp

/** Falloff by distance from the playing line: alpha, then blur in dp. Last entry covers the rest. */
internal val FluidFalloffAlpha = floatArrayOf(1f, 0.78f, 0.62f, 0.5f, 0.4f)
internal val FluidFalloffBlurDp = floatArrayOf(0f, 1.2f, 1.8f, 2.6f, 3.4f)

internal fun fluidFalloffIndex(distance: Int): Int = distance.coerceIn(0, FluidFalloffAlpha.lastIndex)

private fun smooth(f: Float) = f * f * (3f - 2f * f)

private class FluidWord(val start: Int, val end: Int, val startMs: Long, val endMs: Long)

private class FluidTiming(val text: String, val words: List<FluidWord>) {
    val lineStartMs = words.firstOrNull()?.startMs ?: 0L
    val lineEndMs = words.lastOrNull()?.endMs ?: 0L

    /** How far the singing has got, as a fractional character index into [text]. */
    fun revealedChars(positionMs: Long): Float {
        if (words.isEmpty()) return 0f
        words.forEachIndexed { i, w ->
            if (positionMs < w.startMs) return w.start.toFloat()
            if (positionMs < w.endMs) {
                val through = (positionMs - w.startMs).toFloat() / (w.endMs - w.startMs).coerceAtLeast(1L)
                return w.start + through * (w.end - w.start)
            }
            // Between words: the gap after this one fills over the pause, so the edge keeps creeping.
            val next = words.getOrNull(i + 1)
            if (next != null && positionMs < next.startMs) {
                val through = (positionMs - w.endMs).toFloat() / (next.startMs - w.endMs).coerceAtLeast(1L)
                return w.end + through * (next.start - w.end)
            }
        }
        return text.length.toFloat()
    }

    /** 0..1: up from the word's start, back down after its end. */
    fun lift(word: FluidWord, positionMs: Long): Float {
        val rising = ((positionMs - word.startMs) / RISE_MS).coerceIn(0f, 1f)
        val falling = (1f - (positionMs - word.endMs) / RISE_MS).coerceIn(0f, 1f)
        return smooth(minOf(rising, falling))
    }

    fun anyLifted(positionMs: Long): Boolean =
        words.isNotEmpty() && positionMs > lineStartMs && positionMs < lineEndMs + RISE_MS
}

private fun buildTiming(line: LyricLine, words: List<LyricWord>): FluidTiming {
    val spans = LyricsEngine.mapWordsToLineSpans(line.text, words)
    val mapped = spans.mapIndexed { i, span ->
        val start = span.word.time
        // A word with no end runs until the next word starts (or ~0.6s at the end of the line).
        val end = span.word.endTime ?: spans.getOrNull(i + 1)?.word?.time ?: (start + 600L)
        // Trim trailing spaces off the span so the lift doesn't carry a gap with it.
        var e = span.endIndex
        while (e > span.startIndex && line.text[e - 1].isWhitespace()) e--
        FluidWord(span.startIndex, e, start, end.coerceAtLeast(start + 1))
    }
    return FluidTiming(line.text, mapped)
}

/**
 * The playing line, swept and lifted in time with [positionMs]. [words] are the provider's word
 * timings; without them the whole line lights at once.
 */
@Composable
internal fun FluidLyricLine(
    line: LyricLine,
    words: List<LyricWord>?,
    positionMs: State<Long>,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val timing = remember(line, words) {
        if (words.isNullOrEmpty()) null else buildTiming(line, words)
    }
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }

    val rise: Modifier = Modifier.drawWithContent {
        val t = timing
        val l = layout
        val pos = positionMs.value
        if (t == null || l == null || !t.anyLifted(pos)) drawContent() else drawRisen(l, t, pos, WORD_RISE.toPx())
    }

    Box(modifier) {
        // Dim copy: the whole line, held back.
        Text(
            text = line.text,
            style = style,
            color = Color.White.copy(alpha = UNSUNG_ALPHA),
            overflow = TextOverflow.Visible,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth().then(rise)
        )
        // Glow: a blurred copy lit only over words held long enough to earn it.
        if (timing != null && timing.words.any { it.endMs - it.startMs >= GLOW_MIN_HELD_MS }) {
            Text(
                text = line.text,
                style = style,
                color = Color.White,
                overflow = TextOverflow.Visible,
                modifier = Modifier
                    .fillMaxWidth()
                    .blur(GLOW_RADIUS, BlurredEdgeTreatment.Unbounded)
                    .drawWithContent {
                        val l = layout ?: return@drawWithContent
                        drawGlow(l, timing, positionMs.value)
                    }
            )
        }
        // Bright copy: revealed up to the singing, with a feathered front edge.
        Text(
            text = line.text,
            style = style,
            color = Color.White,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .fillMaxWidth()
                .then(rise)
                // The feather erases into its own layer, so it can't take the backdrop with it.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    val pos = positionMs.value
                    val t = timing
                    val l = layout
                    when {
                        t == null -> if (pos >= line.time) drawContent()
                        pos >= t.lineEndMs -> drawContent()
                        pos <= t.lineStartMs -> Unit
                        l != null -> drawSwept(l, t.revealedChars(pos), FEATHER.toPx())
                    }
                }
        )
    }
}

@Composable
private fun Text(
    text: String,
    style: TextStyle,
    color: Color,
    overflow: TextOverflow,
    modifier: Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {}
) = androidx.compose.material3.Text(
    text = text,
    style = style,
    color = color,
    overflow = overflow,
    onTextLayout = onTextLayout,
    modifier = modifier
)

/** X position of a character offset, held to the visual row it belongs to (wrap-safe). */
private fun TextLayoutResult.xOn(offset: Int, row: Int): Float {
    val left = getLineLeft(row)
    val right = getLineRight(row)
    return when {
        offset <= getLineStart(row) -> left
        offset >= getLineEnd(row, visibleEnd = true) -> right
        else -> getHorizontalPosition(offset, usePrimaryDirection = true).coerceIn(left, right)
    }
}

private fun ContentDrawScope.drawSwept(layout: TextLayoutResult, revealed: Float, featherPx: Float) {
    if (revealed <= 0f) return
    if (revealed >= layout.layoutInput.text.length) {
        drawContent()
        return
    }
    for (row in 0 until layout.lineCount) {
        val start = layout.getLineStart(row)
        if (revealed <= start) return
        val end = layout.getLineEnd(row, visibleEnd = true)
        val cut = revealed < end
        val right = if (cut) {
            val i = revealed.toInt().coerceIn(start, end)
            val here = layout.xOn(i, row)
            val next = layout.xOn((i + 1).coerceAtMost(end), row)
            here + (next - here) * (revealed - i)
        } else layout.getLineRight(row)
        // A whole row's height of headroom so lifted words aren't shaved off at the top.
        val top = layout.getLineTop(row) - WORD_RISE.toPx()
        val bottom = layout.getLineBottom(row)
        clipRect(layout.getLineLeft(row), top, right, bottom) { this@drawSwept.drawContent() }
        if (cut) {
            clipRect(top = top, bottom = bottom) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.White,
                        1f to Color.Transparent,
                        startX = (right - featherPx).coerceAtLeast(layout.getLineLeft(row)),
                        endX = right
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
        }
    }
}

/** Redraws each lifted word slice a little higher; everything else is drawn flat. */
private fun ContentDrawScope.drawRisen(layout: TextLayoutResult, timing: FluidTiming, pos: Long, peak: Float) {
    for (row in 0 until layout.lineCount) {
        val rowStart = layout.getLineStart(row)
        val rowEnd = layout.getLineEnd(row, visibleEnd = true)
        val top = layout.getLineTop(row)
        val bottom = layout.getLineBottom(row)
        var flatFrom = layout.getLineLeft(row)
        for (word in timing.words) {
            val s = maxOf(word.start, rowStart)
            val e = minOf(word.end, rowEnd)
            if (s >= e) continue
            val lift = timing.lift(word, pos)
            if (lift <= 0.01f) continue
            val from = layout.xOn(s, row)
            val to = layout.xOn(e, row)
            if (to <= from) continue
            if (from > flatFrom) clipRect(flatFrom, top, from, bottom) { this@drawRisen.drawContent() }
            clipRect(from, top - peak, to, bottom) {
                translate(top = -lift * peak) { this@drawRisen.drawContent() }
            }
            flatFrom = to
        }
        val rowRight = layout.getLineRight(row)
        if (rowRight > flatFrom) clipRect(flatFrom, top, rowRight, bottom) { this@drawRisen.drawContent() }
    }
}

/** Lights the held words only, each at its own lift, so a quick line stays dark. */
private fun ContentDrawScope.drawGlow(layout: TextLayoutResult, timing: FluidTiming, pos: Long) {
    for (word in timing.words) {
        if (word.endMs - word.startMs < GLOW_MIN_HELD_MS) continue
        val strength = timing.lift(word, pos)
        if (strength <= 0.02f) continue
        for (row in 0 until layout.lineCount) {
            val s = maxOf(word.start, layout.getLineStart(row))
            val e = minOf(word.end, layout.getLineEnd(row, visibleEnd = true))
            if (s >= e) continue
            val from = layout.xOn(s, row)
            val to = layout.xOn(e, row)
            val pad = GLOW_RADIUS.toPx() * 2
            clipRect(from - pad, layout.getLineTop(row) - pad, to + pad, layout.getLineBottom(row) + pad) {
                // drawContent ignores alpha, so fade the glow by drawing it into a translucent layer.
                drawContext.canvas.saveLayer(
                    androidx.compose.ui.geometry.Rect(from - pad, layout.getLineTop(row) - pad, to + pad, layout.getLineBottom(row) + pad),
                    androidx.compose.ui.graphics.Paint().apply { alpha = GLOW_ALPHA * strength }
                )
                translate(top = -strength * WORD_RISE.toPx()) { this@drawGlow.drawContent() }
                drawContext.canvas.restore()
            }
        }
    }
}
