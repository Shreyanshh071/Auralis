package com.auralis.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.auralisIconSwapEnter
import com.auralis.music.ui.theme.auralisIconSwapExit
import com.auralis.music.ui.theme.motionTween

/**
 * Measured height of the pill, including its outer margin.
 *
 * Kept as a constant because the bottom bar reserves this space unconditionally:
 * the mini-player fades out during the container transform to Now Playing, and a
 * bottom bar that changed height mid-animation would remeasure the whole Scaffold
 * on every frame.
 */
val MiniPlayerHeight: Dp = 72.dp

/**
 * Pixel-Perfect Floating MiniPlayer Pill:
 * - Interactive Horizontal Swipe Gesture: smoothly slide left/right between previous & next tracks in queue
 * - Left circular album art with circular progress indicator ring + centered Play/Pause toggle
 * - Middle track title and subtitle artist name (clicking opens the full player modal)
 * - Right 3 responsive action buttons:
 *   1. Listen Together / Social Room (Person icon)
 *   2. Add to Playlist (+)
 *   3. Favorite / Like Heart
 *
 * [sharedTransitionScope] and [animatedVisibilityScope] are optional. When both are
 * supplied, the artwork and the title/artist block are tagged as shared content so
 * they travel into the full player instead of cross-fading (see
 * [PlayerContainerTransform.kt][playerSharedArtwork]). Passing neither is valid and
 * simply renders the pill on its own.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
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
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier
) {
    if (track == null && queue.isEmpty()) return

    // Held as State and read only inside the ring's draw lambda. Reading it here
    // with `by` would retarget a tween four times a second in the composable body,
    // recomposing the whole pill — including its pager — at frame rate the entire
    // time something is playing.
    val animatedProgress = animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = motionTween(AuralisDuration.ProgressTick, AuralisEasing.Linear),
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
    val favoriteEnter = auralisIconSwapEnter()
    val favoriteExit = auralisIconSwapExit()

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

                // Only the page that is actually playing may claim the shared keys:
                // the pager keeps neighbours composed off-screen, and two live
                // layouts holding one key at the same time is undefined.
                val trackInfoModifier = playerSharedTrackInfo(
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    enabled = isCurrent
                )

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
                        progress = animatedProgress,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onPlayPauseClick = onPlayPauseClick
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    // Track Title & Subtitle Artist
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Inner column so the shared bounds cover the full text slot
                        // rather than the widest glyph run.
                        Column(modifier = Modifier.fillMaxWidth().then(trackInfoModifier)) {
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
                    AnimatedContent(
                        targetState = isFavorite,
                        transitionSpec = { favoriteEnter togetherWith favoriteExit },
                        label = "miniFavorite"
                    ) { favorited ->
                        Icon(
                            imageVector = if (favorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (favorited) "Favorited" else "Favorite",
                            tint = if (favorited) Color(0xFFD4E157) else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MiniPlayerArtworkDisc(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    progress: State<Float>,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    onPlayPauseClick: () -> Unit
) {
    val sharedArtwork = playerSharedArtwork(
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        enabled = isCurrent
    )
    val artworkCorner = playerArtworkCorner(
        animatedVisibilityScope = if (isCurrent) animatedVisibilityScope else null,
        expanded = false
    )
    val playIconEnter = auralisIconSwapEnter()
    val playIconExit = auralisIconSwapExit()

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
        // Inset Circular Progress Track and Sweep Arc.
        // `progress.value` is read here, inside the draw lambda, so a new position
        // frame invalidates drawing only — never composition or layout.
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
            val sweep = if (isCurrent) progress.value else 0f
            if (sweep > 0f) {
                drawArc(
                    color = Color(0xFFD4E157),
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // Inner Circular Artwork Disc
        Box(
            modifier = Modifier
                .size(36.dp),
            contentAlignment = Alignment.Center
        ) {
            // The shared modifier sits outermost so the artwork is re-measured to the
            // travelling bounds; `elevation = 0.dp` keeps Modifier.shadow out of the
            // chain entirely, which would otherwise re-rasterise a shadow whose shape
            // changes on every frame of the flight.
            ArtworkCard(
                url = track.thumbnail,
                modifier = sharedArtwork.fillMaxSize(),
                cornerRadius = artworkCorner,
                elevation = 0.dp,
                contentDescription = track.title
            )

            // Semi-transparent dark overlay + Crisp white Play/Pause icon.
            // Deliberately *not* shared: it stays behind in the pill and fades, so the
            // artwork reads as lifting out from under it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = isCurrent && isPlaying,
                    transitionSpec = { playIconEnter togetherWith playIconExit },
                    label = "miniPlayPause"
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
