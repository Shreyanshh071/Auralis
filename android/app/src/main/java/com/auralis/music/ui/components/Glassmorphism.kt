package com.auralis.music.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.theme.AuralisSpring
import com.auralis.music.ui.theme.AuralisSurface
import com.auralis.music.ui.theme.AuralisSurfaceElevated
import com.auralis.music.ui.theme.GlassBorderHairline
import com.auralis.music.ui.theme.GlassBorderHighlight
import com.auralis.music.ui.theme.LocalReducedMotion

// ============================================================================
// 💎 AURALIS GLASSMORPHIC MODIFIERS
// ============================================================================

/**
 * Applies a frosted dark glass surface with translucent tonal backing and specular hairline border.
 * Note: Keeps foreground child content (text, artwork, icons) crisp and razor-sharp.
 *
 * @param blurRadius Unused for container graphicsLayer to avoid blurring child text/images
 * @param alpha Surface opacity (0.0 to 1.0)
 * @param backgroundColor Base dark tonal color
 * @param borderColor Specular hairline stroke color (default ~9% white)
 * @param shape Corner clip shape
 */
fun Modifier.auralisGlass(
    blurRadius: Dp = 24.dp,
    alpha: Float = 0.85f,
    backgroundColor: Color = AuralisSurface,
    borderColor: Color = GlassBorderHairline,
    shape: Shape = RoundedCornerShape(18.dp)
): Modifier = this
    .clip(shape)
    .background(backgroundColor.copy(alpha = alpha))
    .border(1.dp, borderColor, shape)

/**
 * Enhanced frosted glass with top-to-bottom specular gradient highlight for a 3D glass slab effect.
 */
fun Modifier.auralisGlassElevated(
    alpha: Float = 0.90f,
    shape: Shape = RoundedCornerShape(22.dp)
): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            0.0f to Color(0xFF1E211A).copy(alpha = alpha),
            1.0f to Color(0xFF10120D).copy(alpha = alpha)
        )
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            0.0f to GlassBorderHighlight,
            0.5f to GlassBorderHairline,
            1.0f to Color.White.copy(alpha = 0.03f)
        ),
        shape = shape
    )

/**
 * Adds a top-down specular hairline highlight gradient border to simulate light reflection.
 *
 * @param shape Container shape
 * @param highlightAlpha Opacity of the top specular sheen (default 14%)
 * @param borderAlpha Opacity of the base hairline border (default 8%)
 */
fun Modifier.specularHighlight(
    shape: Shape = RoundedCornerShape(20.dp),
    highlightAlpha: Float = 0.14f,
    borderAlpha: Float = 0.08f
): Modifier = this.border(
    width = 1.dp,
    brush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = highlightAlpha),
            Color.White.copy(alpha = borderAlpha),
            Color.White.copy(alpha = borderAlpha * 0.4f),
            Color.Transparent
        )
    ),
    shape = shape
)

/**
 * Ultra-fast tactile bouncy spring physics modifier.
 * On press: instantly and smoothly scales down to [scaleDown].
 * On release: springs back with bouncy physics and dispatches [onClick] immediately.
 *
 * Uses Compose Native Interaction Source to guarantee ZERO input lag or gesture conflict.
 * Springs come from [AuralisSpring] and are skipped entirely under reduced motion,
 * where the click still dispatches identically.
 *
 * @param scaleDown Scale factor on press (default 0.94f)
 * @param enabled Whether interaction is enabled
 * @param onClick Optional callback triggered on click release
 */
fun Modifier.tactileBounce(
    scaleDown: Float = 0.94f,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    if (!enabled) return@composed this

    val reducedMotion = LocalReducedMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = if (isPressed) AuralisSpring.TactilePress else AuralisSpring.TactileRelease,
        label = "tactileBounceScale"
    )

    this
        .then(
            if (reducedMotion) {
                Modifier
            } else {
                Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
            }
        )
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
            } else {
                Modifier
            }
        )
}

/**
 * Pre-configured glassmorphic card container with optional tactile bounce and specular top highlight.
 */
fun Modifier.auralisGlassCard(
    shape: Shape = RoundedCornerShape(18.dp),
    backgroundColor: Color = AuralisSurfaceElevated,
    alpha: Float = 0.72f,
    onClick: (() -> Unit)? = null
): Modifier = this
    .clip(shape)
    .auralisGlass(
        blurRadius = 24.dp,
        alpha = alpha,
        backgroundColor = backgroundColor,
        borderColor = GlassBorderHairline,
        shape = shape
    )
    .specularHighlight(shape = shape)
    .then(
        if (onClick != null) {
            Modifier.tactileBounce(onClick = onClick)
        } else {
            Modifier
        }
    )

/**
 * Pre-configured pill badge / floating action button with glass styling and tactile spring feedback.
 */
fun Modifier.auralisPill(
    shape: Shape = CircleShape,
    backgroundColor: Color = AuralisSurfaceElevated,
    alpha: Float = 0.80f,
    onClick: (() -> Unit)? = null
): Modifier = this
    .clip(shape)
    .auralisGlass(
        blurRadius = 20.dp,
        alpha = alpha,
        backgroundColor = backgroundColor,
        borderColor = GlassBorderHighlight.copy(alpha = 0.12f),
        shape = shape
    )
    .specularHighlight(shape = shape, highlightAlpha = 0.20f)
    .then(
        if (onClick != null) {
            Modifier.tactileBounce(scaleDown = 0.92f, onClick = onClick)
        } else {
            Modifier
        }
    )
