package com.auralis.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.theme.AuralisSurface
import com.auralis.music.ui.theme.AuralisSurfaceElevated
import com.auralis.music.ui.theme.GlassBorderHairline
import com.auralis.music.ui.theme.GlassBorderHighlight
import kotlinx.coroutines.launch

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
    alpha: Float = 0.70f,
    backgroundColor: Color = AuralisSurface,
    borderColor: Color = GlassBorderHairline,
    shape: Shape = RoundedCornerShape(20.dp)
): Modifier = this
    .clip(shape)
    .background(backgroundColor.copy(alpha = alpha), shape)
    .border(width = 1.dp, color = borderColor, shape = shape)

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
 * Tactile bouncy spring physics modifier.
 * On press: smoothly scales down to [scaleDown] (0.94f).
 * On release: springs back with bouncy physics and triggers [onClick].
 *
 * @param scaleDown Scale factor on press (default 0.94f)
 * @param enabled Whether interaction is enabled
 * @param onClick Callback triggered on click release
 */
fun Modifier.tactileBounce(
    scaleDown: Float = 0.94f,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    if (!enabled) return@composed this

    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .pointerInput(enabled) {
            while (true) {
                awaitPointerEventScope {
                    // Wait for touch down
                    awaitFirstDown(requireUnconsumed = false)
                    scope.launch {
                        scale.animateTo(
                            targetValue = scaleDown,
                            animationSpec = spring(
                                dampingRatio = 0.70f,
                                stiffness = Spring.StiffnessMedium
                            )
                        )
                    }

                    // Wait for touch up or cancellation
                    val up = waitForUpOrCancellation()
                    scope.launch {
                        scale.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = 0.42f, // Enhanced tactile bouncy spring
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                    }

                    if (up != null) {
                        onClick?.invoke()
                    }
                }
            }
        }
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
