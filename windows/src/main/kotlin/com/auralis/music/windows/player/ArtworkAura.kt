package com.auralis.music.windows.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.auralis.music.ui.theme.ArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.jetbrains.skia.*
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Image-domain flow inspired by Spicy Lyrics' Kawarp background. Kawarp shader port:
 * preblur a tiny cover once, then warp its coordinates on the GPU.
 * No audio analysis is fabricated: the flow runs at a steady speed.
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
        auraTextureCache[url] ?: runCatching {
            val embedded = com.auralis.music.ui.components.DesktopArtworkRenderer.decodeDataUri(url)
            val bytes = if (embedded != null) embedded
            else if (url.startsWith("data:")) null
            else if (java.io.File(url).isFile) java.io.File(url).readBytes()
            else if (url.startsWith("file:")) java.io.File(java.net.URI(url)).readBytes()
            else com.auralis.music.data.network.NetworkClientProvider.okHttpClient
                .newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful)
                    response.body?.bytes()
                }
            // Use the app's native decoder: ImageIO silently returns null for WebP covers.
            bytes?.let { encoded -> Image.makeFromEncoded(encoded).use { decoded ->
                motionAuraTexture(decoded.toComposeImageBitmap())
            } }?.also { auraTextureCache[url] = it }
        }.getOrNull()
    }
}

@Composable
internal fun rememberAuraCover(artwork: String?, fallbackArtworks: List<String> = emptyList()): State<ImageBitmap?> {
    val url = artwork?.let(::auraTextureKey)
    // Keep the decoded previous song until its replacement is ready.
    val cover = remember { mutableStateOf(url?.let { auraTextureCache[it] }) }
    LaunchedEffect(url, fallbackArtworks) {
        if (url == null) cover.value = null
        val sources = (listOfNotNull(url) + fallbackArtworks).distinct()
        if (sources.isNotEmpty()) repeat(3) { attempt ->
            for (source in sources) {
                val loaded = loadAuraTexture(source)
                if (loaded != null) { cover.value = loaded; return@LaunchedEffect }
            }
            if (attempt < 2) kotlinx.coroutines.delay(500L * (attempt + 1))
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
    fallbackArtworks: List<String> = emptyList()) {
    val still by rememberAuraCover(artwork, fallbackArtworks)
    val moving = motionAura?.texture?.value
    val cover = moving ?: still
    val time = remember { mutableFloatStateOf(0f) }
    val auraOff = com.auralis.music.windows.dev.PerfFlags.off("aura")
    LaunchedEffect(Unit) {
        if (auraOff) return@LaunchedEffect
        var previous = 0L
        while (true) withFrameNanos { now ->
            if (previous != 0L) time.floatValue += ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
            previous = now
        }
    }
    // A shortcut whose cover is still loading shows a quiet sheen, then its aura sweeps in from the
    // right as it arrives (a flat grey card that popped into colour looked broken). A cover already
    // decoded (cached) shows at once.
    val shortcut = presentation == AuraPresentation.SHORTCUT
    val reveal = remember { androidx.compose.animation.core.Animatable(if (cover != null) 1f else 0f) }
    LaunchedEffect(cover != null) {
        if (cover != null && reveal.value < 1f) reveal.animateTo(1f,
            androidx.compose.animation.core.tween(SHORTCUT_REVEAL_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    // The first decoded image shows at once. A new one (the next song, or an animated cover's next
    // colour frame) fades in over the one on screen, which stays fully opaque underneath until the
    // fade ends: a crossfade dimmed both halfway, so the sidebar pulsed with every video frame.
    if (cover == null) Box(modifier) {
        if (presentation == AuraPresentation.FULL_PLAYER && artwork != null) {
            BlurredArtworkBackground(artwork)
        } else if (shortcut) {
            // Waiting for this cover's colours: never a fabricated aura, just a slow sheen.
            ShortcutLoadingSheen(time)
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val fallback = listOf(palette.primary, palette.secondary, palette.tertiary)
                drawRect(Brush.linearGradient(fallback.map { lerp(androidx.compose.ui.graphics.Color.Black, it, .45f) }))
            }
        }
    } else Box(modifier) {
      if (shortcut && reveal.value < 1f) ShortcutLoadingSheen(time)
      AuraLayers(cover, Modifier.fillMaxSize().then(if (!shortcut) Modifier else Modifier
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
        else Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas -> resources.draw(canvas.nativeCanvas, size.width, size.height, time.floatValue) }
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
            for (shift in intArrayOf(16, 8, 0)) {
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
            colour = mix(half3(cssLuminance), colour, saturation) * brightness;
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
