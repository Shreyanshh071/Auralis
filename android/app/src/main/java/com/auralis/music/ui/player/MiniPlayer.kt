package com.auralis.music.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.tactileBounce
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pixel-Perfect Floating MiniPlayer Pill:
 * - Left circular album art with circular progress indicator ring + centered Play/Pause toggle
 * - Middle track title and subtitle artist name (clicking opens the full player modal)
 * - Right 3 responsive action buttons:
 *   1. Listen Together / Social Room (Person icon)
 *   2. Add to Playlist (+)
 *   3. Favorite / Like Heart
 */
@Composable
fun MiniPlayer(
    track: Track?,
    isPlaying: Boolean,
    progress: Float, // 0.0f to 1.0f
    isFavorite: Boolean = false,
    onPlayPauseClick: () -> Unit,
    onNextClick: (() -> Unit)? = null,
    onFavoriteToggle: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (track == null) return

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200),
        label = "miniPlayerProgress"
    )

    val pillShape = RoundedCornerShape(32.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .shadow(elevation = 16.dp, shape = pillShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(pillShape)
            .background(Color(0xFF22231A))
            .border(1.dp, Color.White.copy(alpha = 0.09f), pillShape)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ================================================================
            // 1. LEFT CIRCULAR ARTWORK WITH PROGRESS RING & CENTER PLAY/PAUSE
            // ================================================================
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPlayPauseClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Outer Circular Progress Track and Sweep Arc
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 2.2.dp.toPx()
                    val radius = (size.minDimension - strokeWidth) / 2
                    val center = Offset(size.width / 2, size.height / 2)

                    // Background ring track
                    drawCircle(
                        color = Color.White.copy(alpha = 0.12f),
                        radius = radius,
                        center = center,
                        style = Stroke(width = strokeWidth)
                    )

                    // Active progress sweep arc
                    if (animatedProgress > 0f) {
                        drawArc(
                            color = Color(0xFFD4E157),
                            startAngle = -90f,
                            sweepAngle = 360f * animatedProgress,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        // Glowing dot at the progress head
                        val angleRad = Math.toRadians((-90.0 + 360.0 * animatedProgress)).toFloat()
                        val dotCenter = Offset(
                            x = center.x + radius * cos(angleRad),
                            y = center.y + radius * sin(angleRad)
                        )
                        drawCircle(
                            color = Color(0xFFE8F28A),
                            radius = 3.2.dp.toPx(),
                            center = dotCenter
                        )
                    }
                }

                // Inner Circular Artwork Disc
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    ArtworkCard(
                        url = track.thumbnail,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 0.dp,
                        contentDescription = track.title
                    )

                    // Semi-transparent dark overlay + Crisp white Play/Pause icon
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // ================================================================
            // 2. CENTER TRACK TITLE & ARTIST (CLICK OPENS FULL NOW PLAYING)
            // ================================================================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA6A698),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // ================================================================
            // 3. RIGHT 3 ACTION BUTTONS: LISTEN TOGETHER, ADD (+), FAVORITE HEART
            // ================================================================
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Button 1: Listen Together / Social / Artist
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .tactileBounce(scaleDown = 0.85f, onClick = { onArtistClick?.invoke() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = "Listen Together",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Button 2: Add to Playlist (+)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .tactileBounce(scaleDown = 0.85f, onClick = { onAddToPlaylist?.invoke() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add to playlist",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Button 3: Favorite Heart
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .tactileBounce(scaleDown = 0.85f, onClick = { onFavoriteToggle?.invoke() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "Favorited" else "Favorite",
                        tint = if (isFavorite) Color(0xFFD4E157) else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
