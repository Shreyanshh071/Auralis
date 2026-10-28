package com.auralis.music.ui.lyrics.renderers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.rememberTextMeasurer
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
private data class SpicyWord(val start: Int, val end: Int, val parts: List<TimedPart>) {
    val startMs = parts.minOfOrNull { it.range.word.time } ?: 0L
    val endMs = if (parts.isNotEmpty() && parts.all { it.range.word.endTime != null })
        parts.maxOf { it.range.word.endTime!! } else null

    /** Held words get letter timing: each part's letters share it, ending a little early. */
    val letters: List<SpicyLetterTime>? = run {
        if (endMs == null || endMs - startMs < 1000L || end - start < 2) return@run null
        if (parts.any { it.range.word.endTime!! - it.range.word.time < 1000L }) return@run null
        (start until end).map { offset ->
            val part = parts.firstOrNull { offset >= it.from && offset < it.to } ?: return@run null
            val partStart = part.range.word.time
            val partEnd = part.range.word.endTime!!
            val finish = maxOf(partEnd - SpicyLyricsMotion.LETTER_LEAD_MS, partStart + (partEnd - partStart) / 2)
            val each = (finish - partStart).toDouble() / (part.to - part.from)
            val index = offset - part.from
            SpicyLetterTime(partStart + (each * index).toLong(), partStart + (each * (index + 1)).toLong())
        }
    }

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

/** A glow shaped like the slice's own glyphs, so it never shows the edges of a clip box. */
private data class SpicyGlow(val image: ImageBitmap, val topLeft: Offset)

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
    inactiveColor: Color = Color.White
) {
    val imageLifetime = remember(line.text) { SpicyImageLifetime() }
    // Line-sync is a whole-line highlight. Do not manufacture timed words from its text.
    if (!isActive || words.isNullOrEmpty()) {
        Text(line.text, style = style, color = if (isActive) Color.White.copy(alpha = if (line.isBackground) 1f else .85f) else inactiveColor, overflow = TextOverflow.Visible,
            modifier = modifier.fillMaxWidth())
        return
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val measurer = rememberTextMeasurer()
    var layout by remember(line.text, style) { mutableStateOf<TextLayoutResult?>(null) }
    var frameNanos by remember { mutableLongStateOf(0L) }
    val motionActive = com.auralis.music.ui.theme.LocalPreviewMotionActive.current != false
    LaunchedEffect(motionActive) {
        if (motionActive) while (true) withFrameNanos { frameNanos = it }
    }
    val groups = remember(line.text, words) {
        val displayed = SpicyBackingText.displayWords(line.text, words, line.isBackground)
        val spans = LyricsEngine.mapWordsToLineSpans(line.text, displayed?.flatMap { it.syllables ?: listOf(it) })
        // A source syllable is an animation unit, even inside a written word.
        // Combining all syllables first loses the motion of a held final syllable.
        spans.mapNotNull { span ->
            var start = span.startIndex
            var end = span.endIndex
            while (start < end && line.text[start].isWhitespace()) start++
            while (end > start && line.text[end - 1].isWhitespace()) end--
            if (start >= end) null else SpicyWord(start, end, listOf(TimedPart(span, start, end)))
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
                    // Shape complex scripts as a complete word; splitting their glyphs breaks
                    // conjuncts and joining. Letter emphasis is confined to simple Latin runs.
                    val letters = line.text.substring(word.start, word.end)
                    val emphasize = word.letters != null && first == last && !rtl &&
                        style.fontStyle != androidx.compose.ui.text.font.FontStyle.Italic &&
                        letters.all { it in ' '..'~' }
                    if (emphasize) (from until to).map { offset ->
                        SpicySlice(measured.getBoundingBox(offset), wordIndex, bounds, false,
                            offset, offset + 1, row, offset - word.start)
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
                drawText(measured, color = Color.White, topLeft = Offset(pad.toFloat(), pad.toFloat()))
            }
            imageLifetime.retain(listOf(bitmap))
            bitmap to pad.toFloat()
        }
    }
    // Spicy's text-shadow is 4 + 12 x glow px for letters and 4 + 2 x glow px for words at its
    // 56 px lyric size. Each glow is shaped from the slice's own glyphs at the full radius.
    val glows = remember(layout, geometry, density, direction) {
        layout?.let { measured ->
            val fontPx = with(density) { style.fontSize.toPx() }
            geometry.map { slice ->
                if (groups[slice.wordIndex].endMs == null) return@map null
                val cssRadius = (if (slice.letter >= 0) 16f else 6f) * fontPx / 56f
                val blur = ((cssRadius / 2f - .5f) / .57735f).coerceAtLeast(.5f)
                val alone = measurer.measure(line.text.substring(slice.from, slice.to), style)
                val pad = cssRadius * 2f + 2f
                val size = Size(alone.size.width + pad * 2, alone.size.height + pad * 2)
                val image = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                CanvasDrawScope().draw(density, direction, Canvas(image), size) {
                    drawText(alone, color = Color.Transparent, topLeft = Offset(pad, pad),
                        shadow = Shadow(Color.White, blurRadius = blur))
                }
                val glyphs = alone.getPathForRange(0, slice.to - slice.from).getBounds()
                SpicyGlow(image, Offset(slice.bounds.left - glyphs.left - pad,
                    measured.getLineBaseline(slice.row) - alone.firstBaseline - pad))
            }
        }.orEmpty().also { values -> imageLifetime.retain(values.mapNotNull { it?.image }) }
    }
    val wordSprings = remember(groups) { groups.map { SpicySprings() } }
    val letterSprings = remember(geometry) { geometry.map { SpicySprings() } }
    val clock = remember(geometry) { SpicyClock() }
    // onTextLayout supplies the mask for the following draw. Until it is available,
    // drawContent must stay unsung: white text here flashed the entire new line.
    // The cached raster above is explicitly white; its timed fills are unaffected.
    Text(line.text, style = style, color = Color.White.copy(alpha = SpicyLyricsMotion.UNSUNG_ALPHA), overflow = TextOverflow.Visible,
        onTextLayout = { layout = it }, modifier = modifier.fillMaxWidth().drawWithContent {
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
            geometry.forEachIndexed { index, slice ->
                val word = groups[slice.wordIndex]
                val wordPose = wordPoses[slice.wordIndex]
                val whole = slice.wordBounds
                // Adjacent syllables scale from their shared written boundary, keeping
                // the spelling joined while their own source intervals animate.
                val joinedBefore = groups.getOrNull(slice.wordIndex - 1)?.end == word.start
                val joinedAfter = groups.getOrNull(slice.wordIndex + 1)?.start == word.end
                val pivot = when {
                    joinedBefore -> Offset(if (slice.rtl) whole.right else whole.left, whole.center.y)
                    joinedAfter -> Offset(if (slice.rtl) whole.left else whole.right, whole.center.y)
                    else -> whole.center
                }
                val letters = word.letters
                val letterPose = if (slice.letter >= 0 && letters != null) {
                    val goal = SpicyLyricsMotion.letterPose(letters, slice.letter, now)
                    val springs = letterSprings[index]
                    SpicyWordPose(springs.scale.drive(goal.scale, dt, snap), springs.lift.drive(goal.liftEm, dt, snap),
                        springs.glow.drive(goal.glow, dt, snap))
                } else null
                val fill = if (letterPose != null && letters != null) SpicyLyricsMotion.letterFill(letters[slice.letter], now)
                    else -.2f + 1.2f * word.progress(now)
                withTransform({
                    translate(top = wordPose.liftEm * fontPx)
                    scale(wordPose.scale, wordPose.scale, pivot = pivot)
                    if (letterPose != null) {
                        translate(top = letterPose.liftEm * 2f * fontPx)
                        scale(letterPose.scale, letterPose.scale, pivot = slice.bounds.center)
                    }
                }) {
                    val glow = glows.getOrNull(index)
                    val strength = if (letterPose != null) (letterPose.glow * 1.85f) else (wordPose.glow * .35f)
                    if (glow != null && strength > .01f) drawImage(glow.image, glow.topLeft, alpha = strength.coerceAtMost(1f))
                    val inkBounds = inkMasks[index]
                    drawFill(text, origin, slice.bounds, fill, slice.rtl, fontPx, inkBounds,
                        if (line.isBackground) 1f else .85f)
                }
            }
        })
}

/** Spicy's gradient: lit up to [fill] of the slice, then a fade over the next fifth of it. */
private fun DrawScope.drawFill(text: ImageBitmap, origin: Offset, bounds: Rect, fill: Float, rtl: Boolean, fontPx: Float, inkBounds: Rect = bounds, litAlpha: Float = .85f) {
    // The raster contains the whole paragraph. Stay inside this row vertically:
    // expanding into its neighbours paints their unsung glyphs with this word's fill.
    clipRect(inkBounds.left - .5f, inkBounds.top, inkBounds.right + .5f, inkBounds.bottom) {
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
