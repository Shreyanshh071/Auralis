/*
 * Copyright 2025 Kyant0. Licensed under the Apache License, Version 2.0.
 * From github.com/Kyant0/AndroidLiquidGlass, module "backdrop", tag 2.0.1.
 * Bundled as source: the multiplatform expect/actual split is merged into Android-only code.
 */
package com.kyant.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import com.kyant.backdrop.internal.recordLayer

fun Modifier.layerBackdrop(backdrop: LayerBackdrop, invalidationSignal: State<*>? = null): Modifier =
    this then LayerBackdropElement(backdrop, invalidationSignal)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop,
    val invalidationSignal: State<*>?
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop, invalidationSignal)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
            // Auralis: hand the new backdrop the position this node already has. Waiting for the
            // next onGloballyPositioned left it without coordinates until something re-laid the
            // page out (scrolling doesn't), and drawBackdrop skips drawing without them: glass
            // over that page drew only its dark base after a tab switch replaced the backdrop.
            node.lastCoordinates?.takeIf { it.isAttached }?.let { backdrop.layerCoordinates = it }
        }
        node.invalidationSignal = invalidationSignal
        node.observeInvalidationSignal()
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
        properties["invalidationSignal"] = invalidationSignal
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (invalidationSignal != other.invalidationSignal) return false

        return true
    }

    override fun hashCode(): Int {
        return 31 * backdrop.hashCode() + (invalidationSignal?.hashCode() ?: 0)
    }
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop,
    var invalidationSignal: State<*>?
) : DrawModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode, Modifier.Node() {

    fun observeInvalidationSignal() {
        observeReads { invalidationSignal?.value }
    }

    override fun onAttach() {
        observeInvalidationSignal()
    }

    override fun onObservedReadsChanged() {
        invalidateDraw()
        observeInvalidationSignal()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        recordLayer(this, backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
    }

    var lastCoordinates: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            lastCoordinates = coordinates
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        lastCoordinates = null
        backdrop.layerCoordinates = null
    }
}
