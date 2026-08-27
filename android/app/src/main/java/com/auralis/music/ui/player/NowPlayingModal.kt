package com.auralis.music.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.PlaylistPickerBottomSheet
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.components.auralisGlass
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.lyrics.SyncedLyricsView
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.AuralisPrimary
import com.auralis.music.ui.theme.AuralisSurfaceElevated
import com.auralis.music.ui.theme.GlassBorderHairline
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.theme.auralisContentEnter
import com.auralis.music.ui.theme.auralisContentExit
import com.auralis.music.ui.theme.auralisIconSwapEnter
import com.auralis.music.ui.theme.auralisIconSwapExit
import com.auralis.music.ui.theme.dynamicPalette
import com.auralis.music.ui.theme.motionTween
import com.auralis.music.ui.viewmodel.PlayerUiState

enum class NowPlayingTab {
    LYRICS,
    QUEUE,
    PLAYER
}

/**
 * Boosts HSV color saturation and balances lightness to ensure rich, vibrant aurora blooms
 * that never appear muddy, dull, or washed out.
 */
private fun boostColorVibrancy(color: Color, minSaturation: Float = 0.65f, targetLightness: Float = 0.55f): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toArgb(), hsv)
    if (hsv[1] < minSaturation) {
        hsv[1] = (hsv[1] * 1.8f).coerceIn(minSaturation, 1.0f)
    }
    if (hsv[2] < 0.35f) hsv[2] = targetLightness
    if (hsv[2] > 0.90f) hsv[2] = 0.85f
    return Color(AndroidColor.HSVToColor(hsv))
}

/**
 * Pixel-Perfect Fullscreen Now Playing Modal & Sheet with dynamic fluid aurora background,
 * segmented multi-mode switcher (Lyrics | Queue | Player), sub-header badges, and zero control collision.
 *
 * [sharedTransitionScope] / [animatedVisibilityScope] are optional. When supplied, the
 * artwork and the title/artist block are matched against the mini-player's so the sheet
 * reads as the pill expanding rather than a new screen appearing. Both null is valid.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingModal(
    uiState: PlayerUiState,
    playbackSpeed: Float = 1.0f,
    userPlaylists: List<Playlist> = emptyList(),
    onPlayPauseClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit = {},
    onSleepTimerSelect: (Int) -> Unit,
    onSelectQueueTrack: (Int) -> Unit,
    onLyricsOffsetChange: (Long) -> Unit = {},
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onArtistClick: ((com.auralis.music.domain.model.Artist) -> Unit)? = null,
    onDismiss: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier
) {
    val track = uiState.currentTrack ?: return
    val dynamicPalette = MaterialTheme.dynamicPalette

    // Intercept Android Back Gesture & Hardware Back Button to dismiss the Fullscreen Player
    androidx.activity.compose.BackHandler(enabled = true) {
        onDismiss()
    }

    val coroutineScope = rememberCoroutineScope()
    val queue = uiState.queue
    val currentTrackIndex = remember(uiState.currentIndex, queue, track.id) {
        if (uiState.currentIndex >= 0 && uiState.currentIndex < queue.size) {
            uiState.currentIndex
        } else {
            queue.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: 0
        }
    }
    val pageCount = if (queue.isNotEmpty()) queue.size else 1
    val pagerState = rememberPagerState(
        initialPage = currentTrackIndex.coerceIn(0, pageCount - 1)
    ) { pageCount }

    LaunchedEffect(currentTrackIndex) {
        if (currentTrackIndex in 0 until pageCount && pagerState.currentPage != currentTrackIndex) {
            pagerState.animateScrollToPage(currentTrackIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != currentTrackIndex && queue.isNotEmpty()) {
            onSelectQueueTrack(pagerState.currentPage)
        }
    }

    var currentTab by remember { mutableStateOf(NowPlayingTab.PLAYER) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showOffsetControls by remember { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(true) }

    LaunchedEffect(uiState.showLyricsView) {
        if (uiState.showLyricsView && currentTab != NowPlayingTab.LYRICS) {
            currentTab = NowPlayingTab.LYRICS
        }
    }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableFloatStateOf(0f) }

    val currentPosMs = if (isScrubbing) scrubPositionMs.toLong() else uiState.playbackPositionMs
    val totalDurationMs = if (uiState.durationMs > 0) uiState.durationMs else (track.duration * 1000L)

    val context = LocalContext.current

    // Cached and downsampled palette extraction for zero-jank background rendering
    val cachedPalette = remember(track.id) {
        com.auralis.music.ui.theme.ArtworkPaletteCache.getCached(track.id) 
            ?: com.auralis.music.ui.theme.ArtworkPaletteCache.getCached(track.thumbnail)
    }

    var extractedColors by remember {
        mutableStateOf(cachedPalette ?: com.auralis.music.ui.theme.ArtworkPaletteCache.defaultPalette)
    }

    // Async extraction with downsampling and LRU caching on Dispatchers.Default
    LaunchedEffect(track.id, track.thumbnail) {
        if (cachedPalette != null) {
            extractedColors = cachedPalette
        } else {
            val palette = com.auralis.music.ui.theme.ArtworkPaletteCache.extractPalette(
                context = context,
                key = track.id,
                artworkUrl = track.thumbnail
            )
            extractedColors = palette
        }
    }

    // Smooth continuous color interpolation executed on song/palette change, completely static while playing
    val colorSpec = motionTween<Color>(AuralisDuration.Large, AuralisEasing.Standard)
    val animatedPrimaryColor by androidx.compose.animation.animateColorAsState(extractedColors.primary, colorSpec, label = "animPrimary")
    val animatedSecondaryColor by androidx.compose.animation.animateColorAsState(extractedColors.secondary, colorSpec, label = "animSecondary")
    val animatedTertiaryColor by androidx.compose.animation.animateColorAsState(extractedColors.tertiary, colorSpec, label = "animTertiary")

    val dragOffsetY = remember { Animatable(0f) }

    // Hoisted: transitionSpec is not a composable scope, so reduced-motion-aware
    // specs have to be built out here and captured.
    val favoriteEnter = auralisIconSwapEnter()
    val favoriteExit = auralisIconSwapExit()

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dragOffsetY.value
                val dragFraction = (dragOffsetY.value / 600f).coerceIn(0f, 1f)
                scaleX = 1f - (dragFraction * 0.08f)
                scaleY = 1f - (dragFraction * 0.08f)
                alpha = 1f - (dragFraction * 0.35f)
            }
            .background(Color(0xFF08060C))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {} // Intercept all clicks on empty player space to prevent bleed-through to underlying screens
            )
    ) {
        // ====================================================================
        // 1. STATIC HIGH-VIBRANCY AURORA MESH BACKGROUND (SYNCED WITH COVER)
        // ====================================================================
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Base dark tone
            drawRect(color = Color(0xFF0C0912))

            // Layer 1: Primary Radiant Orb (Upper Right / Center Bloom)
            val center1 = Offset(width * 0.75f, height * 0.22f)
            val radius1 = width * 1.30f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        animatedPrimaryColor.copy(alpha = 0.82f),
                        animatedPrimaryColor.copy(alpha = 0.45f),
                        animatedPrimaryColor.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = center1,
                    radius = radius1
                ),
                center = center1,
                radius = radius1
            )

            // Layer 2: Secondary Harmonic Orb (Left-Center Bloom)
            val center2 = Offset(width * 0.15f, height * 0.52f)
            val radius2 = width * 1.20f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        animatedSecondaryColor.copy(alpha = 0.70f),
                        animatedSecondaryColor.copy(alpha = 0.35f),
                        animatedSecondaryColor.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = center2,
                    radius = radius2
                ),
                center = center2,
                radius = radius2
            )

            // Layer 3: Tertiary Deep Anchor Orb (Bottom Anchor)
            val center3 = Offset(width * 0.50f, height * 0.85f)
            val radius3 = width * 1.10f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        animatedTertiaryColor.copy(alpha = 0.60f),
                        animatedTertiaryColor.copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = center3,
                    radius = radius3
                ),
                center = center3,
                radius = radius3
            )

            // Layer 4: Atmospheric Contrast Vignette for Header & Bottom Controls
            drawRect(
                brush = Brush.verticalGradient(
                    0.00f to Color.Black.copy(alpha = 0.25f),
                    0.18f to Color.Transparent,
                    0.58f to Color.Transparent,
                    0.82f to Color.Black.copy(alpha = 0.35f),
                    1.00f to Color.Black.copy(alpha = 0.65f)
                )
            )
        }

        // ====================================================================
        // 2. FOREGROUND CONTENT WITH SEGMENTED SWITCHER (PHOTO 2 DESIGN)
        // ====================================================================
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── TOP BAR: DOWN CHEVRON + NOW PLAYING ARTIST + PULL-DOWN DRAG GESTURE ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 6.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                coroutineScope.launch {
                                    val nextVal = (dragOffsetY.value + dragAmount).coerceAtLeast(0f)
                                    dragOffsetY.snapTo(nextVal)
                                }
                            },
                            onDragEnd = {
                                if (dragOffsetY.value > 160f) {
                                    onDismiss()
                                } else {
                                    coroutineScope.launch {
                                        dragOffsetY.animateTo(
                                            0f,
                                            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                coroutineScope.launch {
                                    dragOffsetY.animateTo(
                                        0f,
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                                    )
                                }
                            }
                        )
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp).tactileBounce(scaleDown = 0.88f)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Dismiss",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.55f),
                        letterSpacing = 2.0.sp,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.artist,
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

            Spacer(modifier = Modifier.height(6.dp))

            // ── ULTRA-PREMIUM FROSTED GLASS SEGMENTED MODE SWITCHER (LYRICS | QUEUE | PLAYER) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 14.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = Color.Black.copy(alpha = 0.35f)
                    )
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.18f),
                                Color.White.copy(alpha = 0.08f)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.38f),
                                Color.White.copy(alpha = 0.10f)
                            )
                        ),
                        CircleShape
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tab 1: Lyrics
                PlayerModeTab(
                    weight = 1f,
                    selected = currentTab == NowPlayingTab.LYRICS,
                    onClick = { currentTab = NowPlayingTab.LYRICS }
                ) { contentColor, selected ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Lyrics",
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = contentColor
                        )
                    }
                }

                // Tab 2: Queue
                PlayerModeTab(
                    weight = 1.1f,
                    selected = currentTab == NowPlayingTab.QUEUE,
                    onClick = { currentTab = NowPlayingTab.QUEUE }
                ) { contentColor, selected ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Queue (${uiState.queue.size})",
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = contentColor
                        )
                    }
                }

                // Tab 3: Player
                PlayerModeTab(
                    weight = 1f,
                    selected = currentTab == NowPlayingTab.PLAYER,
                    onClick = { currentTab = NowPlayingTab.PLAYER }
                ) { contentColor, selected ->
                    Text(
                        text = "Player",
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = contentColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── TAB CONTENT (NO COLLISION) ──
            // Wrapped in a single AnimatedContent so switching mode cross-fades in
            // place instead of hard-cutting. Specs are hoisted because transitionSpec
            // is not a composable scope. SizeTransform(clip = false) stops the
            // outgoing body being clipped to the incoming one's bounds mid-swap.
            val tabBodyEnter = auralisContentEnter()
            val tabBodyExit = auralisContentExit()
            AnimatedContent(
                targetState = currentTab,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                transitionSpec = {
                    tabBodyEnter togetherWith tabBodyExit using SizeTransform(clip = false)
                },
                label = "nowPlayingTabBody"
            ) { tab ->
                when (tab) {
                // ============================================================
                // 🎤 A. FULL LYRICS VIEW (MATCHING PHOTO 2)
                // ============================================================
                NowPlayingTab.LYRICS -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        SyncedLyricsView(
                            lyrics = uiState.lyrics,
                            currentPositionMs = currentPosMs,
                            onSeekTo = onSeekTo,
                            isLoading = uiState.isLoadingLyrics,
                            lyricsMode = com.auralis.music.domain.model.LyricsMode.CINEMA,
                            offsetMs = uiState.lyricsOffsetMs,
                            onOffsetChange = onLyricsOffsetChange
                        )
                    }
                }

                // ============================================================
                // ≡♪ B. QUEUE VIEW
                // ============================================================
                NowPlayingTab.QUEUE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        Text(
                            text = "Up Next (${uiState.queue.size} songs)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        val animateQueueItems = !LocalReducedMotion.current
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(
                                items = uiState.queue,
                                // Keys let reorders/removals animate instead of the list
                                // silently re-binding rows. A queue may legitimately hold
                                // the same track twice, so the index is part of the key.
                                key = { index, item -> "${item.id}#$index" }
                            ) { index, item ->
                                val isCurrent = index == uiState.currentIndex
                                Row(
                                    modifier = Modifier
                                        .then(if (animateQueueItems) Modifier.animateItem() else Modifier)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isCurrent) Color(0xFFD5E15B).copy(alpha = 0.20f) else Color.White.copy(alpha = 0.08f))
                                        .clickable {
                                            onSelectQueueTrack(index)
                                            currentTab = NowPlayingTab.PLAYER
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ArtworkCard(
                                        url = item.thumbnail,
                                        modifier = Modifier.size(44.dp),
                                        cornerRadius = 8.dp,
                                        contentDescription = item.title
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (isCurrent) Color(0xFFD5E15B) else Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = item.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isCurrent) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                            contentDescription = "Playing",
                                            tint = Color(0xFFD5E15B),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ============================================================
                // 🎵 C. MAIN PLAYER VIEW (ALBUM ART, SCRUBBER, CONTROLS, UTILITY)
                // ============================================================
                NowPlayingTab.PLAYER -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // ── CENTER ARTWORK WITH AMBIENT HALO GLOW ──
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            // Ambient Radial Halo directly behind artwork
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .aspectRatio(1f)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                animatedPrimaryColor.copy(alpha = 0.65f),
                                                animatedSecondaryColor.copy(alpha = 0.30f),
                                                Color.Transparent
                                            )
                                        ),
                                        shape = CircleShape
                                    )
                            )

                            // Main Album Artwork Carousel (Native Jetpack Compose Horizontal Pager)
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier
                                    .fillMaxWidth(0.88f)
                                    .aspectRatio(1f)
                                    .shadow(
                                        elevation = 32.dp,
                                        shape = RoundedCornerShape(26.dp),
                                        ambientColor = animatedPrimaryColor,
                                        spotColor = animatedPrimaryColor
                                    )
                                    .clip(RoundedCornerShape(26.dp))
                            ) { page ->
                                val pageTrack = if (queue.isNotEmpty() && page in queue.indices) queue[page] else track
                                // Only the playing page claims the shared key — the pager
                                // keeps neighbours composed off-screen and two live layouts
                                // holding one key at once is undefined.
                                val isCurrentPage = page == currentTrackIndex
                                ArtworkCard(
                                    url = pageTrack.thumbnail,
                                    modifier = playerSharedArtwork(
                                        sharedTransitionScope = sharedTransitionScope,
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        enabled = isCurrentPage
                                    ).fillMaxSize(),
                                    cornerRadius = playerArtworkCorner(
                                        animatedVisibilityScope = if (isCurrentPage) animatedVisibilityScope else null,
                                        expanded = true
                                    ),
                                    // The visible drop shadow belongs to the pager above; this
                                    // one is clipped away by it, and dropping the node keeps a
                                    // shadow from being re-rasterised on every frame of the
                                    // container transform.
                                    elevation = 0.dp,
                                    contentDescription = pageTrack.title
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // ── TRACK INFO & ACTION BUTTONS (ADD TO PLAYLIST + LIKE HEART) ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                androidx.compose.animation.AnimatedContent(
                                    targetState = track,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            playerSharedTrackInfo(
                                                sharedTransitionScope = sharedTransitionScope,
                                                animatedVisibilityScope = animatedVisibilityScope,
                                                enabled = true
                                            )
                                        ),
                                    transitionSpec = {
                                        (androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(180)) +
                                                androidx.compose.animation.slideInVertically(animationSpec = androidx.compose.animation.core.tween(180)) { it / 3 }) togetherWith
                                                (androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(120)) +
                                                        androidx.compose.animation.slideOutVertically(animationSpec = androidx.compose.animation.core.tween(120)) { -it / 3 })
                                    },
                                    label = "TrackInfoAnim"
                                ) { curTrack ->
                                    Column {
                                        Text(
                                            text = curTrack.title,
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 20.sp,
                                            modifier = Modifier.basicMarquee()
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = curTrack.artist,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White.copy(alpha = 0.65f),
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 14.sp,
                                            modifier = Modifier.clickable {
                                                onArtistClick?.invoke(
                                                    com.auralis.music.domain.model.Artist(
                                                        id = "",
                                                        name = curTrack.artist,
                                                        thumbnail = curTrack.thumbnail
                                                    )
                                                )
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Add to Playlist Button (Circular Dark Glass)
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                                    .tactileBounce(scaleDown = 0.86f, onClick = { showPlaylistPicker = true }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = "Add to Playlist",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // Like Heart Button (Circular Solid White)
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .tactileBounce(scaleDown = 0.86f, onClick = onToggleFavorite),
                                contentAlignment = Alignment.Center
                            ) {
                                AnimatedContent(
                                    targetState = uiState.isFavorite,
                                    transitionSpec = { favoriteEnter togetherWith favoriteExit },
                                    label = "nowPlayingFavorite"
                                ) { favorited ->
                                    Icon(
                                        imageVector = if (favorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = if (favorited) "Favorited" else "Favorite",
                                        tint = Color.Black,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // ── TIME SCRUBBER SLIDER & TIMESTAMPS ──
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Slider(
                                value = if (totalDurationMs > 0) (currentPosMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) else 0f,
                                onValueChange = { frac ->
                                    isScrubbing = true
                                    scrubPositionMs = frac * totalDurationMs
                                },
                                onValueChangeFinished = {
                                    isScrubbing = false
                                    onSeekTo(scrubPositionMs.toLong())
                                },
                                thumb = {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .shadow(2.dp, CircleShape)
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
                                },
                                track = { sliderState ->
                                    SliderDefaults.Track(
                                        sliderState = sliderState,
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = Color.White.copy(alpha = 0.60f),
                                            inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                                        ),
                                        modifier = Modifier.height(4.dp)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formatTime(currentPosMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = formatTime(totalDurationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // ── MAIN PLAYBACK CONTROLS (PREVIOUS, WIDE THICK WHITE PLAY/PAUSE PILL, NEXT) ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Previous Button (Circular Dark Glass)
                            Box(
                                modifier = Modifier
                                    .size(58.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                                    .tactileBounce(scaleDown = 0.84f, onClick = {
                                        coroutineScope.launch {
                                            if (pagerState.currentPage > 0) {
                                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                            } else {
                                                onPreviousClick()
                                            }
                                        }
                                    }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Previous",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }

                            // Wide Thick White Play/Pause Button Pill with Bouncy Feedback
                            Box(
                                modifier = Modifier
                                    .height(66.dp)
                                    .fillMaxWidth(0.72f)
                                    .shadow(
                                        elevation = 16.dp,
                                        shape = CircleShape,
                                        ambientColor = Color.Black.copy(alpha = 0.40f),
                                        spotColor = Color.Black.copy(alpha = 0.40f)
                                    )
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .tactileBounce(scaleDown = 0.86f, onClick = onPlayPauseClick),
                                contentAlignment = Alignment.Center
                            ) {
                                AnimatedContent(
                                    targetState = uiState.isPlaying,
                                    transitionSpec = {
                                        (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.80f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)))
                                            .togetherWith(fadeOut(animationSpec = tween(120)) + scaleOut(targetScale = 0.80f, animationSpec = tween(120)))
                                    },
                                    label = "PlayPauseButtonAnimation"
                                ) { isPlaying ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = if (isPlaying) "Pause" else "Play",
                                            tint = Color.Black,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isPlaying) "Pause" else "Play",
                                            color = Color.Black,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 17.sp
                                        )
                                    }
                                }
                            }

                            // Next Button (Circular Dark Glass)
                            Box(
                                modifier = Modifier
                                    .size(58.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                                    .tactileBounce(scaleDown = 0.84f, onClick = {
                                        coroutineScope.launch {
                                            if (pagerState.currentPage < pageCount - 1) {
                                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                            } else {
                                                onNextClick()
                                            }
                                        }
                                    }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Next",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // ── BOTTOM UTILITY BAR (LEFT CAPSULE PILL: SLEEP/SHUFFLE/REPEAT & RIGHT QUEUE BUTTON) ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 18.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left Glass Capsule with 3 Icons (Sleep, Shuffle, Repeat)
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF222028).copy(alpha = 0.85f))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(22.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Sleep Timer (Crescent Moon)
                                PlayerUtilityIcon(
                                    imageVector = Icons.Default.Bedtime,
                                    contentDescription = "Sleep Timer",
                                    active = uiState.sleepTimerSeconds > 0,
                                    onClick = { showSleepDialog = true }
                                )

                                // 2. Shuffle
                                PlayerUtilityIcon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Shuffle",
                                    active = uiState.isShuffled,
                                    onClick = { onToggleShuffle() }
                                )

                                // 3. Repeat
                                PlayerUtilityIcon(
                                    imageVector = when (uiState.repeatMode) {
                                        RepeatMode.ONE -> Icons.Default.RepeatOne
                                        else -> Icons.Default.Repeat
                                    },
                                    contentDescription = "Repeat",
                                    active = uiState.repeatMode != RepeatMode.OFF,
                                    onClick = { onToggleRepeat() }
                                )
                            }

                            // Right White Circular Queue Button
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .tactileBounce(scaleDown = 0.88f, onClick = { currentTab = NowPlayingTab.QUEUE }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = "Queue",
                                    tint = Color.Black,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

    // Direct Add to Playlist Bottom Sheet (Shows all user playlists + Create new)
    if (showPlaylistPicker) {
        PlaylistPickerBottomSheet(
            track = track,
            userPlaylists = userPlaylists,
            onAddToPlaylist = { playlist ->
                onAddToPlaylist(playlist.id, track)
                Toast.makeText(context, "Added to ${playlist.title}", Toast.LENGTH_SHORT).show()
                showPlaylistPicker = false
            },
            onCreatePlaylistAndAdd = { title ->
                onCreatePlaylistAndAdd(title, track)
                Toast.makeText(context, "Created and added to $title", Toast.LENGTH_SHORT).show()
                showPlaylistPicker = false
            },
            onDismiss = { showPlaylistPicker = false }
        )
    }

    // Sleep Timer Dialog
    if (showSleepDialog) {
        SleepTimerDialog(
            currentSeconds = uiState.sleepTimerSeconds,
            onSelectMinutes = { minutes ->
                onSleepTimerSelect(minutes)
                showSleepDialog = false
            },
            onDismiss = { showSleepDialog = false }
        )
    }
}

/**
 * One segment of the Lyrics | Queue | Player switcher.
 *
 * Extracted so the selection cross-fade recomposes three small lambdas rather than
 * the whole modal body, which owns the aurora canvas and the artwork pager. The
 * design is unchanged — only the hard flip between states is now interpolated.
 */
@Composable
private fun RowScope.PlayerModeTab(
    weight: Float,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable (contentColor: Color, selected: Boolean) -> Unit
) {
    val colorSpec = motionTween<Color>(AuralisDuration.Fast, AuralisEasing.Standard)
    val background by androidx.compose.animation.animateColorAsState(
        // Fading to a transparent *white* rather than Color.Transparent keeps the hue
        // constant; transparent-black would darken the pill on the way out.
        targetValue = if (selected) Color.White else Color.White.copy(alpha = 0f),
        animationSpec = colorSpec,
        label = "playerModeTabBackground"
    )
    val contentColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) Color.Black else Color.White.copy(alpha = 0.75f),
        animationSpec = colorSpec,
        label = "playerModeTabContent"
    )
    val elevation by animateDpAsState(
        targetValue = if (selected) 8.dp else 0.dp,
        animationSpec = motionTween(AuralisDuration.Fast, AuralisEasing.Standard),
        label = "playerModeTabElevation"
    )

    Box(
        modifier = Modifier
            .weight(weight)
            // Modifier.shadow is a no-op at 0.dp, so unselected tabs carry no shadow
            // node at all once the animation has settled.
            .shadow(
                elevation = elevation,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.25f),
                spotColor = Color.Black.copy(alpha = 0.25f)
            )
            .clip(CircleShape)
            .background(background)
            .tactileBounce(scaleDown = 0.92f, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        content(contentColor, selected)
    }
}

/**
 * A 20.dp toggle in the bottom utility capsule (sleep timer, shuffle, repeat).
 *
 * Owns its own tint animation so a toggle does not recompose the player body, and
 * cross-fades [imageVector] so Repeat -> RepeatOne reads as one control changing
 * mode instead of two different icons.
 */
@Composable
private fun PlayerUtilityIcon(
    imageVector: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (active) Color(0xFFD5E15B) else Color.White.copy(alpha = 0.70f),
        animationSpec = motionTween(AuralisDuration.Fast, AuralisEasing.Standard),
        label = "playerUtilityTint"
    )
    val iconEnter = auralisIconSwapEnter()
    val iconExit = auralisIconSwapExit()

    Box(
        modifier = Modifier
            .size(20.dp)
            .tactileBounce(scaleDown = 0.82f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = imageVector,
            transitionSpec = { iconEnter togetherWith iconExit },
            label = "playerUtilityIcon"
        ) { vector ->
            Icon(
                imageVector = vector,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Compatibility alias for NowPlayingSheet
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingSheet(
    uiState: PlayerUiState,
    playbackSpeed: Float = 1.0f,
    userPlaylists: List<Playlist> = emptyList(),
    onPlayPauseClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleLyricsView: () -> Unit = {},
    onLyricsOffsetChange: (Long) -> Unit = {},
    onSleepTimerSelect: (Int) -> Unit,
    onSelectQueueTrack: (Int) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onArtistClick: ((com.auralis.music.domain.model.Artist) -> Unit)? = null,
    onDismiss: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier
) {
    NowPlayingModal(
        uiState = uiState,
        playbackSpeed = playbackSpeed,
        userPlaylists = userPlaylists,
        onPlayPauseClick = onPlayPauseClick,
        onSeekTo = onSeekTo,
        onNextClick = onNextClick,
        onPreviousClick = onPreviousClick,
        onToggleShuffle = onToggleShuffle,
        onToggleRepeat = onToggleRepeat,
        onToggleFavorite = onToggleFavorite,
        onSetPlaybackSpeed = {},
        onSleepTimerSelect = onSleepTimerSelect,
        onSelectQueueTrack = onSelectQueueTrack,
        onLyricsOffsetChange = onLyricsOffsetChange,
        onAddToPlaylist = onAddToPlaylist,
        onCreatePlaylistAndAdd = onCreatePlaylistAndAdd,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onArtistClick = onArtistClick,
        onDismiss = onDismiss,
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        modifier = modifier
    )
}

// ============================================================================
// ⏱️ DIALOG HELPERS
// ============================================================================

@Composable
private fun SleepTimerDialog(
    currentSeconds: Long,
    onSelectMinutes: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMinutes by remember {
        mutableIntStateOf(
            if (currentSeconds > 0) ((currentSeconds + 59) / 60).toInt().coerceIn(5, 120) else 30
        )
    }
    var isEndOfSong by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF1C1E17))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. Title
                Text(
                    text = "Sleep timer",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 22.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 2. Subtitle readout
                val durationText = if (isEndOfSong) {
                    "End of song"
                } else if (selectedMinutes >= 60) {
                    val h = selectedMinutes / 60
                    val m = selectedMinutes % 60
                    if (m == 0) "$h ${if (h == 1) "hour" else "hours"}"
                    else "$h hr $m min"
                } else {
                    "$selectedMinutes minutes"
                }

                Text(
                    text = durationText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.70f),
                    fontSize = 15.sp
                )

                Spacer(modifier = Modifier.height(28.dp))

                // 3. Custom Dotted Slider Track
                val minMinutes = 5f
                val maxMinutes = 120f
                val fraction = ((selectedMinutes - minMinutes) / (maxMinutes - minMinutes)).coerceIn(0f, 1f)

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    val widthPx = constraints.maxWidth.toFloat()
                    val trackHeight = 16.dp
                    val trackCorner = 8.dp

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(trackHeight)
                            .clip(RoundedCornerShape(trackCorner))
                            .background(Color(0xFF323724))
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val newFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                                    val rawMin = (minMinutes + newFraction * (maxMinutes - minMinutes)).toInt()
                                    selectedMinutes = ((rawMin + 2) / 5 * 5).coerceIn(5, 120)
                                    isEndOfSong = false
                                }
                            }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { change, _ ->
                                        val newFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                                        val rawMin = (minMinutes + newFraction * (maxMinutes - minMinutes)).toInt()
                                        selectedMinutes = ((rawMin + 2) / 5 * 5).coerceIn(5, 120)
                                        isEndOfSong = false
                                    }
                                )
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val activeWidth = if (isEndOfSong) 0f else w * fraction

                            // 1. Inactive Track (Dark Olive)
                            drawRoundRect(
                                color = Color(0xFF353C24),
                                size = Size(w, h),
                                cornerRadius = CornerRadius(h / 2, h / 2)
                            )

                            // 2. Active Track (Lime Accent)
                            if (activeWidth > 0f) {
                                drawRoundRect(
                                    color = Color(0xFFD4E157),
                                    size = Size(activeWidth, h),
                                    cornerRadius = CornerRadius(h / 2, h / 2)
                                )
                            }

                            // 3. Dotted Tick Marks along track
                            val numDots = 24
                            for (i in 0..numDots) {
                                val dotX = (w / numDots) * i
                                val isCovered = dotX <= activeWidth && !isEndOfSong
                                drawCircle(
                                    color = if (isCovered) Color(0xFF1B1D16).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.35f),
                                    radius = 2.dp.toPx(),
                                    center = Offset(dotX, h / 2)
                                )
                            }
                        }
                    }

                    // Vertical Pill Thumb Indicator
                    if (!isEndOfSong) {
                        val thumbOffset = ((maxWidth - 10.dp) * fraction)
                        Box(
                            modifier = Modifier
                                .padding(start = thumbOffset)
                                .size(width = 8.dp, height = 36.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFD4E157))
                                .border(1.dp, Color(0xFF1B1D16), RoundedCornerShape(4.dp))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                // 4. "End of song" Quick Preset Pill
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isEndOfSong) Color(0xFFD4E157) else Color.Transparent)
                        .border(
                            1.dp,
                            if (isEndOfSong) Color(0xFFD4E157) else Color.White.copy(alpha = 0.18f),
                            CircleShape
                        )
                        .clickable { isEndOfSong = !isEndOfSong }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "End of song",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isEndOfSong) Color.Black else Color.White
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 5. Bottom Buttons (Reset, Cancel, OK)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            onSelectMinutes(0)
                        }
                    ) {
                        Text(
                            text = "Reset",
                            color = Color(0xFFE57373),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = "Cancel",
                                color = Color.White.copy(alpha = 0.70f),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }

                        TextButton(
                            onClick = {
                                if (isEndOfSong) {
                                    onSelectMinutes(4)
                                } else {
                                    onSelectMinutes(selectedMinutes)
                                }
                            }
                        ) {
                            Text(
                                text = "OK",
                                color = Color(0xFFD4E157),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
