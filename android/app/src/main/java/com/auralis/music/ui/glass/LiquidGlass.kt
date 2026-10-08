package com.auralis.music.ui.glass

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight

/**
 * Liquid glass theme (Appearance → Themes and colour → Liquid glass).
 *
 * When on, the app's pages are recorded into [backdrop] and the floating dock and mini player
 * render it through [liquidGlass]: blurred, saturated, bent at the edges like a lens, with a
 * specular rim. [collapse] runs 0 → 1 as the dock minimizes and the mini player tucks in beside it.
 */
@Stable
class LiquidGlassContext(
    val backdrop: Backdrop,
    val isDark: Boolean,
    val collapse: State<Float>
)

/**
 * 0 -> 1 as the dock minimizes on scroll and the mini player tucks in beside it. Independent of
 * the glass theme: the New and Material3 mini players collapse with or without it.
 */
val LocalDockCollapse = staticCompositionLocalOf<State<Float>?> { null }

/** Null when the theme is off; the dock and mini player then keep their usual frosted look. */
val LocalLiquidGlass = staticCompositionLocalOf<LiquidGlassContext?> { null }

/**
 * Glass for the mini player alone: set when the whole theme is on, or when the mini-player
 * background style is "Liquid glass" (which leaves the dock and everything else as they are).
 */
val LocalMiniPlayerGlass = staticCompositionLocalOf<LiquidGlassContext?> { null }

/** Blur needs RenderEffect (Android 12); the lens needs RuntimeShader (Android 13). */
fun isLiquidGlassSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Draws this element as liquid glass over [LiquidGlassContext.backdrop].
 * [tint] is laid over the glass so content stays readable on any artwork.
 */
@Composable
fun Modifier.liquidGlass(
    glass: LiquidGlassContext,
    shape: CornerBasedShape,
    tint: Color = if (glass.isDark) Color(0xFF101010).copy(alpha = 0.38f) else Color.White.copy(alpha = 0.42f),
    press: GlassPress? = null,
    pressedScale: Float = 1.12f
): Modifier {
    // Held, the glass swells on a bouncy spring; the scale goes through the backdrop's layer
    // block so the library inverts it and the glass keeps showing what is really behind it.
    val pressLayer: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)? = press?.let { p ->
        {
            val scale = lerp(1f, pressedScale, p.progress)
            scaleX = scale
            scaleY = scale
            // The clip rides inside the scaled layer, so the swollen glass isn't cut to rest size.
            this.shape = shape
            clip = true
        }
    }
    if (!isLiquidGlassSupported()) {
        // Older Android: a translucent fill with the same rim, no live blur.
        return this
            .then(if (pressLayer != null) Modifier.graphicsLayer(pressLayer) else Modifier)
            .background(if (glass.isDark) Color(0xFF1A1A1A).copy(alpha = 0.92f) else Color(0xFFF2F2F2).copy(alpha = 0.94f), shape)
            .border(0.75.dp, rimBrush(glass.isDark), shape)
    }
    val density = LocalDensity.current
    // Strong enough that whatever is behind (artwork card edges especially) melts into colour;
    // at 6dp the edges stayed sharp and read as a square behind every glass circle.
    val blurPx = with(density) { 18.dp.toPx() }
    val lensHeightPx = with(density) { 20.dp.toPx() }
    val lensAmountPx = with(density) { 28.dp.toPx() }
    val fallbackBaseColor = if (glass.isDark) {
        Color(0xFF1C1D20).copy(alpha = 0.70f)
    } else {
        Color(0xFFF2F2F5).copy(alpha = 0.75f)
    }
    // Clip the whole element to its shape from the outside. The library clips its own layer to
    // the shape, but on this Compose version that clip doesn't hold, and the glass filled its
    // square bounding box (a dark square behind every circle and pill end). An outer clip
    // contains everything the glass draws, whatever part of it spills.
    val pressBumpPx = with(density) { 2.dp.toPx() }
    return this.then(if (pressLayer == null) Modifier.clip(shape) else Modifier).drawBackdrop(
        backdrop = glass.backdrop,
        shape = { shape },
        layerBlock = pressLayer,
        effects = {
            val pressed = press?.progress ?: 0f
            vibrancy()
            blur(blurPx + pressBumpPx * pressed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(
                    refractionHeight = lensHeightPx + pressBumpPx * pressed,
                    refractionAmount = lensAmountPx,
                    depthEffect = true,
                    chromaticAberration = true
                )
            }
        },
        highlight = { Highlight.Default },
        // An outer shadow would be cut off by the clip above, so none is drawn.
        shadow = null,
        // Base translucent glass fill behind the live backdrop. Guarantees the element never degrades
        // into a transparent frame when sampling unattached coordinates or transparent pixels.
        onDrawBehind = { drawOutline(shape.createOutline(size, layoutDirection, this), fallbackBaseColor) },
        // Fill the tint in the element's own shape. A plain drawRect relied on the layer clipping
        // to the shape, which it doesn't on this Compose version: the tint showed as a dark
        // square around every glass circle and pill.
        onDrawSurface = {
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, tint)
            val pressed = press?.progress ?: 0f
            if (pressed > 0f) {
                // A soft light pooled under the finger while held.
                val touch = press?.touch?.takeIf { it.isSpecified } ?: center
                drawOutline(
                    outline,
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.18f * pressed), Color.Transparent),
                        center = touch,
                        radius = size.minDimension * 1.2f
                    ),
                    blendMode = BlendMode.Plus
                )
            }
        }
    ).then(if (press != null) Modifier.glassPress(press) else Modifier)
}

/**
 * Press state for one glass surface: [progress] springs 0 -> 1 while a finger is down on it
 * (held or sliding across it) and bounces back on release. [touch] is where the finger is.
 */
@Stable
class GlassPress internal constructor(private val scope: CoroutineScope) {
    private val animation = Animatable(0f, 0.001f)
    private val pressSpec = spring(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.001f)

    /** 0 at rest, 1 fully pressed. Read it in draw or layer blocks only. */
    val progress: Float get() = animation.value

    var touch by mutableStateOf(Offset.Unspecified)
        private set

    internal fun down(position: Offset) {
        touch = position
        scope.launch { animation.animateTo(1f, pressSpec) }
    }

    internal fun move(position: Offset) {
        touch = position
    }

    internal fun up() {
        scope.launch { animation.animateTo(0f, pressSpec) }
    }
}

@Composable
fun rememberGlassPress(): GlassPress {
    val scope = rememberCoroutineScope()
    return remember(scope) { GlassPress(scope) }
}

/** Watches touches without consuming any, so the tabs and buttons inside still get their taps. */
private fun Modifier.glassPress(press: GlassPress): Modifier = pointerInput(press) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        press.down(down.position)
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.pressed } ?: break
                press.move(change.position)
            }
        } finally {
            press.up()
        }
    }
}

private fun rimBrush(isDark: Boolean) = Brush.verticalGradient(
    if (isDark) listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f))
    else listOf(Color.White.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.06f))
)

/**
 * Scrolling a page down minimizes the dock (and pulls the mini player down beside it);
 * scrolling up restores it. Small jitters are ignored so it doesn't flicker mid-scroll.
 */
@Stable
class DockMinimizeState {
    var isMinimized by mutableStateOf(false)
        private set

    private var travel = 0f

    fun expand() {
        travel = 0f
        isMinimized = false
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            if (dy == 0f) return Offset.Zero
            // Accumulate in one direction; reset when the direction flips.
            travel = if ((travel < 0f) == (dy < 0f)) travel + dy else dy
            when {
                travel < -ThresholdPx && !isMinimized -> isMinimized = true
                travel > ThresholdPx && isMinimized -> isMinimized = false
            }
            // Pulling past the top of a page always brings the dock back.
            if (available.y > 0f && isMinimized) isMinimized = false
            return Offset.Zero
        }
    }

    private companion object {
        const val ThresholdPx = 48f
    }
}
