package com.auralis.music.windows.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import coil3.toBitmap
import com.auralis.music.ui.components.DesktopArtworkRenderer
import com.auralis.music.ui.theme.ArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.*
import java.io.File
import java.net.URI
import java.awt.image.BufferedImage

/** Image-domain flow inspired by Spicy Lyrics' Kawarp background. Kawarp shader port:
 * preblur a tiny cover once, then warp its coordinates on the GPU.
 * The full player slows the flow when playback pauses.
 */
internal enum class AuraPresentation(val brightness: Float, val saturation: Float) {
    FULL_PLAYER(.65f, 2.5f), SIDEBAR(.6f, 2.65f), SHORTCUT(.78f, 1.7f)
}

private val auraTextureCache = java.util.Collections.synchronizedMap(
    object : java.util.LinkedHashMap<String, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > 160
        }
    }
)

// Serialize duplicate loads without putting every shortcut and player cover behind one request.
private val auraTextureLoadMutexes = Array(32) { Mutex() }

internal fun auraTextureKey(artwork: String): String =
    com.auralis.music.ui.components.getOptimizedThumbnailUrl(artwork) ?: artwork

/** Shared by preloading and visible auras, including different resolutions of the same cover. */
internal suspend fun loadAuraTexture(artwork: String): ImageBitmap? = withContext(Dispatchers.IO) {
    val url = auraTextureKey(artwork)
    auraTextureLoadMutexes[(url.hashCode() and Int.MAX_VALUE) % auraTextureLoadMutexes.size].withLock {
        auraTextureCache[url] ?: run {
            val context = PlatformContext.INSTANCE
            val loader = SingletonImageLoader.get(context)
            for (candidate in listOf(url, artwork).distinct()) {
                val texture = if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                    val request = DesktopArtworkRenderer.request(context, candidate, DesktopArtworkRenderer.TILE_PX, crossfade = false)
                    val result = loader.execute(request) as? SuccessResult ?: continue
                    withContext(Dispatchers.Default) {
                        runCatching { motionAuraTexture(result.image.toBitmap().asComposeImageBitmap()) }.getOrNull()
                    }
                } else {
                    runCatching {
                        val bytes = when {
                            candidate.startsWith("data:") -> DesktopArtworkRenderer.decodeDataUri(candidate)
                            candidate.startsWith("file:") -> File(URI(candidate)).readBytes()
                            else -> File(candidate).readBytes()
                        }
                        bytes?.let { Image.makeFromEncoded(it).use { image -> motionAuraTexture(image.toComposeImageBitmap()) } }
                    }.getOrNull()
                } ?: continue
                auraTextureCache[url] = texture
                return@withLock texture
            }
            null
        }
    }
}

@Composable
internal fun rememberAuraCover(artwork: String?, fallbackArtworks: List<String> = emptyList()): State<ImageBitmap?> {
    val url = artwork?.let(::auraTextureKey)
    // Keep the decoded previous song until its replacement is ready.
    val cover = remember { mutableStateOf(url?.let { auraTextureCache[it] }) }
    LaunchedEffect(artwork, fallbackArtworks) {
        if (url == null) cover.value = null
        val sources = (listOfNotNull(artwork?.takeIf { it.isNotBlank() }) + fallbackArtworks).distinct()
        var attempt = 0
        while (sources.isNotEmpty()) {
            for (source in sources) {
                val loaded = loadAuraTexture(source)
                if (loaded != null) { cover.value = loaded; return@LaunchedEffect }
            }
            kotlinx.coroutines.delay(minOf(30_000L, 1000L shl attempt.coerceAtMost(5)))
            attempt++
        }
    }
    return cover
}

@Composable
internal fun ArtworkAura(palette: ArtworkPalette, modifier: Modifier, artwork: String? = null,
    presentation: AuraPresentation = AuraPresentation.SIDEBAR,
    brightness: Float = presentation.brightness,
    saturation: Float = presentation.saturation,
    motionAura: MotionAura? = null,
    fallbackArtworks: List<String> = emptyList(),
    playbackClock: com.auralis.music.data.service.PlaybackClockSource? = null,
    spotifyTrackId: String? = null, trackDurationSeconds: Long = 0L) {
    val still by rememberAuraCover(artwork, fallbackArtworks)
    val moving = motionAura?.texture?.value
    val cover = moving ?: still
    val time = remember { mutableFloatStateOf(0f) }
    val zoom = remember { mutableFloatStateOf(1f) }
    val motion = remember { ArtworkMotionClock() }
    val auraOff = com.auralis.music.windows.dev.PerfFlags.off("aura")
    val visualsActive = com.auralis.music.ui.player.LocalPlaybackVisualsActive.current
    val analysis = produceState<ArtworkEnergy?>(null, spotifyTrackId, trackDurationSeconds, visualsActive, auraOff) {
        value = null
        if (visualsActive && !auraOff) value = DynamicArtworkAnalysis.find(spotifyTrackId, trackDurationSeconds)
    }
    LaunchedEffect(visualsActive, auraOff, playbackClock) {
        if (auraOff || !visualsActive) return@LaunchedEffect
        var previous = 0L
        while (true) {
            // Slow colour drift needs 30 updates/s, even on a 120 Hz display.
            kotlinx.coroutines.delay(25L)
            withFrameNanos { now ->
                if (previous != 0L) {
                    val elapsed = (now - previous) / 1_000_000_000f
                    if (playbackClock == null) time.floatValue += elapsed.coerceAtMost(.05f)
                    else {
                        val energy = analysis.value?.at(playbackClock.rawPositionMs() / 1000.0 + .6)
                        motion.step(elapsed, playbackClock.isPlaying(), energy)
                        time.floatValue = motion.time
                        zoom.floatValue = motion.scale
                    }
                }
                previous = now
            }
        }
    }
    // A shortcut whose cover is still loading shows a quiet sheen, then its aura sweeps in from the
    // right as it arrives (a flat grey card that popped into colour looked broken). A cover already
    // decoded (cached) shows at once.
    val shortcut = presentation == AuraPresentation.SHORTCUT
    val fullPlayer = presentation == AuraPresentation.FULL_PLAYER
    val reveal = remember { androidx.compose.animation.core.Animatable(if (cover != null) 1f else 0f) }
    LaunchedEffect(cover != null) {
        if (cover == null && fullPlayer) reveal.snapTo(0f)
        else if (cover != null && reveal.value < 1f) {
            reveal.animateTo(1f, tween(if (fullPlayer) 220 else SHORTCUT_REVEAL_MS,
                easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    // Keep a painted base under the reveal. Cached textures can draw immediately.
    Box(modifier) {
        if (fullPlayer || (cover == null && !shortcut)) {
            val shade = if (fullPlayer && palette.isDefault) .9f else if (fullPlayer) brightness else .45f
            Canvas(Modifier.fillMaxSize()) {
                val colors = listOf(palette.primary, palette.secondary, palette.tertiary)
                drawRect(Brush.linearGradient(colors.map { lerp(Color.Black, it, shade) }))
            }
        }
        if (shortcut && (cover == null || reveal.value < 1f)) ShortcutLoadingSheen(time)
        if (cover != null) {
            AuraLayers(cover, Modifier.fillMaxSize().then(if (fullPlayer) Modifier.graphicsLayer { alpha = reveal.value }
                  else if (!shortcut) Modifier else Modifier
                  .graphicsLayer {
                      val progress = reveal.value
                      alpha = .35f + .65f * progress
                      // Its soft leading edge sweeps across from the right (drawWithContent below). Shifting
                      // the layer as well left a hard edge on the card's left side.
                      compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                  }
                  .drawWithContent {
                      drawContent()
                      val progress = reveal.value
                      if (progress < 1f) {
                          val feather = size.width * .55f
                          val edge = size.width - progress * (size.width + feather)
                          drawRect(Brush.horizontalGradient(
                              listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black),
                              startX = edge, endX = edge + feather), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                      }
                  }), fadeMs = if (moving != null) MOTION_FADE_MS else 1000) { image ->
                val resources = remember(image, presentation, brightness, saturation) {
                    AuraRenderer(image, presentation, brightness, saturation)
                }
                DisposableEffect(resources) { onDispose { resources.close() } }
                if (auraOff) androidx.compose.foundation.Image(image, null, Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    // The source is already blurred to 128px. Evaluate the noise on a smaller GPU
                    // layer and upscale it, rather than shading every Retina pixel on every frame.
                    // Video and sharp lyric glyphs remain at the window's native resolution.
                    val renderScale = if (presentation == AuraPresentation.FULL_PLAYER) .25f else .5f
                    Canvas(Modifier.fillMaxSize(renderScale).graphicsLayer {
                        scaleX = 1f / renderScale
                        scaleY = 1f / renderScale
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    }) {
                        scale(zoom.floatValue) {
                            drawIntoCanvas { canvas -> resources.draw(canvas.nativeCanvas, size.width, size.height, time.floatValue) }
                        }
                    }
                }
            }
        }
    }
}

/** How long each new animated-cover colour frame takes to fade in (MotionAura waits longer between frames). */
internal const val MOTION_FADE_MS = 600

/** How long a shortcut's aura takes to sweep in once its cover is ready. */
internal const val SHORTCUT_REVEAL_MS = 1100

/** A shortcut waiting for its cover: the card's dark base with a faint light drifting right to left. */
@Composable
private fun ShortcutLoadingSheen(time: androidx.compose.runtime.FloatState) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(androidx.compose.ui.graphics.Color(0xFF202024))
        val band = size.width * .7f
        // One pass every 2.4 s, entering at the right edge and leaving past the left.
        val cycle = (time.floatValue / 2.4f) % 1f
        val center = size.width + band / 2 - cycle * (size.width + band)
        drawRect(Brush.horizontalGradient(
            listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.White.copy(alpha = .07f),
                androidx.compose.ui.graphics.Color.Transparent),
            startX = center - band / 2, endX = center + band / 2))
    }
}

/** [image] fading in on top of the previous image, which stays opaque underneath until the fade completes. */
@Composable
private fun AuraLayers(image: ImageBitmap, modifier: Modifier, fadeMs: Int, layer: @Composable (ImageBitmap) -> Unit) {
    var top by remember { mutableStateOf(image) }
    var below by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(image) {
        if (image === top) return@LaunchedEffect
        below = top
        top = image
        fade.snapTo(0f)
        fade.animateTo(1f, tween(fadeMs))
        below = null
    }
    Box(modifier) {
        below?.let { previous -> key(previous) { Box(Modifier.fillMaxSize()) { layer(previous) } } }
        key(top) { Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) { layer(top) } }
    }
}

/** Kawarp's eight diagonal Kawase passes, computed once off the render thread at 128px. */
internal fun auraTexture(source: BufferedImage): ImageBitmap {
    val side = 128
    val tiny = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
    tiny.createGraphics().let { graphics ->
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(source, 0, 0, side, side, null); graphics.dispose()
    }
    var pixels = tiny.getRGB(0, 0, side, side, null, 0, side)
    val shifts = intArrayOf(16, 8, 0)
    fun channel(x: Float, y: Float, shift: Int): Float {
        val cx = x.coerceIn(0f, (side - 1).toFloat()); val cy = y.coerceIn(0f, (side - 1).toFloat())
        val ix = cx.toInt(); val iy = cy.toInt(); val nx = (ix + 1).coerceAtMost(side - 1); val ny = (iy + 1).coerceAtMost(side - 1)
        val fx = cx - ix; val fy = cy - iy
        val top = (pixels[iy * side + ix] shr shift and 255) * (1 - fx) + (pixels[iy * side + nx] shr shift and 255) * fx
        val bottom = (pixels[ny * side + ix] shr shift and 255) * (1 - fx) + (pixels[ny * side + nx] shr shift and 255) * fx
        return top * (1 - fy) + bottom * fy
    }
    repeat(8) { pass ->
        val offset = pass + .5f
        val output = IntArray(pixels.size)
        for (y in 0 until side) for (x in 0 until side) {
            var pixel = 255 shl 24
            for (shift in shifts) {
                val value = (channel(x - offset, y - offset, shift) + channel(x + offset, y - offset, shift) +
                    channel(x - offset, y + offset, shift) + channel(x + offset, y + offset, shift)) * .25f
                pixel = pixel or (value.toInt() shl shift)
            }
            output[y * side + x] = pixel
        }
        pixels = output
    }
    tiny.setRGB(0, 0, side, side, pixels, 0, side)
    return tiny.toComposeImageBitmap()
}

/** The aura shader, compiled once and shared: compiling it per renderer cost a hitch for every animated-cover frame. */
private val sharedAuraEffect: RuntimeEffect by lazy { RuntimeEffect.makeForShader("""
        uniform shader cover;
        uniform float2 resolution;
        uniform float time;
        uniform float brightness;
        uniform float saturation;
  float3 mod289(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
  float2 mod289(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
  float3 permute(float3 x) { return mod289(((x*34.0)+1.0)*x); }

  float snoise(float2 v) {
    const float4 C = float4(0.211324865405187, 0.366025403784439,
                        -0.577350269189626, 0.024390243902439);
    float2 i  = floor(v + dot(v, C.yy));
    float2 x0 = v - i + dot(i, C.xx);
    float2 i1 = (x0.x > x0.y) ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod289(i);
    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
    float3 m = max(0.5 - float3(dot(x0,x0), dot(x12.xy,x12.xy), dot(x12.zw,x12.zw)), 0.0);
    m = m*m; m = m*m;
    float3 x = 2.0 * fract(p * C.www) - 1.0;
    float3 h = abs(x) - 0.5;
    float3 ox = floor(x + 0.5);
    float3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0*a0 + h*h);
    float3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
  }

        half4 main(float2 p) {
            float2 uv = p / resolution;
            float t = time * 0.05;
            float2 center = uv - 0.5;
            float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(center));
            float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));
            float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0));
            float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));
            float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));
            float2 warp = float2(n1 * 0.65 + n3 * 0.35, n2 * 0.65 + n4 * 0.35) * centerWeight;
            float2 samplePoint = clamp(uv + warp, 0.0, 1.0) * 128.0;
            half3 colour = cover.eval(samplePoint).rgb;
            // Kawarp's output is first written to an 8-bit canvas: vignette, 1.5x
            // saturation, then clamp. Its CSS filter is a separate, subsequent pass.
            colour *= half(1.0 - dot(center, center) * 0.3);
            half luminance = dot(colour, half3(0.299, 0.587, 0.114));
            colour = clamp(mix(half3(luminance), colour, 1.5), 0.0, 1.0);
            // spicy-dynamic-bg.css: saturate(2.5) brightness(.65) for the full player,
            // brightness(.6) saturate(2.65) for the sidebar. These linear operations
            // commute; keep the intermediate canvas clamp above, before CSS filtering.
            half cssLuminance = dot(colour, half3(0.213, 0.715, 0.072));
            // Clip the saturation pass before darkening, across every album colour.
            colour = clamp(mix(half3(cssLuminance), colour, saturation), 0.0, 1.0) * brightness;
            return half4(clamp(colour, 0.0, 1.0), 1.0);
        }
    """.trimIndent()) }

// Domain-warp noise and octave mixing adapted from Better Lyrics/Kawarp (MIT).
// License is bundled at third-party/kawarp-LICENSE.txt.
internal class AuraRenderer(image: ImageBitmap,
    private val presentation: AuraPresentation = AuraPresentation.SIDEBAR,
    private val brightness: Float = presentation.brightness,
    private val saturation: Float = presentation.saturation) : AutoCloseable {
    private val texture = Image.makeFromBitmap(image.asSkiaBitmap())
    private val child = texture.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR)
    private val effect = sharedAuraEffect
    private val builder = RuntimeShaderBuilder(effect).apply { child("cover", child) }
    private val paint = org.jetbrains.skia.Paint()
    private val software = System.getProperty("skiko.renderApi").orEmpty().startsWith("SOFTWARE")
    private var raster: Surface? = null
    private var frame: Image? = null
    private var frameTick = Long.MIN_VALUE
    fun draw(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float, seconds: Float) {
        if (software) {
            // This background is already heavily blurred. Rasterize its warp at a small
            // resolution so backdrop filters sample an image instead of re-evaluating
            // the noise shader for every blur/refraction sample on the CPU.
            val scale = 64f / maxOf(width, height).coerceAtLeast(64f)
            val w = (width * scale).toInt().coerceAtLeast(1)
            val h = (height * scale).toInt().coerceAtLeast(1)
            if (raster?.width != w || raster?.height != h) {
                raster?.close(); raster = Surface.makeRasterN32Premul(w,h)
                frameTick = Long.MIN_VALUE
            }
            val tick = (seconds * 30).toLong()
            if (frameTick != tick) {
                drawWarp(raster!!.canvas, w.toFloat(), h.toFloat(), seconds)
                frame?.close(); frame = raster!!.makeImageSnapshot(); frameTick = tick
            }
            canvas.drawImageRect(frame!!, Rect.makeWH(w.toFloat(),h.toFloat()), Rect.makeWH(width,height), SamplingMode.LINEAR, null, true)
        } else drawWarp(canvas, width, height, seconds)
    }
    private fun drawWarp(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float, seconds: Float) {
        builder.uniform("resolution", width, height); builder.uniform("time", seconds)
        builder.uniform("brightness", brightness); builder.uniform("saturation", saturation)
        builder.makeShader().use { shader ->
            paint.shader = shader
            canvas.drawRect(Rect.makeWH(width, height), paint)
            paint.shader = null
        }
    }
    // The shared shader stays alive; only this renderer's own objects are freed.
    override fun close() { frame?.close(); raster?.close(); paint.close(); builder.close(); child.close(); texture.close() }
}
