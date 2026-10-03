/*
 * Copyright 2025 Kyant0. Licensed under the Apache License, Version 2.0.
 * From github.com/Kyant0/AndroidLiquidGlass, module "backdrop", tag 2.0.1.
 * Bundled as source: the multiplatform expect/actual split is merged into Android-only code.
 */
package com.kyant.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

@Composable
fun rememberBackdrop(
    backdrop: Backdrop,
    onDraw: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit
): Backdrop {
    return remember(backdrop, onDraw) {
        Backdrop(backdrop, onDraw)
    }
}

@Immutable
private class Backdrop(
    val backdrop: Backdrop,
    val onDraw: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = backdrop.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        onDraw { with(backdrop) { drawBackdrop(density, coordinates, layerBlock) } }
    }
}
