package com.auralis.music.ui.lyrics.renderers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Native drawing of the Spicy style: soft word fill, swelling syllables and held-note letters.
 * This is an independent Compose renderer; the API supplies data, never executable UI code.
 * Reference behavior: github.com/Spikerko/spicy-lyrics, LyricsAnimator.ts and css/Lyrics/Mixed.css.
 * Goals are sampled from the playback clock and eased by springs, as Spicy does; a seek or
 * the first frame snaps every spring to its goal, so a seek always lands on the same drawing. */
data class SpicyWordPose(val scale: Float, val liftEm: Float, val glow: Float)

/** A letter of a held word: Spicy times them one after another across the syllable. */
data class SpicyLetterTime(val startMs: Long, val endMs: Long)

/** Keep consecutive pieces of one written word touching as their widths change. */
object SpicyJoinedInk {
    fun leftEdges(left: Float, widths: List<Float>, scales: List<Float>): List<Float> {
        var cursor = left + (widths.sum() - widths.indices.sumOf { (widths[it] * scales[it]).toDouble() }.toFloat()) / 2f
        return widths.indices.map { i -> cursor.also { cursor += widths[i] * scales[i] } }
    }
}

/** Natural cubic spline through Spicy's keyframes (its `Spline` from the cubic-spline package). */
internal class SpicySpline(private val xs: FloatArray, private val ys: FloatArray) {
    private val second = FloatArray(xs.size).also { m ->
        val n = xs.size
        if (n < 3) return@also
        val c = FloatArray(n)
        val d = FloatArray(n)
        for (i in 1 until n - 1) {
            val h0 = xs[i] - xs[i - 1]
            val h1 = xs[i + 1] - xs[i]
            val diag = 2f * (h0 + h1) - h0 * c[i - 1]
            c[i] = h1 / diag
            d[i] = (6f * ((ys[i + 1] - ys[i]) / h1 - (ys[i] - ys[i - 1]) / h0) - h0 * d[i - 1]) / diag
        }
        for (i in n - 2 downTo 1) m[i] = d[i] - c[i] * m[i + 1]
    }

    fun at(value: Float): Float {
        val x = value.coerceIn(xs.first(), xs.last())
        var i = 1
        while (i < xs.size - 1 && x > xs[i]) i++
        val h = xs[i] - xs[i - 1]
        val a = (xs[i] - x) / h
        val b = (x - xs[i - 1]) / h
        return a * ys[i - 1] + b * ys[i] + ((a * a * a - a) * second[i - 1] + (b * b * b - b) * second[i]) * h * h / 6f
    }
}

/** Damped spring (spr, as ported in Spicy's Spring.ts); frequency in Hz, damping below 1. */
internal class SpicySpring(private val frequency: Float, private val damping: Float) {
    var position = Float.NaN
        private set
    private var velocity = 0f
    private var goal = 0f

    fun drive(target: Float, dtSec: Float, snap: Boolean): Float {
        goal = target
        if (snap || position.isNaN()) {
            position = target
            velocity = 0f
            return position
        }
        if (dtSec <= 0f) return position
        val f = frequency * 2.0 * PI
        val d = damping.toDouble()
        val q = exp(-d * f * dtSec)
        val c = sqrt(1 - d * d)
        val i = cos(dtSec * f * c)
        val j = sin(dtSec * f * c)
        val z = j / c
        val y = j / (f * c)
        val o = (position - goal).toDouble()
        val p = (o * (i + z * d) + velocity * y) * q + goal
        val v = (velocity * (i - z * d) - o * (z * f)) * q
        position = p.toFloat()
        velocity = v.toFloat()
        return position
    }
}

object SpicyLyricsMotion {
    const val UNSUNG_ALPHA = .35f
    /** Lines not being sung: Spicy's line opacity times its text alpha (Mixed.css). */
    const val SUNG_LINE_ALPHA = .497f * .85f
    const val UPCOMING_LINE_ALPHA = .51f * .35f
    /** Spicy finishes a held word's letters this long before the word ends (Emphasize.ts). */
    const val LETTER_LEAD_MS = 250L

    private val wordScale = SpicySpline(floatArrayOf(0f, .7f, 1f), floatArrayOf(.95f, 1.0505f, 1f))
    private val letterScale = SpicySpline(floatArrayOf(0f, .7f, 1f), floatArrayOf(.95f, 1.175f, 1f))
    private val wordLift = SpicySpline(floatArrayOf(0f, .9f, 1f), floatArrayOf(1f / 100, -1f / 60, 0f))
    private val letterLift = SpicySpline(floatArrayOf(0f, .9f, 1f), floatArrayOf(1f / 100, -1f / 56, 0f))
    private val glowCurve = SpicySpline(floatArrayOf(0f, .15f, .6f, 1f), floatArrayOf(0f, 1f, 1f, 0f))
    private val lineGlowCurve = SpicySpline(floatArrayOf(0f, .5f, 1f), floatArrayOf(0f, 1f, 0f))

    /** Whole-line progress only; never split a line into fabricated word timestamps. */
    fun lineProgress(startMs: Long, endMs: Long?, positionMs: Long): Float = when {
        positionMs < startMs -> 0f
        endMs == null || endMs <= startMs -> 1f
        else -> ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }

    fun lineGlow(progress: Float): Float = lineGlowCurve.at(progress.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    private const val SUNG_LETTER_GLOW = .2f

    fun progress(word: LyricWord, positionMs: Long): Float =
        LyricsEngine.calculateWordProgress(word, positionMs)

    private fun glowAt(t: Float) = glowCurve.at(t).coerceAtLeast(0f)

    /** Spring goal for a whole word. Presentation only: the supplied timestamps stay unchanged. */
    fun pose(startMs: Long, endMs: Long?, positionMs: Long): SpicyWordPose {
        if (endMs == null || endMs <= startMs) {
            return if (positionMs < startMs) SpicyWordPose(wordScale.at(0f), wordLift.at(0f), 0f)
                else SpicyWordPose(1f, 0f, 0f)
        }
        val t = when {
            positionMs < startMs -> 0f
            positionMs >= endMs -> 1f
            else -> (positionMs - startMs).toFloat() / (endMs - startMs)
        }
        return SpicyWordPose(wordScale.at(t), wordLift.at(t), if (positionMs >= endMs) 0f else glowAt(t))
    }

    /** Spring goal for letter [index] of a held word. Only the letter being sung swells fully;
     * its neighbours follow with a steep falloff and letters still to come wait at rest. */
    fun letterPose(letters: List<SpicyLetterTime>, index: Int, positionMs: Long): SpicyWordPose {
        val rest = SpicyWordPose(letterScale.at(0f), letterLift.at(0f), 0f)
        if (letters.isEmpty() || positionMs < letters.first().startMs) return rest
        if (positionMs >= letters.last().endMs) return SpicyWordPose(letterScale.at(1f), letterLift.at(1f), 0f)
        val letter = letters[index]
        if (positionMs < letter.startMs) return rest
        val active = letters.indexOfFirst { positionMs >= it.startMs && positionMs < it.endMs }
        if (active < 0) return rest.copy(glow = glowAt(SUNG_LETTER_GLOW))
        val current = letters[active]
        val t = (positionMs - current.startMs).toFloat() / (current.endMs - current.startMs).coerceAtLeast(1)
        val distance = abs(index - active).toFloat()
        val falloff = 1f / (1f + distance.pow(2.8f))
        val glowFalloff = 1f / (1f + distance * .9f)
        return SpicyWordPose(
            rest.scale + (letterScale.at(t) - rest.scale) * falloff,
            rest.liftEm + (letterLift.at(t) - rest.liftEm) * falloff,
            glowAt(t) * glowFalloff
        )
    }

    /** Where a letter's gradient stands, from -0.2 (dim) to 1 (lit), as in Spicy's per-letter fill. */
    fun letterFill(letter: SpicyLetterTime, positionMs: Long): Float = when {
        positionMs < letter.startMs -> -.2f
        positionMs >= letter.endMs -> 1f
        else -> -.2f + 1.2f * sin((positionMs - letter.startMs).toFloat() /
            (letter.endMs - letter.startMs).coerceAtLeast(1) * PI.toFloat() / 2f)
    }
}

private data class TimedPart(val range: LyricsEngine.WordRange, val from: Int, val to: Int)
private data class SpicyWord(val start: Int, val end: Int, val parts: List<TimedPart>,
                             val emphasizedLetters: List<SpicyEmphasizedLetter>?) {
    val startMs = parts.minOfOrNull { it.range.word.time } ?: 0L
    val endMs = if (parts.isNotEmpty() && parts.all { it.range.word.endTime != null })
        parts.maxOf { it.range.word.endTime!! } else null

    // Geometry resolves grapheme ranges from the full source text before assigning letter timing.
    val letters: List<SpicyLetterTime>? = emphasizedLetters?.map { it.time }

    fun progress(position: Long): Float {
        var revealed = 0f
        for (part in parts) {
            if (position < part.range.word.time) break
            val p = SpicyLyricsMotion.progress(part.range.word, position)
            val absolute = part.from + (part.to - part.from) * p
            revealed = maxOf(revealed, (absolute - start).coerceIn(0f, (end - start).toFloat()))
            if (p < 1f) break
        }
        if (parts.isNotEmpty() && position >= parts.last().range.word.time &&
            SpicyLyricsMotion.progress(parts.last().range.word, position) >= 1f) return 1f
        return (revealed / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

/** One drawn piece: a whole word (per wrapped row), or one letter of a held word. */
private data class SpicySlice(val bounds: Rect, val wordIndex: Int, val wordBounds: Rect,
                              val rtl: Boolean, val from: Int, val to: Int, val row: Int, val letter: Int = -1)

private data class SpicyBloom(val image: ImageBitmap, val origin: Offset)
private val spicyPreparationDispatcher = Dispatchers.Default.limitedParallelism(2)

/** Recorded native layers can outlive the active-text branch by a frame. Keep the
 * latest image generations alive when a row switches between active and static. */
private class SpicyImageLifetime {
    private val generations = ArrayDeque<List<ImageBitmap>>()
    fun retain(images: List<ImageBitmap>) {
        if (images.isEmpty()) return
        generations.addLast(images)
        while (generations.size > 6) generations.removeFirst()
    }
}

private class SpicySprings {
    val scale = SpicySpring(.88f, .64f)
    val lift = SpicySpring(1.45f, .4f)
    val glow = SpicySpring(1.18f, .56f)
}

private class SpicyClock {
    var lastFrame = -1L
    var lastPosition = 0L
}

@Composable
fun SpicyLyricsLine(
    line: LyricLine,
    words: List<LyricWord>?,
    positionMs: State<Long>,
    style: TextStyle,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    /** Colour of a line not being sung; callers dim it to Spicy's sung/unsung levels. */
    inactiveColor: Color = Color.White,
    nextLineTime: Long? = null
) {
    val displayText = remember(line.text, line.isBackground) { SpicyBackingText.displayText(line.text, line.isBackground) }
    val displayWords = remember(displayText, words, line.isBackground) {
        SpicyBackingText.displayWords(displayText, words, line.isBackground)
    }
    val imageLifetime = remember(displayText) { SpicyImageLifetime() }
    // Line-sync is a whole-line highlight. Do not manufacture timed words from its text.
    if (words.isNullOrEmpty()) {
        SpicyLineSyncedText(line.copy(text = displayText), positionMs, style, isActive, modifier, inactiveColor, nextLineTime)
        return
    }
    val nearPlayback by remember(line.time, line.effectiveEndTime, positionMs) { derivedStateOf {
        positionMs.value in (line.time - 3_000L)..(line.effectiveEndTime ?: line.time + 10_000L)
    } }
    if (!isActive && !nearPlayback) {
        // Browsing distant verses must not rasterize and blur every off-screen
        // word. Only the playing/upcoming phrases need prepared animation masks.
        Text(displayText, style = style, color = inactiveColor, overflow = TextOverflow.Visible,
            modifier = modifier.fillMaxWidth())
        return
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var layout by remember(displayText, style) { mutableStateOf<TextLayoutResult?>(null) }
    var frameNanos by remember { mutableLongStateOf(0L) }
    val groups = remember(displayText, displayWords) {
        val spans = LyricsEngine.mapWordsToLineSpans(displayText, displayWords.orEmpty().flatMap { word ->
            val pieces = word.syllables
            // Only use pieces whose ink still matches after spacing/translation repair.
            if (!pieces.isNullOrEmpty() && pieces.joinToString("") { it.word }.trim() == word.word.trim()) pieces else listOf(word)
        })
        // A source syllable is an animation unit, even inside a written word.
        // Combining all syllables first loses the motion of a held final syllable.
        spans.mapNotNull { span ->
            var start = span.startIndex
            var end = span.endIndex
            while (start < end && displayText[start].isWhitespace()) start++
            while (end > start && displayText[end - 1].isWhitespace()) end--
            if (start >= end) null else SpicyWord(start, end, listOf(TimedPart(span, start, end)),
                SpicyEmphasis.letters(displayText.substring(start, end), span.word.time, span.word.endTime))
        }
    }
    val geometry = remember(layout, groups) {
        layout?.let { measured ->
            groups.flatMapIndexed { wordIndex, word ->
                val first = measured.getLineForOffset(word.start)
                val last = measured.getLineForOffset(word.end - 1)
                (first..last).flatMap { row ->
                    val from = maxOf(word.start, measured.getLineStart(row))
                    val to = minOf(word.end, measured.getLineEnd(row, visibleEnd = true))
                    if (from >= to) return@flatMap emptyList()
                    val bounds = measured.getPathForRange(from, to).getBounds()
                    val rtl = measured.getBidiRunDirection(from) == ResolvedTextDirection.Rtl
                    // Like Spicy's SplitLetters, animate graphemes (including vowel marks,
                    // surrogate pairs and joined conjuncts), not ASCII/UTF-16 characters.
                    // Connected RTL shaping still stays intact as in the upstream renderer.
                    val letters = word.emphasizedLetters
                    if (letters != null && !rtl) letters.mapIndexedNotNull { index, letter ->
                        val letterFrom = word.start + letter.from
                        val letterTo = word.start + letter.to
                        if (letterFrom < from || letterTo > to) null else
                            SpicySlice(measured.getPathForRange(letterFrom, letterTo).getBounds(), wordIndex, bounds, false,
                                letterFrom, letterTo, row, index)
                    } else listOf(SpicySlice(bounds, wordIndex, bounds, rtl, from, to, row))
                }
            }
        }.orEmpty()
    }
    val inkMasks = remember(layout, geometry, style, density) {
        val fontPx = with(density) { style.fontSize.toPx() }
        geometry.map { slice ->
            if (slice.letter >= 0) slice.bounds else {
                        val previous = geometry.filter { it.row == slice.row && it.bounds.right <= slice.bounds.left }
                            .maxOfOrNull { it.bounds.right }
                        val next = geometry.filter { it.row == slice.row && it.bounds.left >= slice.bounds.right }
                            .minOfOrNull { it.bounds.left }
                        Rect(previous?.let { (it + slice.bounds.left) / 2 } ?: (slice.bounds.left - fontPx * .2f),
                            layout!!.getLineTop(slice.row),
                            next?.let { (it + slice.bounds.right) / 2 } ?: (slice.bounds.right + fontPx * .2f),
                            layout!!.getLineBottom(slice.row))
            }
        }
    }
    val raster = remember(layout, density, direction, groups) {
        layout?.let { measured ->
            val fontPx = with(density) { style.fontSize.toPx() }
            val pad = (fontPx * .8f).toInt().coerceAtLeast(4)
            val size = Size((measured.size.width + pad * 2).toFloat(), (measured.size.height + pad * 2).toFloat())
            val bitmap = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            CanvasDrawScope().draw(density, direction, Canvas(bitmap), size) {
                drawRect(Color.Transparent, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                drawText(measured, color = Color.White, topLeft = Offset(pad.toFloat(), pad.toFloat()))
            }
            imageLifetime.retain(listOf(bitmap))
            bitmap to pad.toFloat()
        }
    }
    val wordSprings = remember(groups) { groups.map { SpicySprings() } }
    val blooms by produceState<List<SpicyBloom>>(emptyList(), raster, geometry, inkMasks) {
        value = emptyList()
        val source = raster
        if (source != null) {
            val fontPx = with(density) { style.fontSize.toPx() }
            // Software mask blur used to block composition at every new phrase.
            // The timed fill/swells draw immediately; glow preparation is cancellable
            // background work and upcoming rows warm it before their first word.
            val prepared = withContext(spicyPreparationDispatcher) {
            geometry.mapIndexed { index, slice ->
                coroutineContext.ensureActive()
                // Spicy's regular shadow is wider on emphasized letters than words.
                val sigma = (fontPx * if (slice.letter >= 0) .065f else .045f).coerceAtLeast(1f)
                val padding = kotlin.math.ceil(sigma * 3f + 2f).toInt()
                val ink = inkMasks[index]
                val size = Size(kotlin.math.ceil(ink.width + padding * 2).toFloat(),
                    kotlin.math.ceil(ink.height + padding * 2).toFloat())
                val mask = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                CanvasDrawScope().draw(density, direction, Canvas(mask), size) {
                    drawRect(Color.Transparent, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                    clipRect(padding.toFloat(), padding.toFloat(), padding + ink.width, padding + ink.height) {
                        drawImage(source.first, Offset(padding - ink.left - source.second, padding - ink.top - source.second))
                    }
                }
                SpicyBloom(blurSpicyGlyphMask(mask, sigma), Offset(ink.left - padding, ink.top - padding))
            }
            }
            imageLifetime.retain(prepared.map { it.image })
            value = prepared
        }
    }
    val joinedRuns = remember(geometry) {
        geometry.indices.groupBy { geometry[it].row }.values.flatMap { row ->
            val ordered = row.sortedBy { geometry[it].bounds.left }
            val runs = mutableListOf<List<Int>>()
            var first = 0
            while (first < ordered.size) {
                var last = first
                while (last + 1 < ordered.size && !geometry[ordered[last]].rtl &&
                    geometry[ordered[last]].to == geometry[ordered[last + 1]].from) last++
                if (last > first) runs += ordered.subList(first, last + 1).toList()
                first = last + 1
            }
            runs
        }
    }
    val joinedTranslations = remember(geometry) { FloatArray(geometry.size) }
    val letterSprings = remember(geometry) { geometry.map { SpicySprings() } }
    val clock = remember(geometry) { SpicyClock() }
    LaunchedEffect(isActive, clock) {
        clock.lastFrame = -1L
        if (isActive) while (true) withFrameNanos { frameNanos = it }
    }
    // onTextLayout supplies the mask for the following draw. Until it is available,
    // drawContent must stay unsung: white text here flashed the entire new line.
    // The cached raster above is explicitly white; its timed fills are unaffected.
    Text(displayText, style = style, color = Color.White.copy(alpha = SpicyLyricsMotion.UNSUNG_ALPHA), overflow = TextOverflow.Visible,
        onTextLayout = {
            // Relayout/scroll invalidations can return a new result for the
            // identical paragraph. Do not rebuild every glyph and glow mask.
            if (layout?.layoutInput != it.layoutInput || layout?.size != it.size) layout = it
        }, modifier = modifier.fillMaxWidth().drawWithContent {
            if (!isActive) {
                // Prepare the stable, white glyph masks while this row is upcoming.
                // Painting its dim colour separately avoids rebuilding masks when
                // the caller animates opacity or the next vocal begins.
                val measured = layout
                if (measured == null) drawContent() else drawText(measured, color = inactiveColor, shadow = Shadow.None)
                return@drawWithContent
            }
            val images = raster
            if (images == null || geometry.isEmpty()) { drawContent(); return@drawWithContent }
            val (text, pad) = images
            // The clock is read only in drawing: paragraph measurement and rasterization stay cached.
            val now = positionMs.value
            val frame = frameNanos
            val step = SpicyFrameTiming.sample(clock.lastFrame, frame, clock.lastPosition, now)
            val dt = step.seconds
            val snap = step.reset
            clock.lastFrame = frame
            clock.lastPosition = now
            val fontPx = style.fontSize.toPx()
            val origin = Offset(-pad, -pad)

            val wordPoses = groups.mapIndexed { index, word ->
                val end = word.letters?.last()?.endMs ?: word.endMs
                val goal = SpicyLyricsMotion.pose(word.startMs, end, now)
                val springs = wordSprings[index]
                SpicyWordPose(springs.scale.drive(goal.scale, dt, snap), springs.lift.drive(goal.liftEm, dt, snap),
                    springs.glow.drive(if (word.letters != null) 0f else goal.glow, dt, snap))
            }
            val letterPoses = geometry.mapIndexed { index, slice ->
                val letters = groups[slice.wordIndex].letters
                if (slice.letter < 0 || letters == null) null else {
                    val goal = SpicyLyricsMotion.letterPose(letters, slice.letter, now)
                    val springs = letterSprings[index]
                    SpicyWordPose(springs.scale.drive(goal.scale, dt, snap), springs.lift.drive(goal.liftEm, dt, snap),
                        springs.glow.drive(goal.glow, dt, snap))
                }
            }
            joinedTranslations.fill(0f)
            // Pack ink by its measured advances, never by inserting spaces or
            // snapping it to a different origin when a syllable becomes active.
            joinedRuns.forEach { run ->
                        var originalWidth = 0f
                        var scaledWidth = 0f
                        for (index in run) {
                            val width = geometry[index].bounds.width
                            originalWidth += width
                            scaledWidth += width * wordPoses[geometry[index].wordIndex].scale * (letterPoses[index]?.scale ?: 1f)
                        }
                        var cursor = geometry[run.first()].bounds.left + (originalWidth - scaledWidth) / 2f
                        run.forEach { index ->
                            val slice = geometry[index]
                            val wordScale = wordPoses[slice.wordIndex].scale
                            val letterScale = letterPoses[index]?.scale ?: 1f
                            val originalLeft = slice.wordBounds.center.x + wordScale *
                                (slice.bounds.center.x + letterScale * (slice.bounds.left - slice.bounds.center.x) - slice.wordBounds.center.x)
                            joinedTranslations[index] = cursor - originalLeft
                            cursor += slice.bounds.width * wordScale * letterScale
                        }
            }
            geometry.forEachIndexed { index, slice ->
                val word = groups[slice.wordIndex]
                val wordPose = wordPoses[slice.wordIndex]
                // Spicy's inline-block scale uses its own centre, including split
                // syllables. Anchoring neighbouring pieces to their shared edge
                // introduced opposing sideways motion as each spring swelled.
                val pivot = slice.wordBounds.center
                val letters = word.letters
                val letterPose = letterPoses[index]
                val fill = if (letterPose != null && letters != null) SpicyLyricsMotion.letterFill(letters[slice.letter], now)
                    else -.2f + 1.2f * word.progress(now)
                withTransform({
                    translate(left = joinedTranslations[index], top = wordPose.liftEm * fontPx)
                    scale(wordPose.scale, wordPose.scale, pivot = pivot)
                    if (letterPose != null) {
                        translate(top = letterPose.liftEm * 2f * fontPx)
                        scale(letterPose.scale, letterPose.scale, pivot = slice.bounds.center)
                    }
                }) {
                    val strength = if (letterPose != null) letterPose.glow else wordPose.glow
                    val bloomAlpha = if (letterPose != null) (strength * .65f).coerceIn(0f, .65f)
                        else (strength * .35f).coerceIn(0f, .35f)
                    blooms.getOrNull(index)?.let { bloom ->
                        if (bloomAlpha > .001f) drawImage(bloom.image, bloom.origin, alpha = bloomAlpha)
                    }
                    val inkBounds = inkMasks[index]
                    val baseAlpha = if (line.isBackground) 1f else .85f
                    val glowingAlpha = baseAlpha + (1f - baseAlpha) * strength.coerceIn(0f, 1f)
                    drawFill(text, origin, slice.bounds, fill, slice.rtl, fontPx, inkBounds, glowingAlpha)
                }
            }
        })
}

/** Line-sync's vertical fill and breath use the original line interval, including wrapped rows. */
@Composable
private fun SpicyLineSyncedText(line: LyricLine, positionMs: State<Long>, style: TextStyle,
    active: Boolean, modifier: Modifier, inactiveColor: Color, nextLineTime: Long?) {
    val scale by animateFloatAsState(if (active) 1.05f else 1f,
        tween(200, easing = CubicBezierEasing(.37f, 0f, .63f, 1f)), label = "spicyLineScale")
    val direction = LocalLayoutDirection.current
    val right = style.textAlign == TextAlign.Right ||
        (style.textAlign == TextAlign.Start && direction == LayoutDirection.Rtl) ||
        (style.textAlign == TextAlign.End && direction == LayoutDirection.Ltr)
    val pivot = if (style.textAlign == TextAlign.Center) .5f else if (right) 1f else 0f
    var layout by remember(line.text, style) { mutableStateOf<TextLayoutResult?>(null) }
    var frame by remember { mutableLongStateOf(0L) }
    val clock = remember(line.time, line.text) { SpicyClock() }
    val glow = remember(line.time, line.text) { SpicySpring(1f, .5f) }
    LaunchedEffect(active) {
        clock.lastFrame = -1L
        if (active) while (true) withFrameNanos { frame = it }
    }
    val end = line.effectiveEndTime?.takeIf { it > line.time }
        ?: nextLineTime?.takeIf { it > line.time }
    Text(line.text, style = style, color = inactiveColor, overflow = TextOverflow.Visible,
        onTextLayout = {
            if (layout?.layoutInput != it.layoutInput || layout?.size != it.size) layout = it
        }, modifier = modifier.fillMaxWidth().graphicsLayer {
            scaleX = scale; scaleY = scale
            transformOrigin = TransformOrigin(pivot, .5f)
            clip = false
        }.drawWithContent {
            val measured = layout
            if (!active || measured == null) { drawContent(); return@drawWithContent }
            val now = positionMs.value
            val step = SpicyFrameTiming.sample(clock.lastFrame, frame, clock.lastPosition, now)
            val dt = step.seconds
            val snap = step.reset
            clock.lastFrame = frame; clock.lastPosition = now
            val progress = SpicyLyricsMotion.lineProgress(line.time, end, now)
            val light = glow.drive(if (end == null) 0f else SpicyLyricsMotion.lineGlow(progress), dt, snap)
            val height = measured.size.height.toFloat().coerceAtLeast(1f)
            val edge = height * progress
            // Brighten the glyph fill itself. A paragraph shadow can become a rectangular
            // haze on the desktop GPU backend and also paints outside counters and spaces.
            val innerGlow = light.coerceIn(0f, 1f)
            drawText(measured, brush = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = .85f + innerGlow * .15f),
                    Color.White.copy(alpha = .65f + innerGlow * .35f)),
                startY = edge, endY = edge + height * .2f),
                // The paragraph was measured with the caller's dim upcoming/sung
                // colour. Compose otherwise inherits that alpha into this brush,
                // suppressing the active highlight and its entire glow envelope.
                alpha = 1f, shadow = Shadow.None)
        })
}

/** Spicy's gradient: lit up to [fill] of the slice, then a fade over the next fifth of it. */
private fun DrawScope.drawFill(text: ImageBitmap, origin: Offset, bounds: Rect, fill: Float, rtl: Boolean, fontPx: Float, inkBounds: Rect = bounds, litAlpha: Float = .85f) {
    // The raster contains the whole paragraph. Stay inside this row vertically:
    // expanding into its neighbours paints their unsung glyphs with this word's fill.
    // Slices must not overlap: half a pixel of a neighbouring glyph otherwise
    // follows two different transforms and flickers along the split boundary.
    clipRect(inkBounds.left, inkBounds.top, inkBounds.right, inkBounds.bottom) {
        if (fill >= 1f) { drawImage(text, origin, alpha = litAlpha); return@clipRect }
        drawImage(text, origin, alpha = SpicyLyricsMotion.UNSUNG_ALPHA)
        if (fill <= -.2f) return@clipRect
        val overlay = ((litAlpha - SpicyLyricsMotion.UNSUNG_ALPHA) / (1f - SpicyLyricsMotion.UNSUNG_ALPHA)).coerceIn(0f, 1f)
        val fade = maxOf(bounds.width * .2f, fontPx * .05f)
        val solid = bounds.width * fill
        if (solid > 0f) {
            if (rtl) clipRect(left = bounds.right - solid) { drawImage(text, origin, alpha = overlay) }
            else clipRect(right = bounds.left + solid) { drawImage(text, origin, alpha = overlay) }
        }
        for (step in 0 until 10) {
            val near = solid + step * fade / 10
            val left = if (rtl) bounds.right - near - fade / 10 else bounds.left + near
            val right = left + fade / 10
            val clippedLeft = maxOf(bounds.left - .5f, left)
            val clippedRight = minOf(bounds.right + .5f, right)
            if (clippedRight > clippedLeft) clipRect(left = clippedLeft, right = clippedRight) {
                drawImage(text, origin, alpha = overlay * (1f - (step + .5f) / 10))
            }
        }
    }
}
