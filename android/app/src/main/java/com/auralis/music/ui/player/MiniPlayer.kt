package com.auralis.music.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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

/**
 * Pixel-Perfect Floating MiniPlayer Pill:
 * - Interactive Horizontal Swipe Gesture: smoothly slide left/right between previous & next tracks in queue
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
    queue: List<Track> = emptyList(),
    currentIndex: Int = 0,
    isFavorite: Boolean = false,
    userScrollEnabled: Boolean = true,
    onPlayPauseClick: () -> Unit,
    onNextClick: (() -> Unit)? = null,
    onPreviousClick: (() -> Unit)? = null,
    onSelectQueueTrack: ((Int) -> Unit)? = null,
    onFavoriteToggle: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (track == null && queue.isEmpty()) return

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200),
        label = "miniPlayerProgress"
    )

    val queueTracks = remember(track, queue) {
        if (queue.isNotEmpty()) {
            queue
        } else if (track != null) {
            listOf(track)
        } else {
            emptyList()
        }
    }

    val pageCount = queueTracks.size.coerceAtLeast(1)
    val safeCurrentIndex = remember(currentIndex, track, queueTracks) {
        if (currentIndex in queueTracks.indices) {
            currentIndex
        } else if (track != null) {
            val found = queueTracks.indexOfFirst { it.id == track.id }
            if (found >= 0) found else 0
        } else {
            0
        }
    }

    val pagerState = rememberPagerState(
        initialPage = safeCurrentIndex.coerceIn(0, pageCount - 1)
    ) { pageCount }

    // External track index changes (e.g. background completion, notification, or full modal)
    LaunchedEffect(safeCurrentIndex) {
        if (safeCurrentIndex in 0 until pageCount && pagerState.currentPage != safeCurrentIndex) {
            val diff = kotlin.math.abs(pagerState.currentPage - safeCurrentIndex)
            if (diff == 1) {
                pagerState.animateScrollToPage(safeCurrentIndex, animationSpec = tween(durationMillis = 280))
            } else {
                pagerState.scrollToPage(safeCurrentIndex)
            }
        }
    }

    // User swipe gestures settled on a different page -> switch track
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != safeCurrentIndex && queueTracks.isNotEmpty()) {
            val newPage = pagerState.currentPage
            if (newPage in queueTracks.indices) {
                if (onSelectQueueTrack != null) {
                    onSelectQueueTrack(newPage)
                } else if (newPage > safeCurrentIndex) {
                    onNextClick?.invoke()
                } else if (newPage < safeCurrentIndex) {
                    onPreviousClick?.invoke()
                }
            }
        }
    }

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
            // 1. SWIPEABLE TRACK CONTENT CAROUSEL (ARTWORK + TITLE + ARTIST)
            // ================================================================
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = userScrollEnabled,
                modifier = Modifier
                    .weight(1f)
                    .clipToBounds(),
                pageSpacing = 12.dp,
                verticalAlignment = Alignment.CenterVertically
            ) { page ->
                val pageTrack = queueTracks.getOrNull(page) ?: track ?: return@HorizontalPager
                val isCurrent = (page == safeCurrentIndex)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Circular Artwork Disc with Inset Progress Ring (if current) + Center Play/Pause
                    MiniPlayerArtworkDisc(
                        track = pageTrack,
                        isCurrent = isCurrent,
                        isPlaying = isPlaying,
                        animatedProgress = if (isCurrent) animatedProgress else 0f,
                        onPlayPauseClick = onPlayPauseClick
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    // Track Title & Subtitle Artist
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = pageTrack.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = pageTrack.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA6A698),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // ================================================================
            // 2. RIGHT 3 ACTION BUTTONS: LISTEN TOGETHER, ADD (+), FAVORITE HEART
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

@Composable
private fun MiniPlayerArtworkDisc(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    animatedProgress: Float,
    onPlayPauseClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(Color(0xFF282920))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onPlayPauseClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Inset Circular Progress Track and Sweep Arc
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 2.5.dp.toPx()
            val insetPadding = 3.5.dp.toPx()
            val radius = (size.minDimension / 2) - strokeWidth / 2 - insetPadding
            val center = Offset(size.width / 2, size.height / 2)

            // Background ring track
            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth)
            )

            // Active progress sweep arc
            if (isCurrent && animatedProgress > 0f) {
                drawArc(
                    color = Color(0xFFD4E157),
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // Inner Circular Artwork Disc
        Box(
            modifier = Modifier
                .size(36.dp)
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
                    imageVector = if (isCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isCurrent && isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
