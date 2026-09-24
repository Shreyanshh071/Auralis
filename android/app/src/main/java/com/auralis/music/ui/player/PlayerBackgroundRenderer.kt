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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
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
 * A song-change palette blend whose progress is read only at draw time.
 *
 * [from] and [to] change once per song; [progress] animates 0→1. Consumers draw the outgoing
 * look and fade the incoming one over it (or lerp inside a draw lambda), so the background
 * never recomposes during the blend. The previous driver returned a freshly lerped palette
 * every frame, rebuilding every gradient/brush in the background for the whole transition.
 */
@androidx.compose.runtime.Stable
class PaletteBlend(
    val from: ArtworkPalette,
    val to: ArtworkPalette,
    val progress: androidx.compose.runtime.State<Float>
)

private val SettledProgress: androidx.compose.runtime.State<Float> = androidx.compose.runtime.mutableFloatStateOf(1f)

@Composable
fun rememberPaletteBlend(
    target: ArtworkPalette,
    snap: Boolean = false,
    durationMillis: Int = PlayerTransitionMotion.paletteDurationMillis
): PaletteBlend {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(target, snap) {
        if (snap) {
            // Pager-driven palette is already interpolated per frame by the caller.
            progress.snapTo(1f)
            from = target
            to = target
            return@LaunchedEffect
        }
        if (target != to) {
            // Start from whatever is on screen right now, so rapid A→B→C skips never jump.
            val visible = lerpArtworkPalette(from, to, progress.value)
            val spec = PlayerTransitionMotion.paletteSpec(progress.value, durationMillis)
            progress.snapTo(0f)
            from = visible
            to = target
            progress.animateTo(1f, spec)
        }
    }

    return if (snap) {
        remember(target) { PaletteBlend(target, target, SettledProgress) }
    } else {
        remember(from, to) { PaletteBlend(from, to, progress.asState()) }
    }
}

private fun glowColorsOf(palette: ArtworkPalette): List<Color> =
    if (palette.glowColors.size >= 6) {
        palette.glowColors.take(6)
    } else {
        val baseList = palette.glowColors.ifEmpty { listOf(palette.primary, palette.secondary, palette.tertiary) }
        List(6) { idx -> baseList[idx % baseList.size] }
    }

private fun gradientStopsOf(palette: ArtworkPalette): GradientStops = PlayerGradientPalette.create(
    primary = palette.primary,
    secondary = palette.secondary,
    tertiary = palette.tertiary,
    isMonochrome = palette.isMonochrome
)

/** Outgoing brush drawn solid, incoming brush faded over it by [progress] — all at draw time. */
private fun Modifier.crossfadeBackground(from: Brush, to: Brush, progress: androidx.compose.runtime.State<Float>): Modifier =
    this.drawBehind {
        val p = progress.value
        if (p < 1f) drawRect(from)
        if (p > 0f) drawRect(to, alpha = p)
    }

/**
 * Coordinated palette driver ensuring:
 * 1. During ACTIVE PAGER MOTION: direct interpolation without time-based lag.
 * 2. When PAGER SETTLES: zero redundant post-settle animation restart (state is pre-settled to arrival palette).
 * 3. During IDLE TRACK COMMITS: smooth time-based interpolation.
 * 4. During IMMEDIATE RE-SWIPE: zero leftover animation fighting the new swipe.
 */
@Composable
fun coordinatedArtworkPalette(
    isMotionActive: Boolean,
    motionPalette: ArtworkPalette,
    committedPalette: ArtworkPalette,
    durationMillis: Int = PlayerTransitionMotion.paletteDurationMillis
): ArtworkPalette {
    var currentVisiblePalette by remember { mutableStateOf(committedPalette) }
    var previousTargetPalette by remember { mutableStateOf(committedPalette) }
    val animProgress = remember { Animatable(1f) }

    if (isMotionActive) {
        currentVisiblePalette = motionPalette
        previousTargetPalette = motionPalette
        LaunchedEffect(Unit) {
            if (animProgress.value < 1f) {
                animProgress.snapTo(1f)
            }
        }
        return motionPalette
    } else {
        LaunchedEffect(committedPalette) {
            if (committedPalette != previousTargetPalette) {
                currentVisiblePalette = lerpArtworkPalette(currentVisiblePalette, previousTargetPalette, animProgress.value)
                previousTargetPalette = committedPalette
                val transitionSpec = PlayerTransitionMotion.paletteSpec(animProgress.value, durationMillis)
                animProgress.snapTo(0f)
                animProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = transitionSpec
                )
            }
        }
        return lerpArtworkPalette(currentVisiblePalette, previousTargetPalette, animProgress.value)
    }
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
    isVisible: Boolean = true,
    secondaryArtworkUrl: String? = null,
    swipeFraction: Float = 0f,
    skipPaletteAnimation: Boolean = false
) {
    val context = LocalContext.current

    val isSwiping = (swipeFraction > 0.005f && secondaryArtworkUrl != null) || skipPaletteAnimation
    // If we are actively swiping or skipPaletteAnimation is requested (e.g. direct pager-driven palette),
    // the palette is already mathematically interpolated in real-time.
    // Committed changes use one interruptible palette driver.
    val blend = rememberPaletteBlend(extractedColors, snap = isSwiping)
    val blendProgress = blend.progress
    // Discrete (non-animated) properties follow the incoming palette.
    val effectivePalette = blend.to

    val fromGlowColors = remember(blend.from) { glowColorsOf(blend.from) }
    val toGlowColors = remember(blend.to) { glowColorsOf(blend.to) }
    val fromStops = remember(blend.from) { gradientStopsOf(blend.from) }
    val toStops = remember(blend.to) { gradientStopsOf(blend.to) }

    Crossfade(
        targetState = style,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        modifier = modifier.clipToBounds(),
        label = "playerBackgroundStyleCrossfade"
    ) { currentStyle ->
        when (currentStyle) {
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
                SeamlessGradientLayer(
                    fromStops = fromStops,
                    toStops = toStops,
                    progress = blendProgress,
                    isMiniPlayer = isMiniPlayer,
                    modifier = Modifier.fillMaxSize()
                )
            }

            PlayerBackgroundStyle.BLUR -> {
                val blurColorMatrix = remember(effectivePalette.isMonochrome) {
                    if (effectivePalette.isMonochrome) {
                        ColorMatrix().apply { setToSaturation(0f) }
                    } else {
                        null
                    }
                }

                // Base surface: dynamic theme background to avoid flash during initial load
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isMiniPlayer) Color.Transparent else MaterialTheme.dynamicBackground)
                )

                // Seamless Dual-Layer Blurred Artwork (zero black frames while image decodes, matching Metrolist)
                SeamlessArtworkBlurLayer(
                    artworkUrl = artworkUrl,
                    isMiniPlayer = isMiniPlayer,
                    blurRadius = if (isMiniPlayer) 50.dp else 120.dp,
                    scale = if (isMiniPlayer) 1.20f else 1.10f,
                    targetAlpha = if (isMiniPlayer) 0.65f else 1.0f,
                    colorMatrix = blurColorMatrix,
                    translationYRatio = 0f,
                    secondaryArtworkUrl = secondaryArtworkUrl,
                    swipeFraction = swipeFraction,
                    modifier = Modifier.fillMaxSize()
                )

                // Legibility Scrim: uniform dark overlay matching Metrolist exactly (30% full player, 35% mini player)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (isMiniPlayer) 0.35f else 0.30f))
                )
            }

            PlayerBackgroundStyle.GLOW_MOTION -> {
                // ViVi-exact 20-second continuous rotation
                val infiniteTransition = rememberInfiniteTransition(label = "glowMotionTransition")
                // Kept as State and read only in the draw phase: reading it in composition rebuilt
                // this whole background on every frame of the never-ending 20s rotation, which
                // made song-change colour blends stutter on top of it.
                val glowProgressState = infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 20000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "glowMotionProgress"
                )
                fun rotated(colors: List<Color>, index: Int, glowProgress: Float): Color {
                    val size = colors.size
                    val idx = index.toFloat() + glowProgress * size
                    val a = floor(idx).toInt() % size
                    val b = (a + 1) % size
                    val frac = idx - floor(idx)
                    return lerp(colors[a], colors[b], frac)
                }

                // Called only from draw lambdas: song-change blend and rotation both resolve here.
                fun rotatedColorAt(index: Int, glowProgress: Float): Color = lerp(
                    rotated(fromGlowColors, index, glowProgress),
                    rotated(toGlowColors, index, glowProgress),
                    blendProgress.value
                )

                fun oscillate(glowProgress: Float, min: Float, max: Float, phase: Float, speed: Float = 1f): Float {
                    val v = sin(2f * PI.toFloat() * (glowProgress * speed + phase))
                    return min + (max - min) * ((v + 1f) * 0.5f)
                }

                if (isMiniPlayer) {
                    // ViVi's EXACT Mini Player Glow Motion (vivi_MiniPlayer.kt lines 1069-1128)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val gp = glowProgressState.value
                                val width = size.width
                                val height = size.height
                                val c1 = rotatedColorAt(0, gp)
                                val c2 = rotatedColorAt(1, gp)
                                val b1 = Brush.radialGradient(
                                    colors = listOf(c1.copy(alpha = 0.8f), Color.Transparent),
                                    center = Offset(width * oscillate(gp, 0.0f, 1.0f, 0.0f), height * oscillate(gp, 0.0f, 0.5f, 0.1f)),
                                    radius = width * 1.2f
                                )
                                val b2 = Brush.radialGradient(
                                    colors = listOf(c2.copy(alpha = 0.7f), Color.Transparent),
                                    center = Offset(width * oscillate(gp, 1.0f, 0.0f, 0.2f), height * oscillate(gp, 0.5f, 1.0f, 0.3f)),
                                    radius = width * 1.0f
                                )
                                drawRect(Color(0xFF050505))
                                drawRect(b1)
                                drawRect(b2)
                            }
                    )
                } else {
                    // ViVi's EXACT Full Player Glow Motion (vivi_Player.kt lines 921-1065)
                    val baseColor = Color(0xFF050505)
                    // (colour index, peak/mid alpha, center x/y ranges + phases, radius range + phase)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val gp = glowProgressState.value
                                val width = size.width
                                val height = size.height

                                fun blob(
                                    index: Int, peak: Float, mid: Float,
                                    xMin: Float, xMax: Float, xPhase: Float,
                                    yMin: Float, yMax: Float, yPhase: Float,
                                    rMin: Float, rMax: Float, rPhase: Float
                                ): Brush {
                                    val c = rotatedColorAt(index, gp)
                                    return Brush.radialGradient(
                                        colors = listOf(c.copy(alpha = peak), c.copy(alpha = mid), Color.Transparent),
                                        center = Offset(width * oscillate(gp, xMin, xMax, xPhase), height * oscillate(gp, yMin, yMax, yPhase)),
                                        radius = width * oscillate(gp, rMin, rMax, rPhase)
                                    )
                                }

                                drawRect(color = baseColor)
                                drawRect(blob(0, 0.85f, 0.5f, 0.0f, 1.0f, 0.00f, 0.0f, 0.5f, 0.07f, 0.8f, 1.6f, 0.12f))
                                drawRect(blob(1, 0.8f, 0.45f, 1.0f, 0.0f, 0.20f, 0.5f, 1.0f, 0.25f, 0.7f, 1.5f, 0.18f))
                                drawRect(blob(2, 0.75f, 0.4f, 0.2f, 0.8f, 0.33f, 0.8f, 0.2f, 0.36f, 0.6f, 1.4f, 0.29f))
                                drawRect(blob(3, 0.7f, 0.35f, 0.3f, 0.7f, 0.44f, 0.2f, 0.8f, 0.41f, 0.9f, 1.7f, 0.47f))
                                drawRect(blob(4, 0.65f, 0.3f, 0.4f, 0.6f, 0.55f, 0.0f, 1.0f, 0.51f, 0.7f, 1.5f, 0.58f))
                                drawRect(blob(5, 0.6f, 0.25f, 0.0f, 1.0f, 0.66f, 0.5f, 0.7f, 0.62f, 0.8f, 1.8f, 0.69f))
                            }
                    )
                }
            }

            PlayerBackgroundStyle.APPLE_MUSIC -> {
                // Apple Music-inspired blurred backdrop with authentic frosted liquid glass refraction
                fun appleBrush(stops: GradientStops): Brush = if (isMiniPlayer) {
                    Brush.horizontalGradient(
                        0.0f to stops.miniLeft.copy(alpha = 0.16f),
                        0.5f to stops.miniCenter.copy(alpha = 0.10f),
                        1.0f to stops.miniRight.copy(alpha = 0.20f)
                    )
                } else {
                    Brush.verticalGradient(
                        0.0f to stops.topVibrant,
                        0.48f to stops.midHarmonic,
                        1.0f to stops.bottomObsidian
                    )
                }
                val appleFrom = remember(fromStops, isMiniPlayer) { appleBrush(fromStops) }
                val appleTo = remember(toStops, isMiniPlayer) { appleBrush(toStops) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .crossfadeBackground(appleFrom, appleTo, blendProgress)
                )

                // Seamless Dual-Layer Apple Music Blurred Artwork (zero black frames while image decodes)
                SeamlessArtworkBlurLayer(
                    artworkUrl = artworkUrl,
                    isMiniPlayer = isMiniPlayer,
                    blurRadius = if (isMiniPlayer) 24.dp else 85.dp,
                    scale = if (isMiniPlayer) 1.35f else 1.55f,
                    targetAlpha = if (isMiniPlayer) 0.22f else 0.80f,
                    secondaryArtworkUrl = secondaryArtworkUrl,
                    swipeFraction = swipeFraction,
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
                fun meshBrush(stops: GradientStops): Brush = Brush.verticalGradient(
                    0.0f to stops.topVibrant.copy(alpha = 0.45f),
                    0.5f to stops.midHarmonic.copy(alpha = 0.25f),
                    1.0f to Color(0xFF050505)
                )
                val meshFrom = remember(fromStops) { meshBrush(fromStops) }
                val meshTo = remember(toStops) { meshBrush(toStops) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .crossfadeBackground(meshFrom, meshTo, blendProgress)
                )

                LiveMeshArtworkLayer(
                    artworkUrl = artworkUrl,
                    isMiniPlayer = isMiniPlayer,
                    isMonochrome = effectivePalette.isMonochrome,
                    secondaryArtworkUrl = secondaryArtworkUrl,
                    swipeFraction = swipeFraction,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Zero-recomposition, GPU-accelerated dual-layer gradient background renderer.
 * Keeps outgoing track's gradient fully stable in Slot 0/1 while incoming track's gradient
 * stops are computed ONCE. The incoming slot fades smoothly over the outgoing slot entirely
 * on the GPU compositor via [Modifier.graphicsLayer], eliminating 30-60 per-frame recompositions,
 * shader reallocations, and HSV math passes that previously caused transition lag.
 */
@Composable
private fun SeamlessGradientLayer(
    fromStops: GradientStops,
    toStops: GradientStops,
    progress: androidx.compose.runtime.State<Float>,
    isMiniPlayer: Boolean,
    modifier: Modifier = Modifier
) {
    // Outgoing gradient underneath, incoming gradient fading in over it on the GPU.
    Box(modifier = modifier.clipToBounds()) {
        SingleGradientLayer(
            stops = fromStops,
            isMiniPlayer = isMiniPlayer,
            alpha = { if (progress.value < 1f) 1f else 0f },
            modifier = Modifier.fillMaxSize()
        )
        SingleGradientLayer(
            stops = toStops,
            isMiniPlayer = isMiniPlayer,
            alpha = { progress.value },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SingleGradientLayer(
    stops: GradientStops,
    isMiniPlayer: Boolean,
    alpha: () -> Float,
    modifier: Modifier = Modifier
) {
    if (isMiniPlayer) {
        Box(
            modifier = modifier
                .graphicsLayer { this.alpha = alpha() }
                .drawWithCache {
                    val hGrad = Brush.horizontalGradient(
                        0.0f to stops.miniLeft.copy(alpha = 0.70f),
                        0.48f to stops.miniCenter.copy(alpha = 0.65f),
                        1.0f to stops.miniRight.copy(alpha = 0.75f)
                    )
                    val rBloom = Brush.radialGradient(
                        colors = listOf(stops.glowAccent.copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.5f),
                        radius = size.width * 0.45f
                    )
                    val vSheen = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.18f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.18f)
                        )
                    )

                    onDrawBehind {
                        drawRect(hGrad)
                        drawRect(rBloom)
                        drawRect(vSheen)
                    }
                }
        )
    } else {
        Box(
            modifier = modifier
                .graphicsLayer { this.alpha = alpha() }
                .drawWithCache {
                    val vGrad = Brush.verticalGradient(
                        0.0f to stops.topVibrant,
                        0.48f to stops.midHarmonic,
                        1.0f to stops.bottomObsidian
                    )
                    val rBloom = Brush.radialGradient(
                        colors = listOf(stops.topVibrant.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(size.width * 0.45f, size.height * 0.22f),
                        radius = size.width * 0.85f
                    )
                    val contrastWash = Color.Black.copy(alpha = 0.18f)

                    onDrawBehind {
                        drawRect(vGrad)
                        drawRect(rBloom)
                        drawRect(contrastWash)
                    }
                }
        )
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
    rotationZ: Float = 0f,
    translationYRatio: Float = 0f,
    secondaryArtworkUrl: String? = null,
    swipeFraction: Float = 0f
) {
    val context = LocalContext.current
    val colorFilter = remember(colorMatrix) {
        colorMatrix?.let { ColorFilter.colorMatrix(it) }
    }
    // Blur a quarter-size copy (radius / 4) and scale it back up. A 120dp blur over the full
    // screen, twice during every song-change crossfade, was the heaviest GPU work in the app and
    // dropped frames; blurred content has no fine detail, so the downscaled result looks the same.
    val blurDownscale = if (isMiniPlayer) 1f else 4f

    // Ping-pong layer slots: Slot 0 (base) and Slot 1 (overlay)
    // slot1Alpha: 0f = Slot 0 is fully visible; 1f = Slot 1 is fully visible.
    var slot0Url by remember { mutableStateOf<String?>(artworkUrl) }
    var slot1Url by remember { mutableStateOf<String?>(null) }
    var activeSlot by remember { mutableIntStateOf(0) }
    val slot1Alpha = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    // Which URL each slot has actually finished decoding. The incoming slot only starts fading
    // once its bitmap is ready; fading immediately finished on an empty layer and the artwork
    // then popped in, which read as a jerky background change.
    var slot0LoadedUrl by remember { mutableStateOf<String?>(null) }
    var slot1LoadedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artworkUrl, swipeFraction) {
        if (!artworkUrl.isNullOrBlank() && swipeFraction <= 0.005f) {
            val currentActiveUrl = if (activeSlot == 0) slot0Url else slot1Url
            if (artworkUrl != currentActiveUrl) {
                val blend = tween<Float>(PlayerTransitionMotion.paletteDurationMillis, easing = FastOutSlowInEasing)
                if (activeSlot == 0) {
                    slot1Url = artworkUrl
                    activeSlot = 1
                    awaitSlotLoaded(artworkUrl) { slot1LoadedUrl }
                    slot1Alpha.animateTo(1f, blend)
                } else {
                    slot0Url = artworkUrl
                    activeSlot = 0
                    awaitSlotLoaded(artworkUrl) { slot0LoadedUrl }
                    slot1Alpha.animateTo(0f, blend)
                }
            }
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        if (secondaryArtworkUrl != null && swipeFraction > 0.005f) {
            val prim = artworkUrl ?: (if (activeSlot == 0) slot0Url else slot1Url)
            if (!prim.isNullOrBlank()) {
                val primReq = remember(prim) {
                    ImageRequest.Builder(context)
                        .data(prim)
                        .size(if (isMiniPlayer) 128 else 256, if (isMiniPlayer) 128 else 256)
                        .allowHardware(true)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = primReq,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize(1f / blurDownscale)
                        .graphicsLayer {
                            scaleX = scale * blurDownscale
                            scaleY = scale * blurDownscale
                            if (translationYRatio != 0f) translationY = size.height * blurDownscale * translationYRatio
                            alpha = targetAlpha * (1f - swipeFraction)
                            if (rotationZ != 0f) this.rotationZ = rotationZ
                        }
                        .blur(radius = blurRadius / blurDownscale)
                )
            }

            val secReq = remember(secondaryArtworkUrl) {
                ImageRequest.Builder(context)
                    .data(secondaryArtworkUrl)
                    .size(if (isMiniPlayer) 128 else 256, if (isMiniPlayer) 128 else 256)
                    .allowHardware(true)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = secReq,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilter,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(1f / blurDownscale)
                    .graphicsLayer {
                        scaleX = scale * blurDownscale
                        scaleY = scale * blurDownscale
                        if (translationYRatio != 0f) translationY = size.height * blurDownscale * translationYRatio
                        alpha = targetAlpha * swipeFraction
                        if (rotationZ != 0f) this.rotationZ = rotationZ
                    }
                    .blur(radius = blurRadius / blurDownscale)
            )
        } else {
            // Stable Ping-Pong Dual-Layer: Slot 0 and Slot 1 persist seamlessly.
            // When reaching 1f or 0f, the active image is NEVER destroyed or reset, preventing any black flash.
            val u0 = slot0Url
            if (!u0.isNullOrBlank()) {
                val req0 = remember(u0) {
                    ImageRequest.Builder(context)
                        .data(u0)
                        .size(if (isMiniPlayer) 128 else 256, if (isMiniPlayer) 128 else 256)
                        .allowHardware(true)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = req0,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter,
                    onSuccess = { slot0LoadedUrl = u0 },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize(1f / blurDownscale)
                        .graphicsLayer {
                            scaleX = scale * blurDownscale
                            scaleY = scale * blurDownscale
                            if (translationYRatio != 0f) translationY = size.height * blurDownscale * translationYRatio
                            alpha = targetAlpha
                            if (rotationZ != 0f) this.rotationZ = rotationZ
                        }
                        .blur(radius = blurRadius / blurDownscale)
                )
            }

            val u1 = slot1Url
            if (!u1.isNullOrBlank()) {
                val req1 = remember(u1) {
                    ImageRequest.Builder(context)
                        .data(u1)
                        .size(if (isMiniPlayer) 128 else 256, if (isMiniPlayer) 128 else 256)
                        .allowHardware(true)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = req1,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter,
                    onSuccess = { slot1LoadedUrl = u1 },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize(1f / blurDownscale)
                        .graphicsLayer {
                            scaleX = scale * blurDownscale
                            scaleY = scale * blurDownscale
                            if (translationYRatio != 0f) translationY = size.height * blurDownscale * translationYRatio
                            alpha = targetAlpha * slot1Alpha.value
                            if (rotationZ != 0f) this.rotationZ = rotationZ
                        }
                        .blur(radius = blurRadius / blurDownscale)
                )
            }
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
    modifier: Modifier = Modifier,
    secondaryArtworkUrl: String? = null,
    swipeFraction: Float = 0f
) {
    val context = LocalContext.current
    var slot0Url by remember { mutableStateOf<String?>(artworkUrl) }
    var slot1Url by remember { mutableStateOf<String?>(null) }
    var activeSlot by remember { mutableIntStateOf(0) }
    val slot1Alpha = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    // Which URL each slot has actually finished decoding. The incoming slot only starts fading
    // once its bitmap is ready; fading immediately finished on an empty layer and the artwork
    // then popped in, which read as a jerky background change.
    var slot0LoadedUrl by remember { mutableStateOf<String?>(null) }
    var slot1LoadedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(artworkUrl, swipeFraction) {
        if (!artworkUrl.isNullOrBlank() && swipeFraction <= 0.005f) {
            val currentActiveUrl = if (activeSlot == 0) slot0Url else slot1Url
            if (artworkUrl != currentActiveUrl) {
                val blend = tween<Float>(PlayerTransitionMotion.paletteDurationMillis, easing = FastOutSlowInEasing)
                if (activeSlot == 0) {
                    slot1Url = artworkUrl
                    activeSlot = 1
                    awaitSlotLoaded(artworkUrl) { slot1LoadedUrl }
                    slot1Alpha.animateTo(1f, blend)
                } else {
                    slot0Url = artworkUrl
                    activeSlot = 0
                    awaitSlotLoaded(artworkUrl) { slot0LoadedUrl }
                    slot1Alpha.animateTo(0f, blend)
                }
            }
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
    val miniRotation = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "miniMeshRotation"
    )
    val anchorRotation = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 80000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "anchorRotation"
    )
    val fastRotation = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 40000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fastRotation"
    )
    val slowRotation = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "slowRotation"
    )

    Box(modifier = modifier.clipToBounds()) {
        if (secondaryArtworkUrl != null && swipeFraction > 0.005f) {
            val prim = artworkUrl ?: (if (activeSlot == 0) slot0Url else slot1Url)
            if (!prim.isNullOrBlank()) {
                LiveMeshArtworkContent(
                    url = prim,
                    isMiniPlayer = isMiniPlayer,
                    colorFilter = colorFilter,
                    miniRotation = miniRotation,
                    anchorRotation = anchorRotation,
                    fastRotation = fastRotation,
                    slowRotation = slowRotation,
                    alpha = { 1f - swipeFraction },
                    onLoaded = null
                )
            }
            LiveMeshArtworkContent(
                url = secondaryArtworkUrl,
                isMiniPlayer = isMiniPlayer,
                colorFilter = colorFilter,
                miniRotation = miniRotation,
                anchorRotation = anchorRotation,
                fastRotation = fastRotation,
                slowRotation = slowRotation,
                alpha = { swipeFraction },
                onLoaded = null
            )
        } else {
            // Stable Ping-Pong Live Mesh Layers
            val u0 = slot0Url
            if (!u0.isNullOrBlank()) {
                LiveMeshArtworkContent(
                    url = u0,
                    isMiniPlayer = isMiniPlayer,
                    colorFilter = colorFilter,
                    miniRotation = miniRotation,
                    anchorRotation = anchorRotation,
                    fastRotation = fastRotation,
                    slowRotation = slowRotation,
                    alpha = { 1f },
                    onLoaded = { slot0LoadedUrl = u0 }
                )
            }

            val u1 = slot1Url
            if (!u1.isNullOrBlank()) {
                LiveMeshArtworkContent(
                    url = u1,
                    isMiniPlayer = isMiniPlayer,
                    colorFilter = colorFilter,
                    miniRotation = miniRotation,
                    anchorRotation = anchorRotation,
                    fastRotation = fastRotation,
                    slowRotation = slowRotation,
                    // Read at draw time: reading slot1Alpha.value here recomposed the mesh per frame.
                    alpha = { slot1Alpha.value },
                    onLoaded = { slot1LoadedUrl = u1 }
                )
            }
        }
    }
}

@Composable
private fun LiveMeshArtworkContent(
    url: String,
    isMiniPlayer: Boolean,
    colorFilter: ColorFilter,
    // States/lambdas, read only inside graphicsLayer: passing the raw Floats recomposed the whole
    // mesh (four blurred images) on every frame of its never-ending rotation.
    miniRotation: androidx.compose.runtime.State<Float>,
    anchorRotation: androidx.compose.runtime.State<Float>,
    fastRotation: androidx.compose.runtime.State<Float>,
    slowRotation: androidx.compose.runtime.State<Float>,
    alpha: () -> Float,
    onLoaded: (() -> Unit)?
) {
    val context = LocalContext.current
    val meshReq = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(128, 128)
            .allowHardware(true)
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
                    this.alpha = alpha()
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
                    .graphicsLayer { rotationZ = miniRotation.value }
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
                    this.alpha = alpha()
                }
        ) {
            // The three blurred layers render at quarter size (blur radius / 4) and are scaled
            // back up: six full-screen 100-120dp blurs during a song-change crossfade dropped frames.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(0.25f)
                    .graphicsLayer {
                        scaleX = 4f
                        scaleY = 4f
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
                        .blur(25.dp)
                        .graphicsLayer { rotationZ = anchorRotation.value }
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
                        .blur(30.dp)
                        .graphicsLayer {
                            rotationZ = fastRotation.value
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
                        .blur(30.dp)
                        .graphicsLayer {
                            rotationZ = slowRotation.value
                            this.alpha = 0.5f
                        }
                )
            }

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

/** Suspends until [loadedUrl] reports [url] decoded, or a short timeout so a failed load cannot stall. */
private suspend fun awaitSlotLoaded(url: String, loadedUrl: () -> String?) {
    kotlinx.coroutines.withTimeoutOrNull(1_500L) {
        androidx.compose.runtime.snapshotFlow { loadedUrl() }.first { it == url }
    }
}
