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
    val hover by animateFloatAsState(if (pressed) 1f else if (hovered) .6f else 0f, label = "resync glass hover")
    // Neutral frosted glass, like the player's other controls: a dark tint over a well blurred,
    // gently refracted backdrop, so a bright cover reads through muted instead of turning the
    // button into a saturated pill of the song's colour.
    Row(modifier.graphicsLayer { scaleX = swell; scaleY = swell }
        .shadow(2.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = .22f), spotColor = Color.Black.copy(alpha = .22f)).clip(shape)
        .onGloballyPositioned { coordinates = it }
        .drawWithCache {
            val pad = 32.dp.toPx()
            val copiedDensity = Density(density, fontScale)
            layer.renderEffect = effect.create(size.width, size.height, pad, density,
                blurDp = 18f, lensHeightDp = 14f, lensAmountDp = 8f)
            onDrawWithContent {
                drawRect(Color(0xFF121212).copy(alpha = .55f))
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
                // Tint the refracted backdrop back toward neutral, then a faint sheen.
                drawRect(Color.Black.copy(alpha = .38f))
                drawRect(Color.White.copy(alpha = .06f + .08f * hover))
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .10f), Color.Transparent),
                    startY = 0f, endY = size.height * .6f))
                drawContent()
            }
        }
        .border(.75.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .28f + .12f * hover),
            Color.White.copy(alpha = .06f))), shape)
        .clickable(interactions, indication = null, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Sync, null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        // Trim the font's extra top/bottom line spacing so the glyphs centre on the icon,
        // instead of the label sitting low in the pill.
        Text(label, color = Color.White.copy(alpha = .92f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
            style = androidx.compose.material3.LocalTextStyle.current.merge(
                lineHeight = 13.sp,
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
                )
            ))
    }
}
