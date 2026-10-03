package com.auralis.music.ui.screens.wrapped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Wide display face used for Wrapped headlines and the big outlined numbers. */
val WrappedDisplayFont = FontFamily(Font(R.font.bbh_bartle_regular, FontWeight.Normal))

// Taken from the Auralis logo: near-black brown base, light and dark browns, black, a little dark gold.
private val BaseColor = Color(0xFF120B07)

/** A soft colour glow that wanders on its own looping path. Positions and radius are fractions of width/height. */
private class Glow(
    val color: Color,
    val alpha: Float,
    val radius: Float,
    val cx: Float, val cy: Float,
    val ax: Float, val ay: Float,
    val fx: Int, val fy: Int,
    val px: Float, val py: Float
) {
    // Three stops fall off gently, so the edge of a glow is never visible.
    val stops = arrayOf(
        0f to color.copy(alpha = alpha),
        0.45f to color.copy(alpha = alpha * 0.45f),
        1f to Color.Transparent
    )
}

private val Glows = listOf(
    Glow(Color(0xFF4A2C15), 0.85f, 1.00f, 0.70f, 0.25f, 0.30f, 0.20f, 1, 2, 0.0f, 1.0f), // dark brown
    Glow(Color(0xFFA0703F), 0.55f, 0.85f, 0.30f, 0.35f, 0.30f, 0.25f, 2, 1, 2.1f, 0.3f), // light brown
    Glow(Color(0xFFC9971C), 0.32f, 0.75f, 0.60f, 0.75f, 0.35f, 0.20f, 1, 3, 4.0f, 2.2f), // dark gold
    Glow(Color(0xFF7A4E26), 0.60f, 0.90f, 0.25f, 0.85f, 0.25f, 0.15f, 3, 2, 1.3f, 5.0f), // caramel
    Glow(Color(0xFF050302), 0.75f, 0.80f, 0.50f, 0.55f, 0.40f, 0.30f, 2, 3, 3.3f, 0.7f), // black
    Glow(Color(0xFFE6B45A), 0.16f, 0.55f, 0.50f, 0.45f, 0.45f, 0.35f, 3, 1, 5.2f, 3.9f)  // gold highlight
)

private val SheenStops = arrayOf(
    0f to Color.Transparent,
    0.45f to Color(0xFFE6B45A).copy(alpha = 0f),
    0.5f to Color(0xFFE6B45A).copy(alpha = 0.07f),
    0.55f to Color(0xFFE6B45A).copy(alpha = 0f),
    1f to Color.Transparent
)

/**
 * Near-black brown base under large soft glows in the logo's browns, black and dark gold that
 * drift across the screen and blend into each other, plus a faint gold sheen sweeping across.
 */
@Composable
fun WrappedBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    // One clock for everything; every motion uses a whole-number multiple of it, so the loop is seamless.
    val t by rememberInfiniteTransition(label = "wrappedBackground").animateFloat(
        0f, 2f * PI.toFloat(),
        infiniteRepeatable(tween(48_000, easing = LinearEasing)), label = "clock"
    )

    BoxWithConstraints(modifier.fillMaxSize().background(BaseColor)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val dots = remember(widthPx, heightPx) {
            val spacing = 30f
            buildList {
                for (x in 0..(widthPx / spacing).toInt()) {
                    for (y in 0..(heightPx / spacing).toInt()) add(Offset(x * spacing, y * spacing))
                }
            }
        }

        // The clock is read only inside the draw lambda, so the background moves without recomposing.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            Glows.forEach { g ->
                val center = Offset(
                    w * (g.cx + g.ax * sin(t * g.fx + g.px)),
                    h * (g.cy + g.ay * sin(t * g.fy + g.py))
                )
                val radius = w * g.radius * (1f + 0.15f * sin(t * (g.fx + g.fy) + g.px))
                drawCircle(Brush.radialGradient(*g.stops, center = center, radius = radius), radius, center)
            }

            // Sheen: a wide, faint diagonal light passing across twice per loop.
            val sweep = ((t / (2f * PI.toFloat())) * 2f) % 1f
            val shift = (sweep * 3f - 1.5f) * w
            drawRect(
                Brush.linearGradient(
                    *SheenStops,
                    start = Offset(shift - w, shift * 0.6f),
                    end = Offset(shift + w, shift * 0.6f + h)
                )
            )

            drawPoints(dots, PointMode.Points, Color.White.copy(alpha = 0.035f), strokeWidth = 3f, cap = StrokeCap.Round)
        }
        Box(Modifier.fillMaxSize(), content = content)
    }
}

enum class FloatingShape { Circle, Square, Line }

private class Floater(
    val shape: FloatingShape,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val size: Float,
    val alpha: Float,
    val periodMs: Int
)

/** Small white circles, squares or streaks drifting back and forth behind a page. */
@Composable
fun FloatingShapes(count: Int = 20, shapes: List<FloatingShape> = listOf(FloatingShape.Circle)) {
    val floaters = remember {
        val random = Random(System.nanoTime())
        List(count) {
            val shape = shapes.random(random)
            Floater(
                shape = shape,
                startX = random.nextFloat(),
                startY = random.nextFloat(),
                endX = random.nextFloat(),
                endY = random.nextFloat(),
                size = if (shape == FloatingShape.Circle) random.nextFloat() * 15f + 5f else random.nextFloat() * 50f + 10f,
                alpha = random.nextFloat() * 0.3f + 0.1f,
                periodMs = random.nextInt(4_000, 10_000)
            )
        }
    }
    // One shared clock (in ms) instead of an animation per shape.
    val clock by rememberInfiniteTransition(label = "floaters").animateFloat(
        0f, 600_000f, infiniteRepeatable(tween(600_000, easing = LinearEasing)), label = "clock"
    )
    Canvas(Modifier.fillMaxSize()) {
        floaters.forEach { f ->
            // Triangle wave 0 → 1 → 0 over two periods, like a reversing tween.
            val phase = (clock / f.periodMs) % 2f
            val p = 1f - abs(phase - 1f)
            val x = f.startX + (f.endX - f.startX) * p
            val y = f.startY + (f.endY - f.startY) * p
            val color = Color.White.copy(alpha = f.alpha)
            when (f.shape) {
                FloatingShape.Circle -> drawCircle(color, f.size, Offset(x * size.width, y * size.height))
                FloatingShape.Square -> drawRect(color, Offset(x * size.width, y * size.height), Size(f.size, f.size))
                FloatingShape.Line -> {
                    val ex = x + (f.endX - f.startX) * 0.1f
                    val ey = y + (f.endY - f.startY) * 0.1f
                    drawLine(color, Offset(x * size.width, y * size.height), Offset(ex * size.width, ey * size.height), 2f)
                }
            }
        }
    }
}

/** A spinning outlined arc, ring or square, scattered in page corners. */
@Composable
fun SpinningOutline(modifier: Modifier = Modifier, isVisible: Boolean) {
    val rotation = remember { Animatable(0f) }
    val shape = remember { Random.nextInt(3) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            kotlinx.coroutines.delay(Random.nextLong(500))
            rotation.animateTo(
                360f,
                infiniteRepeatable(tween(Random.nextInt(1_000, 3_000), easing = LinearEasing), RepeatMode.Restart)
            )
        }
    }
    Canvas(modifier.graphicsLayer { rotationZ = rotation.value }) {
        val stroke = Stroke(2.dp.toPx())
        val color = Color.White.copy(alpha = 0.2f)
        when (shape) {
            0 -> drawArc(color, 0f, 90f, false, style = stroke)
            1 -> drawCircle(color, style = stroke)
            else -> drawRect(color, style = stroke)
        }
    }
}

/** Outlines in each corner of a page: (start/top offset pair, size). */
@Composable
fun BoxScope.CornerOutlines(isVisible: Boolean, perCorner: Int = 3, spread: Int = 120) {
    val corners = remember {
        listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd).associateWith {
            List(perCorner) { Triple(Random.nextInt(0, spread).dp, Random.nextInt(0, spread).dp, Random.nextInt(20, 90).dp) }
        }
    }
    corners.forEach { (alignment, items) ->
        Box(Modifier.align(alignment)) {
            items.forEach { (dx, dy, size) ->
                val pad = when (alignment) {
                    Alignment.TopStart -> Modifier.padding(start = dx, top = dy)
                    Alignment.TopEnd -> Modifier.padding(end = dx, top = dy)
                    Alignment.BottomStart -> Modifier.padding(start = dx, bottom = dy)
                    else -> Modifier.padding(end = dx, bottom = dy)
                }
                SpinningOutline(pad.size(size), isVisible)
            }
        }
    }
}

/** Single-line text that shrinks until it fits the width; hidden until it does. */
@Composable
fun AutoResizingText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    var scaled by remember(text, style) { mutableStateOf(style) }
    var ready by remember(text, style) { mutableStateOf(false) }
    Text(
        text = text,
        style = scaled,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        onTextLayout = { layout ->
            if (layout.didOverflowWidth) scaled = scaled.copy(fontSize = scaled.fontSize * 0.9f) else ready = true
        }
    )
}

/** Text where `**…**` spans render bold. */
@Composable
fun BoldSpansText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val annotated = remember(text) {
        buildAnnotatedString {
            var bold = false
            text.split("**").forEachIndexed { i, part ->
                if (i > 0) bold = !bold
                withStyle(SpanStyle(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)) { append(part) }
            }
        }
    }
    Text(annotated, modifier, style = style)
}

/** Big outlined number that counts up from zero when its page becomes visible. */
@Composable
fun CountUpNumber(target: Long, isVisible: Boolean, modifier: Modifier = Modifier, horizontalPadding: Dp = 16.dp) {
    val animated = remember { Animatable(0f) }
    LaunchedEffect(isVisible, target) {
        if (isVisible && target > 0) animated.animateTo(target.toFloat(), tween(1_500, easing = FastOutSlowInEasing))
    }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
        val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
        // Sized for the final value so the number doesn't change size while counting.
        val style = remember(target, constraints.maxWidth) {
            var s = TextStyle(
                color = Color.White,
                fontFamily = WrappedDisplayFont,
                fontSize = 96.sp,
                textAlign = TextAlign.Center,
                drawStyle = Stroke(strokePx)
            )
            val text = target.toString()
            while (measurer.measure(text, s).size.width > constraints.maxWidth && s.fontSize.value > 12f) {
                s = s.copy(fontSize = s.fontSize * 0.95f)
            }
            s.copy(lineHeight = s.fontSize * 1.08f)
        }
        Text(
            text = animated.value.toLong().toString(),
            style = style,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
