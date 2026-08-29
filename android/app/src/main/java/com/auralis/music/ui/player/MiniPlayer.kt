package com.auralis.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
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
import kotlinx.coroutines.launch
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
    return when (style.trim().lowercase()) {
        "apple liquid glass", "liquid glass", "apple glass", "glass" -> "Apple Liquid Glass"
        "blur", "frosted glass / blur", "frosted glass", "dynamic blurred artwork" -> "Blur"
        "dark black", "pure black", "black", "amoled black", "solid amoled black" -> "Dark Black"
        else -> "Gradient"
    }
}

/**
 * Helper to compute rich, saturated, non-transparent gradient stops based on album art colors.
 */
private fun tuneColorForGradient(baseColor: Color, targetValue: Float, satMultiplier: Float = 1.0f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(baseColor.toArgb(), hsv)
    hsv[1] = (hsv[1] * satMultiplier).coerceIn(0.55f, 1.0f)
    hsv[2] = targetValue.coerceIn(0.08f, 0.95f)
    return Color(android.graphics.Color.HSVToColor(hsv))
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
    onClose: (() -> Unit)? = null,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier
) {
    if (track == null && queue.isEmpty()) return

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val dismissOffsetY = remember { Animatable(0f) }
    var isDismissing by remember { mutableStateOf(false) }
    val dismissThresholdPx = with(density) { 50.dp.toPx() }

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
                    if (dismissOffsetY.value > dismissThresholdPx || velocity > 800f) {
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

    val context = LocalContext.current
    val currentTrack = track ?: queueTracks.getOrNull(safeCurrentIndex)

    // Dynamic Artwork Palette Extraction with Cache
    val cachedPalette = remember(currentTrack?.id) {
        currentTrack?.let {
            com.auralis.music.ui.theme.ArtworkPaletteCache.getCached(it.id)
                ?: com.auralis.music.ui.theme.ArtworkPaletteCache.getCached(it.thumbnail)
        }
    }
    var extractedColors by remember(currentTrack?.id) {
        mutableStateOf(cachedPalette ?: com.auralis.music.ui.theme.ArtworkPaletteCache.defaultPalette)
    }
    LaunchedEffect(currentTrack?.id, currentTrack?.thumbnail) {
        if (currentTrack != null) {
            if (cachedPalette != null) {
                extractedColors = cachedPalette
            } else {
                extractedColors = com.auralis.music.ui.theme.ArtworkPaletteCache.extractPalette(
                    context = context,
                    key = currentTrack.id,
                    artworkUrl = currentTrack.thumbnail
                )
            }
        }
    }

    val appearance = com.auralis.music.ui.theme.LocalAppearanceSettings.current
    val isLightMode = appearance.appTheme == "Light Mode"

    // Active Mini-Player Visual Theme (Gradient, Apple Liquid Glass, Blur, Dark Black)
    val activeTheme = remember(appearance.miniPlayerBackgroundStyle) {
        normalizeMiniPlayerTheme(appearance.miniPlayerBackgroundStyle)
    }

    // Compact floating pill shape
    val pillShape = RoundedCornerShape(30.dp)

    // Derived Gradient Stops matching user's reference image:
    // Left: Deep moody background -> Center: Saturated mid-tone -> Right: Electric luminous vibrant accent!
    val gradLeft = remember(extractedColors.primary) {
        tuneColorForGradient(extractedColors.primary, targetValue = 0.16f, satMultiplier = 0.90f)
    }
    val gradMid = remember(extractedColors.primary) {
        tuneColorForGradient(extractedColors.primary, targetValue = 0.36f, satMultiplier = 1.05f)
    }
    val gradRight = remember(extractedColors.secondary) {
        tuneColorForGradient(extractedColors.secondary, targetValue = 0.58f, satMultiplier = 1.25f)
    }
    val gradEnd = remember(extractedColors.secondary) {
        tuneColorForGradient(extractedColors.secondary, targetValue = 0.48f, satMultiplier = 1.15f)
    }

    // Smooth animated color transitions when track changes
    val animPrimary by animateColorAsState(
        targetValue = extractedColors.primary,
        animationSpec = tween(durationMillis = 400),
        label = "miniPlayerPrimary"
    )
    val animGradLeft by animateColorAsState(
        targetValue = gradLeft,
        animationSpec = tween(durationMillis = 400),
        label = "miniGradLeft"
    )
    val animGradMid by animateColorAsState(
        targetValue = gradMid,
        animationSpec = tween(durationMillis = 400),
        label = "miniGradMid"
    )
    val animGradRight by animateColorAsState(
        targetValue = gradRight,
        animationSpec = tween(durationMillis = 400),
        label = "miniGradRight"
    )
    val animGradEnd by animateColorAsState(
        targetValue = gradEnd,
        animationSpec = tween(durationMillis = 400),
        label = "miniGradEnd"
    )

    // Theme-specific elevation, border, and shadow configuration
    val elevation: Dp = when (activeTheme) {
        "Blur" -> 12.dp
        "Dark Black" -> 10.dp
        "Apple Liquid Glass" -> 18.dp
        else -> 16.dp // Gradient
    }

    val spotShadowColor: Color = when (activeTheme) {
        "Gradient" -> animGradRight.copy(alpha = 0.70f)
        "Apple Liquid Glass" -> Color(0xFFE8F0FE).copy(alpha = 0.35f)
        else -> Color.Black.copy(alpha = 0.50f)
    }

    val ambientShadowColor: Color = when (activeTheme) {
        "Apple Liquid Glass" -> Color.Black.copy(alpha = 0.45f)
        else -> Color.Black.copy(alpha = 0.25f)
    }

    // Typography and Icon colors tailored for optimal contrast per theme
    val titleTextColor: Color = Color.White
    val artistTextColor: Color = if (activeTheme == "Apple Liquid Glass") Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.78f)

    // Black circular button discs with pure white icons for maximum punchy contrast
    val actionButtonTint: Color = Color.White
    val actionButtonBackground: Color = Color.Black.copy(alpha = 0.45f)

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
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .shadow(
                elevation = elevation,
                shape = pillShape,
                ambientColor = ambientShadowColor,
                spotColor = spotShadowColor
            )
            .clip(pillShape)
            .then(
                when (activeTheme) {
                    "Gradient" -> Modifier.border(
                        width = 1.3.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                animGradLeft.copy(alpha = 0.70f),
                                animGradMid.copy(alpha = 0.50f),
                                animGradRight.copy(alpha = 0.85f),
                                Color.White.copy(alpha = 0.25f)
                            )
                        ),
                        shape = pillShape
                    )
                    "Apple Liquid Glass" -> Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), pillShape)
                    "Blur" -> Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), pillShape)
                    "Dark Black" -> Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), pillShape)
                    else -> Modifier.border(1.dp, Color.White.copy(alpha = 0.15f), pillShape)
                }
            )
    ) {
        // ────────────────────────────────────────────────────────────────────
        // 1. SELECTABLE THEME BACKGROUND SURFACE INSIDE THE FLOATING PILL
        // ────────────────────────────────────────────────────────────────────
        when (activeTheme) {
            "Gradient" -> {
                // 1. Rich solid horizontal gradient flowing from deep moody left to electric vibrant right
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                0.0f to animGradLeft,
                                0.45f to animGradMid,
                                0.85f to animGradRight,
                                1.0f to animGradEnd
                            )
                        )
                )

                // 2. Ambient glowing bloom on the right side behind action buttons
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    animGradRight.copy(alpha = 0.60f),
                                    Color.Transparent
                                ),
                                center = Offset(800f, 60f),
                                radius = 300f
                            )
                        )
                )

                // 3. Subtle physical depth sheen (top highlight & bottom shading)
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.16f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.25f)
                                )
                            )
                        )
                )
            }

            "Apple Liquid Glass" -> {
                // 1. Dark glass base
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0xFF141620))
                )

                // 2. Heavy Frosted Blur Background (grayscale monochrome, zero color bleed)
                if (!currentTrack?.thumbnail.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(getHighResArtworkUrl(currentTrack?.thumbnail))
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                            androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }
                        ),
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                scaleX = 1.45f
                                scaleY = 1.45f
                                alpha = 0.70f
                            }
                            .blur(radius = 36.dp)
                    )
                }

                // 3. Matte Frosted Glass Diffusion Overlay (smooth, clean, no harsh shine)
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.35f),
                                    Color.Black.copy(alpha = 0.55f)
                                )
                            )
                        )
                )
            }

            "Blur" -> {
                // Solid base to ensure no transparent wash out
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0xFF14161F))
                )

                // Heavily blurred album artwork background
                if (!currentTrack?.thumbnail.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(getHighResArtworkUrl(currentTrack?.thumbnail))
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                scaleX = 1.4f
                                scaleY = 1.4f
                            }
                            .blur(radius = 36.dp)
                    )
                }

                // Darkening atmospheric vignette overlay for maximum text contrast
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.38f),
                                    Color.Black.copy(alpha = 0.62f)
                                )
                            )
                        )
                )
            }

            else -> {
                // "Dark Black": Clean deep AMOLED Black minimal style
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0xFF08090C))
                )
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // 2. MINI-PLAYER CONTENT & FUNCTIONALITY
        // ────────────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ================================================================
            // SWIPEABLE TRACK CONTENT CAROUSEL (ARTWORK + TITLE + ARTIST)
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
                        progressColor = if (activeTheme == "Gradient" || activeTheme == "Apple Liquid Glass") Color.White else if (appearance.dynamicIconColors) animPrimary else MaterialTheme.colorScheme.primary,
                        isLiquidGlass = activeTheme == "Apple Liquid Glass",
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
                                color = titleTextColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = pageTrack.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = artistTextColor,
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Button 1: Listen Together / Social / Artist
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(actionButtonBackground)
                        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                        .tactileBounce(scaleDown = 0.85f, onClick = { onArtistClick?.invoke() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = "Listen Together",
                        tint = actionButtonTint,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Button 2: Add to Playlist (+)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(actionButtonBackground)
                        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                        .tactileBounce(scaleDown = 0.85f, onClick = { onAddToPlaylist?.invoke() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add to playlist",
                        tint = actionButtonTint,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Button 3: Favorite Heart
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(actionButtonBackground)
                        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
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
                            tint = if (fav) Color(0xFFFF4081) else actionButtonTint,
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
    progressColor: Color,
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
            .size(50.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .then(
                if (isLiquidGlass) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
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
            val strokeWidth = 2.5.dp.toPx()
            val insetPadding = 3.5.dp.toPx()
            val radius = (size.minDimension / 2) - strokeWidth / 2 - insetPadding
            val center = Offset(size.width / 2, size.height / 2)

            // Background ring track
            drawCircle(
                color = if (isLiquidGlass) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.14f),
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth)
            )

            // Active progress sweep arc
            val sweep = if (isCurrent) progress.value else 0f
            if (sweep > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // Inner Circular Artwork Disc
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center
        ) {
            ArtworkCard(
                url = track.thumbnail,
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
