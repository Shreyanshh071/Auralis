package com.auralis.music.windows.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.auralis.music.ui.theme.ArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jetbrains.skia.*
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Image-domain flow inspired by Spicy Lyrics' Kawarp background. Kawarp shader port:
 * preblur a tiny cover once, then warp its coordinates on the GPU.
 * No audio analysis is fabricated: the flow runs at a steady speed.
 */
@Composable
internal fun ArtworkAura(palette: ArtworkPalette, modifier: Modifier, artwork: String? = null) {
    var cover by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artwork) {
        if (artwork == null) { cover = null; return@LaunchedEffect }
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val url = com.auralis.music.ui.components.getOptimizedThumbnailUrl(artwork) ?: artwork
                com.auralis.music.data.network.NetworkClientProvider.okHttpClient
                    .newCall(Request.Builder().url(url).build()).execute().use { response ->
                        check(response.isSuccessful)
                        response.body?.byteStream()?.use(ImageIO::read)?.let(::auraTexture)
                    }
            }.getOrNull()
        }
        // Retain the previous cover while the next one loads; crossfade only decoded images.
        if (decoded != null) cover = decoded
    }
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var previous = 0L
        while (true) withFrameNanos { now ->
            if (previous != 0L) time.floatValue += ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
            previous = now
        }
    }
    Crossfade(cover, modifier, animationSpec = tween(1000), label = "cover flow transition") { image ->
        if (image == null) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.linearGradient(listOf(palette.primary, palette.secondary, palette.tertiary)
                    .map { lerp(androidx.compose.ui.graphics.Color.Black, it, .45f) }))
            }
        } else {
            val resources = remember(image) { AuraRenderer(image) }
            DisposableEffect(resources) { onDispose { resources.close() } }
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas -> resources.draw(canvas.nativeCanvas, size.width, size.height, time.floatValue) }
            }
        }
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

// Domain-warp noise and octave mixing adapted from Better Lyrics/Kawarp (MIT).
// License is bundled at third-party/kawarp-LICENSE.txt.
internal class AuraRenderer(image: ImageBitmap) : AutoCloseable {
    private val texture = Image.makeFromBitmap(image.asSkiaBitmap())
    private val child = texture.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR)
    private val effect = RuntimeEffect.makeForShader("""
        uniform shader cover;
        uniform float2 resolution;
        uniform float time;
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
            half luminance = dot(colour, half3(0.2126, 0.7152, 0.0722));
            colour = max(half3(0.0), mix(half3(luminance), colour, 1.5));
            // Readable foreground while retaining the cover's bright coloured regions.
            colour *= 0.57 - 0.12 * uv.y;
            return half4(colour, 1.0);
        }
    """.trimIndent())
    private val builder = RuntimeShaderBuilder(effect).apply { child("cover", child) }
    private val paint = org.jetbrains.skia.Paint()
    fun draw(canvas: org.jetbrains.skia.Canvas, width: Float, height: Float, seconds: Float) {
        builder.uniform("resolution", width, height); builder.uniform("time", seconds)
        builder.makeShader().use { shader ->
            paint.shader = shader
            canvas.drawRect(Rect.makeWH(width, height), paint)
            paint.shader = null
        }
    }
    override fun close() { paint.close(); builder.close(); effect.close(); child.close(); texture.close() }
}
