package com.auralis.music.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import com.auralis.music.ui.components.getOptimizedThumbnailUrl
import com.auralis.music.ui.theme.ArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ExpandedMiniPlayerAura(artwork: String?, palette: ArtworkPalette, modifier: Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !artwork.isNullOrBlank()) {
        val context = LocalContext.current
        val texture by key(artwork) {
            produceState<Bitmap?>(null) {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(getOptimizedThumbnailUrl(artwork) ?: artwork)
                        .size(128)
                        .scale(Scale.FILL)
                        .allowHardware(false)
                        .build()
                )
                if (result is SuccessResult) {
                    value = withContext(Dispatchers.Default) {
                        val cover = result.drawable.toBitmap(config = Bitmap.Config.ARGB_8888)
                        val side = minOf(cover.width, cover.height)
                        val square = Bitmap.createBitmap(
                            cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side
                        )
                        // Reduce the cover to a soft field of colors before warping it.
                        var blurred = square
                        for (size in listOf(64, 32, 16, 8, 4)) {
                            if (blurred.width > size) {
                                blurred = Bitmap.createScaledBitmap(blurred, size, size, true)
                            }
                        }
                        Bitmap.createScaledBitmap(blurred, 128, 128, true)
                    }
                }
            }
        }
        texture?.let {
            FlowingCover(it, modifier)
            return
        }
    }
    PlayerBackground(
        style = PlayerBackgroundStyle.GLOW_MOTION,
        artworkUrl = artwork,
        extractedColors = palette,
        modifier = modifier,
        isMiniPlayer = true
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun FlowingCover(texture: Bitmap, modifier: Modifier) {
    val clock = rememberInfiniteTransition(label = "Cover aura")
    // Reverse the clock at the end of each pass to keep the flow continuous.
    val time = clock.animateFloat(
        initialValue = 0f,
        targetValue = 300f,
        animationSpec = infiniteRepeatable(
            animation = tween(300_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Aura time"
    )
    val shader = remember(texture) {
        RuntimeShader(SHORTCUT_AURA_SHADER).apply {
            val cover = BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                filterMode = BitmapShader.FILTER_MODE_LINEAR
            }
            setInputShader("cover", cover)
            setFloatUniform("brightness", 0.62f)
            setFloatUniform("saturation", 1.7f)
        }
    }
    val paint = remember(shader) { Paint().apply { this.shader = shader } }
    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (native.isHardwareAccelerated) {
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("time", time.value)
                native.drawRect(0f, 0f, size.width, size.height, paint)
            }
        }
    }
}

// Matches the desktop shortcut's domain-warp motion, adapted from Better Lyrics/Kawarp (MIT).
// License: assets/third-party/kawarp-LICENSE.txt.
private val SHORTCUT_AURA_SHADER = """

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
            colour *= half(1.0 - dot(center, center) * 0.3);
            half luminance = dot(colour, half3(0.299, 0.587, 0.114));
            colour = clamp(mix(half3(luminance), colour, 1.5), 0.0, 1.0);
            half cssLuminance = dot(colour, half3(0.213, 0.715, 0.072));
            colour = clamp(mix(half3(cssLuminance), colour, saturation), 0.0, 1.0) * brightness;
            return half4(clamp(colour, 0.0, 1.0), 1.0);
        }
          """.trimIndent()
