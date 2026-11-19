package com.auralis.music.windows.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.components.AuralisSlider
import com.kyant.backdrop.Backdrop
import kotlin.math.ceil
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GlassSeekTrack(
    fraction: Float,
    dragFraction: Float?,
    duration: Long,
    backdrop: Backdrop,
    compact: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var hovered by remember { mutableStateOf(false) }
    var lastPreview by remember { mutableStateOf(0f) }
    fun previewAt(x: Float?, width: Int) {
        if (x != null && width > 0) lastPreview = (x / width).coerceIn(0f, 1f)
    }
    val preview = dragFraction ?: lastPreview
    val bubbleBottom = with(LocalDensity.current) { (if (compact) 13.dp else 6.dp).roundToPx() }
    Box(modifier
        .onPointerEvent(PointerEventType.Enter) {
            hovered = true
            previewAt(it.changes.firstOrNull()?.position?.x, size.width)
        }
        .onPointerEvent(PointerEventType.Move) { previewAt(it.changes.firstOrNull()?.position?.x, size.width) }
        .onPointerEvent(PointerEventType.Exit) { hovered = false }
    ) {
        AuralisSlider(
            value = fraction,
            onValueChange = {
                lastPreview = it
                onValueChange(it)
            },
            onValueChangeFinished = onValueChangeFinished,
            isPlaying = false,
            currentPosMs = 0L,
            totalDurationMs = duration,
            sliderStyle = "Slim",
            showTimestamps = false,
            slimThicknessRest = if (compact) 3.dp else 12.dp,
            slimThicknessHover = if (compact) 5.dp else 18.dp,
            slimTrackSurface = { thickness ->
                Box(Modifier.fillMaxWidth().height(thickness).seekGlass(backdrop, CircleShape, bubble = false))
            }
        )
        AnimatedVisibility(
            visible = duration > 0 && (hovered || dragFraction != null),
            modifier = Modifier.align(Alignment { bubble, track, _ ->
                val x = (track.width * preview - bubble.width / 2f).roundToInt()
                    .coerceIn(0, (track.width - bubble.width).coerceAtLeast(0))
                IntOffset(x, bubbleBottom - bubble.height)
            }),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(100))
        ) {
            val shape = RoundedCornerShape(7.dp)
            Text(
                text = playerTimeText((preview * duration).toLong()),
                color = Color.White,
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.shadow(2.dp, shape, clip = false,
                    ambientColor = Color.Black.copy(alpha = .22f), spotColor = Color.Black.copy(alpha = .22f))
                    .seekGlass(backdrop, shape, bubble = true)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun Modifier.seekGlass(backdrop: Backdrop, shape: CornerBasedShape, bubble: Boolean): Modifier {
    val layer = rememberGraphicsLayer()
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val tint = if (bubble) Color(0xFF28252B).copy(alpha = .56f) else Color.White.copy(alpha = .12f)
    return clip(shape)
        .onGloballyPositioned { coordinates = it }
        .drawWithCache {
            val radius = (if (bubble) 9.dp else 3.dp).toPx()
            val pad = ceil(radius * 3).toInt()
            val drawDensity = Density(density, fontScale)
            val sampleSize = IntSize(ceil(size.width).toInt() + pad * 2, ceil(size.height).toInt() + pad * 2)
            layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            onDrawWithContent {
                val position = coordinates
                if (position != null && position.isAttached) {
                    // Include the area around the chip so the artwork edge diffuses across it.
                    layer.record(drawDensity, layoutDirection, sampleSize) {
                        translate(pad.toFloat(), pad.toFloat()) {
                            with(backdrop) { drawBackdrop(drawDensity, position, null) }
                        }
                    }
                    translate(-pad.toFloat(), -pad.toFloat()) { drawLayer(layer) }
                }
                drawRect(tint)
                drawContent()
            }
        }
        .then(if (bubble) Modifier else Modifier.border(.75.dp, Brush.verticalGradient(listOf(
            Color.White.copy(alpha = .46f),
            Color.White.copy(alpha = .10f),
            Color.White.copy(alpha = .25f)
        )), shape))
}
