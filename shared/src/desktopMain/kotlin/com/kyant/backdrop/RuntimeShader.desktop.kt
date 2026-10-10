/*
 * Copyright 2025 Kyant0. Licensed under the Apache License, Version 2.0.
 * From github.com/Kyant0/AndroidLiquidGlass, module "backdrop", tag 2.0.1.
 * Bundled as source, with its multiplatform expect/actual split restored (androidMain / desktopMain).
 */
package com.kyant.backdrop

import androidx.compose.ui.graphics.Shader

actual fun RuntimeShader(shaderString: String): RuntimeShader = throw UnsupportedOperationException("Runtime shaders aren't supported on desktop yet")

actual fun RuntimeShader.asComposeShader(): Shader = throw UnsupportedOperationException("Runtime shaders aren't supported on desktop yet")
