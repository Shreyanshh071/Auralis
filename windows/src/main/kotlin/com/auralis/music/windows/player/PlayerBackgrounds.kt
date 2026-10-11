package com.auralis.music.windows.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.auralis.music.ui.player.PlayerBackgroundStyle
import com.auralis.music.ui.theme.ArtworkPalette
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * The full player's backgrounds, after the phone's PlayerBackgroundRenderer: Blur, Gradient,
 * Follow theme, Glow motion (drifting palette glows) and Live mesh (three counter-rotating,
 * saturated, blurred copies of the cover, as Spicy Lyrics' dynamic background). Always opaque.
 */
@Composable
internal fun PlayerBackdrop(styleKey: String, artworkUrl: String?, palette: ArtworkPalette) {
    val style = PlayerBackgroundStyle.fromKey(styleKey)
    Box(Modifier.fillMaxSize().background(Color(0xFF050505)).clipToBounds()) {
        when (style) {
            PlayerBackgroundStyle.FOLLOW_THEME, PlayerBackgroundStyle.APPLE_MUSIC ->
                Box(Modifier.fillMaxSize().background(Color(0xFF111014)))
            PlayerBackgroundStyle.GRADIENT -> {
                // The phone's full-player gradient: vibrant top, harmonic middle, dark base, a soft
                // bloom up top and an 18% contrast wash.
                val stops = rememberAnimatedStops(palette)
                Box(Modifier.fillMaxSize().drawBehind {
                    drawRect(Brush.verticalGradient(0f to stops.top, .48f to stops.mid, 1f to stops.bottom))
                    drawRect(Brush.radialGradient(listOf(stops.top.copy(alpha = .35f), Color.Transparent),
                        center = Offset(size.width * .45f, size.height * .22f), radius = size.width * .85f))
                    drawRect(Color.Black.copy(alpha = .18f))
                })
            }
            PlayerBackgroundStyle.BLUR -> BlurredArtworkBackground(artworkUrl)
            PlayerBackgroundStyle.GLOW_MOTION -> GlowMotion(palette)
            // The mesh is blurred heavily, so a small cover decodes and blurs faster with no visible loss.
            PlayerBackgroundStyle.LIVE_MESH -> LiveMesh(com.auralis.music.ui.components.getOptimizedThumbnailUrl(artworkUrl) ?: artworkUrl, palette)
        }
    }
}

@Composable
private fun GlowMotion(palette: ArtworkPalette) {
    // As the phone: the palette's first six glow swatches, cycled when there are fewer.
    val colours = remember(palette) {
        val base = palette.glowColors.ifEmpty { listOf(palette.primary, palette.secondary, palette.tertiary) }
        if (base.size >= 6) base.take(6) else List(6) { base[it % base.size] }
    }
    val progress = rememberInfiniteTransition(label = "glow").animateFloat(0f, 1f,
        infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Restart), label = "glowProgress")
    Box(Modifier.fillMaxSize().drawBehind {
        val p = progress.value
        fun colourAt(index: Int): Color {
            val i = index + p * colours.size
            val a = floor(i).toInt() % colours.size
            return lerp(colours[a], colours[(a + 1) % colours.size], i - floor(i))
        }
        fun osc(min: Float, max: Float, phase: Float) = min + (max - min) * ((sin(2f * PI.toFloat() * (p + phase)) + 1f) / 2f)
        fun blob(i: Int, peak: Float, mid: Float, x: Pair<Float, Float>, xp: Float, y: Pair<Float, Float>, yp: Float, r: Pair<Float, Float>, rp: Float) {
            val c = colourAt(i)
            drawRect(Brush.radialGradient(listOf(c.copy(alpha = peak), c.copy(alpha = mid), Color.Transparent),
                center = Offset(size.width * osc(x.first, x.second, xp), size.height * osc(y.first, y.second, yp)),
                radius = size.width * osc(r.first, r.second, rp)))
        }
        blob(0, .85f, .5f, 0f to 1f, 0f, 0f to .5f, .07f, .8f to 1.6f, .12f)
        blob(1, .8f, .45f, 1f to 0f, .2f, .5f to 1f, .25f, .7f to 1.5f, .18f)
        blob(2, .75f, .4f, .2f to .8f, .33f, .8f to .2f, .36f, .6f to 1.4f, .29f)
        blob(3, .7f, .35f, .3f to .7f, .44f, .2f to .8f, .41f, .9f to 1.7f, .47f)
        blob(4, .65f, .3f, .4f to .6f, .55f, 0f to 1f, .51f, .7f to 1.5f, .58f)
        blob(5, .6f, .25f, 0f to 1f, .66f, .5f to .7f, .62f, .8f to 1.8f, .69f)
    })
}

@Composable
private fun LiveMesh(artworkUrl: String?, palette: ArtworkPalette) {
    // Foundation under the mesh, as the phone: the gradient's top and middle colours over black.
    val stops = rememberAnimatedStops(palette)
    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(Brush.verticalGradient(0f to stops.top.copy(alpha = .45f), .5f to stops.mid.copy(alpha = .25f), 1f to Color(0xFF050505)))
    })
    val transition = rememberInfiniteTransition(label = "mesh")
    val anchor = transition.animateFloat(0f, -360f, infiniteRepeatable(tween(80_000, easing = LinearEasing), RepeatMode.Restart), label = "anchor")
    val fast = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart), label = "fast")
    val slow = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "slow")
    // The phone boosts the mesh to 1.8x saturation, or greys it fully for black-and-white covers.
    val saturate = remember(palette.isMonochrome) { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(if (palette.isMonochrome) 0f else 1.8f) }) }
    Crossfade(artworkUrl, animationSpec = tween(900), label = "meshArtwork") { url ->
        if (url == null) return@Crossfade
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = 1.7f; scaleY = 1.7f }) {
            // Drawn at half size and scaled up: three full-window blurs per frame are costly.
            Box(Modifier.align(Alignment.Center).fillMaxSize(.5f).graphicsLayer { scaleX = 2f; scaleY = 2f }) {
                MeshLayer(url, saturate, anchor, 25, Alignment.Center, 1f)
                MeshLayer(url, saturate, fast, 30, Alignment.TopStart, .6f)
                MeshLayer(url, saturate, slow, 30, Alignment.BottomEnd, .5f)
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .2f)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .25f)))))
        }
    }
}

@Composable
private fun MeshLayer(url: String, filter: ColorFilter, rotation: State<Float>, blurDp: Int, alignment: Alignment, alpha: Float) {
    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = filter,
        alignment = alignment,
        modifier = Modifier.fillMaxSize().blur(blurDp.dp).graphicsLayer { rotationZ = rotation.value; this.alpha = alpha })
}

/** The phone's PlayerGradientPalette stops for the full player, animated between songs. */
private class AnimatedStops(val top: Color, val mid: Color, val bottom: Color)

@Composable
private fun rememberAnimatedStops(palette: ArtworkPalette): AnimatedStops {
    val target = remember(palette) { gradientStops(palette) }
    val top by animateColorAsState(target.top, tween(700), label = "stopTop")
    val mid by animateColorAsState(target.mid, tween(700), label = "stopMid")
    val bottom by animateColorAsState(target.bottom, tween(700), label = "stopBottom")
    return AnimatedStops(top, mid, bottom)
}

/** PlayerGradientPalette.create from the phone (top vibrant, mid harmonic, bottom obsidian). */
private fun gradientStops(palette: ArtworkPalette): AnimatedStops {
    if (palette.isMonochrome) {
        return if (palette.primary == Color(0xFF161619) || palette.primary == Color.Unspecified)
            AnimatedStops(Color(0xFF18181C), Color(0xFF121215), Color(0xFF08080A))
        else AnimatedStops(Color(0xFF555964), Color(0xFF2E3139), Color(0xFF0C0D0F))
    }
    val p = hsv(palette.primary); val sc = hsv(palette.secondary)
    val pSat = (p[1] * 1.30f).coerceIn(.55f, 1f)
    val sSat = (sc[1] * 1.25f).coerceIn(.50f, 1f)
    val hueDiff = kotlin.math.abs(p[0] - sc[0]).let { if (it > 180f) 360f - it else it }
    val distinct = hueDiff > 28f && sc[1] > .20f
    val top = Color.hsv(p[0], pSat, .78f)
    val mid = if (distinct) Color.hsv(sc[0], sSat, .48f) else Color.hsv(p[0], pSat, .44f)
    val bottom = Color.hsv(p[0], (pSat * .75f).coerceIn(.30f, .75f), .08f)
    return AnimatedStops(top, mid, bottom)
}

private fun hsv(color: Color): FloatArray {
    val max = maxOf(color.red, color.green, color.blue); val min = minOf(color.red, color.green, color.blue); val d = max - min
    val hue = when {
        d == 0f -> 0f
        max == color.red -> ((color.green - color.blue) / d * 60f + 360f) % 360f
        max == color.green -> ((color.blue - color.red) / d * 60f + 120f) % 360f
        else -> ((color.red - color.green) / d * 60f + 240f) % 360f
    }
    return floatArrayOf(hue, if (max == 0f) 0f else d / max, max)
}
