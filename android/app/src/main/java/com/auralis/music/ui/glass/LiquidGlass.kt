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
import com.kyant.backdrop.shadow.Shadow

// BitChord LiquidGlass.kt, commit 04a4a83889bb8fa5bd6ee6f6097f0c6608671d68
// (GPL-3.0): https://github.com/kushagrasinghx/BitChord
private const val GLASS_RESOLUTION_SCALE = 0.33f

internal fun glassSurfaceTint(isDark: Boolean): Color =
    (if (isDark) Color(0xFF121212) else Color(0xFFFAFAFA)).copy(alpha = 0.4f)

internal fun glassBarTint(isDark: Boolean): Color =
    if (isDark) Color.White.copy(alpha = 0.09f) else glassSurfaceTint(false)

internal fun glassIndicatorTint(isDark: Boolean): Color =
    Color.White.copy(alpha = if (isDark) 0.14f else 0.5f)

internal fun glassContentColor(isDark: Boolean): Color = if (isDark) Color.White else Color.Black

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
    tint: Color = glassSurfaceTint(glass.isDark),
    press: GlassPress? = null,
    pressedScale: Float = 1.12f,
    // Large modal surfaces need more frosting than a small dock or button.
    blurRadius: androidx.compose.ui.unit.Dp = 8.dp,
    lensDepth: androidx.compose.ui.unit.Dp = 24.dp,
    edgeHighlight: Highlight? = Highlight.Default
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
    // Match BitChord's 8dp blur / 24dp lens at one-third capture resolution.
    // Effects stay constant during presses; only the surface transform animates.
    val blurPx = with(density) { blurRadius.toPx() } * GLASS_RESOLUTION_SCALE
    val lensHeightPx = with(density) { lensDepth.toPx() } * GLASS_RESOLUTION_SCALE
    val lensAmountPx = with(density) { lensDepth.toPx() } * GLASS_RESOLUTION_SCALE
    // The backdrop node clips its sampled layer; shaped tint also keeps circles and pills
    // contained without clipping the reference's outer shadow and directional highlight.
    return this.drawBackdrop(
        backdrop = glass.backdrop,
        shape = { shape },
        layerBlock = pressLayer,
        // The effects never read the press: changing blur/lens strength while held rebuilt the
        // RenderEffect every frame, and the first rebuild on touch-down froze the screen ~190ms
        // (measured on device), so the swell started late. The press only scales and glows.
        effects = {
            vibrancy()
            blur(blurPx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(
                    // Keep refraction visible on tall dialogs as well as pills.
                    // The fixed 24dp band left almost the whole modal unwarped.
                    refractionHeight = if (size.isSpecified && !size.isEmpty())
                        maxOf(lensHeightPx, size.minDimension * .45f) else lensHeightPx,
                    refractionAmount = lensAmountPx,
                    depthEffect = true,
                    // The dispersion shader samples seven times per pixel. Keep
                    // the lens geometry, using one sample so scrolling glass does
                    // not pay for rainbow splitting across the whole surface.
                    chromaticAberration = false
                )
            }
        },
        highlight = { edgeHighlight },
        shadow = { Shadow.Default },
        backdropScale = GLASS_RESOLUTION_SCALE,
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

    /**
     * Spring stiffness. Small circles need a stiffer spring than the wide dock: the same early
     * fraction of the swell is only a pixel or two on a 56dp circle, so with the dock's soft
     * spring the circle looked like it started swelling late.
     */
    var stiffness: Float = 300f
    private val pressSpec get() = spring(dampingRatio = 0.5f, stiffness = stiffness, visibilityThreshold = 0.001f)

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
