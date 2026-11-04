package com.auralis.music.ui.lyrics

import android.graphics.BlendMode
import android.graphics.LinearGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.pow

/** A screen-space focus field. Neither lyric identity nor scroll offset enters this profile. */
/** True while something else needs the frame budget; read at draw, so a change never recomposes. */
internal val LocalLyricsBlurSuspended =
    androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.State<Boolean>?> { null }

internal object LyricsViewportBlurProfile {
    // Each level past the first is a full-screen blur on every lyric frame.
    const val LEVELS = 3
    const val MAX_RADIUS_DP = 24f

    fun amount(y: Float, height: Float, focusFraction: Float, clearHalfHeight: Float): Float {
        if (!y.isFinite() || !height.isFinite() || height <= 0f || !focusFraction.isFinite() || !clearHalfHeight.isFinite()) return 0f
        val focus = height * focusFraction.coerceIn(.2f, .6f)
        val reach = minOf(focus, height - focus)
        val clear = clearHalfHeight.coerceAtLeast(0f)
        val distance = abs(y.coerceIn(0f, height) - focus)
        return ((distance - clear) / (reach - clear).coerceAtLeast(1f)).coerceIn(0f, 1f).pow(1.35f)
    }

    /** Adjacent blur levels crossfade with weights summing to one (no duplicate sharp text). */
    fun weight(amount: Float, level: Int): Float =
        (1f - abs(amount.coerceIn(0f, 1f) * (LEVELS - 1) - level)).coerceIn(0f, 1f)
}

/**
 * Blur belongs to the viewport, outside the scrolling list. The render graph and masks are
 * cached by viewport geometry, not rebuilt as individual rows move or become active.
 * Android <12 has no RenderEffect, matching Compose blur's existing sharp fallback.
 */
@Composable
internal fun Modifier.stationaryLyricsBlur(enabled: Boolean, focusFraction: Float, clearHalfHeightPx: () -> Float = { 0f }): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < 31) return this
    val density = LocalDensity.current.density
    val suspended = LocalLyricsBlurSuspended.current
    val cache = remember(density, focusFraction) { ViewportBlurEffectCache(density, focusFraction) }
    return graphicsLayer {
        renderEffect = if (suspended?.value == true) null else cache.forHeight(size.height, clearHalfHeightPx())
        clip = true
    }
}

@RequiresApi(31)
private class ViewportBlurEffectCache(private val density: Float, private val focus: Float) {
    private var previousHeight = -1f
    private var previousClear = -1f
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null

    fun forHeight(height: Float, requestedClear: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (height <= 0f) return null
        val clearHalfHeight = maxOf(minOf(48f * density, height * .10f), kotlin.math.ceil(requestedClear / 16f) * 16f)
        if (height == previousHeight && previousClear == clearHalfHeight) return effect
        previousClear = clearHalfHeight
        previousHeight = height
        val positions = FloatArray(129) { it / 128f }
        val amounts = positions.map { LyricsViewportBlurProfile.amount(it * height, height, focus, clearHalfHeight) }
        var combined: RenderEffect? = null
        repeat(LyricsViewportBlurProfile.LEVELS) { level ->
            val colors = IntArray(positions.size) { index ->
                val alpha = (LyricsViewportBlurProfile.weight(amounts[index], level) * 255f).toInt().coerceIn(0, 255)
                (alpha shl 24) or 0x00FFFFFF
            }
            val mask = RenderEffect.createShaderEffect(LinearGradient(0f, 0f, 0f, height, colors, positions, Shader.TileMode.CLAMP))
            val radius = LyricsViewportBlurProfile.MAX_RADIUS_DP * density * level / (LyricsViewportBlurProfile.LEVELS - 1)
            val content = if (level == 0) RenderEffect.createOffsetEffect(0f, 0f)
                else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
            val masked = RenderEffect.createBlendModeEffect(content, mask, BlendMode.DST_IN)
            // Add premultiplied, complementary masks. SRC_OVER would dim their overlap.
            combined = combined?.let { RenderEffect.createBlendModeEffect(it, masked, BlendMode.PLUS) } ?: masked
        }
        effect = combined?.asComposeRenderEffect()
        return effect
    }
}
