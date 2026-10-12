package com.auralis.music.ui.lyrics.renderers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
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

/**
 * Alpha by distance from the playing line, Metro's own curve. Last entry covers the rest.
 * The old 0.78/0.62 steps on top of the 0.42 grey left neighbours at ~0.33: blurred white text at
 * that level reads as lit, not as a line that is over.
 */
internal val FluidFalloffAlpha = floatArrayOf(1f, 0.20f, 0.20f, 0.15f, 0.10f, 0.08f)

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
 * timings; without them the whole line lights at once. Every other line ([isActive] false), sung
 * or not, is the dim copy only: a finished line going back to dim is what keeps the sung one
 * standing out, as in Metro.
 */
@Composable
fun FluidLyricLine(
    line: LyricLine,
    words: List<LyricWord>?,
    positionMs: State<Long>,
    style: TextStyle,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isActive) {
        // Full white: the row's alpha is the whole dimming ([FluidFalloffAlpha]), as in Metro.
        Text(
            text = line.text,
            style = style,
            color = Color.White,
            overflow = TextOverflow.Visible,
            modifier = modifier.fillMaxWidth()
        )
        return
    }
    val timing = remember(line, words) {
        if (words.isNullOrEmpty()) null else buildTiming(line, words)
    }
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }

    val geometry = remember(layout, timing) {
        layout?.let { measured -> buildFluidGeometry(measured, timing) }
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val raster = remember(layout, density, direction, timing) {
        layout?.let { measured ->
            val pad = with(density) { (GLOW_RADIUS * 2).toPx() }.toInt() + 2
            val size = Size((measured.size.width + pad * 2).toFloat(), (measured.size.height + pad * 2).toFloat())
            fun image(glow: Boolean): ImageBitmap {
                val bitmap = ImageBitmap(size.width.toInt(), size.height.toInt())
                CanvasDrawScope().draw(density, direction, Canvas(bitmap), size) {
                    drawText(measured, color = if (glow) Color.Transparent else Color.White,
                        topLeft = Offset(pad.toFloat(), pad.toFloat()),
                        shadow = if (glow) Shadow(Color.White.copy(alpha = GLOW_ALPHA), blurRadius = with(density) { GLOW_RADIUS.toPx() }) else null)
                }
                return bitmap
            }
            FluidRaster(image(false), if (timing?.words?.any { it.endMs - it.startMs >= GLOW_MIN_HELD_MS } == true) image(true) else null, pad.toFloat())
        }
    }
    // Rasterize glyphs and held-note blur once per layout. Animation only clips/translates
    // these images; no per-frame paragraph repaint, shadow rasterization, or offscreen mask.
    Text(
        text = line.text, style = style, color = Color.White.copy(alpha = UNSUNG_ALPHA),
        overflow = TextOverflow.Visible, onTextLayout = { layout = it },
        modifier = modifier.fillMaxWidth().drawWithContent {
            val measured = layout
            val rows = geometry
            val images = raster
            if (measured == null || rows == null || images == null) { drawContent(); return@drawWithContent }
            val pos = positionMs.value
            val revealed = timing?.revealedChars(pos) ?: if (pos >= line.time) line.text.length.toFloat() else 0f
            val peak = WORD_RISE.toPx()
            for (row in rows) {
                val edge = when {
                    revealed <= row.start -> row.left
                    revealed >= row.end -> row.right
                    else -> {
                        val i = revealed.toInt().coerceIn(row.start, row.end)
                        val here = measured.xOn(i, row.index)
                        here + (measured.xOn((i + 1).coerceAtMost(row.end), row.index) - here) * (revealed - i)
                    }
                }
                for (slice in row.slices) {
                    val word = slice.word
                    val lift = if (word != null && timing != null) timing.lift(word, pos) else 0f
                    val offset = Offset(-images.pad, -images.pad - lift * peak)
                    clipRect(slice.left, row.top - peak, slice.right, row.bottom) {
                        drawImage(images.text, topLeft = offset, alpha = UNSUNG_ALPHA)
                        if (edge > slice.left) {
                            val featherStart = if (revealed >= row.end) edge else (edge - FEATHER.toPx()).coerceAtLeast(row.left)
                            if (featherStart > slice.left) clipRect(right = minOf(slice.right, featherStart), top = row.top - peak, bottom = row.bottom) {
                                drawImage(images.text, topLeft = offset)
                            }
                            if (revealed < row.end) {
                                val width = (edge - featherStart) / 16f
                                for (step in 0 until 16) {
                                    val left = maxOf(slice.left, featherStart + step * width)
                                    val right = minOf(slice.right, featherStart + (step + 1) * width)
                                    if (right > left) clipRect(left, row.top - peak, right, row.bottom) {
                                        drawImage(images.text, topLeft = offset, alpha = 1f - (step + .5f) / 16f)
                                    }
                                }
                            }
                        }
                    }
                    if (images.glow != null && word != null && word.endMs - word.startMs >= GLOW_MIN_HELD_MS && lift > .02f) {
                        val pad = GLOW_RADIUS.toPx() * 2
                        clipRect(slice.left - pad, row.top - pad, slice.right + pad, row.bottom + pad) {
                            drawImage(images.glow, topLeft = offset, alpha = lift)
                        }
                    }
                }
            }
        }
    )
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


private class FluidRaster(val text: ImageBitmap, val glow: ImageBitmap?, val pad: Float)

private class FluidSlice(val left: Float, val right: Float, val word: FluidWord?)
private class FluidRow(val index: Int, val start: Int, val end: Int, val left: Float, val right: Float,
    val top: Float, val bottom: Float, val slices: List<FluidSlice>)

private fun buildFluidGeometry(layout: TextLayoutResult, timing: FluidTiming?): List<FluidRow> =
    (0 until layout.lineCount).map { row ->
        val start = layout.getLineStart(row)
        val end = layout.getLineEnd(row, visibleEnd = true)
        val left = layout.getLineLeft(row)
        val right = layout.getLineRight(row)
        val slices = mutableListOf<FluidSlice>()
        var from = left
        for (word in timing?.words.orEmpty()) {
            val s = maxOf(start, word.start)
            val e = minOf(end, word.end)
            if (s >= e) continue
            val wordLeft = layout.xOn(s, row)
            val wordRight = layout.xOn(e, row)
            if (wordRight <= wordLeft) continue
            if (wordLeft > from) slices += FluidSlice(from, wordLeft, null)
            slices += FluidSlice(wordLeft, wordRight, word)
            from = wordRight
        }
        if (right > from) slices += FluidSlice(from, right, null)
        FluidRow(row, start, end, left, right, layout.getLineTop(row), layout.getLineBottom(row), slices)
    }
