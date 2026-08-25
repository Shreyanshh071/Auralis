package com.auralis.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.auralis.music.ui.theme.AuralisBackground
import com.auralis.music.ui.theme.AuralisPitchBlack
import com.auralis.music.ui.theme.dynamicPalette
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================
// 🌌 ATMOSPHERIC GLOW & MUSIC-REACTIVE BACKGROUND ENGINE
// ============================================================================

/**
 * Multi-layer hardware-accelerated ambient glow background that responds dynamically
 * to album artwork colors and playback state.
 *
 * @param modifier Layout modifier
 * @param primaryTint Primary atmospheric tint (default: dynamicPalette.tintA)
 * @param secondaryTint Secondary atmospheric tint (default: dynamicPalette.tintB)
 * @param tertiaryTint Tertiary atmospheric tint (default: dynamicPalette.tintC)
 * @param isPlaying Whether music is playing (activates subtle organic drift)
 * @param alpha Overall intensity of the background atmospheric blooms
 * @param content Screen content rendered above the glowing atmosphere
 */
@Composable
fun AtmosphericGlowBackground(
    modifier: Modifier = Modifier,
    primaryTint: Color = MaterialTheme.dynamicPalette.tintA,
    secondaryTint: Color = MaterialTheme.dynamicPalette.tintB,
    tertiaryTint: Color = MaterialTheme.dynamicPalette.tintC,
    isPlaying: Boolean = true,
    alpha: Float = 0.85f,
    content: @Composable BoxScope.() -> Unit
) {
    // Smooth 1.2-second color morphing transitions on song/artwork change
    val animTintA by animateColorAsState(
        targetValue = primaryTint,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "AtmosphericTintA"
    )
    val animTintB by animateColorAsState(
        targetValue = secondaryTint,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "AtmosphericTintB"
    )
    val animTintC by animateColorAsState(
        targetValue = tertiaryTint,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "AtmosphericTintC"
    )

    // Subtle organic breathing drift (14s infinite loop)
    val infiniteTransition = rememberInfiniteTransition(label = "AtmosphericMotion")
    val motionPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isPlaying) (2f * Math.PI.toFloat()) else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "MotionPhase"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawAtmosphericLayers(
                    tintA = animTintA,
                    tintB = animTintB,
                    tintC = animTintC,
                    motionPhase = motionPhase,
                    intensity = alpha
                )
            }
    ) {
        content()
    }
}

/**
 * High-performance Canvas renderer for multi-layer blurred mesh gradients.
 */
private fun DrawScope.drawAtmosphericLayers(
    tintA: Color,
    tintB: Color,
    tintC: Color,
    motionPhase: Float,
    intensity: Float
) {
    val width = size.width
    val height = size.height

    // 1. Base Pitch Dark Foundation
    drawRect(color = AuralisBackground)

    // Dynamic orbital offsets for organic breathing
    val driftX1 = sin(motionPhase) * (width * 0.08f)
    val driftY1 = cos(motionPhase) * (height * 0.05f)

    val driftX2 = cos(motionPhase * 0.7f) * (width * 0.06f)
    val driftY2 = sin(motionPhase * 0.7f) * (height * 0.06f)

    // 2. Layer 1: Top Primary Tint Radial Bloom
    val center1 = Offset(width * 0.35f + driftX1, height * 0.22f + driftY1)
    val radius1 = width * 1.1f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                tintA.copy(alpha = 0.42f * intensity),
                tintA.copy(alpha = 0.20f * intensity),
                tintA.copy(alpha = 0.05f * intensity),
                Color.Transparent
            ),
            center = center1,
            radius = radius1
        ),
        center = center1,
        radius = radius1
    )

    // 3. Layer 2: Right/Center Secondary Tint Bloom
    val center2 = Offset(width * 0.80f + driftX2, height * 0.55f + driftY2)
    val radius2 = width * 1.0f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                tintB.copy(alpha = 0.32f * intensity),
                tintB.copy(alpha = 0.15f * intensity),
                tintB.copy(alpha = 0.03f * intensity),
                Color.Transparent
            ),
            center = center2,
            radius = radius2
        ),
        center = center2,
        radius = radius2
    )

    // 4. Layer 3: Bottom-Left Tertiary Glow
    val center3 = Offset(width * 0.15f - driftX2, height * 0.80f - driftY1)
    val radius3 = width * 0.95f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                tintC.copy(alpha = 0.25f * intensity),
                tintC.copy(alpha = 0.08f * intensity),
                Color.Transparent
            ),
            center = center3,
            radius = radius3
        ),
        center = center3,
        radius = radius3
    )

    // 5. Protective Vignette Scrim (Ensures text/controls remain crisp & accessible)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                AuralisBackground.copy(alpha = 0.40f),
                AuralisBackground.copy(alpha = 0.75f),
                AuralisBackground.copy(alpha = 0.95f)
            ),
            startY = height * 0.35f,
            endY = height
        )
    )
}

/**
 * Modifier to render a localized radial bloom glow behind an element (e.g. artwork or play button).
 */
fun Modifier.atmosphericBloom(
    color: Color,
    radiusRatio: Float = 1.4f,
    alpha: Float = 0.5f
): Modifier = this.drawBehind {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = (size.maxDimension / 2f) * radiusRatio

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = alpha),
                color.copy(alpha = alpha * 0.4f),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        center = center,
        radius = radius
    )
}
