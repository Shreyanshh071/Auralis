package com.auralis.music.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.auralis.music.ui.theme.LocalReducedMotion
import kotlin.math.cos
import kotlin.math.sin

private val Base = Color(0xFF0B0806)
private val Brown = Color(0xFF5A3A22)
private val Umber = Color(0xFF6B4527)
private val Gold = Color(0xFF8C6A35)

/**
 * The tour's and artist picker's backdrop, in the sign-in screen's colours: near-black with soft
 * brown and muted-gold glows drifting slowly. It is a few gradients on its own layer, redrawn
 * about 30 times a second, so it never redraws the pages or competes with the live previews.
 */
@Composable
internal fun TourBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val reduced = LocalReducedMotion.current
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        var start = 0L
        var lastTick = -1L
        while (true) withFrameNanos { now ->
            if (start == 0L) start = now
            // Slow drift: a 30 fps step is invisible and halves the work.
            val tick = (now - start) / 33_333_333L
            if (tick != lastTick) { lastTick = tick; time.floatValue = tick / 30f }
        }
    }
    Box(modifier.fillMaxSize()) {
        Spacer(Modifier.fillMaxSize().graphicsLayer().drawBehind { drawGlows(time.floatValue) })
        content()
    }
}

private fun DrawScope.drawGlows(t: Float) {
    val w = size.width
    val h = size.height
    drawRect(Base)
    glow(Offset(w * (.22f + .10f * sin(t * .11f)), h * (.14f + .05f * cos(t * .09f))), w * 1.05f, Brown, .62f)
    glow(Offset(w * (.86f + .08f * cos(t * .08f)), h * (.48f + .07f * sin(t * .10f))), w * .95f, Umber, .38f)
    glow(Offset(w * (.30f + .12f * cos(t * .07f + 1.3f)), h * (.86f + .04f * sin(t * .12f))), w * .9f, Gold, .26f)
    // Darker toward the bottom, where the buttons sit.
    drawRect(Brush.verticalGradient(0f to Color.Transparent, .55f to Base.copy(alpha = .25f), 1f to Base.copy(alpha = .7f)))
}

private fun DrawScope.glow(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * .35f), Color.Transparent),
        center = center, radius = radius), radius = radius, center = center)
}
