package com.auralis.music.windows.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.components.AuralisSlider
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
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
            val shape = RoundedCornerShape(9.dp)
            Text(
                text = playerTimeText((preview * duration).toLong()),
                color = Color.White,
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.shadow(3.dp, shape, clip = false)
                    .seekGlass(backdrop, shape, bubble = true)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun Modifier.seekGlass(backdrop: Backdrop, shape: CornerBasedShape, bubble: Boolean): Modifier {
    val tint = if (bubble) Color(0xFF252327).copy(alpha = .58f) else Color.White.copy(alpha = .12f)
    return clip(shape)
        .background(tint.copy(alpha = if (bubble) .32f else .16f), shape)
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                if (bubble) {
                    // Sample beyond the pill so the cover edge blurs into the player's background.
                    val radius = 16.dp.toPx()
                    padding = radius * 1.5f
                    blur(radius)
                } else {
                    vibrancy()
                    blur(3.dp.toPx())
                    val edge = 3.dp.toPx()
                    lens(refractionHeight = edge, refractionAmount = edge, depthEffect = true)
                }
            },
            highlight = null,
            shadow = null,
            onDrawSurface = { drawRect(tint) }
        )
        .then(if (bubble) Modifier else Modifier.border(.75.dp, Brush.verticalGradient(listOf(
            Color.White.copy(alpha = .46f),
            Color.White.copy(alpha = .10f),
            Color.White.copy(alpha = .25f)
        )), shape))
}
