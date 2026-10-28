/* Lens shader from Kyant0/AndroidLiquidGlass 2.0.1, Copyright 2025 Kyant.
 * Licensed under Apache-2.0; same shader used by Auralis's Android dock. */
package com.auralis.music.windows

import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/** Compiled once per dock, filters rebuilt only on size/density changes. */
internal class DockGlassEffect : AutoCloseable {
    private val effect = RuntimeEffect.makeForShader(DockLensShader)
    fun create(width: Float, height: Float, padding: Float, density: Float, cornerRadius: Float = height / 2f,
        blurDp: Float = 18f, lensHeightDp: Float = 20f, lensAmountDp: Float = 28f): androidx.compose.ui.graphics.RenderEffect {
        return RuntimeShaderBuilder(effect).use { builder ->
            builder.uniform("size", width, height)
            builder.uniform("offset", -padding, -padding)
            val radius = cornerRadius.coerceAtMost(minOf(width, height) / 2f)
            builder.uniform("cornerRadii", radius, radius, radius, radius)
            builder.uniform("refractionHeight", lensHeightDp * density)
            builder.uniform("refractionAmount", -lensAmountDp * density)
            builder.uniform("depthEffect", 1f)
            builder.uniform("chromaticAberration", 1f)
            // Android's vibrancy(): saturation 1.5, applied before blur and refraction.
            val r = -0.5f * 0.213f; val g = -0.5f * 0.715f; val b = -0.5f * 0.072f
            ColorFilter.makeMatrix(ColorMatrix(
                r + 1.5f, g, b, 0f, 0f,
                r, g + 1.5f, b, 0f, 0f,
                r, g, b + 1.5f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )).use { saturation ->
                ImageFilter.makeColorFilter(saturation, null, null).use { vibrant ->
                    ImageFilter.makeBlur(blurDp * density, blurDp * density, FilterTileMode.CLAMP, vibrant).use { blur ->
                        ImageFilter.makeRuntimeShader(builder, "content", blur).asComposeRenderEffect()
                    }
                }
            }
        }
    }
    override fun close() = effect.close()
}

private const val RoundedRectSDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

private val DockLensShader = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$RoundedRectSDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);
    
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    
    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;
    
    half4 color = half4(0.0);
    
    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;
    
    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;
    
    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;
    
    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;
    
    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;
    
    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;
    
    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;
    
    return color;
}"""
