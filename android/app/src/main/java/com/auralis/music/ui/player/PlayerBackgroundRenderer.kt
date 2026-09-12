package com.auralis.music.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.components.getOptimizedThumbnailUrl
import com.auralis.music.ui.theme.ArtworkPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Player Background Style Options for both Mini-Player and Full Player.
 */
enum class PlayerBackgroundStyle(val displayName: String) {
    FOLLOW_THEME("Follow theme"),
    GRADIENT("Gradient"),
    BLUR("Blur"),
    GLOW_MOTION("Glow motion"),
    APPLE_MUSIC("Apple Music"),
    LIVE_MESH("Live Mesh");

    companion object {
        fun fromKey(key: String?): PlayerBackgroundStyle {
            return when (key?.trim()?.lowercase()) {
                "follow theme", "follow_theme", "theme" -> FOLLOW_THEME
                "gradient", "dynamic artwork gradient", "artwork tinted", "dynamic artwork tint" -> GRADIENT
                "blur", "frosted glass / blur", "dynamic blurred artwork" -> BLUR
                "glow motion", "glow_motion", "glow" -> GLOW_MOTION
                "apple music", "apple_music", "apple liquid glass", "liquid glass", "apple glass", "glass" -> APPLE_MUSIC
                "live mesh", "live_mesh", "mesh" -> LIVE_MESH
                "pure black", "solid amoled black", "dark black" -> FOLLOW_THEME
                else -> GRADIENT
            }
        }
    }
}

/**
 * Unified gradient palette model for both Full Player and Mini-Player.
 */
data class GradientStops(
    val topVibrant: Color,
    val midHarmonic: Color,
    val bottomObsidian: Color,
    val miniLeft: Color,
    val miniCenter: Color,
    val miniRight: Color,
    val glowAccent: Color
)

/**
 * Calculates vibrant, rich gradient colors derived from album artwork.
 * Handles both colorful artwork and authentic grayscale / monochrome albums.
 */
object PlayerGradientPalette {

    /**
     * Pure Kotlin RGB to HSV conversion directly operating on Compose Color float components [0..1].
     * Completely independent of android.graphics.Color stub, allowing seamless JVM unit testing and performance.
     */
    fun colorToHsv(color: Color, hsv: FloatArray) {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        val hue = when {
            delta == 0f -> 0f
            max == r -> ((g - b) / delta * 60f + 360f) % 360f
            max == g -> ((b - r) / delta * 60f + 120f) % 360f
            else -> ((r - g) / delta * 60f + 240f) % 360f
        }

        val sat = if (max == 0f) 0f else delta / max
        val value = max

        hsv[0] = hue
        hsv[1] = sat
        hsv[2] = value
    }

    fun create(
        primary: Color,
        secondary: Color,
        tertiary: Color = secondary,
        isMonochrome: Boolean = false
    ): GradientStops {
        if (isMonochrome) {
            val isDefaultAmbient = (primary == Color(0xFF161619)) || (primary == Color.Unspecified)
            if (isDefaultAmbient) {
                return GradientStops(
                    topVibrant = Color(0xFF18181C),
                    midHarmonic = Color(0xFF121215),
                    bottomObsidian = Color(0xFF08080A),
                    miniLeft = Color(0xFF121215),
                    miniCenter = Color(0xFF151518),
                    miniRight = Color(0xFF19191D),
                    glowAccent = Color(0xFF222228)
                )
            }
            return GradientStops(
                topVibrant = Color(0xFF555964),
                midHarmonic = Color(0xFF2E3139),
                bottomObsidian = Color(0xFF0C0D0F),
                miniLeft = Color(0xFF2D3037),
                miniCenter = Color(0xFF383C45),
                miniRight = Color(0xFF4C525E),
                glowAccent = Color(0xFF7B8394)
            )
        }

        val primaryHsv = FloatArray(3)
        colorToHsv(primary, primaryHsv)
        val pHue = primaryHsv[0]
        val pSat = primaryHsv[1]

        val secondaryHsv = FloatArray(3)
        colorToHsv(secondary, secondaryHsv)
        val sHue = secondaryHsv[0]
        val sSat = secondaryHsv[1]

        // Boost saturation for vivid, rich appearance like ViVi & Metrolist
        val enhancedPSat = (pSat * 1.30f).coerceIn(0.55f, 1.0f)
        val enhancedSSat = (sSat * 1.25f).coerceIn(0.50f, 1.0f)

        // Check if secondary color provides a distinct hue/saturation contrast
        val hueDiff = kotlin.math.abs(pHue - sHue).let { if (it > 180f) 360f - it else it }
        val hasDistinctSecondary = hueDiff > 28f && sSat > 0.20f

        // Top vibrant color: alive and clearly colorful
        val topVibrant = Color.hsv(
            hue = pHue,
            saturation = enhancedPSat,
            value = 0.78f.coerceIn(0.68f, 0.88f)
        )

        // Mid harmonic color: uses secondary hue if distinct, otherwise deeper primary tone
        val midHarmonic = if (hasDistinctSecondary) {
            Color.hsv(
                hue = sHue,
                saturation = enhancedSSat,
                value = 0.48f.coerceIn(0.40f, 0.56f)
            )
        } else {
            Color.hsv(
                hue = pHue,
                saturation = enhancedPSat,
                value = 0.44f.coerceIn(0.38f, 0.50f)
            )
        }

        // Bottom atmospheric dark: rich deep base that carries the artwork's authentic undertone
        val bottomObsidian = Color.hsv(
            hue = pHue,
            saturation = (enhancedPSat * 0.75f).coerceIn(0.30f, 0.75f),
            value = 0.08f
        )

        // Mini player stops:
        // Left: comfortable artwork color that ensures white track title and artist have pristine contrast
        val miniLeft = Color.hsv(
            hue = pHue,
            saturation = enhancedPSat,
            value = 0.38f.coerceIn(0.32f, 0.44f)
        )

        // Center: smooth transition
        val miniCenter = if (hasDistinctSecondary) {
            Color.hsv(
                hue = (pHue + (sHue - pHue) * 0.4f).let { (it + 360f) % 360f },
                saturation = ((enhancedPSat + enhancedSSat) / 2f).coerceIn(0.55f, 1.0f),
                value = 0.46f.coerceIn(0.40f, 0.52f)
            )
        } else {
            Color.hsv(
                hue = pHue,
                saturation = enhancedPSat,
                value = 0.45f.coerceIn(0.40f, 0.50f)
            )
        }

        // Right: glowing, vibrant accent on the playback controls side
        val miniRight = if (hasDistinctSecondary) {
            Color.hsv(
                hue = sHue,
                saturation = enhancedSSat,
                value = 0.60f.coerceIn(0.52f, 0.68f)
            )
        } else {
            Color.hsv(
                hue = pHue,
                saturation = enhancedPSat,
                value = 0.62f.coerceIn(0.54f, 0.70f)
            )
        }

        val glowAccent = Color.hsv(
            hue = if (hasDistinctSecondary) sHue else pHue,
            saturation = if (hasDistinctSecondary) enhancedSSat else enhancedPSat,
            value = 0.72f.coerceIn(0.65f, 0.85f)
        )

        return GradientStops(
            topVibrant = topVibrant,
            midHarmonic = midHarmonic,
            bottomObsidian = bottomObsidian,
            miniLeft = miniLeft,
            miniCenter = miniCenter,
            miniRight = miniRight,
            glowAccent = glowAccent
        )
    }
}

/**
 * Backward-compatible helper to compute rich, saturated, non-transparent gradient stops.
 */
fun tuneColorForGradient(baseColor: Color, targetValue: Float, satMultiplier: Float = 1.0f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(baseColor.toArgb(), hsv)
    if (hsv[1] > 0.05f) {
        hsv[1] = (hsv[1] * satMultiplier).coerceIn(0.55f, 1.0f)
    }
    hsv[2] = targetValue.coerceIn(0.08f, 0.95f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Linearly interpolates between two [ArtworkPalette] instances component-by-component.
 */
fun lerpArtworkPalette(start: ArtworkPalette, stop: ArtworkPalette, fraction: Float): ArtworkPalette {
    val f = fraction.coerceIn(0f, 1f)
    if (f <= 0f) return start
    if (f >= 1f) return stop

    val primary = lerp(start.primary, stop.primary, f)
    val secondary = lerp(start.secondary, stop.secondary, f)
    val tertiary = lerp(start.tertiary, stop.tertiary, f)
    val seed = if (start.seedColor != Color.Unspecified && stop.seedColor != Color.Unspecified) {
        lerp(start.seedColor, stop.seedColor, f)
    } else if (stop.seedColor != Color.Unspecified) {
        stop.seedColor
    } else {
        start.seedColor
    }

    val startGlow = if (start.isMonochrome) {
        listOf(
            Color(0xFFE2E2E2), Color(0xFFB8B8B8), Color(0xFF8E8E8E),
            Color(0xFF686868), Color(0xFF484848), Color(0xFFA2A2A2)
        )
    } else {
        start.glowColors.ifEmpty { listOf(start.primary, start.secondary, start.tertiary) }
    }

    val stopGlow = if (stop.isMonochrome) {
        listOf(
            Color(0xFFE2E2E2), Color(0xFFB8B8B8), Color(0xFF8E8E8E),
            Color(0xFF686868), Color(0xFF484848), Color(0xFFA2A2A2)
        )
    } else {
        stop.glowColors.ifEmpty { listOf(stop.primary, stop.secondary, stop.tertiary) }
    }

    val glow = List(6) { idx ->
        val c1 = startGlow[idx % startGlow.size]
        val c2 = stopGlow[idx % stopGlow.size]
        lerp(c1, c2, f)
    }

    return ArtworkPalette(
        primary = primary,
        secondary = secondary,
        tertiary = tertiary,
        seedColor = seed,
        isMonochrome = if (f > 0.5f) stop.isMonochrome else start.isMonochrome,
        glowColors = glow
    )
}

/**
 * Smoothly and fluidly interpolates an [ArtworkPalette] during track changes over a 650ms FastOutSlowIn curve.
 * Uses a single [Animatable] float + [lerpArtworkPalette] to animate all color channels together in one
 * invalidation per frame, eliminating the 10-simultaneous animateColorAsState flood that caused frame jank.
 */
@Composable
fun animateArtworkPalette(
    targetPalette: ArtworkPalette,
    durationMillis: Int = 650
): ArtworkPalette {
    // Keep track of the "start" snapshot (what was visible the moment the target changed)
    var currentVisiblePalette by remember { mutableStateOf(targetPalette) }
    var previousTargetPalette by remember { mutableStateOf(targetPalette) }
    val animProgress = remember { Animatable(1f) }

    LaunchedEffect(targetPalette) {
        if (targetPalette != previousTargetPalette) {
            // Snapshot whatever is CURRENTLY visible at the interruption moment so rapid
            // A → B → C switches never jump or restart from a stale baseline.
            currentVisiblePalette = lerpArtworkPalette(currentVisiblePalette, previousTargetPalette, animProgress.value)
            previousTargetPalette = targetPalette
            animProgress.snapTo(0f)
            animProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
            )
        }
    }

    return lerpArtworkPalette(currentVisiblePalette, previousTargetPalette, animProgress.value)
}

/**
 * Unified, reusable Player Background Renderer shared between:
 * - Mini-player (floating pill interior)
 * - Full Now Playing player
 *
 * Implements 6 distinct styles:
 * 1. FOLLOW_THEME: Clean Solid Theme / Surface background
 * 2. GRADIENT: Metrolist & ViVi grade full-bleed gradient derived from artwork's authentic dominant hue
 * 3. BLUR: Fullscreen vivid album artwork blur with seamless crossfade & contrast vignette
 * 4. GLOW_MOTION: Animated GPU-drawn ambient radial glowing blobs
 * 5. APPLE_MUSIC: Apple Music-inspired ambient blurred backdrop with cloudy atmospheric fade
 * 6. LIVE_MESH: Dynamic rotating and pulsing mesh artwork layers with boosted saturation
 */
@Composable
fun PlayerBackground(
    style: PlayerBackgroundStyle,
    artworkUrl: String?,
    extractedColors: ArtworkPalette,
    modifier: Modifier = Modifier,
    isMiniPlayer: Boolean = false,
    isPlaying: Boolean = true,
    isVisible: Boolean = true
) {
    val context = LocalContext.current

    // Unified smooth palette interpolation: fluid 650ms continuous color transitions
    val animatedPalette = animateArtworkPalette(extractedColors, durationMillis = 650)

    val animatedGlowColors = remember(animatedPalette) {
        if (animatedPalette.glowColors.size >= 6) {
            animatedPalette.glowColors.take(6)
        } else {
            val baseList = animatedPalette.glowColors.ifEmpty {
                listOf(animatedPalette.primary, animatedPalette.secondary, animatedPalette.tertiary)
            }
            List(6) { idx -> baseList[idx % baseList.size] }
        }
    }

    // Unified vibrant gradient stops derived from animated artwork colors
    val gradStops = remember(animatedPalette.primary, animatedPalette.secondary, animatedPalette.tertiary, animatedPalette.isMonochrome) {
        PlayerGradientPalette.create(
            primary = animatedPalette.primary,
            secondary = animatedPalette.secondary,
            tertiary = animatedPalette.tertiary,
            isMonochrome = animatedPalette.isMonochrome
        )
    }

    Box(
        modifier = modifier.clipToBounds()
    ) {
        when (style) {
            PlayerBackgroundStyle.FOLLOW_THEME -> {
                if (isMiniPlayer) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.dynamicSurface)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.dynamicBackground)
                    )
                }
            }

            PlayerBackgroundStyle.GRADIENT -> {
                if (isMiniPlayer) {
                    // Mini-Player: Horizontal full-pill gradient + radial bloom on controls + polished glass sheen
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    0.0f to gradStops.miniLeft,
                                    0.48f to gradStops.miniCenter,
                                    1.0f to gradStops.miniRight
                                )
                            )
                    )
                    // Radial bloom on the right side behind controls
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(gradStops.glowAccent.copy(alpha = 0.45f), Color.Transparent),
                                    center = Offset(800f, 60f),
                                    radius = 350f
                                )
                            )
                    )
                    // Polished glass sheen (soft highlight at top edge, subtle shadow at bottom)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.18f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.18f)
                                    )
                                )
                            )
                    )
                } else {
                    // Full Player: Metrolist & ViVi grade 3-stop vertical gradient + ambient top bloom + contrast overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.0f to gradStops.topVibrant,
                                    0.48f to gradStops.midHarmonic,
                                    1.0f to gradStops.bottomObsidian
                                )
                            )
                    )
                    // Top ambient radiant glow enhancing the dominant hue
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(gradStops.topVibrant.copy(alpha = 0.35f), Color.Transparent),
                                    center = Offset(450f, 250f),
                                    radius = 800f
                                )
                            )
                    )
                    // Subtle contrast wash (ViVi / Metrolist standard alpha = 0.18f)
                    // ensuring white chevron, track title, seekbar, and playback controls have 100% pristine contrast
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.18f))
                    )
                }
            }

            PlayerBackgroundStyle.BLUR -> {
                // Base surface: dynamic vibrant gradient stops ensuring the player NEVER flashes black during changes
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isMiniPlayer) {
                                Brush.horizontalGradient(
                                    0.0f to gradStops.miniLeft,
                                    0.5f to gradStops.miniCenter,
                                    1.0f to gradStops.miniRight
                                )
                            } else {
                                Brush.verticalGradient(
                                    0.0f to gradStops.topVibrant,
                                    0.48f to gradStops.midHarmonic,
                                    1.0f to gradStops.bottomObsidian
                                )
                            }
                        )
                )

                // Seamless Dual-Layer Blurred Artwork (zero black frames while image decodes)
                SeamlessArtworkBlurLayer(
                    artworkUrl = artworkUrl,
                    isMiniPlayer = isMiniPlayer,
                    blurRadius = if (isMiniPlayer) 20.dp else 24.dp,
                    scale = if (isMiniPlayer) 1.25f else 1.15f,
                    targetAlpha = if (isMiniPlayer) 0.95f else 0.88f,
                    modifier = Modifier.fillMaxSize()
                )

                // Legibility Scrim
                if (isMiniPlayer) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.28f))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.00f to Color.Black.copy(alpha = 0.18f),
                                    0.40f to Color.Black.copy(alpha = 0.28f),
                                    0.75f to Color.Black.copy(alpha = 0.48f),
                                    1.00f to Color.Black.copy(alpha = 0.62f)
                                )
                            )
                    )
                }
            }

            PlayerBackgroundStyle.GLOW_MOTION -> {
                // ViVi-exact 20-second continuous rotation
                val infiniteTransition = rememberInfiniteTransition(label = "glowMotionTransition")
                val glowProgress by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 20000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "glowMotionProgress"
                )

                fun rotatedColorAt(index: Int): Color {
                    val size = animatedGlowColors.size
                    val idx = index.toFloat() + glowProgress * size
                    val a = floor(idx).toInt() % size
                    val b = (a + 1) % size
                    val frac = idx - floor(idx)
                    return lerp(animatedGlowColors[a], animatedGlowColors[b], frac)
                }

                fun oscillate(min: Float, max: Float, phase: Float, speed: Float = 1f): Float {
                    val v = sin(2f * PI.toFloat() * (glowProgress * speed + phase))
                    return min + (max - min) * ((v + 1f) * 0.5f)
                }

                if (isMiniPlayer) {
                    // ViVi's EXACT Mini Player Glow Motion (vivi_MiniPlayer.kt lines 1069-1128)
                    val c1 = rotatedColorAt(0)
                    val c2 = rotatedColorAt(1)

                    val o1x = oscillate(0.0f, 1.0f, 0.0f)
                    val o1y = oscillate(0.0f, 0.5f, 0.1f)
                    val o2x = oscillate(1.0f, 0.0f, 0.2f)
                    val o2y = oscillate(0.5f, 1.0f, 0.3f)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                val width = size.width
                                val height = size.height

                                val b1 = Brush.radialGradient(
                                    colors = listOf(c1.copy(alpha = 0.8f), Color.Transparent),
                                    center = Offset(width * o1x, height * o1y),
                                    radius = width * 1.2f
                                )
                                val b2 = Brush.radialGradient(
                                    colors = listOf(c2.copy(alpha = 0.7f), Color.Transparent),
                                    center = Offset(width * o2x, height * o2y),
                                    radius = width * 1.0f
                                )

                                onDrawBehind {
                                    drawRect(Color(0xFF050505))
                                    drawRect(b1)
                                    drawRect(b2)
                                }
                            }
                    )
                } else {
                    // ViVi's EXACT Full Player Glow Motion (vivi_Player.kt lines 921-1065)
                    val color1 = rotatedColorAt(0)
                    val color2 = rotatedColorAt(1)
                    val color3 = rotatedColorAt(2)
                    val color4 = rotatedColorAt(3)
                    val color5 = rotatedColorAt(4)
                    val color6 = rotatedColorAt(5)

                    val o1x = oscillate(0.0f, 1.0f, 0.00f, 1.0f)
                    val o1y = oscillate(0.0f, 0.5f, 0.07f, 1.0f)
                    val r1 = oscillate(0.8f, 1.6f, 0.12f, 1.0f)

                    val o2x = oscillate(1.0f, 0.0f, 0.20f, 1.0f)
                    val o2y = oscillate(0.5f, 1.0f, 0.25f, 1.0f)
                    val r2 = oscillate(0.7f, 1.5f, 0.18f, 1.0f)

                    val o3x = oscillate(0.2f, 0.8f, 0.33f, 1.0f)
                    val o3y = oscillate(0.8f, 0.2f, 0.36f, 1.0f)
                    val r3 = oscillate(0.6f, 1.4f, 0.29f, 1.0f)

                    val o4x = oscillate(0.3f, 0.7f, 0.44f, 1.0f)
                    val o4y = oscillate(0.2f, 0.8f, 0.41f, 1.0f)
                    val r4 = oscillate(0.9f, 1.7f, 0.47f, 1.0f)

                    val o5x = oscillate(0.4f, 0.6f, 0.55f, 1.0f)
                    val o5y = oscillate(0.0f, 1.0f, 0.51f, 1.0f)
                    val r5 = oscillate(0.7f, 1.5f, 0.58f, 1.0f)

                    val o6x = oscillate(0.0f, 1.0f, 0.66f, 1.0f)
                    val o6y = oscillate(0.5f, 0.7f, 0.62f, 1.0f)
                    val r6 = oscillate(0.8f, 1.8f, 0.69f, 1.0f)

                    val baseColor = Color(0xFF050505)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                val width = size.width
                                val height = size.height

                                val brush1 = Brush.radialGradient(
                                    colors = listOf(
                                        color1.copy(alpha = 0.85f),
                                        color1.copy(alpha = 0.5f),
                                        Color.Transparent
                                    ),
                                    center = Offset(width * o1x, height * o1y),
                                    radius = width * r1
                                )
                                val brush2 = Brush.radialGradient(
                                    colors = listOf(
                                        color2.copy(alpha = 0.8f),
                                        color2.copy(alpha = 0.45f),
                                        Color.Transparent
                                    ),
                                    center = Offset(width * o2x, height * o2y),
                                    radius = width * r2
                                )
                                val brush3 = Brush.radialGradient(
                                    colors = listOf(
                                        color3.copy(alpha = 0.75f),
                                        color3.copy(alpha = 0.4f),
                                        Color.Transparent
                                    ),
                                    center = Offset(width * o3x, height * o3y),
                                    radius = width * r3
                                )
                                val brush4 = Brush.radialGradient(
                                    colors = listOf(
                                        color4.copy(alpha = 0.7f),
                                        color4.copy(alpha = 0.35f),
                                        Color.Transparent
                                    ),
                                    center = Offset(width * o4x, height * o4y),
                                    radius = width * r4
                                )
                                val brush5 = Brush.radialGradient(
                                    colors = listOf(
                                        color5.copy(alpha = 0.65f),
                                        color5.copy(alpha = 0.3f),
                                        Color.Transparent
                                    ),
                                    center = Offset(width * o5x, height * o5y),
                                    radius = width * r5
                                )
                                val brush6 = Brush.radialGradient(
                                    colors = listOf(
                                        color6.copy(alpha = 0.6f),
                                        color6.copy(alpha = 0.25f),
                                        Color.Transparent
                                    ),
                                    center = Offset(width * o6x, height * o6y),
                                    radius = width * r6
                                )

                                onDrawBehind {
                                    drawRect(color = baseColor)
                                    drawRect(brush = brush1)
                                    drawRect(brush = brush2)
                                    drawRect(brush = brush3)
                                    drawRect(brush = brush4)
                                    drawRect(brush = brush5)
                                    drawRect(brush = brush6)
                                }
                            }
                    )
                }
            }

            PlayerBackgroundStyle.APPLE_MUSIC -> {
                // Apple Music-inspired blurred backdrop with authentic frosted liquid glass refraction
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isMiniPlayer) {
                                Brush.horizontalGradient(
                                    0.0f to gradStops.miniLeft.copy(alpha = 0.16f),
                                    0.5f to gradStops.miniCenter.copy(alpha = 0.10f),
                                    1.0f to gradStops.miniRight.copy(alpha = 0.20f)
                                )
                            } else {
                                Brush.verticalGradient(
                                    0.0f to gradStops.topVibrant,
                                    0.48f to gradStops.midHarmonic,
                                    1.0f to gradStops.bottomObsidian
                                )
                            }
                        )
                )

                // Seamless Dual-Layer Apple Music Blurred Artwork (zero black frames while image decodes)
                SeamlessArtworkBlurLayer(
                    artworkUrl = artworkUrl,
                    isMiniPlayer = isMiniPlayer,
                    blurRadius = if (isMiniPlayer) 20.dp else 24.dp,
                    scale = if (isMiniPlayer) 1.35f else 1.35f,
                    targetAlpha = if (isMiniPlayer) 0.22f else 0.80f,
                    modifier = Modifier.fillMaxSize()
                )

                if (isMiniPlayer) {
                    // Apple Music frosted liquid glass specular sheen overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.18f), // top glass specular gleam
                                        Color.White.copy(alpha = 0.02f), // subtle frosted body sheen
                                        Color.Transparent,               // lets vibrant refraction shine through center
                                        Color.Black.copy(alpha = 0.18f)  // bottom depth grounding
                                    )
                                )
                            )
                    )
                    // High-frequency glass curvature specular highlight at very top
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.00f to Color.Black.copy(alpha = 0.15f),
                                    0.35f to Color.Black.copy(alpha = 0.22f),
                                    0.70f to Color.Black.copy(alpha = 0.45f),
                                    1.00f to Color.Black.copy(alpha = 0.70f)
                                )
                            )
                    )
                }
            }

            PlayerBackgroundStyle.LIVE_MESH -> {
                // ViVi-inspired Live Mesh Dynamic Background
                // Foundation: Deep vertical gradient anchored to top dominant color
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to gradStops.topVibrant.copy(alpha = 0.45f),
                                0.5f to gradStops.midHarmonic.copy(alpha = 0.25f),
                                1.0f to Color(0xFF050505)
                            )
                        )
                )

                LiveMeshArtworkLayer(
                    artworkUrl = artworkUrl,
                    isMiniPlayer = isMiniPlayer,
                    isMonochrome = extractedColors.isMonochrome,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Seamless dual-layer blurred artwork renderer.
 * Keeps the previously rendered artwork fully visible while the incoming artwork
 * is decoded by Coil in the background. Once the new bitmap is decoded and emitted,
 * it smoothly fades in over the old artwork over 500ms, completely eliminating
 * any black/empty frames during song transitions.
 */
@Composable
private fun SeamlessArtworkBlurLayer(
    artworkUrl: String?,
    isMiniPlayer: Boolean,
    blurRadius: Dp,
    scale: Float,
    targetAlpha: Float,
    modifier: Modifier = Modifier,
    colorMatrix: ColorMatrix? = null,
    rotationZ: Float = 0f
) {
    val context = LocalContext.current
    val isInitiallyCached = remember(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) false else {
            try {
                val memCache = context.imageLoader.memoryCache
                val key = coil.memory.MemoryCache.Key(artworkUrl)
                memCache?.get(key) != null
            } catch (_: Exception) {
                false
            }
        }
    }
    // Base layer URL that is confirmed loaded and visible
    var visibleUrl by remember { mutableStateOf<String?>(artworkUrl) }
    // Incoming URL that is loading or fading in on top
    var incomingUrl by remember { mutableStateOf<String?>(null) }
    var isIncomingLoaded by remember { mutableStateOf(false) }

    val incomingAlpha = remember { Animatable(0f) }

    LaunchedEffect(artworkUrl) {
        if (!artworkUrl.isNullOrBlank()) {
            if (artworkUrl != visibleUrl) {
                // If previous incoming image has already achieved substantial visibility, promote to visible base
                if (isIncomingLoaded && incomingAlpha.value >= 0.5f && incomingUrl != null) {
                    visibleUrl = incomingUrl
                }
                incomingUrl = artworkUrl
                val cached = try {
                    val memCache = context.imageLoader.memoryCache
                    val key = coil.memory.MemoryCache.Key(artworkUrl)
                    memCache?.get(key) != null
                } catch (_: Exception) { false }

                incomingAlpha.snapTo(0f)
                isIncomingLoaded = cached
            }
        }
    }

    LaunchedEffect(isIncomingLoaded) {
        if (isIncomingLoaded && incomingUrl != null) {
            incomingAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
            )
            visibleUrl = incomingUrl
            incomingUrl = null
            isIncomingLoaded = false
            incomingAlpha.snapTo(0f)
        }
    }

    val colorFilter = remember(colorMatrix) {
        colorMatrix?.let { ColorFilter.colorMatrix(it) }
    }

    Box(modifier = modifier.clipToBounds()) {
        // Base Layer: previously loaded artwork remains visible until incoming layer finishes fading in
        val base = visibleUrl
        if (!base.isNullOrBlank()) {
            val baseReq = remember(base) {
                ImageRequest.Builder(context)
                    .data(base)
                    .size(if (isMiniPlayer) 128 else 256, if (isMiniPlayer) 128 else 256)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = baseReq,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = if (incomingUrl != null && isIncomingLoaded) {
                            targetAlpha * (1f - incomingAlpha.value)
                        } else {
                            targetAlpha
                        }
                        if (rotationZ != 0f) this.rotationZ = rotationZ
                    }
                    .blur(radius = blurRadius)
            )
        }

        // Incoming Layer: fades in on top once successfully loaded
        val inc = incomingUrl
        if (!inc.isNullOrBlank()) {
            val incReq = remember(inc) {
                ImageRequest.Builder(context)
                    .data(inc)
                    .size(if (isMiniPlayer) 128 else 256, if (isMiniPlayer) 128 else 256)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = incReq,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter,
                onSuccess = { isIncomingLoaded = true },
                onError = { isIncomingLoaded = false },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = targetAlpha * incomingAlpha.value
                        if (rotationZ != 0f) this.rotationZ = rotationZ
                    }
                    .blur(radius = blurRadius)
            )
        }
    }
}

/**
 * Dual-layer Live Mesh renderer that continuously rotates mesh layers while fluidly cross-fading
 * artwork between track changes without showing black backgrounds.
 * Fully preserves the authentic 3-layer multi-speed blurred ViVi music aesthetic.
 */
@Composable
private fun LiveMeshArtworkLayer(
    artworkUrl: String?,
    isMiniPlayer: Boolean,
    isMonochrome: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var visibleUrl by remember { mutableStateOf<String?>(artworkUrl) }
    var incomingUrl by remember { mutableStateOf<String?>(null) }
    var isIncomingLoaded by remember { mutableStateOf(false) }
    val incomingAnim = remember { Animatable(0f) }

    LaunchedEffect(artworkUrl) {
        if (!artworkUrl.isNullOrBlank()) {
            if (artworkUrl != visibleUrl) {
                if (isIncomingLoaded && incomingAnim.value >= 0.5f && incomingUrl != null) {
                    visibleUrl = incomingUrl
                }
                incomingUrl = artworkUrl
                val cached = try {
                    val memCache = context.imageLoader.memoryCache
                    val key = coil.memory.MemoryCache.Key(artworkUrl)
                    memCache?.get(key) != null
                } catch (_: Exception) { false }

                incomingAnim.snapTo(0f)
                isIncomingLoaded = cached
            }
        }
    }

    LaunchedEffect(isIncomingLoaded) {
        if (isIncomingLoaded && incomingUrl != null) {
            incomingAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
            )
            visibleUrl = incomingUrl
            incomingUrl = null
            isIncomingLoaded = false
            incomingAnim.snapTo(0f)
        }
    }

    val matrix = remember(isMonochrome) {
        ColorMatrix().apply {
            if (isMonochrome) {
                setToSaturation(0.0f)
            } else {
                setToSaturation(if (isMiniPlayer) 1.6f else 1.8f)
            }
        }
    }
    val colorFilter = remember(matrix) { ColorFilter.colorMatrix(matrix) }

    val infiniteTransition = rememberInfiniteTransition(label = "liveMeshRotation")
    val miniRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "miniMeshRotation"
    )
    val anchorRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 80000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "anchorRotation"
    )
    val fastRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 40000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fastRotation"
    )
    val slowRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "slowRotation"
    )

    Box(modifier = modifier.clipToBounds()) {
        val base = visibleUrl
        if (!base.isNullOrBlank()) {
            LiveMeshArtworkContent(
                url = base,
                isMiniPlayer = isMiniPlayer,
                colorFilter = colorFilter,
                miniRotation = miniRotation,
                anchorRotation = anchorRotation,
                fastRotation = fastRotation,
                slowRotation = slowRotation,
                alpha = if (incomingUrl != null && isIncomingLoaded) (1f - incomingAnim.value) else 1f,
                onLoaded = null
            )
        }

        val inc = incomingUrl
        if (!inc.isNullOrBlank()) {
            LiveMeshArtworkContent(
                url = inc,
                isMiniPlayer = isMiniPlayer,
                colorFilter = colorFilter,
                miniRotation = miniRotation,
                anchorRotation = anchorRotation,
                fastRotation = fastRotation,
                slowRotation = slowRotation,
                alpha = incomingAnim.value,
                onLoaded = { isIncomingLoaded = true }
            )
        }
    }
}

@Composable
private fun LiveMeshArtworkContent(
    url: String,
    isMiniPlayer: Boolean,
    colorFilter: ColorFilter,
    miniRotation: Float,
    anchorRotation: Float,
    fastRotation: Float,
    slowRotation: Float,
    alpha: Float,
    onLoaded: (() -> Unit)?
) {
    val context = LocalContext.current
    val meshReq = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(128, 128)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }

    if (isMiniPlayer) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.5f
                    scaleY = 1.5f
                    this.alpha = alpha
                }
        ) {
            AsyncImage(
                model = meshReq,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter,
                onSuccess = { onLoaded?.invoke() },
                modifier = Modifier
                    .fillMaxSize()
                    .blur(40.dp)
                    .graphicsLayer { rotationZ = miniRotation }
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.7f
                    scaleY = 1.7f
                    this.alpha = alpha
                }
        ) {
            // Layer 1: The Anchor (Full Image, Counter-Clockwise)
            AsyncImage(
                model = meshReq,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter,
                onSuccess = { onLoaded?.invoke() },
                modifier = Modifier
                    .fillMaxSize()
                    .blur(100.dp)
                    .graphicsLayer { rotationZ = anchorRotation }
            )

            // Layer 2: Fast Rotating Crop (Top-Left)
            AsyncImage(
                model = meshReq,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter,
                alignment = Alignment.TopStart,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(120.dp)
                    .graphicsLayer {
                        rotationZ = fastRotation
                        this.alpha = 0.6f
                    }
            )

            // Layer 3: Slow Rotating Crop (Bottom-Right)
            AsyncImage(
                model = meshReq,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter,
                alignment = Alignment.BottomEnd,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(120.dp)
                    .graphicsLayer {
                        rotationZ = slowRotation
                        this.alpha = 0.5f
                    }
            )

            // Global dark tint + vertical gradient depth matching ViVi
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.25f)
                            )
                        )
                    )
            )
        }
    }
}
