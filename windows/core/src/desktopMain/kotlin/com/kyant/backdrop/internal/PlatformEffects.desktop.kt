/*
 * Copyright 2025 Kyant0. Licensed under the Apache License, Version 2.0.
 * From github.com/Kyant0/AndroidLiquidGlass, module "backdrop", tag 2.0.1.
 * Bundled as source, with its multiplatform expect/actual split restored (androidMain / desktopMain).
 */
package com.kyant.backdrop.internal

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import com.kyant.backdrop.RuntimeShader

// Only reached when isRenderEffectSupported() is true, which it isn't on desktop yet.
internal actual fun RenderEffect?.chain(other: RenderEffect): RenderEffect = other

internal actual fun RuntimeShaderEffect(runtimeShader: RuntimeShader, uniformShaderName: String): RenderEffect = throw UnsupportedOperationException()

internal actual fun ColorFilterEffect(renderEffect: RenderEffect?, colorFilter: ColorFilter): RenderEffect = throw UnsupportedOperationException()

internal actual fun Paint.blur(radius: Float) {}

internal actual fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {}
