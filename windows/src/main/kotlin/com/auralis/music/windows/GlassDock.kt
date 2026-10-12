package com.auralis.music.windows

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** The phone dock's swell when held (DOCK_PRESSED_SCALE) and its spring. */
private const val DOCK_PRESSED_SCALE = 1.12f
private const val DOCK_PRESS_STIFFNESS = 300f
/** Hovering swells part of the way, so a press still has somewhere to go. */
private const val HOVER_SWELL = 0.6f

/**
 * The phone's liquid glass dock on Windows (no round ••• button). Hovering swells it on the
 * phone's bouncy spring and pools a soft light under the pointer; pressing swells it fully
 * (the phone's held state). The selected tab is a raised pill with its label, as on the phone.
 */
@Composable
internal fun GlassDock(
    selected: DesktopDestination?,
    tabs: List<DesktopDestination>,
    onSelect: (DesktopDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(30.dp)
    var pointer by remember { mutableStateOf(Offset.Unspecified) }
    var hovered by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val swell = remember { Animatable(0f, 0.001f) }
    LaunchedEffect(hovered, pressed) {
        swell.animateTo(if (pressed) 1f else if (hovered) HOVER_SWELL else 0f,
            spring(dampingRatio = 0.5f, stiffness = DOCK_PRESS_STIFFNESS, visibilityThreshold = 0.001f))
    }
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .height(56.dp)
            .graphicsLayer {
                val scale = 1f + (DOCK_PRESSED_SCALE - 1f) * swell.value
                scaleX = scale; scaleY = scale
                this.shape = shape; clip = true
            }
            // Watches the pointer without consuming it, so the tabs still get their clicks.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val position = event.changes.firstOrNull()?.position
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move -> { hovered = true; position?.let { pointer = it } }
                            PointerEventType.Press -> { pressed = true; position?.let { pointer = it } }
                            PointerEventType.Release -> pressed = false
                            PointerEventType.Exit -> { hovered = false; pressed = false }
                        }
                    }
                }
            }
            .drawWithContent {
                val outline = shape.createOutline(size, layoutDirection, this)
                // Glass: a translucent dark fill (the phone's fallback base and tint together).
                drawOutline(outline, Color(0xFF1C1D20).copy(alpha = 0.86f))
                // A soft light pooled under the pointer, brighter while pressed.
                val glow = (0.10f * (swell.value / HOVER_SWELL).coerceIn(0f, 1f) + 0.10f * ((swell.value - HOVER_SWELL) / (1f - HOVER_SWELL)).coerceIn(0f, 1f))
                if (glow > 0.005f && pointer.isSpecified) drawOutline(outline, Brush.radialGradient(
                    listOf(Color.White.copy(alpha = glow), Color.Transparent), center = pointer, radius = size.minDimension * 1.6f),
                    blendMode = BlendMode.Plus)
                // Specular rim, bright on top (the phone's rimBrush) and a faint top sheen.
                drawOutline(outline, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f), 0.45f to Color.Transparent))
                drawContent()
                val stroke = 1.dp.toPx()
                drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f))),
                    topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(size.height / 2 - stroke / 2), style = Stroke(stroke))
            }
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEach { tab ->
                DockTab(tab, tab == selected, primary) { onSelect(tab) }
            }
        }
    }
}

/** One tab: the phone's DockTabItem (icon; selected adds a lit pill and its label). */
@Composable
private fun DockTab(tab: DesktopDestination, isSelected: Boolean, primary: Color, onClick: () -> Unit) {
    val selectedFraction by animateFloatAsState(if (isSelected) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow), label = "dockTabSelected")
    val pillBrush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.06f)))
    val rimBrush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.06f)))
    Box(
        Modifier
            .fillMaxHeight()
            .drawBehind {
                val a = selectedFraction.coerceIn(0f, 1f)
                if (a > 0f) {
                    val stroke = 1.dp.toPx()
                    val radius = minOf(22.dp.toPx(), size.height / 2f)
                    drawRoundRect(brush = pillBrush, cornerRadius = CornerRadius(radius), alpha = a)
                    drawRoundRect(brush = rimBrush, topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(radius - stroke / 2f), alpha = a, style = Stroke(stroke))
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .layout { measurable, constraints ->
                val padding = lerp(12.dp, 14.dp, selectedFraction.coerceIn(0f, 1f)).roundToPx()
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                layout(placeable.width + 2 * padding, placeable.height) { placeable.placeRelative(padding, 0) }
            },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(tab.icon, tab.label, Modifier.size(22.dp), tint = if (isSelected) primary else Color.White.copy(alpha = 0.65f))
            Row(Modifier.horizontalReveal { selectedFraction }.graphicsLayer { alpha = selectedFraction.coerceIn(0f, 1f) },
                verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(8.dp))
                Text(tab.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1)
            }
        }
    }
}

/** Shows [fraction] of the content's natural width (the phone dock's label reveal). */
private fun Modifier.horizontalReveal(fraction: () -> Float): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val width = (placeable.width * fraction().coerceIn(0f, 1f)).roundToInt()
        layout(width, placeable.height) { placeable.placeRelative(0, 0) }
    }
