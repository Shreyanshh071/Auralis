package com.auralis.music.ui.player

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.glass.liquidGlass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Width of the Material3 pill (about the dock's), shared with the host so its touch area matches. */
val Material3MiniPlayerMaxWidth = 300.dp

/**
 * "Material3" mini player, in the Material 3 Expressive style:
 * a pill in the chosen mini-player background style, a round cover ringed by playback progress, a
 * scrolling title, outlined skip buttons either side of a large white play/pause. While playing the
 * play/pause is a slowly turning scalloped "cookie"; paused, it relaxes into a plain circle.
 */
@Composable
internal fun Material3MiniPlayerView(
    track: Track,
    isPlaying: Boolean,
    progressProvider: () -> Float,
    dominantColor: Color,
    isPureBlack: Boolean,
    hazeState: HazeState?,
    activeStyle: PlayerBackgroundStyle,
    extractedColors: com.auralis.music.ui.theme.ArtworkPalette,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: (() -> Unit)?,
    onNextClick: (() -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    val glass = if (isPureBlack) null else com.auralis.music.ui.glass.LocalMiniPlayerGlass.current
    val lightGlass = glass?.isDark == false
    val contentColor = if (lightGlass) Color(0xFF1B1B1F) else Color.White
    val glassTint = if (lightGlass) Color.White.copy(alpha = 0.70f) else Color(0xFF101010).copy(alpha = 0.70f)
    // Tucked into the minimized glass dock: no artist line or skip buttons, so it fits the gap.
    val dockCollapse = com.auralis.music.ui.glass.LocalDockCollapse.current
    val dockSettled by remember(dockCollapse) {
        androidx.compose.runtime.derivedStateOf {
            val collapse = dockCollapse?.value ?: 0f
            collapse == 0f || collapse == 1f
        }
    }

    val surface = when {
        glass != null -> Modifier.liquidGlass(glass, shape, tint = glassTint).clip(shape)
        isPureBlack -> Modifier.clip(shape).background(Color.Black)
        hazeState != null -> Modifier
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = Color(0xFF15121A),
                    tint = HazeTint(Color(0xFF1C1822).copy(alpha = 0.78f)),
                    blurRadius = 24.dp,
                    noiseFactor = 0.02f
                )
            )
        else -> Modifier.clip(shape).background(Color(0xFF1C1822))
    }

    // The host sizes and places this (about the dock's width and centred; filling the gap beside
    // the minimized dock), so it just fills what it's given. A cap here kept it 300dp wide even
    // when the host widened it, which is why it never fitted the gap.
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(vertical = 6.dp)
            .then(surface)
            .border(1.dp, contentColor.copy(alpha = 0.16f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        // The chosen "Mini-player background style" (Blur, Live Mesh, Gradient, ...), drawn the
        // same way the pill design draws it. Pure black and liquid glass keep their own surface.
        if (!isPureBlack && glass == null) {
            PlayerBackground(
                style = activeStyle,
                artworkUrl = track.thumbnail,
                extractedColors = extractedColors,
                modifier = Modifier.matchParentSize(),
                isMiniPlayer = true,
                isPlaying = isPlaying
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 6.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkProgressDisc(track, isPlaying, progressProvider, Modifier.size(44.dp), contentColor)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = track.title,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (dockSettled) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500) else Modifier
                )
                Box(Modifier.miniPlayerDockReveal(dockCollapse, horizontal = false)) {
                    Text(
                        text = track.artist,
                        color = contentColor.copy(alpha = if (glass != null) 0.78f else 0.65f),
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.miniPlayerDockReveal(dockCollapse, horizontal = true)) {
            Icon(
                imageVector = Icons.Outlined.SkipPrevious,
                contentDescription = str(R.string.previous),
                tint = contentColor,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .tactileBounce(scaleDown = 0.85f, onClick = { onPreviousClick?.invoke() })
                    .padding(8.dp)
            )
            }
            CookiePlayButton(isPlaying, onPlayPauseClick, Modifier.size(44.dp), contentColor, if (lightGlass) Color.White else Color.Black)
            Box(Modifier.miniPlayerDockReveal(dockCollapse, horizontal = true)) {
            Icon(
                imageVector = Icons.Outlined.SkipNext,
                contentDescription = str(R.string.next),
                tint = contentColor,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .tactileBounce(scaleDown = 0.85f, onClick = { onNextClick?.invoke() })
                    .padding(8.dp)
            )
            }
        }
    }
    }
}

/** Round artwork inside a playback progress ring. The cover itself stays still. */
@Composable
private fun ArtworkProgressDisc(
    track: Track,
    isPlaying: Boolean,
    progressProvider: () -> Float,
    modifier: Modifier = Modifier,
    progressColor: Color = Color.White
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(progressColor.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                progressColor, -90f, 360f * progressProvider().coerceIn(0f, 1f), false,
                Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        AsyncImage(
            model = track.thumbnail,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(5.dp)
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0xFF2A2530))
        )
    }
}

/** Contrasting play/pause: a turning scalloped cookie while playing, a plain circle when paused. */
@Composable
private fun CookiePlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.White,
    iconColor: Color = Color.Black
) {
    val scallop by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "cookieScallop"
    )
    val spin = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            spin.animateTo(spin.value + 360f, tween(9_000, easing = LinearEasing))
        }
    }
    val path = remember { Path() }
    Box(
        modifier = modifier
            .tactileBounce(scaleDown = 0.88f, onClick = onClick)
            .drawBehind {
                val lobes = 9
                val radius = size.minDimension / 2f
                val depth = 0.075f * scallop
                val center = Offset(size.width / 2f, size.height / 2f)
                path.reset()
                val steps = 180
                for (i in 0..steps) {
                    val a = 2.0 * PI * i / steps
                    val r = radius * (1f - depth + depth * cos(lobes * a).toFloat())
                    val x = center.x + r * cos(a).toFloat()
                    val y = center.y + r * sin(a).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                rotate(spin.value, center) { drawPath(path, containerColor) }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) str(R.string.pause) else str(R.string.play),
            tint = iconColor,
            modifier = Modifier.size(26.dp)
        )
    }
}
