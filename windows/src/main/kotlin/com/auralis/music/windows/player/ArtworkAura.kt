package com.auralis.music.windows.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.auralis.music.ui.theme.ArtworkPalette
import kotlin.math.sin
import kotlin.math.cos

/** Soft drifting pools of cover colour; animation invalidates only the canvas. */
@Composable
internal fun ArtworkAura(palette: ArtworkPalette, modifier: Modifier) {
    val primary by animateColorAsState(palette.primary, tween(900))
    val secondary by animateColorAsState(palette.secondary, tween(900))
    val tertiary by animateColorAsState(palette.tertiary, tween(900))
    val motion = rememberInfiniteTransition(label = "artwork aura")
    val phase = motion.animateFloat(0f, (Math.PI * 2).toFloat(),
        infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Restart), label = "drift")
    Canvas(modifier) {
        drawRect(lerp(Color(0xFF100C14), primary, .30f))
        val t = phase.value
        val radius = maxOf(size.width, size.height) * .82f
        listOf(primary, secondary, tertiary).forEachIndexed { index, colour ->
            val angle = t + index * 2.1f
            val center = Offset(size.width * (.5f + .40f * sin(angle)),
                size.height * (.5f + .38f * cos(angle * (if (index == 1) 2f else 1f))))
            drawRect(Brush.radialGradient(listOf(colour.copy(alpha = .72f), colour.copy(alpha = 0f)),
                center = center, radius = radius))
        }
        drawRect(Color.Black.copy(alpha = .18f))
    }
}
