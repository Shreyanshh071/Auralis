package com.auralis.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import com.auralis.music.domain.model.MiniPlayerDesign
import com.auralis.music.ui.theme.ArtworkPalette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.auralisIconSwapEnter
import com.auralis.music.ui.theme.auralisIconSwapExit
import com.auralis.music.ui.theme.motionTween

/**
 * Standard height of the floating MiniPlayer pill.
 */
val MiniPlayerHeight: Dp = 68.dp

/**
 * Normalizes any stored setting string into one of the selectable Mini-Player themes:
 * 1. Gradient (Vibrant dynamic horizontal gradient matching reference)
 * 2. Apple Liquid Glass (Ultra-premium frosted glassmorphism liquid pill)
 * 3. Blur (Atmospheric album artwork blur)
 * 4. Dark Black (Clean deep AMOLED black)
 */
fun normalizeMiniPlayerTheme(style: String): String {
    return PlayerBackgroundStyle.fromKey(style).displayName
}


/**
 * Pixel-Perfect Floating MiniPlayer Pill:
 * - Truly floating independently above the UI with transparent surroundings
 * - Exactly 4 selectable visual themes:
 *   1. Gradient: Rich dynamic horizontal gradient (dark moody left -> vibrant neon right) based on album art
 *   2. Apple Liquid Glass: Frosted liquid glassmorphism with specular edges, fluid refraction, and prismatic glow
 *   3. Blur: Atmospheric heavily blurred album artwork with clean contrast overlay
 *   4. Dark Black: Minimal, solid deep AMOLED black
 * - Interactive Horizontal Swipe Gesture: smoothly slide left/right between tracks in queue
 * - Interactive Vertical Drag-Down Gesture: drag/swipe down to dismiss the current track
 * - Left circular album art disc with circular progress indicator ring + centered Play/Pause toggle
 * - Middle track title and subtitle artist name (clicking opens the full Now Playing screen)
 * - Right 3 responsive action buttons: Listen Together, Add to Playlist (+), and Favorite Heart
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    track: Track?,
    isPlaying: Boolean,
    progress: Float = 0f, // 0.0f to 1.0f
    progressProvider: (() -> Float)? = null,
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
    onClose: (() -> Unit)? = null,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    if (track == null && queue.isEmpty()) return

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val appearance = com.auralis.music.ui.theme.LocalAppearanceSettings.current
    val sensitivityRatio = (appearance.miniPlayerSwipeSensitivity / 100f).coerceIn(0.10f, 1.0f)

    val dismissOffsetY = remember { Animatable(0f) }
    var isDismissing by remember { mutableStateOf(false) }
    val dismissThresholdPx = with(density) { (90.dp * (1.15f - sensitivityRatio * 0.40f)).toPx() }
    val dismissVelocityThreshold = 1800f * (1.15f - sensitivityRatio * 0.40f)

    val dragModifier = if (onClose != null && !isDismissing) {
        Modifier.draggable(
            state = rememberDraggableState { delta ->
                if (!isDismissing) {
                    val currentVal = dismissOffsetY.value
                    val newVal = (currentVal + delta).coerceAtLeast(0f)
                    coroutineScope.launch {
                        dismissOffsetY.snapTo(newVal)
                    }
                }
            },
            orientation = Orientation.Vertical,
            onDragStopped = { velocity ->
                if (!isDismissing) {
                    if (dismissOffsetY.value > dismissThresholdPx || velocity > dismissVelocityThreshold) {
                        isDismissing = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        coroutineScope.launch {
                            dismissOffsetY.animateTo(
                                targetValue = dismissThresholdPx * 3.5f,
                                animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing)
                            )
                            onClose.invoke()
                            dismissOffsetY.snapTo(0f)
                            isDismissing = false
                        }
                    } else {
                        coroutineScope.launch {
                            dismissOffsetY.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                }
            }
        )
    } else Modifier

    val effectiveProgressProvider = progressProvider ?: { progress }

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
        if (track != null) {
            val found = queueTracks.indexOfFirst { it.id == track.id }
            if (found >= 0) return@remember found
        }
        if (currentIndex in queueTracks.indices) {
            currentIndex
        } else {
            0
        }
    }

    val pagerState = rememberPagerState(
        initialPage = safeCurrentIndex.coerceIn(0, pageCount - 1)
    ) { pageCount }

    var isProgrammaticScroll by remember { mutableStateOf(false) }
    var lastDispatchedIndex by remember { mutableIntStateOf(safeCurrentIndex) }

    // External track index changes (e.g. background completion, notification, or full modal)
    LaunchedEffect(safeCurrentIndex) {
        lastDispatchedIndex = safeCurrentIndex
        if (!pagerState.isScrollInProgress && safeCurrentIndex in 0 until pageCount && pagerState.currentPage != safeCurrentIndex) {
            isProgrammaticScroll = true
            try {
                val diff = kotlin.math.abs(pagerState.currentPage - safeCurrentIndex)
                if (diff == 1) {
                    pagerState.animateScrollToPage(safeCurrentIndex, animationSpec = tween(durationMillis = 280))
                } else {
                    pagerState.scrollToPage(safeCurrentIndex)
                }
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    val context = LocalContext.current

    // Proactively pre-extract artwork palettes for neighboring tracks in the queue
    // so swiping immediately hits memory cache with zero delay or theme interruption.
    LaunchedEffect(safeCurrentIndex, queueTracks) {
        if (queueTracks.isNotEmpty()) {
            val nextTrack = queueTracks.getOrNull(safeCurrentIndex + 1)
            val prevTrack = queueTracks.getOrNull(safeCurrentIndex - 1)
            val nextNextTrack = queueTracks.getOrNull(safeCurrentIndex + 2)
            withContext(Dispatchers.IO) {
                for (neighborTrack in listOfNotNull(nextTrack, prevTrack, nextNextTrack)) {
                    if (com.auralis.music.ui.theme.ArtworkPaletteCache.getCached(neighborTrack.id) == null) {
                        com.auralis.music.ui.theme.ArtworkPaletteCache.extractPalette(
                            context = context,
                            key = neighborTrack.id,
                            artworkUrl = neighborTrack.thumbnail
                        )
                    }
                }
            }
        }
    }

    // User swipe gestures settled on a different page -> switch track immediately & update theme
    LaunchedEffect(pagerState, queueTracks) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { newPage ->
                if (!isProgrammaticScroll && newPage != safeCurrentIndex && newPage != lastDispatchedIndex && queueTracks.isNotEmpty()) {
                    if (newPage in queueTracks.indices) {
                        lastDispatchedIndex = newPage
                        val targetTrack = queueTracks[newPage]
                        // Immediately update dynamic theme palette for target track with zero delay
                        com.auralis.music.ui.theme.ArtworkPaletteCache.updateForTrack(context, targetTrack)
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
    }

    val activeTrack = queueTracks.getOrNull(pagerState.currentPage) ?: track ?: queueTracks.getOrNull(safeCurrentIndex)

    // Shared Dynamic Artwork Palette Extraction via ArtworkPaletteCache
    val sharedPalette by com.auralis.music.ui.theme.ArtworkPaletteCache.currentPalette.collectAsState()
    val extractedColors = if (activeTrack != null) {
        val cached = com.auralis.music.ui.theme.ArtworkPaletteCache.getCached(activeTrack.id)
            ?: com.auralis.music.ui.theme.ArtworkPaletteCache.getCached(activeTrack.thumbnail)
        cached ?: sharedPalette
    } else {
        sharedPalette
    }

    val isLightMode = appearance.appTheme == "Light Mode"

    // Active Mini-Player Visual Theme (Follow theme, Gradient, Blur, Glow motion, Apple Music, Live Mesh)
    val activeStyle = remember(appearance.miniPlayerBackgroundStyle) {
        PlayerBackgroundStyle.fromKey(appearance.miniPlayerBackgroundStyle)
    }

    // Derived Gradient Stops from artwork palette using unified PlayerGradientPalette:
    val gradStops = remember(extractedColors) {
        PlayerGradientPalette.create(
            primary = extractedColors.primary,
            secondary = extractedColors.secondary,
            tertiary = extractedColors.tertiary,
            isMonochrome = extractedColors.isMonochrome
        )
    }

    // Smooth animated color transitions when track changes (matching the global 650ms timeline)
    val animGradLeft by animateColorAsState(
        targetValue = gradStops.miniLeft,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "miniGradLeft"
    )
    val animGradMid by animateColorAsState(
        targetValue = gradStops.miniCenter,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "miniGradMid"
    )
    val animGradRight by animateColorAsState(
        targetValue = gradStops.miniRight,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "miniGradRight"
    )
    val animGradEnd by animateColorAsState(
        targetValue = gradStops.glowAccent,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "miniGradEnd"
    )

    val elevation: Dp = when (activeStyle) {
        PlayerBackgroundStyle.BLUR -> 16.dp
        PlayerBackgroundStyle.FOLLOW_THEME -> 12.dp
        PlayerBackgroundStyle.APPLE_MUSIC -> 18.dp
        PlayerBackgroundStyle.LIVE_MESH -> 18.dp
        PlayerBackgroundStyle.GLOW_MOTION -> 16.dp
        PlayerBackgroundStyle.GRADIENT -> 16.dp
    }

    val spotShadowColor: Color = when (activeStyle) {
        PlayerBackgroundStyle.GRADIENT -> animGradEnd.copy(alpha = 0.65f)
        PlayerBackgroundStyle.APPLE_MUSIC -> Color(0xFFE8F0FE).copy(alpha = 0.30f)
        PlayerBackgroundStyle.LIVE_MESH -> animGradRight.copy(alpha = 0.45f)
        PlayerBackgroundStyle.GLOW_MOTION -> animGradMid.copy(alpha = 0.45f)
        else -> Color.Black.copy(alpha = 0.50f)
    }

    val ambientShadowColor: Color = when (activeStyle) {
        PlayerBackgroundStyle.APPLE_MUSIC -> Color.Black.copy(alpha = 0.40f)
        else -> Color.Black.copy(alpha = 0.30f)
    }

    // Compact floating pill shape matching Photo 2
    val pillShape = RoundedCornerShape(32.dp)
    val favoriteEnter = auralisIconSwapEnter()
    val favoriteExit = auralisIconSwapExit()

    val miniPlayerModifier = modifier
        .offset { IntOffset(0, dismissOffsetY.value.roundToInt()) }
        .graphicsLayer {
            val progressFrac = (dismissOffsetY.value / (dismissThresholdPx * 2.2f)).coerceIn(0f, 1f)
            alpha = 1f - progressFrac
            scaleX = 1f - (progressFrac * 0.12f)
            scaleY = 1f - (progressFrac * 0.12f)
        }
        .then(dragModifier)

    when (appearance.miniPlayerDesign) {
        MiniPlayerDesign.EXPANDED.displayName -> {
            val currentTrack = activeTrack ?: track ?: queueTracks.firstOrNull()
            if (currentTrack != null) {
                ExpandedMiniPlayerView(
                    track = currentTrack,
                    isPlaying = isPlaying,
                    progressProvider = effectiveProgressProvider,
                    isFavorite = isFavorite,
                    dominantColor = animGradMid,
                    hazeState = hazeState,
                    onPlayPauseClick = onPlayPauseClick,
                    onPreviousClick = onPreviousClick,
                    onNextClick = onNextClick,
                    onFavoriteToggle = onFavoriteToggle,
                    onArtistClick = onArtistClick,
                    onClick = onClick,
                    modifier = miniPlayerModifier
                )
            }
        }
        MiniPlayerDesign.CLASSIC.displayName -> {
            val currentTrack = activeTrack ?: track ?: queueTracks.firstOrNull()
            if (currentTrack != null) {
                ClassicMiniPlayerView(
                    track = currentTrack,
                    isPlaying = isPlaying,
                    progressProvider = effectiveProgressProvider,
                    dominantColor = animGradMid,
                    isPureBlack = appearance.pureBlackMiniPlayer,
                    activeStyle = activeStyle,
                    extractedColors = extractedColors,
                    hazeState = hazeState,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onClick = onClick,
                    modifier = miniPlayerModifier
                )
            }
        }
        else -> {
            NewMiniPlayerPillView(
                track = track,
                activeTrack = activeTrack,
                queueTracks = queueTracks,
                safeCurrentIndex = safeCurrentIndex,
                pagerState = pagerState,
                isPlaying = isPlaying,
                isFavorite = isFavorite,
                userScrollEnabled = userScrollEnabled,
                effectiveProgressProvider = effectiveProgressProvider,
                appearance = appearance,
                activeStyle = activeStyle,
                extractedColors = extractedColors,
                isPureBlack = appearance.pureBlackMiniPlayer,
                elevation = elevation,
                pillShape = pillShape,
                ambientShadowColor = ambientShadowColor,
                spotShadowColor = spotShadowColor,
                animGradLeft = animGradLeft,
                animGradMid = animGradMid,
                animGradRight = animGradRight,
                hazeState = hazeState,
                sensitivityRatio = sensitivityRatio,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onPlayPauseClick = onPlayPauseClick,
                onFavoriteToggle = onFavoriteToggle,
                onAddToPlaylist = onAddToPlaylist,
                onArtistClick = onArtistClick,
                onClick = onClick,
                modifier = miniPlayerModifier
            )
        }
    }
}

/**
 * Expanded Mini Player (Photo 4):
 * - Floating glassmorphic card with blurry transparent cover-synced background.
 * - Ignores pure black setting to maintain its vibrant translucent aesthetic.
 * - Top row: 10.dp rounded square artwork, bold title, artist with SpeakerBoxIcon, chevron down expand button.
 * - Bottom row: Artist navigation button (left), Playback Controls capsule [Prev | Play/Pause | Next] (center), Like button (right).
 * - Bottom edge: subtle cover-tinted progress line.
 */
@Composable
private fun ExpandedMiniPlayerView(
    track: Track,
    isPlaying: Boolean,
    progressProvider: () -> Float,
    isFavorite: Boolean,
    dominantColor: Color,
    hazeState: HazeState?,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: (() -> Unit)?,
    onNextClick: (() -> Unit)?,
    onFavoriteToggle: (() -> Unit)?,
    onArtistClick: (() -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(22.dp)
    val favoriteEnter = auralisIconSwapEnter()
    val favoriteExit = auralisIconSwapExit()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .shadow(
                elevation = 16.dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.40f),
                spotColor = dominantColor.copy(alpha = 0.50f)
            )
            .clip(cardShape)
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = dominantColor.copy(alpha = 0.20f),
                            tint = HazeTint(dominantColor.copy(alpha = 0.25f)),
                            blurRadius = 30.dp,
                            noiseFactor = 0.02f
                        )
                    )
                } else Modifier
            )
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        dominantColor.copy(alpha = 0.42f),
                        Color(0xFF0C0D14).copy(alpha = 0.78f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.30f),
                        dominantColor.copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.08f)
                    )
                ),
                shape = cardShape
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 11.dp, start = 12.dp, end = 12.dp, bottom = 9.dp)
        ) {
            // Top row: Artwork + Title/Artist info + Expand Chevron
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
                // Album artwork (rounded square 10.dp)
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(getHighResArtworkUrl(track.thumbnail))
                            .crossfade(true)
                            .build(),
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Title + Artist with speaker icon
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SpeakerBoxIcon(
                            tint = dominantColor.copy(alpha = 0.95f),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.75f)
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Expand Chevron down button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .tactileBounce(scaleDown = 0.88f, onClick = onClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Expand Player",
                        tint = Color.White.copy(alpha = 0.90f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom row: Artist Button | Playback Controls Capsule | Like Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Artist Button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .tactileBounce(scaleDown = 0.88f, onClick = { onArtistClick?.invoke() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = "Artist: ${track.artist}",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Center: Playback Controls Capsule (Previous | Play/Pause | Next)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Previous
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .tactileBounce(scaleDown = 0.85f, onClick = { onPreviousClick?.invoke() }),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Track",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Play / Pause (solid white circular button with black icon)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .tactileBounce(scaleDown = 0.88f, onClick = onPlayPauseClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Next
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .tactileBounce(scaleDown = 0.85f, onClick = { onNextClick?.invoke() }),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Track",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Right: Like Button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .tactileBounce(scaleDown = 0.88f, onClick = { onFavoriteToggle?.invoke() }),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = isFavorite,
                        transitionSpec = { favoriteEnter togetherWith favoriteExit },
                        label = "expandedFavorite"
                    ) { fav ->
                        Icon(
                            imageVector = if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (fav) "Favorited" else "Favorite",
                            tint = if (fav) Color(0xFFFF4081) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Progress Indicator Line
            val currentProgress = progressProvider()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(currentProgress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(dominantColor.copy(alpha = 0.95f))
                )
            }
        }
    }
}

/**
 * Classic Mini Player (Photo 1):
 * - Flat docked bar with square 8.dp artwork, track info, Play/Pause and Next buttons.
 * - Bottom tan accent progress line.
 * - Supports Pure Black toggle: when enabled, turns solid AMOLED black.
 */
@Composable
private fun ClassicMiniPlayerView(
    track: Track,
    isPlaying: Boolean,
    progressProvider: () -> Float,
    dominantColor: Color,
    isPureBlack: Boolean,
    activeStyle: PlayerBackgroundStyle,
    extractedColors: ArtworkPalette,
    hazeState: HazeState?,
    onPlayPauseClick: () -> Unit,
    onNextClick: (() -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val bgColor = if (isPureBlack) Color.Black else Color(0xFF161616)
    val borderColor = if (isPureBlack) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .shadow(elevation = 10.dp, shape = shape)
            .clip(shape)
            .then(
                if (!isPureBlack && hazeState != null && (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC || activeStyle == PlayerBackgroundStyle.BLUR)) {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = Color(0xFF10121A),
                            tint = HazeTint(Color(0xFF10121A).copy(alpha = 0.45f)),
                            blurRadius = 30.dp,
                            noiseFactor = 0.02f
                        )
                    )
                } else Modifier
            )
            .background(bgColor)
            .border(1.dp, borderColor, shape)
    ) {
        if (!isPureBlack && activeStyle != PlayerBackgroundStyle.FOLLOW_THEME) {
            PlayerBackground(
                style = activeStyle,
                artworkUrl = track.thumbnail,
                extractedColors = extractedColors,
                modifier = Modifier.matchParentSize(),
                isMiniPlayer = true
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Square Artwork (8.dp radius) matching Photo 1
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(getHighResArtworkUrl(track.thumbnail))
                            .crossfade(true)
                            .build(),
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Title + Artist
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.70f)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Play/Pause Button
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .tactileBounce(scaleDown = 0.88f, onClick = onPlayPauseClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Next Track Button
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .tactileBounce(scaleDown = 0.88f, onClick = { onNextClick?.invoke() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Bottom tan accent progress line matching Photo 1
            val currentProgress = progressProvider()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(Color.White.copy(alpha = 0.12f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(currentProgress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color(0xFFEBA671))
                )
            }
        }
    }
}

/**
 * New Mini Player Pill View (Photo 2):
 * - Floating pill shape with circular artwork disc + center play/pause, swipable track info, and 3 action buttons.
 * - Supports Pure Black toggle: when enabled, turns solid AMOLED black with clean borders.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun NewMiniPlayerPillView(
    track: Track?,
    activeTrack: Track?,
    queueTracks: List<Track>,
    safeCurrentIndex: Int,
    pagerState: androidx.compose.foundation.pager.PagerState,
    isPlaying: Boolean,
    isFavorite: Boolean,
    userScrollEnabled: Boolean,
    effectiveProgressProvider: () -> Float,
    appearance: com.auralis.music.domain.model.AppearanceSettings,
    activeStyle: PlayerBackgroundStyle,
    extractedColors: ArtworkPalette,
    isPureBlack: Boolean,
    elevation: Dp,
    pillShape: RoundedCornerShape,
    ambientShadowColor: Color,
    spotShadowColor: Color,
    animGradLeft: Color,
    animGradMid: Color,
    animGradRight: Color,
    hazeState: HazeState?,
    sensitivityRatio: Float,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    onPlayPauseClick: () -> Unit,
    onFavoriteToggle: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onArtistClick: (() -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val favoriteEnter = auralisIconSwapEnter()
    val favoriteExit = auralisIconSwapExit()

    val actualBgColor = if (isPureBlack) {
        Color.Black
    } else when (activeStyle) {
        PlayerBackgroundStyle.APPLE_MUSIC -> Color(0xFF10121A).copy(alpha = 0.55f)
        PlayerBackgroundStyle.GLOW_MOTION, PlayerBackgroundStyle.LIVE_MESH -> Color(0xFF050505)
        PlayerBackgroundStyle.GRADIENT -> Color(0xFF08080A)
        else -> Color(0xFF141512)
    }

    val actualBorderModifier = if (isPureBlack) {
        Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), pillShape)
    } else {
        when (activeStyle) {
            PlayerBackgroundStyle.GRADIENT -> Modifier.border(
                width = 1.2.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        animGradLeft.copy(alpha = 0.60f),
                        animGradMid.copy(alpha = 0.70f),
                        animGradRight.copy(alpha = 0.85f),
                        Color.White.copy(alpha = 0.30f)
                    )
                ),
                shape = pillShape
            )
            PlayerBackgroundStyle.APPLE_MUSIC -> Modifier.border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.45f),
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.06f)
                    )
                ),
                shape = pillShape
            )
            PlayerBackgroundStyle.BLUR -> Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), pillShape)
            PlayerBackgroundStyle.LIVE_MESH -> Modifier.border(1.dp, Color.White.copy(alpha = 0.15f), pillShape)
            PlayerBackgroundStyle.GLOW_MOTION -> Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), pillShape)
            PlayerBackgroundStyle.FOLLOW_THEME -> Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), pillShape)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .shadow(
                elevation = elevation,
                shape = pillShape,
                ambientColor = if (isPureBlack) Color.Black else ambientShadowColor,
                spotColor = if (isPureBlack) Color.Black else spotShadowColor
            )
            .clip(pillShape)
            .then(
                if (!isPureBlack && hazeState != null && (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC || activeStyle == PlayerBackgroundStyle.BLUR)) {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = Color(0xFF10121A),
                            tint = HazeTint(Color(0xFF10121A).copy(alpha = 0.40f)),
                            blurRadius = 30.dp,
                            noiseFactor = 0.02f
                        )
                    )
                } else Modifier
            )
            .background(actualBgColor)
            .then(actualBorderModifier)
    ) {
        if (!isPureBlack) {
            PlayerBackground(
                style = activeStyle,
                artworkUrl = activeTrack?.thumbnail,
                extractedColors = extractedColors,
                modifier = Modifier.matchParentSize(),
                isMiniPlayer = true
            )

            if (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color.White.copy(alpha = 0.14f),
                                0.18f to Color.White.copy(alpha = 0.02f),
                                0.45f to Color.Transparent
                            )
                        )
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isHorizontalSwipeEnabled = userScrollEnabled && appearance.enableSwipeToChangeSong
            val snapPositionalThreshold = (0.85f - (sensitivityRatio * 0.45f)).coerceIn(0.38f, 0.75f)
            val pagerFlingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                state = pagerState,
                snapPositionalThreshold = snapPositionalThreshold
            )

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = isHorizontalSwipeEnabled,
                flingBehavior = pagerFlingBehavior,
                modifier = Modifier
                    .weight(1f)
                    .clipToBounds(),
                pageSpacing = 12.dp,
                verticalAlignment = Alignment.CenterVertically
            ) { page ->
                val pageTrack = queueTracks.getOrNull(page) ?: track ?: return@HorizontalPager
                val isCurrent = (page == safeCurrentIndex)

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
                    MiniPlayerArtworkDisc(
                        track = pageTrack,
                        isCurrent = isCurrent,
                        isPlaying = isPlaying,
                        progressProvider = effectiveProgressProvider,
                        progressColor = if (isPureBlack) Color.White.copy(alpha = 0.90f) else if (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC) Color.White else Color.White.copy(alpha = 0.92f),
                        isLiquidGlass = !isPureBlack && (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC),
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onPlayPauseClick = onPlayPauseClick
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
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
                                color = if (isPureBlack) Color.White.copy(alpha = 0.70f) else if (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC) Color.White.copy(alpha = 0.85f) else Color(0xFFA6A698),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            val actionButtonBg = if (isPureBlack) Color(0xFF181818) else if (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f)
            val actionButtonBorder = if (!isPureBlack && activeStyle == PlayerBackgroundStyle.APPLE_MUSIC) {
                Modifier.border(0.8.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            } else Modifier

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(actionButtonBg)
                        .then(actionButtonBorder)
                        .tactileBounce(scaleDown = 0.85f, onClick = { onArtistClick?.invoke() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = "Artist: ${activeTrack?.artist ?: ""}",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(actionButtonBg)
                        .then(actionButtonBorder)
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

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(actionButtonBg)
                        .then(actionButtonBorder)
                        .tactileBounce(scaleDown = 0.85f, onClick = { onFavoriteToggle?.invoke() }),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = isFavorite,
                        transitionSpec = { favoriteEnter togetherWith favoriteExit },
                        label = "miniPlayerFavorite"
                    ) { fav ->
                        Icon(
                            imageVector = if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (fav) "Favorited" else "Favorite",
                            tint = if (fav) Color(0xFFFF4081) else Color.White.copy(alpha = 0.85f),
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
    progressProvider: () -> Float,
    progressColor: Color = Color.White.copy(alpha = 0.92f),
    isLiquidGlass: Boolean = false,
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
            .size(48.dp)
            .clip(CircleShape)
            .then(
                if (isLiquidGlass) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                } else Modifier
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onPlayPauseClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Inset Circular Progress Track and Sweep Arc
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 2.2.dp.toPx()
            val insetPadding = 1.2.dp.toPx()
            val radius = (size.minDimension / 2) - strokeWidth / 2 - insetPadding
            val center = Offset(size.width / 2, size.height / 2)

            // Background ring track (sleek, subtle hairline)
            drawCircle(
                color = if (isLiquidGlass) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.20f),
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth)
            )

            // Active progress sweep arc (perfectly aligned with background ring track)
            val sweep = if (isCurrent) progressProvider().coerceIn(0f, 1f) else 0f
            if (sweep > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // Inner Circular Artwork Disc (Expanded to fill cleanly, NO thick grey ring)
        Box(
            modifier = Modifier
                .size(41.dp)
                .clip(CircleShape)
                .background(if (isLiquidGlass) Color(0xFF181A22).copy(alpha = 0.60f) else Color(0xFF22231E)),
            contentAlignment = Alignment.Center
        ) {
            ArtworkCard(
                url = track.thumbnail,
                fallbackTrack = track,
                modifier = sharedArtwork.fillMaxSize(),
                cornerRadius = artworkCorner,
                elevation = 0.dp,
                contentDescription = track.title
            )

            // Semi-transparent dark overlay + Crisp white Play/Pause icon
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f)),
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
