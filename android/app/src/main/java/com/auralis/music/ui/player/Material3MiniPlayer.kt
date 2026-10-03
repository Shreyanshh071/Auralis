package com.auralis.music.ui.player

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
    // Tucked into the minimized glass dock: no artist line or skip buttons, so it fits the gap.
    val dockCollapse = com.auralis.music.ui.glass.LocalDockCollapse.current
    val isCompact by remember(dockCollapse) {
        androidx.compose.runtime.derivedStateOf { (dockCollapse?.value ?: 0f) > 0.5f }
    }

    val surface = when {
        glass != null -> Modifier.liquidGlass(glass, shape).clip(shape)
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
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
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
            ArtworkProgressDisc(track, isPlaying, progressProvider, Modifier.size(44.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.5.sp,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500)
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isCompact,
                    enter = androidx.compose.animation.fadeIn(tween(220)) + androidx.compose.animation.expandVertically(tween(260)),
                    exit = androidx.compose.animation.fadeOut(tween(160)) + androidx.compose.animation.shrinkVertically(tween(220))
                ) {
                    Text(
                        text = track.artist,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            androidx.compose.animation.AnimatedVisibility(
                visible = !isCompact,
                enter = androidx.compose.animation.fadeIn(tween(220)) + androidx.compose.animation.expandHorizontally(tween(280)),
                exit = androidx.compose.animation.fadeOut(tween(140)) + androidx.compose.animation.shrinkHorizontally(tween(240))
            ) {
            Icon(
                imageVector = Icons.Outlined.SkipPrevious,
                contentDescription = "Previous",
                tint = Color.White,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .tactileBounce(scaleDown = 0.85f, onClick = { onPreviousClick?.invoke() })
                    .padding(8.dp)
            )
            }
            CookiePlayButton(isPlaying, onPlayPauseClick, Modifier.size(44.dp))
            androidx.compose.animation.AnimatedVisibility(
                visible = !isCompact,
                enter = androidx.compose.animation.fadeIn(tween(220)) + androidx.compose.animation.expandHorizontally(tween(280)),
                exit = androidx.compose.animation.fadeOut(tween(140)) + androidx.compose.animation.shrinkHorizontally(tween(240))
            ) {
            Icon(
                imageVector = Icons.Outlined.SkipNext,
                contentDescription = "Next",
                tint = Color.White,
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
    modifier: Modifier = Modifier
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                Color.White, -90f, 360f * progressProvider().coerceIn(0f, 1f), false,
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

/** White play/pause: a turning scalloped cookie while playing, a plain circle when paused. */
@Composable
private fun CookiePlayButton(isPlaying: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
                rotate(spin.value, center) { drawPath(path, Color.White) }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.Black,
            modifier = Modifier.size(26.dp)
        )
    }
}
