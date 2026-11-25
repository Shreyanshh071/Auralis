package com.auralis.music.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke

/*
 * Bold, rounded transport glyphs for the classic player. Material's FastRewind / Pause are thin at
 * any size; these are solid shapes whose corners are softened by stroking the same path with a
 * round join, so they read heavy and friendly like a native music app.
 */

/** Fills [path] and strokes it with a round join, which rounds every corner by about [round]/2. */
private fun DrawScope.roundedFill(path: Path, color: Color, round: Float) {
    drawPath(path, color, style = Fill)
    drawPath(path, color, style = Stroke(width = round, join = StrokeJoin.Round))
}

/** A right-pointing triangle filling the box from (left, top) to (right, bottom). */
private fun triangle(left: Float, top: Float, right: Float, bottom: Float, pointsRight: Boolean) = Path().apply {
    if (pointsRight) {
        moveTo(left, top)
        lineTo(right, (top + bottom) / 2f)
        lineTo(left, bottom)
    } else {
        moveTo(right, top)
        lineTo(left, (top + bottom) / 2f)
        lineTo(right, bottom)
    }
    close()
}

/**
 * Two overlapping triangles pointing forward ([forward] = true) or back. [skipProgress] (0..1)
 * plays the skip: the leading triangle shrinks away at its tip, the trailing one slides into its
 * place and a new one grows in behind. 0 and 1 both draw the resting glyph.
 */
@Composable
fun ClassicSkipGlyph(
    forward: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    skipProgress: () -> Float = { 0f }
) {
    Canvas(modifier) {
        val round = size.height * 0.16f
        val inset = round / 2f
        val half = size.width / 2f
        val top = inset
        val bottom = size.height - inset
        val midY = (top + bottom) / 2f
        // Each triangle spans just over half the width so the two touch at the middle.
        val backLeft = inset
        val backRight = half + inset * 0.2f
        val frontLeft = half - inset * 0.2f
        val frontRight = size.width - inset
        val p = skipProgress().coerceIn(0f, 1f)
        // Drawn pointing forward; the back glyph is the same picture mirrored.
        scale(scaleX = if (forward) 1f else -1f, scaleY = 1f, pivot = center) {
            if (p <= 0f || p >= 1f) {
                roundedFill(triangle(backLeft, top, backRight, bottom, true), color, round)
                roundedFill(triangle(frontLeft, top, frontRight, bottom, true), color, round)
                return@scale
            }
            // The leading triangle shrinks toward its tip and fades.
            val leave = 1f - p
            scale(leave, leave, pivot = Offset(frontRight, midY)) {
                roundedFill(triangle(frontLeft, top, frontRight, bottom, true), color.copy(alpha = color.alpha * leave), round)
            }
            // The trailing one slides forward into its place.
            val shift = (frontLeft - backLeft) * p
            roundedFill(triangle(backLeft + shift, top, backRight + shift, bottom, true), color, round)
            // A new one grows in at the back.
            scale(p, p, pivot = Offset(backLeft, midY)) {
                roundedFill(triangle(backLeft, top, backRight, bottom, true), color.copy(alpha = color.alpha * p), round)
            }
        }
    }
}

/** A one-shot 0→1 run started by each tap, for a transport button's press animation. */
@Stable
class TransportTapAnimation internal constructor(private val scope: CoroutineScope) {
    val progress = Animatable(1f)
    fun play() {
        scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        }
    }
}

@Composable
fun rememberTransportTapAnimation(): TransportTapAnimation {
    val scope = rememberCoroutineScope()
    return remember(scope) { TransportTapAnimation(scope) }
}

/**
 * A transport button without the stock ripple: its tap highlight is the feedback, and the
 * ripple drew a second, smaller disc inside it that lingered after the tap.
 */
@Composable
fun TransportButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClick = onClick
        ),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** The soft round highlight that swells behind a transport button while its tap plays. */
fun Modifier.transportTapHighlight(progress: () -> Float, color: Color = Color.White): Modifier =
    drawBehind {
        val p = progress().coerceIn(0f, 1f)
        if (p <= 0f || p >= 1f) return@drawBehind
        // Up quickly, then a slower fade.
        val bump = if (p < 0.25f) p / 0.25f else 1f - (p - 0.25f) / 0.75f
        drawCircle(color.copy(alpha = 0.14f * bump), radius = size.minDimension / 2f * (0.86f + 0.14f * bump))
    }

/**
 * Play/pause that trades glyphs like a native player: the old one shrinks and blurs away while
 * the new one grows in from small and soft.
 */
@Composable
fun ClassicPlayPauseGlyph(
    isPlaying: Boolean,
    color: Color,
    pauseModifier: Modifier,
    playModifier: Modifier
) {
    val t by animateFloatAsState(if (isPlaying) 1f else 0f, tween(340, easing = FastOutSlowInEasing), label = "playPauseSwap")
    Box(contentAlignment = Alignment.Center) {
        // Each glyph fills its own box edge to edge, so the blur needs room around it: drawn in
        // a tight layer it was cut off flat at the box edges (chopped bar ends, a sliced
        // triangle side). The padded layer gives the soft edge somewhere to fade into.
        Box(Modifier.graphicsLayer { swapLayer(t) }.padding(BLUR_ROOM)) {
            ClassicPauseGlyph(color, pauseModifier)
        }
        Box(Modifier.graphicsLayer { swapLayer(1f - t) }.padding(BLUR_ROOM)) {
            ClassicPlayGlyph(color, playModifier)
        }
    }
}

private val BLUR_ROOM = 12.dp

/** [shown] = 1 is fully in; toward 0 the glyph shrinks, fades and blurs. */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.swapLayer(shown: Float) {
    // The outgoing glyph is mostly gone before the incoming one takes over.
    val v = ((shown - 0.3f) / 0.7f).coerceIn(0f, 1f)
    alpha = v
    val s = 0.55f + 0.45f * v
    scaleX = s
    scaleY = s
    val blur = (1f - v) * 10f
    // Decal: outside the glyph is empty, so the blur fades out instead of smearing the edge pixels.
    renderEffect = if (blur > 0.5f && v > 0f) BlurEffect(blur, blur, TileMode.Decal) else null
}

@Composable
fun ClassicPlayGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val round = size.height * 0.14f
        val inset = round / 2f
        // Nudged right so the triangle looks centred (its visual mass sits left of its box).
        val shift = size.width * 0.06f
        roundedFill(
            triangle(size.width * 0.14f + shift, inset, size.width * 0.92f + shift - inset, size.height - inset, true),
            color,
            round
        )
    }
}

@Composable
fun ClassicPauseGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val barWidth = size.width * 0.38f
        val gap = size.width * 0.2f
        val start = (size.width - barWidth * 2 - gap) / 2f
        val radius = CornerRadius(barWidth * 0.28f)
        drawRoundRect(color, Offset(start, 0f), Size(barWidth, size.height), radius)
        drawRoundRect(color, Offset(start + barWidth + gap, 0f), Size(barWidth, size.height), radius)
    }
}
