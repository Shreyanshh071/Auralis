/*
 * Copyright 2025 Kyant0. Licensed under the Apache License, Version 2.0.
 * From github.com/Kyant0/AndroidLiquidGlass, module "backdrop", tag 2.0.1.
 * Bundled as source, with its multiplatform expect/actual split restored (androidMain / desktopMain).
 */
package com.kyant.backdrop

// Not yet ported to Skia: glass draws its plain fallback on desktop.
actual fun isRenderEffectSupported(): Boolean = false

actual fun isRuntimeShaderSupported(): Boolean = false
