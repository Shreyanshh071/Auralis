package com.auralis.music.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithContent
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.zIndex
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableIntStateOf
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.rememberScroller
import com.auralis.music.ui.components.QueueTrackItem
import com.auralis.music.ui.components.createQueueTrackItem
import com.auralis.music.ui.components.syncLocalQueueWithSnapshot
import com.auralis.music.domain.model.QueueOperations
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive
import coil.compose.AsyncImage
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.LyricsMode
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.AudioOutputIcon
import com.auralis.music.ui.components.AuralisPlayerSlider
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.lyrics.SyncedLyricsView
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.viewmodel.PlayerUiState
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

val LocalClassicLyricsHazeState = androidx.compose.runtime.compositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/**
 * Classic ViVi Player screen displayed when `appearance.newPlayerDesign == false`.
 * Faithful pixel-accurate reproduction of the classic player interface:
 * - Top: Centered "Now Playing" and album title
 * - Center: Square album art with rounded corners and swipe-to-skip pager
 * - Track info: Marquee title + artist with circular 3-dots and heart buttons on right
 * - Timeline: Squiggly / Wavy scrubber slider
 * - Transport: Shuffle, Previous, Play/Pause, Next, Repeat
 * - Volume: In-player native volume bar
 * - Bottom utility bar: Queue button, Center capsule (Speaker device + Sleep timer), Lyrics button
 */
@Composable
fun ClassicPlayerView(
    track: Track,
    uiState: PlayerUiState,
    pagerState: PagerState,
    queue: List<Track>,
    currentTrackIndex: Int,
    seekBarPositionState: State<Long>,
    totalDurationMs: Long,
    isScrubbing: Boolean,
    onScrubbing: (Boolean, Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onToggleShuffle: () -> Unit = {},
    onToggleRepeat: () -> Unit = {},
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    onShowMoreOptions: () -> Unit,
    onShowSleepDialog: () -> Unit,
    onShowOutputPicker: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleLyrics: () -> Unit,
    controlsAlpha: Float,
    enableSwipeToChangeSong: Boolean,
    hidePlayerThumbnail: Boolean,
    cropAlbumArt: Boolean,
    sliderStyle: String = com.auralis.music.ui.theme.LocalAppearanceSettings.current.playerSliderStyle,
    modifier: Modifier = Modifier,
    compactHeaderProgress: State<Float>,
    queueTransportProgress: State<Float>,
    compactArtworkTargetInRoot: Offset = Offset.Zero,
    compactMetadataTargetInRoot: Offset = Offset.Zero,
    compactMetadataTargetSize: IntSize = IntSize.Zero,
    onHandleBottomMeasured: (Int) -> Unit = {},
    onTimelineTopMeasured: (Int) -> Unit = {},
    onControlsBottomSpacerMeasured: (Int) -> Unit = {},
    headerMotionModifier: Modifier = Modifier,
    artworkMotionModifier: Modifier = Modifier,
    metadataMotionModifier: Modifier = Modifier,
    controlsMotionModifier: Modifier = Modifier,
    onPlaybackControlsInteraction: () -> Unit = {},
    showBottomBar: Boolean = true,
    onArtistClick: ((Artist) -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val displayedTrack = track
    val artworkContext = LocalContext.current
    val density = LocalDensity.current
    val heroActionsEnabled by remember(compactHeaderProgress) {
        derivedStateOf { compactHeaderProgress.value < 0.5f }
    }
    val queueTransportActionsEnabled by remember(queueTransportProgress) {
        derivedStateOf { queueTransportProgress.value < 0.5f }
    }
    val currentOnPlaybackControlsInteraction = rememberUpdatedState(onPlaybackControlsInteraction)
    val nominalArtworkSizePx = with(density) { ClassicPlayerViewportMotion.CompactArtworkSizeDp.dp.toPx() }
    var heroArtworkPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var heroArtworkWidthPx by remember { mutableFloatStateOf(0f) }
    var heroMetadataPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var heroMetadataSize by remember { mutableStateOf(IntSize.Zero) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .pointerInput(heroActionsEnabled) {
                if (!heroActionsEnabled) return@pointerInput
                // Swipe UP opens the queue. Only an upward drag is claimed: detectVerticalDragGestures
                // consumed every drag at touch slop (either direction), which starved the sheet's own
                // drag-down-to-mini-player handler everywhere on the Player, including the handle.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalDragY = 0f
                    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                        // Leaving a downward slop unconsumed keeps waiting here and lets the
                        // sheet's detector take the gesture.
                        if (over < 0f) {
                            change.consume()
                            totalDragY = over
                        }
                    } ?: return@awaitEachGesture
                    verticalDrag(drag.id) { change ->
                        totalDragY += change.positionChange().y
                        change.consume()
                    }
                    if (totalDragY < -36.dp.toPx()) {
                        onToggleQueue()
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The handle is ordinary sheet chrome; the existing outer sheet still owns dragging.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    onHandleBottomMeasured(it.positionInRoot().y.roundToInt() + it.size.height)
                }
                .padding(top = 12.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.34f))
                    .clickable(onClick = onDismiss)
            )
        }
        // ── 1. TOP HEADER: "Now Playing" + current song name ──
        ClassicTopBar(
            songTitle = displayedTrack.title,
            controlsAlpha = controlsAlpha,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp)
                .then(headerMotionModifier)
        )

        Spacer(modifier = Modifier.weight(0.5f))

        // ── 2. CENTER ALBUM ARTWORK ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .onGloballyPositioned { coordinates ->
                    if (compactHeaderProgress.value <= 0.001f || heroArtworkWidthPx == 0f) {
                        heroArtworkPositionInRoot = coordinates.positionInRoot()
                        heroArtworkWidthPx = coordinates.size.width.toFloat()
                    }
                }
                .then(artworkMotionModifier)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    val compactScale = if (heroArtworkWidthPx > 0f) {
                        (nominalArtworkSizePx / heroArtworkWidthPx).coerceIn(0.12f, 0.4f)
                    } else {
                        1f
                    }
                    val progress = compactHeaderProgress.value.coerceIn(0f, 1f)
                    val animatedScale = 1f + (compactScale - 1f) * progress
                    scaleX = animatedScale
                    scaleY = animatedScale
                    translationX = (compactArtworkTargetInRoot.x - heroArtworkPositionInRoot.x) * progress
                    translationY = (compactArtworkTargetInRoot.y - heroArtworkPositionInRoot.y) * progress
                },
            contentAlignment = Alignment.Center
        ) {
            if (!hidePlayerThumbnail) {
                val artworkShape = RoundedCornerShape(18.dp)

                if (queue.isNotEmpty()) {
                    HorizontalPager(
                        state = pagerState,
                        key = { page -> queue.getOrNull(page)?.id ?: page },
                        userScrollEnabled = enableSwipeToChangeSong,
                        beyondViewportPageCount = 1,
                        flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                            state = pagerState,
                            snapPositionalThreshold = 0.35f
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    ) { pageIndex ->
                        val pageTrack = if (queue.isNotEmpty() && pageIndex in queue.indices) {
                            val qTrack = queue[pageIndex]
                            if (qTrack.id == track.id) track else qTrack
                        } else {
                            track
                        }
                        androidx.compose.runtime.key(pageTrack.id) {
                            // Match adjacent-artwork prefetch so a swipe reuses the decoded bitmap.
                            val artworkRequest = remember(artworkContext, pageTrack.thumbnail) {
                                coil.request.ImageRequest.Builder(artworkContext)
                                    .data(getHighResArtworkUrl(pageTrack.thumbnail))
                                    .size(600, 600)
                                    .allowHardware(true)
                                    .crossfade(true)
                                    .build()
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val pageOffset = kotlin.math.abs((pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction)
                                            .coerceIn(0f, 1f)
                                        val scale = 1f - (pageOffset * 0.15f)
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .drawWithContent {
                                        drawContent()
                                        val pageOffset = kotlin.math.abs((pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction)
                                            .coerceIn(0f, 1f)
                                        if (pageOffset > 0.001f) {
                                            drawRoundRect(
                                                color = Color.Black,
                                                alpha = (pageOffset * 0.70f).coerceIn(0f, 0.70f),
                                                cornerRadius = CornerRadius(with(density) { 18.dp.toPx() })
                                            )
                                        }
                                    }
                                    .shadow(
                                        elevation = 14.dp,
                                        shape = artworkShape,
                                        ambientColor = Color.Black.copy(alpha = 0.50f),
                                        spotColor = Color.Black.copy(alpha = 0.50f)
                                    )
                                    .clip(artworkShape)
                                    .background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = artworkRequest,
                                    contentDescription = pageTrack.title,
                                    contentScale = if (cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .shadow(
                                elevation = 14.dp,
                                shape = artworkShape,
                                ambientColor = Color.Black.copy(alpha = 0.50f),
                                spotColor = Color.Black.copy(alpha = 0.50f)
                            )
                            .clip(artworkShape)
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = getHighResArtworkUrl(displayedTrack.thumbnail),
                            contentDescription = displayedTrack.title,
                            contentScale = if (cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(60.dp))
            }
        }

        Spacer(modifier = Modifier.weight(1.0f))

        // ── 3. TRACK INFO & ACTIONS ROW (TITLE/ARTIST + OVERFLOW & LIKE BUTTONS) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
                .onGloballyPositioned { coordinates ->
                    if (compactHeaderProgress.value <= 0.001f || heroMetadataPositionInRoot == Offset.Zero) {
                        heroMetadataPositionInRoot = coordinates.positionInRoot()
                    }
                    heroMetadataSize = coordinates.size
                }
                .then(metadataMotionModifier)
                .graphicsLayer {
                    // VIVI: the title never travels. It fades out in place as the cover starts to
                    // move; the compact header's own title is revealed at its final spot, under
                    // the shrinking cover. A travelling title dragged across the incoming content.
                    alpha = controlsAlpha * ClassicPlayerViewportMotion.expandedMetadataAlpha(compactHeaderProgress.value)
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = displayedTrack.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (heroActionsEnabled) Modifier.basicMarquee() else Modifier
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = displayedTrack.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = heroActionsEnabled) {
                        onArtistClick?.invoke(
                            Artist(
                                id = "",
                                name = displayedTrack.artist,
                                thumbnail = displayedTrack.thumbnail
                            )
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 3-dots Overflow Menu button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .graphicsLayer {
                        alpha = ClassicPlayerViewportMotion.expandedActionsAlpha(compactHeaderProgress.value)
                    }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .then(
                        if (heroActionsEnabled) Modifier.tactileBounce(scaleDown = 0.88f, onClick = onShowMoreOptions)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More Options",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Like / Favorite button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .graphicsLayer {
                        alpha = ClassicPlayerViewportMotion.expandedActionsAlpha(compactHeaderProgress.value)
                    }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .then(
                        if (heroActionsEnabled) Modifier.tactileBounce(scaleDown = 0.88f, onClick = onToggleFavorite)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (uiState.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (uiState.isFavorite) "Unlike" else "Like",
                    tint = if (uiState.isFavorite) Color(0xFFFF4081) else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // A single draw layer keeps the timeline, transport, and volume in lockstep
        // while the queue/lyrics viewport keeps its own measured bounds.
        Column(
            Modifier
                .fillMaxWidth()
                .then(controlsMotionModifier)
                // This measured column is the actual control hit region. Being a pointer-input
                // ancestor lets buttons/sliders keep their gestures while preventing a higher
                // full-screen Lyrics handler from becoming the hit target for gaps between them.
                .classicPlaybackControlsInteraction(currentOnPlaybackControlsInteraction)
        ) {
            ClassicCompactPlaybackControls(
                track = displayedTrack,
                uiState = uiState,
                seekBarPositionState = seekBarPositionState,
                totalDurationMs = totalDurationMs,
                isScrubbing = isScrubbing,
                onScrubbing = onScrubbing,
                onSeekTo = onSeekTo,
                onPlayPauseClick = onPlayPauseClick,
                onPreviousClick = onPreviousClick,
                onNextClick = onNextClick,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                sliderStyle = sliderStyle,
                controlsAlpha = controlsAlpha,
                // Shuffle and Repeat belong to the bottom utility squircle.
                showTransportPills = false,
                onTimelinePositioned = onTimelineTopMeasured,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            )
        }

        Spacer(
            modifier = Modifier
                .weight(0.6f)
                .onGloballyPositioned { coordinates ->
                    if (coordinates.size.height > 0) {
                        onControlsBottomSpacerMeasured(coordinates.size.height)
                    }
                }
        )

        // ── 7. BOTTOM UTILITY BAR (QUEUE, DEVICE/TIMER CAPSULE, LYRICS) ──
        if (showBottomBar) {
            ClassicBottomBar(
                isQueueActive = false,
                isLyricsActive = false,
                isTimerActive = uiState.sleepTimerSeconds > 0 || uiState.isSleepTimerEndOfSong,
                isShuffled = uiState.isShuffled,
                repeatMode = uiState.repeatMode,
                onToggleQueue = onToggleQueue,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                onShowOutputPicker = onShowOutputPicker,
                onShowSleepDialog = onShowSleepDialog,
                onToggleLyrics = onToggleLyrics,
                controlsAlpha = controlsAlpha,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

/**
 * Centered top header showing "Now Playing" and the current song title.
 */
@Composable
fun ClassicTopBar(
    songTitle: String,
    controlsAlpha: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.graphicsLayer { alpha = controlsAlpha },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 56.dp)
        ) {
            Text(
                text = "Now Playing",
                style = MaterialTheme.typography.titleMedium,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = songTitle,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.70f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Clean interactive slider dynamically adapting to the user's seekbar settings (Default, Wavy, Slim, Squiggly).
 */
@Composable
fun ClassicTimelineSlider(
    positionState: State<Long>,
    totalDurationMs: Long,
    isPlaying: Boolean,
    sliderStyle: String,
    isScrubbing: Boolean,
    onScrubbing: (Boolean, Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    controlsAlpha: Float,
    modifier: Modifier = Modifier
) {
    val currentPosMs = positionState.value
    var localDragFraction by remember { mutableFloatStateOf(0f) }

    val displayPosMs = if (isScrubbing) {
        (localDragFraction * totalDurationMs).toLong()
    } else {
        currentPosMs
    }

    val sliderFraction = if (totalDurationMs > 0) {
        (displayPosMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    AuralisPlayerSlider(
        value = sliderFraction,
        onValueChange = { frac ->
            localDragFraction = frac
            val targetMs = (frac * totalDurationMs).toLong()
            onScrubbing(true, targetMs)
        },
        onValueChangeFinished = {
            val targetMs = (localDragFraction * totalDurationMs).toLong()
            onScrubbing(false, targetMs)
            onSeekTo(targetMs)
        },
        isPlaying = isPlaying,
        currentPosMs = displayPosMs,
        totalDurationMs = totalDurationMs,
        sliderStyle = sliderStyle,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
        thumbColor = Color.White,
        textColor = Color.White.copy(alpha = 0.70f),
        modifier = modifier.graphicsLayer { alpha = controlsAlpha }
    )
}

/**
 * In-Player Volume Slider synced with Android's AudioManager.STREAM_MUSIC.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InPlayerVolumeSlider(
    controlsAlpha: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat().coerceAtLeast(1f)
    }

    var systemVolume by remember {
        mutableFloatStateOf(
            (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume).coerceIn(0f, 1f)
        )
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION" ||
                    intent?.action == "android.media.STREAM_DEVICES_CHANGED_ACTION") {
                    systemVolume = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume).coerceIn(0f, 1f)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_DEVICES_CHANGED_ACTION")
        }
        context.registerReceiver(receiver, filter)
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    Row(
        modifier = modifier.graphicsLayer { alpha = controlsAlpha },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Speaker Low / Mute icon
        IconButton(
            onClick = {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                systemVolume = 0f
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeMute,
                contentDescription = "Mute",
                tint = Color.White.copy(alpha = 0.80f),
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Volume track (no thumb)
        Slider(
            value = systemVolume,
            onValueChange = { newVol ->
                systemVolume = newVol
                val step = (newVol * maxVolume).roundToInt()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
            },
            thumb = {},
            track = { sliderState ->
                val fraction = sliderState.value
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                ) {
                    val h = size.height
                    val w = size.width
                    val activeW = w * fraction
                    // Inactive track
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.22f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(h / 2, h / 2)
                    )
                    // Active track
                    if (activeW > 0f) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.88f),
                            size = Size(activeW, h),
                            cornerRadius = CornerRadius(h / 2, h / 2)
                        )
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Speaker Max icon
        IconButton(
            onClick = {
                val maxStep = maxVolume.roundToInt()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxStep, 0)
                systemVolume = 1f
            },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Max Volume",
                tint = Color.White.copy(alpha = 0.80f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Playback Controls used in Queue and Lyrics panels:
 * Exact pixel-match to Main Player's sizing, icons, paddings, and touch targets.
 */
@Composable
fun ClassicCompactPlaybackControls(
    track: Track,
    uiState: PlayerUiState,
    seekBarPositionState: State<Long>,
    totalDurationMs: Long,
    isScrubbing: Boolean,
    onScrubbing: (Boolean, Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    sliderStyle: String,
    modifier: Modifier = Modifier,
    onToggleShuffle: (() -> Unit)? = null,
    onToggleRepeat: (() -> Unit)? = null,
    controlsAlpha: Float = 1f,
    showTransportPills: Boolean = false,
    onTimelinePositioned: ((Int) -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Timeline Scrubber Slider - Exact match to main player (padding horizontal = 26.dp)
        ClassicTimelineSlider(
            positionState = seekBarPositionState,
            totalDurationMs = totalDurationMs,
            isPlaying = uiState.isPlaying,
            sliderStyle = sliderStyle,
            isScrubbing = isScrubbing,
            onScrubbing = onScrubbing,
            onSeekTo = onSeekTo,
            controlsAlpha = controlsAlpha,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
                .then(
                    if (onTimelinePositioned != null) {
                        Modifier.onGloballyPositioned { onTimelinePositioned(it.positionInRoot().y.roundToInt()) }
                    } else Modifier
                )
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Transport Controls Row - Exact match to main player (padding horizontal = 24.dp)
        val shuffleTint by animateColorAsState(
            targetValue = if (uiState.isShuffled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.70f),
            label = "compactShuffleTint"
        )
        val repeatActive = uiState.repeatMode != RepeatMode.OFF
        val repeatTint by animateColorAsState(
            targetValue = if (repeatActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.70f),
            label = "compactRepeatTint"
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .graphicsLayer { alpha = controlsAlpha },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showTransportPills && onToggleShuffle != null) {
                // Shuffle Button (size 46dp, icon 24dp)
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier
                        .size(46.dp)
                        .tactileBounce(scaleDown = 0.86f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        tint = shuffleTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Previous / Fast Rewind (size 54dp, icon 36dp)
            IconButton(
                onClick = onPreviousClick,
                modifier = Modifier
                    .size(54.dp)
                    .tactileBounce(scaleDown = 0.85f)
            ) {
                Icon(
                    imageVector = Icons.Filled.FastRewind,
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Play / Pause (size 72dp, icon 54dp - Exact match to main player)
            IconButton(
                onClick = onPlayPauseClick,
                modifier = Modifier
                    .size(72.dp)
                    .tactileBounce(scaleDown = 0.88f)
            ) {
                Icon(
                    imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(54.dp)
                )
            }

            // Next / Fast Forward (size 54dp, icon 36dp)
            IconButton(
                onClick = onNextClick,
                modifier = Modifier
                    .size(54.dp)
                    .tactileBounce(scaleDown = 0.85f)
            ) {
                Icon(
                    imageVector = Icons.Filled.FastForward,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            if (showTransportPills && onToggleRepeat != null) {
                // Repeat Button (size 46dp, icon 24dp)
                IconButton(
                    onClick = onToggleRepeat,
                    modifier = Modifier
                        .size(46.dp)
                        .tactileBounce(scaleDown = 0.86f)
                ) {
                    Icon(
                        imageVector = if (uiState.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = "Repeat",
                        tint = repeatTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. In-Player Volume Slider - Exact match to main player (padding horizontal = 26.dp)
        InPlayerVolumeSlider(
            controlsAlpha = controlsAlpha,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
        )
    }
}

/**
 * Reusable Classic Bottom Navigation Bar:
 * - Left: Queue button
 * - Center: Capsule pill (Device Output + Sleep Timer)
 * - Right: Lyrics button
 */
@Composable
fun ClassicBottomBar(
    isQueueActive: Boolean,
    isLyricsActive: Boolean,
    isTimerActive: Boolean,
    isShuffled: Boolean = false,
    repeatMode: RepeatMode = RepeatMode.OFF,
    onToggleQueue: () -> Unit,
    onToggleShuffle: () -> Unit = {},
    onToggleRepeat: () -> Unit = {},
    onShowOutputPicker: () -> Unit,
    onShowSleepDialog: () -> Unit,
    onToggleLyrics: () -> Unit,
    controlsAlpha: Float,
    showUtilityActions: Boolean = true,
    modifier: Modifier = Modifier
) {
    val timerTint by animateColorAsState(
        targetValue = if (isTimerActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.70f),
        label = "bottomBarTimerTint"
    )
    val shuffleTint by animateColorAsState(
        targetValue = if (isShuffled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
        label = "bottomBarShuffleTint"
    )
    val repeatTint by animateColorAsState(
        targetValue = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
        label = "bottomBarRepeatTint"
    )

    Row(
        modifier = modifier.graphicsLayer { alpha = controlsAlpha },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Queue Button
        IconButton(
            onClick = onToggleQueue,
            modifier = Modifier
                .size(44.dp)
                .tactileBounce(scaleDown = 0.88f)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                contentDescription = "Queue",
                tint = if (isQueueActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(26.dp)
            )
        }

        if (showUtilityActions) {
            // Main Player and Lyrics keep all four utilities together here. Queue owns
            // the same four actions in its top row and omits this capsule.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onToggleShuffle)
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = shuffleTint,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onToggleRepeat)
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = repeatTint,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Original listener silhouette with radiating soundwaves.
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onShowOutputPicker() }
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AudioOutputIcon,
                        contentDescription = "Audio Output",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Sleep Timer button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onShowSleepDialog() }
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bedtime,
                        contentDescription = "Sleep Timer",
                        tint = timerTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Right: Lyrics Button
        IconButton(
            onClick = onToggleLyrics,
            modifier = Modifier
                .size(44.dp)
                .tactileBounce(scaleDown = 0.88f)
        ) {
            LyricsQuoteIcon(
                tint = if (isLyricsActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/**
 * AirPlay audio output icon matching Apple Music's device picker symbol
 * (upward triangle with concentric radiating sound waves).
 */
@Composable
fun AirPlayAudioIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeW = (w * 0.09f).coerceAtLeast(1.5f)

        // 1. Upward-pointing triangle at bottom center
        val trianglePath = Path().apply {
            moveTo(w * 0.50f, h * 0.58f) // apex
            lineTo(w * 0.72f, h * 0.88f) // bottom-right
            lineTo(w * 0.28f, h * 0.88f) // bottom-left
            close()
        }
        drawPath(
            path = trianglePath,
            color = tint
        )

        // 2. Concentric audio wave arcs
        val arcCenterY = h * 0.75f
        val arcCenterX = w * 0.50f

        // Inner wave
        val r1 = w * 0.29f
        drawArc(
            color = tint,
            startAngle = 222f,
            sweepAngle = 96f,
            useCenter = false,
            topLeft = Offset(arcCenterX - r1, arcCenterY - r1),
            size = Size(r1 * 2f, r1 * 2f),
            style = Stroke(width = strokeW, cap = StrokeCap.Round)
        )

        // Outer wave
        val r2 = w * 0.47f
        drawArc(
            color = tint,
            startAngle = 226f,
            sweepAngle = 88f,
            useCenter = false,
            topLeft = Offset(arcCenterX - r2, arcCenterY - r2),
            size = Size(r2 * 2f, r2 * 2f),
            style = Stroke(width = strokeW, cap = StrokeCap.Round)
        )
    }
}

/**
 * Backward compatibility alias for AirPlayAudioIcon.
 */
@Composable
fun SpeakerBoxIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    AirPlayAudioIcon(tint = tint, modifier = modifier)
}

/**
 * Chat bubble with quotation marks icon matching the lyrics button in Image 2.
 */
@Composable
fun LyricsQuoteIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeW = 1.8.dp.toPx()
        val corner = 4.dp.toPx()
        val bubbleH = h * 0.82f

        // Bubble outline with tail
        val path = Path().apply {
            moveTo(corner, 0f)
            lineTo(w - corner, 0f)
            quadraticTo(w, 0f, w, corner)
            lineTo(w, bubbleH - corner)
            quadraticTo(w, bubbleH, w - corner, bubbleH)
            lineTo(w * 0.38f, bubbleH)
            lineTo(w * 0.18f, h) // tail tip
            lineTo(w * 0.22f, bubbleH)
            lineTo(corner, bubbleH)
            quadraticTo(0f, bubbleH, 0f, bubbleH - corner)
            lineTo(0f, corner)
            quadraticTo(0f, 0f, corner, 0f)
            close()
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = strokeW, join = StrokeJoin.Round)
        )

        // First quote mark
        drawCircle(
            color = tint,
            radius = w * 0.07f,
            center = Offset(w * 0.38f, bubbleH * 0.44f)
        )
        val quote1Tail = Path().apply {
            moveTo(w * 0.41f, bubbleH * 0.46f)
            quadraticTo(w * 0.38f, bubbleH * 0.65f, w * 0.32f, bubbleH * 0.66f)
        }
        drawPath(
            path = quote1Tail,
            color = tint,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Second quote mark
        drawCircle(
            color = tint,
            radius = w * 0.07f,
            center = Offset(w * 0.60f, bubbleH * 0.44f)
        )
        val quote2Tail = Path().apply {
            moveTo(w * 0.63f, bubbleH * 0.46f)
            quadraticTo(w * 0.60f, bubbleH * 0.65f, w * 0.54f, bubbleH * 0.66f)
        }
        drawPath(
            path = quote2Tail,
            color = tint,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * Formats milliseconds as m:ss or mm:ss
 */
private fun formatClassicTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

/**
 * Top-level container for the Classic Player mode that routes between PLAYER, LYRICS, and QUEUE tabs.
 */
private class ClassicPlayerMotionHolder(
    val compactHeaderProgress: State<Float>,
    val topBarAlpha: State<Float>,
    val topBarTranslationYDp: State<Float>,
    val lyricsAlpha: State<Float>,
    val lyricsTranslationYDp: State<Float>,
    val lyricsScale: State<Float>,
    val queueAlpha: State<Float>,
    val queueTranslationYDp: State<Float>,
    val queueScale: State<Float>,
    val queueTransportProgress: State<Float>,
    val transitionProgress: Animatable<Float, *>,
    val showLyricsLayer: State<Boolean>,
    val showQueueLayer: State<Boolean>
)

@Composable
private fun rememberClassicPlayerMotion(
    currentTab: NowPlayingTab,
    reducedMotion: Boolean
): ClassicPlayerMotionHolder {
    // Linear clock: the per-layer FastOutSlowIn curves (and the faster exit) are applied in
    // ClassicPlayerViewportMotion.entryAlpha/exitAlpha, matching VIVI's crossfade.
    val motionSpec = remember(reducedMotion) {
        if (reducedMotion) {
            snap<Float>()
        } else {
            tween<Float>(ClassicPlayerViewportMotion.ContentEnterDurationMillis, easing = LinearEasing)
        }
    }
    val heroMotionSpec = remember(reducedMotion) {
        if (reducedMotion) {
            snap<Float>()
        } else {
            tween<Float>(ClassicPlayerViewportMotion.HeroDurationMillis, easing = ClassicPlayerViewportMotion.HeroEasing)
        }
    }

    var transitionFromTab by remember { mutableStateOf(currentTab) }
    var transitionToTab by remember { mutableStateOf(currentTab) }
    var transitionStartSnapshot by remember { mutableStateOf<ClassicPlayerViewportMotionValues?>(null) }
    val transitionProgress = remember { Animatable(1f) }

    LaunchedEffect(currentTab, reducedMotion) {
        if (currentTab == transitionToTab) {
            if (reducedMotion) transitionProgress.snapTo(1f)
            return@LaunchedEffect
        }

        // A quick second tap must continue from the currently rendered frame.
        // Resetting the fraction against a tab endpoint made the list flash and
        // the upward movement restart while the cover was still in flight.
        transitionStartSnapshot = if (transitionFromTab != transitionToTab && abs(transitionProgress.value - 1f) > 0.001f) {
            transitionStartSnapshot?.let {
                ClassicPlayerViewportMotion.retarget(it, transitionToTab, transitionProgress.value)
            } ?: ClassicPlayerViewportMotion.interpolate(
                transitionFromTab,
                transitionToTab,
                transitionProgress.value
            )
        } else null
        transitionFromTab = transitionToTab
        transitionToTab = currentTab
        if (reducedMotion) {
            transitionProgress.snapTo(1f)
        } else {
            transitionProgress.snapTo(0f)
            transitionProgress.animateTo(1f, animationSpec = motionSpec)
        }
    }

    val motionValues = remember(transitionFromTab, transitionToTab, transitionStartSnapshot, transitionProgress, reducedMotion) {
        derivedStateOf {
            if (!transitionProgress.isRunning && transitionProgress.value == 1f) {
                ClassicPlayerViewportMotion.target(transitionToTab)
            } else if (transitionStartSnapshot != null) {
                ClassicPlayerViewportMotion.retarget(
                    start = transitionStartSnapshot!!,
                    to = transitionToTab,
                    fraction = transitionProgress.value,
                    reducedMotion = reducedMotion
                )
            } else {
                ClassicPlayerViewportMotion.interpolate(
                    from = transitionFromTab,
                    to = transitionToTab,
                    fraction = transitionProgress.value,
                    reducedMotion = reducedMotion
                )
            }
        }
    }

    // The artwork and metadata share one interruption-safe spring path. The
    // content follows with a softer upward spring and a short opacity ramp.
    val compactHeaderProgress = animateFloatAsState(
        targetValue = ClassicPlayerViewportMotion.heroProgressTarget(currentTab),
        animationSpec = heroMotionSpec,
        label = "classicHeroProgress"
    )
    val topBarAlpha = remember(motionValues) { derivedStateOf { motionValues.value.topBar.alpha } }
    val topBarTranslationYDp = remember(motionValues) { derivedStateOf { motionValues.value.topBar.translationYDp } }
    val lyricsAlpha = remember(motionValues) { derivedStateOf { motionValues.value.lyrics.alpha } }
    val lyricsTranslationYDp = remember(motionValues) { derivedStateOf { motionValues.value.lyrics.translationYDp } }
    val lyricsScale = remember(motionValues) { derivedStateOf { motionValues.value.lyrics.scale } }
    val queueAlpha = remember(motionValues) { derivedStateOf { motionValues.value.queue.alpha } }
    val queueTranslationYDp = remember(motionValues) { derivedStateOf { motionValues.value.queue.translationYDp } }
    val queueScale = remember(motionValues) { derivedStateOf { motionValues.value.queue.scale } }
    val queueTransportProgress = animateFloatAsState(
        targetValue = if (currentTab == NowPlayingTab.QUEUE) 1f else 0f,
        animationSpec = motionSpec,
        label = "classicQueueTransportProgress"
    )

    // Never key these on transitionProgress.value: reading an animating value during composition
    // recomposed the whole player (pager, lyrics, queue, controls) on every frame of every tab
    // switch. derivedStateOf already tracks it and only notifies when the Boolean flips.
    // A layer stays composed while it is the current tab, the settled tab (transitionToTab —
    // i.e. the frame between the tap and the LaunchedEffect that starts the transition), or the
    // outgoing tab of a running transition. Without the transitionToTab term the outgoing tab
    // was torn down for that one frame and rebuilt the next (blank frames + a full rebuild of
    // the queue list on every switch).
    fun layerVisible(tab: NowPlayingTab, snapshotAlpha: Float?): Boolean =
        currentTab == tab || transitionToTab == tab ||
            ((transitionProgress.value < 1f || transitionProgress.isRunning) &&
                (transitionFromTab == tab || (snapshotAlpha ?: 0f) > 0.001f))
    val showLyricsLayer = remember(currentTab, transitionFromTab, transitionToTab, transitionStartSnapshot) {
        derivedStateOf { layerVisible(NowPlayingTab.LYRICS, transitionStartSnapshot?.lyrics?.alpha) }
    }
    val showQueueLayer = remember(currentTab, transitionFromTab, transitionToTab, transitionStartSnapshot) {
        derivedStateOf { layerVisible(NowPlayingTab.QUEUE, transitionStartSnapshot?.queue?.alpha) }
    }

    return remember(
        motionValues,
        compactHeaderProgress,
        topBarAlpha,
        topBarTranslationYDp,
        lyricsAlpha,
        lyricsTranslationYDp,
        lyricsScale,
        queueAlpha,
        queueTranslationYDp,
        queueScale,
        queueTransportProgress,
        transitionProgress,
        showLyricsLayer,
        showQueueLayer
    ) {
        ClassicPlayerMotionHolder(
            compactHeaderProgress = compactHeaderProgress,
            topBarAlpha = topBarAlpha,
            topBarTranslationYDp = topBarTranslationYDp,
            lyricsAlpha = lyricsAlpha,
            lyricsTranslationYDp = lyricsTranslationYDp,
            lyricsScale = lyricsScale,
            queueAlpha = queueAlpha,
            queueTranslationYDp = queueTranslationYDp,
            queueScale = queueScale,
            queueTransportProgress = queueTransportProgress,
            transitionProgress = transitionProgress,
            showLyricsLayer = showLyricsLayer,
            showQueueLayer = showQueueLayer
        )
    }
}

@Composable
private fun ClassicPlayerCompactHeader(
    track: Track,
    uiState: PlayerUiState,
    compactHeaderProgress: State<Float>,
    compactActionsEnabled: Boolean,
    onTabChange: (NowPlayingTab) -> Unit,
    onToggleFavorite: () -> Unit,
    onShowTrackOptions: () -> Unit,
    onArtworkPositioned: (Offset) -> Unit,
    onMetadataPositioned: (Offset, IntSize) -> Unit,
    onHeaderHeightMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { onHeaderHeightMeasured(it.size.height) }
            .graphicsLayer { alpha = ClassicPlayerViewportMotion.compactActionsAlpha(compactHeaderProgress.value) }
            .padding(
                start = ClassicPlayerViewportMotion.CompactHeaderSideInsetDp.dp,
                top = 4.dp,
                end = ClassicPlayerViewportMotion.CompactHeaderSideInsetDp.dp,
                bottom = 4.dp
            )
            .then(
                if (compactActionsEnabled) {
                    Modifier
                        .pointerInput(Unit) {
                            var dragY = 0f
                            detectVerticalDragGestures(
                                onDragStart = { dragY = 0f },
                                onDragEnd = {
                                    if (dragY > 24.dp.toPx()) {
                                        onTabChange(NowPlayingTab.PLAYER)
                                    }
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    if (dragAmount > 0 || dragY > 0) {
                                        dragY += dragAmount
                                        change.consume()
                                    }
                                }
                            )
                        }
                        .clickable { onTabChange(NowPlayingTab.PLAYER) }
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(ClassicPlayerViewportMotion.CompactArtworkSizeDp.dp)
                .onGloballyPositioned { onArtworkPositioned(it.positionInRoot()) }
        ) {
            // Geometry-only destination: the expanded artwork remains the single
            // visible element throughout the transition and at the compact endpoint.
        }
        Spacer(Modifier.width(ClassicPlayerViewportMotion.CompactArtworkTextGapDp.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned {
                    onMetadataPositioned(it.positionInRoot(), it.size)
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier
                    .weight(1f)
                    // The visible destination title (the hero title no longer travels here).
            ) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onToggleFavorite,
                enabled = compactActionsEnabled,
                modifier = Modifier.size(ClassicPlayerViewportMotion.CompactActionSizeDp.dp)
            ) {
                Icon(
                    imageVector = if (uiState.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (uiState.isFavorite) "Unlike" else "Like",
                    tint = if (uiState.isFavorite) Color(0xFFFF4081) else Color.White
                )
            }
            IconButton(
                onClick = onShowTrackOptions,
                enabled = compactActionsEnabled,
                modifier = Modifier.size(ClassicPlayerViewportMotion.CompactActionSizeDp.dp)
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ClassicPlayerTabOverlay(
    tab: NowPlayingTab,
    track: Track,
    uiState: PlayerUiState,
    queue: List<Track>,
    lyricsPositionState: State<Long>,
    lyricsClockSource: PlaybackClockSource?,
    onLyricsOffsetChange: (Long) -> Unit,
    onSearchLyricsManually: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSelectQueueTrack: (Int) -> Unit,
    onReorderQueue: ((Int, Int) -> Unit)?,
    onShowQueueTrackOptions: (Track) -> Unit,
    actualShowOutputPicker: () -> Unit,
    onShowSleepDialog: () -> Unit,
    onTabChange: (NowPlayingTab) -> Unit,
    seekBarPositionState: State<Long>,
    totalDurationMs: Long,
    isScrubbing: Boolean,
    onScrubbing: (Boolean, Long) -> Unit,
    sliderStyle: String,
    standardLyricsBlur: Boolean,
    controlsAlpha: Float,
    lyricsListState: androidx.compose.foundation.lazy.LazyListState,
    queueListState: androidx.compose.foundation.lazy.LazyListState,
    layerModifier: Modifier,
    contentLayer: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {},
    controlsBottomSpacerDp: androidx.compose.ui.unit.Dp = 0.dp
) {
    when (tab) {
        NowPlayingTab.PLAYER -> Unit

        NowPlayingTab.LYRICS -> {
            ClassicLyricsContent(
                track = track,
                uiState = uiState,
                lyricsPositionState = lyricsPositionState,
                lyricsClockSource = lyricsClockSource,
                onLyricsOffsetChange = onLyricsOffsetChange,
                onSearchLyricsManually = onSearchLyricsManually,
                onSeekTo = onSeekTo,
                onPlayPauseClick = onPlayPauseClick,
                onPreviousClick = onPreviousClick,
                onNextClick = onNextClick,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                seekBarPositionState = seekBarPositionState,
                totalDurationMs = totalDurationMs,
                isScrubbing = isScrubbing,
                onScrubbing = onScrubbing,
                sliderStyle = sliderStyle,
                onCloseLyrics = { onTabChange(NowPlayingTab.PLAYER) },
                onShowOutputPicker = actualShowOutputPicker,
                onShowSleepDialog = onShowSleepDialog,
                onToggleQueue = { onTabChange(NowPlayingTab.QUEUE) },
                controlsAlpha = controlsAlpha,
                standardLyricsBlur = standardLyricsBlur,
                showHeader = false,
                applyStatusBarPadding = false,
                showBottomBar = false,
                listState = lyricsListState,
                modifier = layerModifier,
                contentLayer = contentLayer,
                controlsBottomSpacerDp = controlsBottomSpacerDp
            )
        }

        NowPlayingTab.QUEUE -> {
            ClassicQueueContent(
                track = track,
                uiState = uiState,
                queue = queue,
                // Stay on the queue (as VIVI does): the compact header's artwork/title and the
                // background swap in place. Jumping to the Player tab here ran the full hero
                // flight and tore down the queue on the same frames the new song was loading.
                onSelectQueueTrack = onSelectQueueTrack,
                onReorderQueue = onReorderQueue,
                onShowTrackOptions = onShowQueueTrackOptions,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                onQueueControlsVisibilityChange = {},
                onCloseQueue = { onTabChange(NowPlayingTab.PLAYER) },
                onShowOutputPicker = actualShowOutputPicker,
                onShowSleepDialog = onShowSleepDialog,
                onToggleLyrics = { onTabChange(NowPlayingTab.LYRICS) },
                seekBarPositionState = seekBarPositionState,
                totalDurationMs = totalDurationMs,
                isScrubbing = isScrubbing,
                onScrubbing = onScrubbing,
                onSeekTo = onSeekTo,
                onPlayPauseClick = onPlayPauseClick,
                onPreviousClick = onPreviousClick,
                onNextClick = onNextClick,
                sliderStyle = sliderStyle,
                controlsAlpha = controlsAlpha,
                showHeader = false,
                applyStatusBarPadding = false,
                showBottomBar = false,
                queueListState = queueListState,
                modifier = layerModifier,
                contentLayer = contentLayer,
                controlsBottomSpacerDp = controlsBottomSpacerDp
            )
        }
    }
}

@Composable
fun ClassicPlayerContainer(
    track: Track,
    uiState: PlayerUiState,
    currentTab: NowPlayingTab,
    onTabChange: (NowPlayingTab) -> Unit,
    pagerState: PagerState,
    queue: List<Track>,
    currentTrackIndex: Int,
    seekBarPositionState: State<Long>,
    totalDurationMs: Long,
    isScrubbing: Boolean,
    onScrubbing: (Boolean, Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onToggleShuffle: () -> Unit = {},
    onToggleRepeat: () -> Unit = {},
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    onSelectQueueTrack: (Int) -> Unit,
    onReorderQueue: ((Int, Int) -> Unit)? = null,
    onShowTrackOptions: () -> Unit,
    onShowQueueTrackOptions: (Track) -> Unit = {},
    onShowSleepDialog: () -> Unit,
    onShowOutputPicker: (() -> Unit)? = null,
    lyricsPositionState: State<Long>,
    lyricsClockSource: PlaybackClockSource?,
    onLyricsOffsetChange: (Long) -> Unit,
    onSearchLyricsManually: () -> Unit,
    controlsAlpha: Float,
    enableSwipeToChangeSong: Boolean,
    hidePlayerThumbnail: Boolean,
    cropAlbumArt: Boolean,
    sliderStyle: String = com.auralis.music.ui.theme.LocalAppearanceSettings.current.playerSliderStyle,
    standardLyricsBlur: Boolean = com.auralis.music.ui.theme.LocalAppearanceSettings.current.standardLyricsBlur,
    modifier: Modifier = Modifier,
    onArtistClick: ((Artist) -> Unit)? = null
) {
    val context = LocalContext.current
    val actualShowOutputPicker = onShowOutputPicker ?: { openAudioOutputSettings(context) }
    val reducedMotion = LocalReducedMotion.current
    val motion = rememberClassicPlayerMotion(currentTab, reducedMotion)

    val saveableStateHolder = rememberSaveableStateHolder()
    val density = LocalDensity.current
    var viewportTopInRootPx by remember { mutableIntStateOf(-1) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var handleBottomInRootPx by remember { mutableIntStateOf(-1) }
    var timelineTopInRootPx by remember { mutableIntStateOf(-1) }
    var compactArtworkTargetInRoot by remember { mutableStateOf(Offset.Zero) }
    var compactMetadataTargetInRoot by remember { mutableStateOf(Offset.Zero) }
    var compactMetadataTargetSize by remember { mutableStateOf(IntSize.Zero) }
    var compactHeaderHeightPx by remember { mutableIntStateOf(0) }
    var controlsBottomSpacerHeightPx by remember { mutableIntStateOf(0) }
    val controlsBottomSpacerDp = with(density) {
        if (controlsBottomSpacerHeightPx > 0) controlsBottomSpacerHeightPx.toDp() else 52.dp
    }

    val initialQueueScrollIndex = remember {
        val activeIdx = QueueOperations.findActiveTrackIndex(
            queue = queue,
            currentTrack = track,
            currentIndex = currentTrackIndex
        )
        if (activeIdx >= 0) {
            QueueOperations.calculateScrollIndex(
                targetIndex = activeIdx,
                visibleItemCount = 6,
                queueSize = queue.size
            )
        } else {
            0
        }
    }
    val lyricsListState = rememberLazyListState()
    val queueListState = rememberLazyListState(initialFirstVisibleItemIndex = initialQueueScrollIndex)
    val compactActionsEnabled by remember(motion.compactHeaderProgress) {
        derivedStateOf { motion.compactHeaderProgress.value > 0.5f }
    }
    val heroIsInFlight by remember(motion.compactHeaderProgress, currentTab) {
        derivedStateOf {
            val progress = motion.compactHeaderProgress.value
            if (currentTab == NowPlayingTab.PLAYER) progress > 0.001f else progress < 0.999f
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onGloballyPositioned { coordinates ->
                    viewportTopInRootPx = coordinates.positionInRoot().y.roundToInt()
                    viewportHeightPx = coordinates.size.height
                }
        ) {
            val renderedContentTabs = buildList {
                if (motion.showLyricsLayer.value) add(NowPlayingTab.LYRICS)
                if (motion.showQueueLayer.value) add(NowPlayingTab.QUEUE)
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .zIndex(if (currentTab != NowPlayingTab.PLAYER || compactActionsEnabled) 3f else 1f)
            ) {
                val chromeTopOffset = if (handleBottomInRootPx > viewportTopInRootPx) {
                    with(density) { (handleBottomInRootPx - viewportTopInRootPx).toDp() }
                } else {
                    12.dp
                }
                Spacer(Modifier.height(chromeTopOffset))

                ClassicPlayerCompactHeader(
                    track = track,
                    uiState = uiState,
                    compactHeaderProgress = motion.compactHeaderProgress,
                    compactActionsEnabled = compactActionsEnabled,
                    onTabChange = onTabChange,
                    onToggleFavorite = onToggleFavorite,
                    onShowTrackOptions = onShowTrackOptions,
                    onArtworkPositioned = { compactArtworkTargetInRoot = it },
                    onMetadataPositioned = { pos, size ->
                        compactMetadataTargetInRoot = pos
                        compactMetadataTargetSize = size
                    },
                    onHeaderHeightMeasured = { compactHeaderHeightPx = it }
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                ) {
                    renderedContentTabs.forEach { tab ->
                        val isLyrics = tab == NowPlayingTab.LYRICS
                        // Only the tab's content fades/glides; its playback controls stay opaque and
                        // still so they read as one fixed set across Player/Lyrics/Queue (as in VIVI).
                        val layerContent: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
                            alpha = if (isLyrics) motion.lyricsAlpha.value else motion.queueAlpha.value
                            translationY = (if (isLyrics) motion.lyricsTranslationYDp.value else motion.queueTranslationYDp.value).dp.toPx()
                        }
                        val layerModifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (tab == currentTab) 1f else 0f)

                        androidx.compose.runtime.key(tab) {
                            saveableStateHolder.SaveableStateProvider(tab.name) {
                                ClassicPlayerTabOverlay(
                                    tab = tab,
                                    track = track,
                                    uiState = uiState,
                                    queue = queue,
                                    lyricsPositionState = lyricsPositionState,
                                    lyricsClockSource = lyricsClockSource,
                                    onLyricsOffsetChange = onLyricsOffsetChange,
                                    onSearchLyricsManually = onSearchLyricsManually,
                                    onSeekTo = onSeekTo,
                                    onPlayPauseClick = onPlayPauseClick,
                                    onPreviousClick = onPreviousClick,
                                    onNextClick = onNextClick,
                                    onToggleShuffle = onToggleShuffle,
                                    onToggleRepeat = onToggleRepeat,
                                    onSelectQueueTrack = onSelectQueueTrack,
                                    onReorderQueue = onReorderQueue,
                                    onShowQueueTrackOptions = onShowQueueTrackOptions,
                                    actualShowOutputPicker = actualShowOutputPicker,
                                    onShowSleepDialog = onShowSleepDialog,
                                    onTabChange = onTabChange,
                                    seekBarPositionState = seekBarPositionState,
                                    totalDurationMs = totalDurationMs,
                                    isScrubbing = isScrubbing,
                                    onScrubbing = onScrubbing,
                                    sliderStyle = sliderStyle,
                                    standardLyricsBlur = standardLyricsBlur,
                                    controlsAlpha = controlsAlpha,
                                    lyricsListState = lyricsListState,
                                    queueListState = queueListState,
                                    layerModifier = layerModifier,
                                    contentLayer = layerContent,
                                    controlsBottomSpacerDp = controlsBottomSpacerDp
                                )
                            }
                        }
                    }
                }
            }

            ClassicPlayerView(
                track = track,
                uiState = uiState,
                pagerState = pagerState,
                queue = queue,
                currentTrackIndex = currentTrackIndex,
                seekBarPositionState = seekBarPositionState,
                totalDurationMs = totalDurationMs,
                isScrubbing = isScrubbing,
                onScrubbing = onScrubbing,
                onSeekTo = onSeekTo,
                onPlayPauseClick = onPlayPauseClick,
                onNextClick = onNextClick,
                onPreviousClick = onPreviousClick,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                onToggleFavorite = onToggleFavorite,
                onDismiss = onDismiss,
                onShowMoreOptions = onShowTrackOptions,
                onShowSleepDialog = onShowSleepDialog,
                onShowOutputPicker = actualShowOutputPicker,
                onToggleQueue = { onTabChange(NowPlayingTab.QUEUE) },
                onToggleLyrics = { onTabChange(NowPlayingTab.LYRICS) },
                controlsAlpha = controlsAlpha,
                enableSwipeToChangeSong = enableSwipeToChangeSong && currentTab == NowPlayingTab.PLAYER,
                hidePlayerThumbnail = hidePlayerThumbnail,
                cropAlbumArt = cropAlbumArt,
                sliderStyle = sliderStyle,
                compactHeaderProgress = motion.compactHeaderProgress,
                queueTransportProgress = motion.queueTransportProgress,
                compactArtworkTargetInRoot = compactArtworkTargetInRoot,
                compactMetadataTargetInRoot = compactMetadataTargetInRoot,
                compactMetadataTargetSize = compactMetadataTargetSize,
                onHandleBottomMeasured = { handleBottomInRootPx = it },
                onTimelineTopMeasured = { timelineTopInRootPx = it },
                onControlsBottomSpacerMeasured = { controlsBottomSpacerHeightPx = it },
                headerMotionModifier = Modifier.graphicsLayer {
                    alpha = (motion.topBarAlpha.value * 4f - 3f).coerceIn(0f, 1f)
                    translationY = motion.topBarTranslationYDp.value.dp.toPx()
                },
                controlsMotionModifier = Modifier.graphicsLayer {
                    alpha = ClassicPlayerViewportMotion.playerControlsAlpha(motion.compactHeaderProgress.value)
                },
                onPlaybackControlsInteraction = {},
                showBottomBar = false,
                modifier = Modifier
                    .fillMaxSize()
                    // Keep the transformed hero alive at the compact endpoint. The old
                    // full-layer fade forced a late artwork/title handoff and visible pop.
                    // While moving, lift it above both destination screens just like
                    // VIVI's shared-element overlay (zIndexInOverlay = 1f).
                    .zIndex(
                        when {
                            heroIsInFlight -> 4f
                            currentTab == NowPlayingTab.PLAYER -> 2f
                            else -> 0f
                        }
                    ),
                onArtistClick = onArtistClick
            )
        }

        ClassicBottomBar(
            isQueueActive = currentTab == NowPlayingTab.QUEUE,
            isLyricsActive = currentTab == NowPlayingTab.LYRICS,
            isTimerActive = uiState.sleepTimerSeconds > 0 || uiState.isSleepTimerEndOfSong,
            isShuffled = uiState.isShuffled,
            repeatMode = uiState.repeatMode,
            onToggleQueue = {
                onTabChange(if (currentTab == NowPlayingTab.QUEUE) NowPlayingTab.PLAYER else NowPlayingTab.QUEUE)
            },
            onToggleShuffle = onToggleShuffle,
            onToggleRepeat = onToggleRepeat,
            onShowOutputPicker = actualShowOutputPicker,
            onShowSleepDialog = onShowSleepDialog,
            onToggleLyrics = {
                onTabChange(if (currentTab == NowPlayingTab.LYRICS) NowPlayingTab.PLAYER else NowPlayingTab.LYRICS)
            },
            controlsAlpha = controlsAlpha,
            showUtilityActions = ClassicPlayerViewportMotion.showBottomUtilityActions(currentTab),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun ClassicLyricsContent(
    track: Track,
    uiState: PlayerUiState,
    lyricsPositionState: State<Long>,
    lyricsClockSource: PlaybackClockSource?,
    onLyricsOffsetChange: (Long) -> Unit,
    onSearchLyricsManually: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPauseClick: () -> Unit = {},
    onPreviousClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onToggleRepeat: () -> Unit = {},
    seekBarPositionState: State<Long>,
    totalDurationMs: Long,
    isScrubbing: Boolean,
    onScrubbing: (Boolean, Long) -> Unit,
    onCloseLyrics: () -> Unit,
    onShowOutputPicker: () -> Unit,
    onShowSleepDialog: () -> Unit,
    onToggleQueue: () -> Unit,
    controlsAlpha: Float,
    standardLyricsBlur: Boolean = com.auralis.music.ui.theme.LocalAppearanceSettings.current.standardLyricsBlur,
    sliderStyle: String = com.auralis.music.ui.theme.LocalAppearanceSettings.current.playerSliderStyle,
    showHeader: Boolean = true,
    applyStatusBarPadding: Boolean = true,
    showBottomBar: Boolean = true,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    contentLayer: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {},
    controlsBottomSpacerDp: androidx.compose.ui.unit.Dp = 0.dp
) {
    // Plain remember: re-entering Lyrics must start with the controls already in place (VIVI),
    // not restore a stale "hidden" state and slide them in over the Player's copy.
    var lyricsControlsVisible by remember { mutableStateOf(true) }
    var lyricsInteractionTick by remember { mutableIntStateOf(0) }
    var isUserScrollingLyrics by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        lyricsControlsVisible = true
        lyricsInteractionTick++
    }

    LaunchedEffect(lyricsInteractionTick, isScrubbing, isUserScrollingLyrics, listState.isScrollInProgress) {
        if (!lyricsControlsVisible) return@LaunchedEffect
        if (isScrubbing || isUserScrollingLyrics || (listState.isScrollInProgress && isUserScrollingLyrics)) {
            return@LaunchedEffect
        }
        delay(ClassicPlayerViewportMotion.LyricsControlsTimeoutMillis)
        lyricsControlsVisible = false
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && isUserScrollingLyrics) {
            isUserScrollingLyrics = false
            lyricsInteractionTick++
        }
    }

    val lyricsNestedScrollConnection = remember {
        object : NestedScrollConnection {
            // Direction-driven like the queue tab: scrolling down through the lyrics hides the
            // controls panel, scrolling back up reveals it. Tap-toggle and auto-hide still apply.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (available.y < -2f) {
                        lyricsControlsVisible = false
                        isUserScrollingLyrics = true
                    } else if (available.y > 2f) {
                        lyricsControlsVisible = true
                        isUserScrollingLyrics = true
                        lyricsInteractionTick++
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity
            ): Velocity {
                isUserScrollingLyrics = false
                lyricsInteractionTick++
                return super.onPostFling(consumed, available)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
    ) {
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCloseLyrics,
                    modifier = Modifier.size(40.dp).tactileBounce(scaleDown = 0.88f)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Back to Player",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = "LYRICS",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.55f),
                        letterSpacing = 2.0.sp,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.size(40.dp))
            }
        }

        val inheritedHazeState = LocalClassicLyricsHazeState.current
        val lyricsHazeState = inheritedHazeState ?: remember { HazeState() }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = lyricsHazeState, zIndex = 1f)
                    .padding(horizontal = 16.dp)
                    .nestedScroll(lyricsNestedScrollConnection)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                lyricsControlsVisible = !lyricsControlsVisible
                                if (lyricsControlsVisible) {
                                    lyricsInteractionTick++
                                }
                            }
                        )
                    }
                    .graphicsLayer {
                        contentLayer()
                        alpha *= controlsAlpha
                    }
            ) {
                SyncedLyricsView(
                    lyrics = uiState.lyrics,
                    positionState = lyricsPositionState,
                    onSeekTo = { posMs ->
                        lyricsControlsVisible = true
                        lyricsInteractionTick++
                        onSeekTo(posMs)
                        if (!uiState.isPlaying) {
                            onPlayPauseClick()
                        }
                    },
                    isLoading = uiState.isLoadingLyrics,
                    lyricsMode = LyricsMode.CINEMA,
                    offsetMs = uiState.lyricsOffsetMs,
                    onOffsetChange = {
                        lyricsControlsVisible = true
                        lyricsInteractionTick++
                        onLyricsOffsetChange(it)
                    },
                    onSearchManually = onSearchLyricsManually,
                    track = uiState.currentTrack,
                    lyricsClockSource = lyricsClockSource,
                    isPlaying = uiState.isPlaying,
                    isBuffering = uiState.isBuffering,
                    audioLeadingSilenceMs = uiState.audioLeadingSilenceMs,
                    readingFocusFraction = if (showHeader) 0.45f else 0.30f,
                    listState = listState,
                    standardLyricsBlur = standardLyricsBlur,
                    loadingAlignment = androidx.compose.ui.BiasAlignment(0f, -0.28f)
                )
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = lyricsControlsVisible,
                enter = fadeIn(tween(ClassicPlayerViewportMotion.ControlsEnterDurationMillis, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(ClassicPlayerViewportMotion.ControlsEnterDurationMillis, easing = FastOutSlowInEasing)) {
                        it / ClassicPlayerViewportMotion.ControlsSlideFraction
                    },
                exit = fadeOut(tween(ClassicPlayerViewportMotion.ControlsExitDurationMillis, easing = FastOutSlowInEasing)) +
                    slideOutVertically(tween(ClassicPlayerViewportMotion.ControlsExitDurationMillis, easing = FastOutSlowInEasing)) {
                        it / ClassicPlayerViewportMotion.ControlsSlideFraction
                    },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .hazeEffect(
                            state = lyricsHazeState,
                            style = HazeStyle(
                                backgroundColor = Color.Black,
                                // No tint: any darkening shows as a seam against the unblurred
                                // backdrop behind the bottom utility bar.
                                tint = HazeTint(Color.Transparent),
                                blurRadius = 28.dp,
                                noiseFactor = 0f
                            )
                        ) {
                            // Haze defaults blur off below API 32 and silently falls back to a
                            // flat tint scrim, which is the "black-tinted glass" look. Force the
                            // real RenderEffect blur (API 31+) and always composite both the
                            // ambient background (z0) and the lyrics (z1) sources.
                            blurEnabled = true
                            canDrawArea = { true }
                            // Blur a downscaled copy: far cheaper per frame, visually identical
                            // at this radius. Full-res blur was costing frames during tab switches.
                            inputScale = dev.chrisbanes.haze.HazeInputScale.Auto
                            // Feather both edges so the glass blends into the lyrics above and
                            // the bottom utility bar below instead of ending in a hard line.
                            mask = Brush.verticalGradient(
                                0.00f to Color.Transparent,
                                0.22f to Color.Black,
                                0.88f to Color.Black,
                                1.00f to Color.Transparent
                            )
                        }
                        .padding(top = 16.dp)
                ) {
                    ClassicCompactPlaybackControls(
                        track = track,
                        uiState = uiState,
                        seekBarPositionState = seekBarPositionState,
                        totalDurationMs = totalDurationMs,
                        isScrubbing = isScrubbing,
                        onScrubbing = { scrubbing, posMs ->
                            lyricsControlsVisible = true
                            lyricsInteractionTick++
                            onScrubbing(scrubbing, posMs)
                        },
                        onSeekTo = { posMs ->
                            lyricsControlsVisible = true
                            lyricsInteractionTick++
                            onSeekTo(posMs)
                        },
                        onPlayPauseClick = {
                            lyricsControlsVisible = true
                            lyricsInteractionTick++
                            onPlayPauseClick()
                        },
                        onPreviousClick = {
                            lyricsControlsVisible = true
                            lyricsInteractionTick++
                            onPreviousClick()
                        },
                        onNextClick = {
                            lyricsControlsVisible = true
                            lyricsInteractionTick++
                            onNextClick()
                        },
                        onToggleShuffle = {
                            lyricsControlsVisible = true
                            lyricsInteractionTick++
                            onToggleShuffle()
                        },
                        onToggleRepeat = {
                            lyricsControlsVisible = true
                            lyricsInteractionTick++
                            onToggleRepeat()
                        },
                        sliderStyle = sliderStyle,
                        controlsAlpha = controlsAlpha,
                        // Shuffle and Repeat belong to the bottom utility squircle.
                        showTransportPills = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    lyricsControlsVisible = true
                                    lyricsInteractionTick++
                                }
                            }
                    )
                    if (controlsBottomSpacerDp > 0.dp) {
                        Spacer(modifier = Modifier.height(controlsBottomSpacerDp))
                    }
                }
            }
        }

        if (showBottomBar) {
            ClassicBottomBar(
                isQueueActive = false,
                isLyricsActive = true,
                isTimerActive = uiState.sleepTimerSeconds > 0 || uiState.isSleepTimerEndOfSong,
                isShuffled = uiState.isShuffled,
                repeatMode = uiState.repeatMode,
                onToggleQueue = onToggleQueue,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                onShowOutputPicker = onShowOutputPicker,
                onShowSleepDialog = onShowSleepDialog,
                onToggleLyrics = onCloseLyrics,
                controlsAlpha = controlsAlpha,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun ClassicQueueActionTile(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = if (active) 0.22f else 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun ClassicQueueOutputActionTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.11f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = AudioOutputIcon,
            contentDescription = "Audio Output",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun ClassicQueueContent(
    track: Track,
    uiState: PlayerUiState,
    queue: List<Track>,
    onSelectQueueTrack: (Int) -> Unit,
    onReorderQueue: ((Int, Int) -> Unit)? = null,
    onShowTrackOptions: (Track) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onQueueControlsVisibilityChange: (Boolean) -> Unit,
    onCloseQueue: () -> Unit,
    onShowOutputPicker: () -> Unit,
    onShowSleepDialog: () -> Unit,
    onToggleLyrics: () -> Unit,
    seekBarPositionState: State<Long>,
    totalDurationMs: Long,
    isScrubbing: Boolean,
    onScrubbing: (Boolean, Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    controlsAlpha: Float,
    sliderStyle: String = com.auralis.music.ui.theme.LocalAppearanceSettings.current.playerSliderStyle,
    showHeader: Boolean = true,
    applyStatusBarPadding: Boolean = true,
    showBottomBar: Boolean = true,
    modifier: Modifier = Modifier,
    queueListState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    contentLayer: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {},
    controlsBottomSpacerDp: androidx.compose.ui.unit.Dp = 0.dp
) {
    var queueLocked by rememberSaveable { mutableStateOf(true) }
    var queueControlsVisible by remember { mutableStateOf(true) }
    val queueContext = LocalContext.current

    // Tapping a song re-shows the controls, but not on the tap frame: that frame and the next few
    // carry the whole song change (palette, pager, queue sync, lyrics), so a reveal started there
    // dropped its first frames and rose in a stutter. Wait until the new track has actually taken
    // over, give it a few quiet frames, then rise (or after 1s if the change is slow).
    var pendingRevealTrackId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.currentTrack?.id, pendingRevealTrackId) {
        val pending = pendingRevealTrackId ?: return@LaunchedEffect
        if (uiState.currentTrack?.id != pending) delay(1_000L)
        repeat(3) { withFrameNanos { } }
        pendingRevealTrackId = null
        if (!queueControlsVisible) {
            queueControlsVisible = true
            onQueueControlsVisibilityChange(true)
        }
    }

    LaunchedEffect(queueListState) {
        snapshotFlow { queueListState.firstVisibleItemIndex to queueListState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (index == 0 && offset == 0 && !queueControlsVisible) {
                    queueControlsVisible = true
                    onQueueControlsVisibilityChange(true)
                }
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
    ) {
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCloseQueue,
                    modifier = Modifier.size(40.dp).tactileBounce(scaleDown = 0.88f)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Back to Player",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = "QUEUE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.55f),
                        letterSpacing = 2.0.sp,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${queue.size} songs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.size(40.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp)
                .graphicsLayer { contentLayer() },
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ClassicQueueActionTile(
                icon = Icons.Default.Shuffle,
                description = "Shuffle",
                active = uiState.isShuffled,
                onClick = onToggleShuffle,
                modifier = Modifier.weight(1f)
            )
            ClassicQueueActionTile(
                icon = if (uiState.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                description = "Repeat",
                active = uiState.repeatMode != RepeatMode.OFF,
                onClick = onToggleRepeat,
                modifier = Modifier.weight(1f)
            )
            ClassicQueueActionTile(
                icon = AudioOutputIcon,
                description = "Audio Output",
                active = false,
                onClick = onShowOutputPicker,
                modifier = Modifier.weight(1f)
            )
            ClassicQueueActionTile(
                icon = Icons.Default.Bedtime,
                description = "Sleep timer",
                active = uiState.sleepTimerSeconds > 0 || uiState.isSleepTimerEndOfSong,
                onClick = onShowSleepDialog,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp, bottom = 10.dp)
                .graphicsLayer { contentLayer() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Playing from",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f)
                )
                Text(
                    text = uiState.queueSourceTitle ?: "Queue",
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = {
                queueLocked = !queueLocked
                Toast.makeText(
                    queueContext,
                    if (queueLocked) "Queue locked" else "Queue unlocked: drag the handles to reorder",
                    Toast.LENGTH_SHORT
                ).show()
            }) {
                Icon(
                    imageVector = if (queueLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (queueLocked) "Unlock queue reordering" else "Lock queue reordering",
                    tint = if (queueLocked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.75f)
                )
            }
        }

        // Controls overlay the list (like the lyrics tab / VIVI) instead of sitting below it.
        // Resizing the list on every frame of the show/hide animation re-laid out its rows and
        // triggered their placement animations, briefly stacking rows on top of each other.
        val queueHazeState = LocalClassicLyricsHazeState.current
        var queueControlsHeightPx by remember { mutableIntStateOf(0) }
        val queueControlsReveal by animateFloatAsState(
            targetValue = if (queueControlsVisible) 1f else 0f,
            animationSpec = tween(
                if (queueControlsVisible) ClassicPlayerViewportMotion.ControlsEnterDurationMillis
                else ClassicPlayerViewportMotion.ControlsExitDurationMillis,
                easing = FastOutSlowInEasing
            ),
            label = "queueControlsReveal"
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Sharp rows fade out exactly where the frosted panel's blur fades in (its
                    // top 22%), and stay hidden beneath it. Without this the panel's feathered
                    // edges showed crisp rows over the seek bar and under the volume slider.
                    // Placed before hazeSource so the panel still blurs the unmasked rows.
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val panelHeight = queueControlsHeightPx.toFloat()
                        if (queueControlsReveal > 0.001f && panelHeight > 0f) {
                            val panelTop = size.height - panelHeight
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - queueControlsReveal)),
                                    startY = panelTop,
                                    endY = panelTop + panelHeight * 0.22f
                                ),
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
                            )
                        }
                    }
                    // Blur source so the frosted controls can blur the rows scrolling beneath them.
                    .then(if (queueHazeState != null) Modifier.hazeSource(state = queueHazeState, zIndex = 1f) else Modifier)
                    .padding(horizontal = 16.dp)
                    .graphicsLayer {
                        contentLayer()
                        alpha *= controlsAlpha
                    }
            ) {
                val queueSnapshot = uiState.queue
                val queueCurrentIndex = uiState.currentIndex
                val queueItemShape = remember { RoundedCornerShape(14.dp) }
                val queueArtworkCorner = remember { 8.dp }
                val subtitleColor = remember { Color.White.copy(alpha = 0.6f) }
                val context = androidx.compose.ui.platform.LocalContext.current
                val density = LocalDensity.current
                val haptic = LocalHapticFeedback.current
                val localQueue = remember {
                    queueSnapshot.map { track ->
                        createQueueTrackItem(track)
                    }.toMutableStateList()
                }

                // Existing queueListState passed in from caller preserves scroll position across tab switches

                var startDragIndex by remember { mutableIntStateOf(-1) }
                var lastDragEndTime by remember { mutableLongStateOf(0L) }

                val edgeScrollSpeed = with(density) { 45.dp.toPx() }
                val scroller = rememberScroller(
                    scrollableState = queueListState,
                    pixelPerSecond = edgeScrollSpeed
                )
                val reorderableLazyListState = rememberReorderableLazyListState(
                    lazyListState = queueListState,
                    scroller = scroller
                ) { from, to ->
                    val fromIndex = from.index
                    val toIndex = to.index
                    if (!queueLocked && fromIndex in localQueue.indices && toIndex in localQueue.indices) {
                        localQueue.add(toIndex, localQueue.removeAt(fromIndex))
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }

                LaunchedEffect(queueSnapshot) {
                    syncLocalQueueWithSnapshot(localQueue, queueSnapshot, reorderableLazyListState.isAnyItemDragging)
                }

                // Auto-scroll queue to currently playing song only when active track changes,
                // never merely because Queue becomes active or tab changes.
                var lastAutoScrolledTrackId by rememberSaveable { mutableStateOf<String?>(null) }
                val playingTrackId = uiState.currentTrack?.id
                LaunchedEffect(playingTrackId) {
                    if (playingTrackId == null || playingTrackId == lastAutoScrolledTrackId) return@LaunchedEffect
                    lastAutoScrolledTrackId = playingTrackId
                    val playingTrack = uiState.currentTrack ?: return@LaunchedEffect
                    val activeIndex = QueueOperations.findActiveTrackIndex(
                        queue = localQueue.map { it.track },
                        currentTrack = playingTrack,
                        currentIndex = queueCurrentIndex
                    )
                    if (activeIndex >= 0) {
                        val approxItemHeight = with(density) { 68.dp.roundToPx() }
                        val visibleCount = if (queueListState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                            queueListState.layoutInfo.visibleItemsInfo.size
                        } else if (queueListState.layoutInfo.viewportSize.height > 0 && approxItemHeight > 0) {
                            (queueListState.layoutInfo.viewportSize.height / approxItemHeight).coerceAtLeast(1)
                        } else {
                            6
                        }
                        val targetScrollIndex = QueueOperations.calculateScrollIndex(
                            targetIndex = activeIndex,
                            visibleItemCount = visibleCount,
                            queueSize = localQueue.size
                        )
                        if (!reorderableLazyListState.isAnyItemDragging) {
                            queueListState.scrollToItem(targetScrollIndex)
                        }
                    }
                }

                // Pre-cache palettes for visible tracks in the Queue so selecting any song hits cache instantly
                LaunchedEffect(queueListState, localQueue.size) {
                    snapshotFlow {
                        val info = queueListState.layoutInfo
                        val start = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                        val end = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        (start..end).mapNotNull { localQueue.getOrNull(it)?.track }
                    }.collect { visibleTracks ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            visibleTracks.forEach { trk ->
                                if (com.auralis.music.ui.theme.ArtworkPaletteCache.getCached(trk.id) == null) {
                                    com.auralis.music.ui.theme.ArtworkPaletteCache.extractPalette(
                                        context = context,
                                        key = trk.id,
                                        artworkUrl = trk.thumbnail
                                    )
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(queueListState) {
                    snapshotFlow {
                        queueListState.firstVisibleItemIndex == 0 && queueListState.firstVisibleItemScrollOffset == 0
                    }
                        .distinctUntilChanged()
                        .collect { isAtTop ->
                            if (isAtTop && !queueControlsVisible) {
                                queueControlsVisible = true
                                onQueueControlsVisibilityChange(true)
                            }
                        }
                }

                val queueNestedScrollConnection = remember {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            if (reorderableLazyListState.isAnyItemDragging) return Offset.Zero

                            val delta = available.y
                            if (delta < -2f && queueControlsVisible) {
                                queueControlsVisible = false
                                onQueueControlsVisibilityChange(false)
                            } else if (delta > 2f && !queueControlsVisible) {
                                queueControlsVisible = true
                                onQueueControlsVisibilityChange(true)
                            }
                            return Offset.Zero
                        }

                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource
                        ): Offset {
                            if (reorderableLazyListState.isAnyItemDragging) return Offset.Zero
                            if (available.y > 2f && !queueControlsVisible) {
                                queueControlsVisible = true
                                onQueueControlsVisibilityChange(true)
                            }
                            return Offset.Zero
                        }
                    }
                }

                LazyColumn(
                    state = queueListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(queueNestedScrollConnection),
                    // Room to scroll the last songs clear of the overlaid controls.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = with(density) { queueControlsHeightPx.toDp() }
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = localQueue,
                        key = { it.instanceId },
                        contentType = { "queue_track" }
                    ) { queueItem ->
                        val item = queueItem.track
                        val isCurrent = item.id == uiState.currentTrack?.id
                        val primaryColor = MaterialTheme.colorScheme.primary

                        ReorderableItem(
                            state = reorderableLazyListState,
                            key = queueItem.instanceId,
                            animateItemModifier = Modifier.animateItem(
                                fadeInSpec = null,
                                placementSpec = PlayerTransitionMotion.queuePlacement,
                                fadeOutSpec = null
                            )
                        ) { isDragging ->
                            var isHandleHeld by remember { mutableStateOf(false) }
                            var isRowHeld by remember { mutableStateOf(false) }
                            // Holding/dragging a row just moves it: no background, border, bold
                            // title or accent-tinted handle.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(queueItemShape)
                                    .pointerInput(queueItem.instanceId, queueLocked) {
                                        if (queueLocked) return@pointerInput
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            var longPressed = false
                                            try {
                                                withTimeout(350L) {
                                                    waitForUpOrCancellation()
                                                }
                                            } catch (_: TimeoutCancellationException) {
                                                longPressed = true
                                                isRowHeld = true
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                            if (longPressed) {
                                                waitForUpOrCancellation()
                                                isRowHeld = false
                                            }
                                        }
                                    }
                                    .then(
                                        if (!queueLocked) {
                                            Modifier.longPressDraggableHandle(
                                                enabled = true,
                                                onDragStarted = {
                                                    isRowHeld = true
                                                    startDragIndex = localQueue.indexOfFirst { it.instanceId == queueItem.instanceId }
                                                },
                                                onDragStopped = {
                                                    isRowHeld = false
                                                    lastDragEndTime = System.currentTimeMillis()
                                                    val finalIdx = localQueue.indexOfFirst { it.instanceId == queueItem.instanceId }
                                                    val startIdx = startDragIndex
                                                    if (startIdx != -1 && finalIdx != -1 && startIdx != finalIdx) {
                                                        onReorderQueue?.invoke(startIdx, finalIdx)
                                                    }
                                                    startDragIndex = -1
                                                }
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                        enabled = !reorderableLazyListState.isAnyItemDragging && (System.currentTimeMillis() - lastDragEndTime > 450L)
                                    ) {
                                        if (System.currentTimeMillis() - lastDragEndTime <= 450L) return@clickable
                                        val targetIndex = queueSnapshot.indexOfFirst { it.id == item.id }.takeIf { it >= 0 } ?: localQueue.indexOfFirst { it.instanceId == queueItem.instanceId }
                                        if (targetIndex >= 0) {
                                            if (!queueControlsVisible) pendingRevealTrackId = item.id
                                            com.auralis.music.ui.theme.ArtworkPaletteCache.updateForTrack(context, item)
                                            onSelectQueueTrack(targetIndex)
                                        }
                                    }
                                    .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    ArtworkCard(
                                        url = item.thumbnail,
                                        modifier = Modifier.fillMaxSize(),
                                        cornerRadius = queueArtworkCorner,
                                        contentDescription = item.title
                                    )
                                    if (isCurrent) {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(queueArtworkCorner))
                                                .background(Color.Black.copy(alpha = 0.30f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = "Currently playing",
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (item.duration > 0L) {
                                            "${item.artist} · ${formatClassicTime(item.duration * 1000L)}"
                                        } else item.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = subtitleColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onShowTrackOptions(item) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Options for ${item.title}",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                if (!queueLocked) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .pointerInput(queueItem.instanceId) {
                                                awaitEachGesture {
                                                    awaitFirstDown(requireUnconsumed = false)
                                                    isHandleHeld = true
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    waitForUpOrCancellation()
                                                    isHandleHeld = false
                                                }
                                            }
                                            .draggableHandle(
                                                onDragStarted = {
                                                    isHandleHeld = true
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    startDragIndex = localQueue.indexOfFirst { it.instanceId == queueItem.instanceId }
                                                },
                                                onDragStopped = {
                                                    isHandleHeld = false
                                                    lastDragEndTime = System.currentTimeMillis()
                                                    val finalIdx = localQueue.indexOfFirst { it.instanceId == queueItem.instanceId }
                                                    val startIdx = startDragIndex
                                                    if (startIdx != -1 && finalIdx != -1 && startIdx != finalIdx) {
                                                        onReorderQueue?.invoke(startIdx, finalIdx)
                                                    }
                                                    startDragIndex = -1
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DragHandle,
                                            contentDescription = "Drag to reorder ${item.title}",
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Playback Controls in Queue (Dynamic scroll visibility: hides on scroll down, reappears on scroll up)
            androidx.compose.animation.AnimatedVisibility(
                visible = queueControlsVisible,
                // VIVI: draw-layer fade + slide only, no expand/shrink, so the list never relayouts.
                enter = fadeIn(tween(ClassicPlayerViewportMotion.ControlsEnterDurationMillis, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(ClassicPlayerViewportMotion.ControlsEnterDurationMillis, easing = FastOutSlowInEasing)) {
                        it / ClassicPlayerViewportMotion.ControlsSlideFraction
                    },
                exit = fadeOut(tween(ClassicPlayerViewportMotion.ControlsExitDurationMillis, easing = FastOutSlowInEasing)) +
                    slideOutVertically(tween(ClassicPlayerViewportMotion.ControlsExitDurationMillis, easing = FastOutSlowInEasing)) {
                        it / ClassicPlayerViewportMotion.ControlsSlideFraction
                    },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Keep the last measured height (not reset on hide) so the list's bottom
                        // padding stays constant and the list itself never resizes.
                        .onSizeChanged { if (it.height > 0) queueControlsHeightPx = it.height }
                        .then(
                            if (queueHazeState != null) {
                                // Same untinted frosted glass as the lyrics tab (no black scrim).
                                Modifier.hazeEffect(
                                    state = queueHazeState,
                                    style = HazeStyle(
                                        backgroundColor = Color.Black,
                                        tint = HazeTint(Color.Transparent),
                                        blurRadius = 28.dp,
                                        noiseFactor = 0f
                                    )
                                ) {
                                    blurEnabled = true
                                    canDrawArea = { true }
                                    inputScale = dev.chrisbanes.haze.HazeInputScale.Auto
                                    mask = Brush.verticalGradient(
                                        0.00f to Color.Transparent,
                                        0.22f to Color.Black,
                                        0.88f to Color.Black,
                                        1.00f to Color.Transparent
                                    )
                                }
                            } else Modifier
                        )
                        .padding(top = 16.dp)
                ) {
                    ClassicCompactPlaybackControls(
                        track = track,
                        uiState = uiState,
                        seekBarPositionState = seekBarPositionState,
                        totalDurationMs = totalDurationMs,
                        isScrubbing = isScrubbing,
                        onScrubbing = { scrubbing, posMs ->
                            queueControlsVisible = true
                            onScrubbing(scrubbing, posMs)
                        },
                        onSeekTo = { posMs ->
                            queueControlsVisible = true
                            onSeekTo(posMs)
                        },
                        onPlayPauseClick = {
                            queueControlsVisible = true
                            onPlayPauseClick()
                        },
                        onPreviousClick = {
                            queueControlsVisible = true
                            onPreviousClick()
                        },
                        onNextClick = {
                            queueControlsVisible = true
                            onNextClick()
                        },
                        onToggleShuffle = onToggleShuffle,
                        onToggleRepeat = onToggleRepeat,
                        sliderStyle = sliderStyle,
                        controlsAlpha = controlsAlpha,
                        // Shuffle and Repeat already live in Queue's top squircle row.
                        showTransportPills = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    )
                    if (controlsBottomSpacerDp > 0.dp) {
                        Spacer(modifier = Modifier.height(controlsBottomSpacerDp))
                    }
                }
            }
        }

        if (showBottomBar) {
            ClassicBottomBar(
                isQueueActive = true,
                isLyricsActive = false,
                isTimerActive = uiState.sleepTimerSeconds > 0 || uiState.isSleepTimerEndOfSong,
                isShuffled = uiState.isShuffled,
                repeatMode = uiState.repeatMode,
                onToggleQueue = onCloseQueue,
                onToggleShuffle = onToggleShuffle,
                onToggleRepeat = onToggleRepeat,
                onShowOutputPicker = onShowOutputPicker,
                onShowSleepDialog = onShowSleepDialog,
                onToggleLyrics = onToggleLyrics,
                controlsAlpha = controlsAlpha,
                showUtilityActions = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

private fun openAudioOutputSettings(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panelIntent = Intent(Settings.Panel.ACTION_VOLUME)
            panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(panelIntent)
        } else {
            val settingsIntent = Intent(Settings.ACTION_SOUND_SETTINGS)
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
        }
    } catch (_: Exception) {
        try {
            val settingsIntent = Intent(Settings.ACTION_SOUND_SETTINGS)
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "Audio Output Settings unavailable", Toast.LENGTH_SHORT).show()
        }
    }
}
