/*
 * Copyright 2025 Kyant0. Licensed under the Apache License, Version 2.0.
 * From github.com/Kyant0/AndroidLiquidGlass, module "backdrop", tag 2.0.1.
 * Bundled as source, with its multiplatform expect/actual split restored (androidMain / desktopMain).
 */
package com.kyant.backdrop

/** Whether blur and colour-filter effects can run (Android 12+; Skia on desktop). */
expect fun isRenderEffectSupported(): Boolean

/** Whether runtime shaders (the lens and highlights) can run (Android 13+). */
expect fun isRuntimeShaderSupported(): Boolean
