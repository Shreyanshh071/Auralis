/*
 * Copyright 2025 Kyant0. Licensed under the Apache License, Version 2.0.
 * From github.com/Kyant0/AndroidLiquidGlass, module "backdrop", tag 2.0.1.
 * Bundled as source: the multiplatform expect/actual split is merged into Android-only code.
 */
package com.kyant.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.internal.InverseLayerScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer? = null,
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var generation by remember(lifecycle) { mutableIntStateOf(0) }
    val layer = graphicsLayer ?: key(lifecycle, generation) { rememberGraphicsLayer() }
    // Keep the shared backdrop identity/coordinates while replacing a discarded
    // RenderNode. Retained dialogs must follow the current capture, not the old one.
    val backdrop = remember(onDraw) { LayerBackdrop(layer, onDraw) }
    SideEffect {
        backdrop.graphicsLayer = layer
        backdrop.captureGeneration = generation
    }
    DisposableEffect(lifecycle, backdrop) {
        val observer = LifecycleEventObserver { _, event ->
            // Android may discard the recorded RenderNode while this task is
            // hidden. A retained dialog can redraw before the page underneath
            // invalidates, leaving glass with only its tint after task restore.
            if (event == Lifecycle.Event.ON_RESUME) generation++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return backdrop
}

@Stable
class LayerBackdrop internal constructor(
    graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {
    var graphicsLayer by mutableStateOf(graphicsLayer)
        internal set

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)
    internal var captureGeneration by mutableIntStateOf(0)
    private var recordingVersion by mutableIntStateOf(0)
    private val recordingCounter = java.util.concurrent.atomic.AtomicInteger()

    internal fun onRecorded() {
        // A different Android window does not receive RenderNode invalidations
        // from the page. Notify modal consumers whenever the capture changes.
        // Never read this state here: that would make recording invalidate itself.
        recordingVersion = recordingCounter.incrementAndGet()
    }

    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        // Invalidate glass consumers as well as the page capture after resume.
        captureGeneration
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        if (coordinates.findRootCoordinates() !== layerCoordinates.findRootCoordinates()) {
            // Same-window glass already follows the shared display list. Observing
            // here only across windows avoids a capture/redraw feedback loop.
            recordingVersion
        }
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            val offset =
                try {
                    layerCoordinates.localPositionOf(coordinates)
                } catch (_: Exception) {
                    // TODO: outer transformations lead to wrong position calculation
                    coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                }
            translate(-offset.x, -offset.y)
        }) {
            drawLayer(graphicsLayer)
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
