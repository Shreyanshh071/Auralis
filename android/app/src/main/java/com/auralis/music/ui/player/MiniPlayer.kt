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
import androidx.compose.material.icons.outlined.Person
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

    // ════════════════════════════════════════════════════════════════════════
    // FLOATING MINI-PLAYER PILL (SURROUNDING AREA REMAINS 100% TRANSPARENT)
    // ════════════════════════════════════════════════════════════════════════
    Box(
        modifier = modifier
            .offset { IntOffset(0, dismissOffsetY.value.roundToInt()) }
            .graphicsLayer {
                val progressFrac = (dismissOffsetY.value / (dismissThresholdPx * 2.2f)).coerceIn(0f, 1f)
                alpha = 1f - progressFrac
                scaleX = 1f - (progressFrac * 0.12f)
                scaleY = 1f - (progressFrac * 0.12f)
            }
            .then(dragModifier)
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .shadow(
                elevation = elevation,
                shape = pillShape,
                ambientColor = ambientShadowColor,
                spotColor = spotShadowColor
            )
            .clip(pillShape)
            .then(
                if (hazeState != null && (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC || activeStyle == PlayerBackgroundStyle.BLUR)) {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            tint = HazeTint(Color(0xFF10121A).copy(alpha = 0.40f)),
                            blurRadius = 30.dp,
                            noiseFactor = 0.02f
                        )
                    )
                } else Modifier
            )
            .background(
                if (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC) {
                    Color(0xFF10121A).copy(alpha = 0.55f)
                } else if (activeStyle == PlayerBackgroundStyle.GLOW_MOTION || activeStyle == PlayerBackgroundStyle.LIVE_MESH) {
                    Color(0xFF050505)
                } else if (activeStyle == PlayerBackgroundStyle.GRADIENT) {
                    Color(0xFF08080A)
                } else {
                    Color(0xFF141512)
                }
            )
            .then(
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
                                Color.White.copy(alpha = 0.45f), // crisp specular light reflection on top glass rim
                                Color.White.copy(alpha = 0.16f), // subtle translucent side edges
                                Color.White.copy(alpha = 0.06f)  // fading bottom rim
                            )
                        ),
                        shape = pillShape
                    )
                    PlayerBackgroundStyle.BLUR -> Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), pillShape)
                    PlayerBackgroundStyle.LIVE_MESH -> Modifier.border(1.dp, Color.White.copy(alpha = 0.15f), pillShape)
                    PlayerBackgroundStyle.GLOW_MOTION -> Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), pillShape)
                    PlayerBackgroundStyle.FOLLOW_THEME -> Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), pillShape)
                }
            )
    ) {
        // ────────────────────────────────────────────────────────────────────
        // 1. SELECTABLE THEME BACKGROUND SURFACE INSIDE THE FLOATING PILL
        // ────────────────────────────────────────────────────────────────────
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ================================================================
            // SWIPEABLE TRACK CONTENT CAROUSEL (ARTWORK + TITLE + ARTIST)
            // ================================================================
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
                    // Circular Artwork Disc with Inset Progress Ring (if current) + Center Play/Pause
                    MiniPlayerArtworkDisc(
                        track = pageTrack,
                        isCurrent = isCurrent,
                        isPlaying = isPlaying,
                        progressProvider = effectiveProgressProvider,
                        progressColor = if (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC) Color.White else Color.White.copy(alpha = 0.92f),
                        isLiquidGlass = (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC),
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
                                color = if (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC) Color.White.copy(alpha = 0.85f) else Color(0xFFA6A698),
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
            // RIGHT 3 ACTION BUTTONS: LISTEN TOGETHER, ADD (+), FAVORITE HEART
            // ================================================================
            val actionButtonBg = if (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.08f)
            val actionButtonBorder = if (activeStyle == PlayerBackgroundStyle.APPLE_MUSIC) {
                Modifier.border(0.8.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            } else Modifier

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Button 1: Listen Together / Social / Artist
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

                // Button 3: Favorite Heart
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
