package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.auralis.music.ui.components.clickable
import com.auralis.music.windows.DockGlassEffect
import com.kyant.backdrop.Backdrop

internal class ResyncBackdrop(val backdrop: Backdrop,
    val lyrics: com.kyant.backdrop.backdrops.LayerBackdrop? = null) { val users = mutableIntStateOf(0) }
internal val LocalResyncBackdrop = staticCompositionLocalOf<ResyncBackdrop?> { null }

/** 8dp blur, 24dp edge lens, 1.5x vibrancy and translucent white bar tint. */
@Composable
internal fun GlassResyncButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = LocalResyncBackdrop.current
    DisposableEffect(source) {
        source?.users?.let { it.intValue++ }
        onDispose { source?.users?.let { it.intValue-- } }
    }
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val swell by animateFloatAsState(if (pressed) 1.05f else if (hovered) 1.025f else 1f,
        spring(dampingRatio = .7f, stiffness = 350f), label = "resync glass press")
    val shape = RoundedCornerShape(32.dp)
    val layer = rememberGraphicsLayer()
    val effect = remember { DockGlassEffect() }
    DisposableEffect(effect) { onDispose { effect.close() } }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(modifier.graphicsLayer { scaleX = swell; scaleY = swell }
        .shadow(6.dp, shape, clip = false).clip(shape)
        .onGloballyPositioned { coordinates = it }
        .drawWithCache {
            val pad = 32.dp.toPx()
            val copiedDensity = Density(density, fontScale)
            layer.renderEffect = effect.create(size.width, size.height, pad, density,
                blurDp = 8f, lensHeightDp = 24f, lensAmountDp = 24f)
            onDrawWithContent {
                drawRect(Color(0xFF121212).copy(alpha = .4f))
                if (source != null) {
                    layer.record(copiedDensity, layoutDirection,
                        IntSize((size.width + pad * 2).toInt(), (size.height + pad * 2).toInt())) {
                        drawContext.canvas.translate(pad, pad)
                        with(source.backdrop) { drawBackdrop(copiedDensity, coordinates, null) }
                    }
                    drawContext.canvas.translate(-pad, -pad)
                    drawLayer(layer)
                    drawContext.canvas.translate(pad, pad)
                }
                drawRect(Color.White.copy(alpha = .09f))
                drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .12f), Color.Transparent),
                    start = Offset.Zero, end = Offset(size.width, size.height)))
                drawContent()
            }
        }
        .border(.75.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .42f), Color.White.copy(alpha = .08f),
            Color.White.copy(alpha = .22f))), shape)
        .clickable(interactions, indication = null, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Sync, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
