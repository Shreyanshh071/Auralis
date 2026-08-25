package com.auralis.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import com.auralis.music.ui.theme.dynamicPalette

// ============================================================================
// 🌌 STATIC HIGH-PERFORMANCE ATMOSPHERIC GLOW BACKGROUND
// ============================================================================

/**
 * Ultra-efficient, visually static atmospheric background:
 * - Zero frame-by-frame infinite transitions or recomposition loops.
 * - Hardware-accelerated Canvas with 250ms crossfade on track change.
 * - High vibrancy mesh radial gradients without live full-screen blur lag.
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
    // Single 250ms color crossfade executed ONLY on song/palette change
    val animTintA by animateColorAsState(
        targetValue = primaryTint,
        animationSpec = tween(durationMillis = 250),
        label = "AtmosphericTintA"
    )
    val animTintB by animateColorAsState(
        targetValue = secondaryTint,
        animationSpec = tween(durationMillis = 250),
        label = "AtmosphericTintB"
    )
    val animTintC by animateColorAsState(
        targetValue = tertiaryTint,
        animationSpec = tween(durationMillis = 250),
        label = "AtmosphericTintC"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawStaticAtmosphericLayers(
                    tintA = animTintA,
                    tintB = animTintB,
                    tintC = animTintC,
                    intensity = alpha
                )
            }
    ) {
        content()
    }
}

/**
 * Static Canvas renderer for multi-layer gradients without live CPU/GPU animations.
 */
private fun DrawScope.drawStaticAtmosphericLayers(
    tintA: Color,
    tintB: Color,
    tintC: Color,
    intensity: Float
) {
    val width = size.width
    val height = size.height

    // 1. Base Pitch Dark Foundation
    drawRect(color = AuralisBackground)

    // 2. Layer 1: Top Primary Tint Radial Bloom
    val center1 = Offset(width * 0.35f, height * 0.22f)
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
    val center2 = Offset(width * 0.80f, height * 0.55f)
    val radius2 = width * 1.0f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                tintB.copy(alpha = 0.32f * intensity),
                tintB.copy(alpha = 0.15f * intensity),
                Color.Transparent
            ),
            center = center2,
            radius = radius2
        ),
        center = center2,
        radius = radius2
    )

    // 4. Layer 3: Bottom Tertiary Tint Ambient Anchor
    val center3 = Offset(width * 0.25f, height * 0.85f)
    val radius3 = width * 0.9f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                tintC.copy(alpha = 0.28f * intensity),
                tintC.copy(alpha = 0.10f * intensity),
                Color.Transparent
            ),
            center = center3,
            radius = radius3
        ),
        center = center3,
        radius = radius3
    )

    // 5. Global Soft Vertical Contrast Gradient
    drawRect(
        brush = Brush.verticalGradient(
            0.0f to Color.Black.copy(alpha = 0.30f),
            0.3f to Color.Transparent,
            0.7f to Color.Transparent,
            1.0f to Color.Black.copy(alpha = 0.70f)
        )
    )
}
