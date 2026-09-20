package com.auralis.music.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableLongStateOf
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.rememberScroller
import com.auralis.music.ui.components.QueueTrackItem
import com.auralis.music.ui.components.createQueueTrackItem
import com.auralis.music.ui.components.syncLocalQueueWithSnapshot
import com.auralis.music.ui.components.getHighResArtworkUrl
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
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
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.AuralisPlayerSlider
import com.auralis.music.ui.components.PlaylistPickerBottomSheet
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.components.auralisGlass
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.lyrics.ManualLyricsSearchModal
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
import com.auralis.music.ui.theme.PlayerMotion
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPalette
import com.auralis.music.ui.theme.motionTween
import com.auralis.music.ui.viewmodel.PlayerUiState

enum class NowPlayingTab {
    LYRICS,
    QUEUE,
    PLAYER
}

data class DynamicBackgroundData(
    val primaryArtworkUrl: String?,
    val secondaryArtworkUrl: String?,
    val swipeFraction: Float,
    val palette: com.auralis.music.ui.theme.ArtworkPalette
)

/**
 * Authoritative Active Track Synchronization:
 * During an incomplete/partial swipe, the currently playing track remains strictly authoritative
 * for the background, palette, title, and audio.
 * The activeTrack is ONLY committed to a new queue track when:
 * 1. A Next/Previous button is tapped (pendingTargetIndex is set), or
 * 2. A manual carousel swipe genuinely settles on a new page (pendingTargetIndex is set on settle).
 * If the user releases an incomplete swipe and the pager snaps back, the background remains unchanged.
 */
fun deriveActiveTrack(
    currentTab: NowPlayingTab,
    pendingTargetIndex: Int?,
    currentTrackIndex: Int,
    queue: List<Track>,
    playingTrack: Track
): Track {
    if (currentTab == NowPlayingTab.PLAYER && pendingTargetIndex != null && queue.isNotEmpty()) {
        if (pendingTargetIndex in queue.indices) {
            return queue[pendingTargetIndex]
        }
    }
    return playingTrack
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

typealias QueueTrackItem = com.auralis.music.ui.components.QueueTrackItem

/**
 * Pixel-Perfect Fullscreen Now Playing Modal & Sheet with dynamic fluid aurora background,
 * segmented multi-mode switcher (Lyrics | Queue | Player), sub-header badges, and zero control collision.
 *
 * [sharedTransitionScope] / [animatedVisibilityScope] are optional. When supplied, the
 * artwork and the title/artist block are matched against the mini-player's so the sheet
 * reads as the pill expanding rather than a new screen appearing. Both null is valid.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NowPlayingModal(
    uiState: PlayerUiState,
    playbackPositionState: State<Long>,
    lyricsClockSource: com.auralis.music.data.service.PlaybackClockSource? = null,
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
    onReorderQueue: ((Int, Int) -> Unit)? = null,
    onLyricsOffsetChange: (Long) -> Unit = {},
    onSearchLyricsManually: ((String, String) -> Unit)? = null,
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onArtistClick: ((com.auralis.music.domain.model.Artist) -> Unit)? = null,
    onDismiss: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    renderBackground: Boolean = true,
    modifier: Modifier = Modifier
) {
    var lastValidTrack by remember { mutableStateOf(uiState.currentTrack) }
    LaunchedEffect(uiState.currentTrack) {
        if (uiState.currentTrack != null) {
            lastValidTrack = uiState.currentTrack
        }
    }
    val track = uiState.currentTrack ?: lastValidTrack ?: return
    val dynamicPalette = MaterialTheme.dynamicPalette
    val context = androidx.compose.ui.platform.LocalContext.current

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    // Intercept Android Back Gesture & Hardware Back Button to dismiss the Fullscreen Player
    androidx.activity.compose.BackHandler(enabled = true) {
        onDismiss()
    }

    val coroutineScope = rememberCoroutineScope()
    val queue = uiState.queue
    val currentTrackIndex = remember(uiState.currentIndex, queue, track.id) {
        if (uiState.currentIndex in queue.indices && queue[uiState.currentIndex].id == track.id) {
            return@remember uiState.currentIndex
        }
        val found = queue.indexOfFirst { it.id == track.id }
        if (found >= 0) return@remember found
        if (uiState.currentIndex in queue.indices) uiState.currentIndex else 0
    }
    val pageCount = if (queue.isNotEmpty()) queue.size else 1
    val pagerState = rememberPagerState(
        initialPage = currentTrackIndex.coerceIn(0, pageCount - 1)
    ) { pageCount }

    var currentTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(NowPlayingTab.PLAYER) }

    LaunchedEffect(uiState.showLyricsView) {
        if (uiState.showLyricsView && currentTab != NowPlayingTab.LYRICS) {
            currentTab = NowPlayingTab.LYRICS
        }
    }

    var pendingTargetIndex by remember { mutableStateOf<Int?>(null) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }
    var skipPagerAnimation by remember { mutableStateOf(false) }

    // Active track authoritative synchronization:
    // During an incomplete/partial swipe, the currently playing track remains strictly authoritative
    // for background, palette, title, and audio.
    // The activeTrack is ONLY committed to a new queue track when:
    // 1. A Next/Previous button is tapped (pendingTargetIndex is set), or
    // 2. A manual carousel swipe genuinely settles on a new page (pendingTargetIndex is set on settle).
    val activeTrack = remember(currentTab, pendingTargetIndex, currentTrackIndex, queue, track) {
        deriveActiveTrack(currentTab, pendingTargetIndex, currentTrackIndex, queue, track)
    }

    val handleNext = {
        if (queue.isNotEmpty() && pageCount > 1) {
            val fromIndex = pendingTargetIndex ?: pagerState.currentPage
            val targetIndex = (fromIndex + 1).coerceAtMost(pageCount - 1)
            if (targetIndex != pagerState.currentPage) {
                pendingTargetIndex = targetIndex
                coroutineScope.launch {
                    try {
                        pagerState.animateScrollToPage(
                            page = targetIndex,
                            animationSpec = androidx.compose.animation.core.tween(
                                durationMillis = 240,
                                easing = androidx.compose.animation.core.FastOutSlowInEasing
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        }
        onNextClick()
    }

    val handlePrevious = {
        if (queue.isNotEmpty() && pageCount > 1) {
            val fromIndex = pendingTargetIndex ?: pagerState.currentPage
            val targetIndex = (fromIndex - 1).coerceAtLeast(0)
            if (targetIndex != pagerState.currentPage) {
                pendingTargetIndex = targetIndex
                coroutineScope.launch {
                    try {
                        pagerState.animateScrollToPage(
                            page = targetIndex,
                            animationSpec = androidx.compose.animation.core.tween(
                                durationMillis = 240,
                                easing = androidx.compose.animation.core.FastOutSlowInEasing
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        }
        onPreviousClick()
    }

    // 1. Programmatically sync pager when the active track changes externally (Next/Prev buttons, song end, playlist tap, etc.)
    LaunchedEffect(currentTrackIndex, track.id, currentTab) {
        if (pendingTargetIndex != null) {
            if (currentTrackIndex == pendingTargetIndex) {
                // Authoritative playback caught up to our optimistic target!
                pendingTargetIndex = null
            } else {
                // Playback is still catching up to a newer rapid tap/swipe target.
                // Do NOT force-scroll backwards or clear the optimistic target!
                return@LaunchedEffect
            }
        }

        if (currentTrackIndex in 0 until pageCount && pagerState.currentPage != currentTrackIndex) {
            isProgrammaticScroll = true
            try {
                if (currentTab == NowPlayingTab.PLAYER && !skipPagerAnimation) {
                    val distance = kotlin.math.abs(pagerState.currentPage - currentTrackIndex)
                    if (distance == 1 && !pagerState.isScrollInProgress) {
                        pagerState.animateScrollToPage(
                            page = currentTrackIndex,
                            animationSpec = androidx.compose.animation.core.tween(
                                durationMillis = 200,
                                easing = androidx.compose.animation.core.FastOutSlowInEasing
                            )
                        )
                    } else {
                        pagerState.scrollToPage(currentTrackIndex)
                    }
                } else {
                    // Pager is uncomposed on Lyrics or Queue tabs, or skipping animation from queue tap: snap directly without layout frames
                    pagerState.scrollToPage(currentTrackIndex)
                }
            } catch (_: Exception) {
                // Safe ignore if animation gets cancelled or interrupted
            } finally {
                skipPagerAnimation = false
                isProgrammaticScroll = false
            }
        }
    }

    // Tab return sync: When switching back to Player tab, guarantee pager is positioned on current track
    LaunchedEffect(currentTab) {
        if (currentTab == NowPlayingTab.PLAYER && pagerState.currentPage != currentTrackIndex) {
            try {
                pagerState.scrollToPage(currentTrackIndex.coerceIn(0, pageCount - 1))
            } catch (_: Exception) {}
        }
    }

    // Safety timeout: Ensure pendingTargetIndex is never locked indefinitely if track loading fails
    LaunchedEffect(pendingTargetIndex) {
        if (pendingTargetIndex != null) {
            kotlinx.coroutines.delay(2500)
            pendingTargetIndex = null
        }
    }

    var userSwipedPager by remember { mutableStateOf(false) }

    val currentTrackIndexState = rememberUpdatedState(currentTrackIndex)
    val currentQueueState = rememberUpdatedState(queue)
    val currentOnSelectQueueTrackState = rememberUpdatedState(onSelectQueueTrack)

    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> {
                    userSwipedPager = true
                }
            }
        }
    }

    // 2. Reliably trigger track change when user physically swipes the carousel to a new page
    LaunchedEffect(pagerState, currentTab) {
        snapshotFlow { Pair(pagerState.isScrollInProgress, pagerState.settledPage) }
            .distinctUntilChanged()
            .collect { (isScrolling, settledPage) ->
                val curIndex = currentTrackIndexState.value
                val curQueue = currentQueueState.value
                if (currentTab == NowPlayingTab.PLAYER && !isScrolling) {
                    if (userSwipedPager && !isProgrammaticScroll) {
                        userSwipedPager = false
                        if (curQueue.isNotEmpty() && settledPage in curQueue.indices && settledPage != curIndex) {
                            pendingTargetIndex = settledPage
                            val targetTrack = curQueue[settledPage]
                            com.auralis.music.ui.theme.ArtworkPaletteCache.updateForTrack(context, targetTrack)
                            currentOnSelectQueueTrackState.value(settledPage)
                        } else if (settledPage == curIndex) {
                            pendingTargetIndex = null
                        }
                    } else {
                        userSwipedPager = false
                        if (settledPage == curIndex) {
                            pendingTargetIndex = null
                        }
                    }
                }
            }
    }

    // Proactively pre-cache adjacent artwork and blur thumbnails in Coil memory cache
    LaunchedEffect(pagerState.currentPage, queue) {
        if (queue.isNotEmpty()) {
            val nextTrack = queue.getOrNull(pagerState.currentPage + 1)
            val prevTrack = queue.getOrNull(pagerState.currentPage - 1)
            withContext(Dispatchers.IO) {
                val imageLoader = context.imageLoader
                listOfNotNull(nextTrack, prevTrack).forEach { t ->
                    val fullUrl = getHighResArtworkUrl(t.thumbnail)
                    val reqFull = ImageRequest.Builder(context)
                        .data(fullUrl)
                        // PERF FIX #2: Reduced from 1200x1200 to 600x600
                        // 600px is ample for the carousel card display surface.
                        // Palette extraction uses its own 128x128 requests.
                        .size(600, 600)
                        .allowHardware(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                    imageLoader.enqueue(reqFull)

                    t.thumbnail?.let { thumb ->
                        val reqBlur = ImageRequest.Builder(context)
                            .data(thumb)
                            .size(256, 256)
                            .allowHardware(false)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build()
                        imageLoader.enqueue(reqBlur)
                    }
                }
            }
        }
    }

    var showSleepDialog by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showTrackOptions by remember { mutableStateOf(false) }
    var showOffsetControls by remember { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(true) }
    var showManualLyricsSearch by remember { mutableStateOf(false) }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableFloatStateOf(0f) }

    // The player's own clock, sampled once per displayed frame and interpolated
    // between readings. Runs only while the lyrics tab is visible and audio is
    // actually advancing; otherwise it mirrors the coarse ticker.
    val lyricsClock = com.auralis.music.ui.lyrics.rememberLyricsClock(
        source = lyricsClockSource,
        enabled = currentTab == NowPlayingTab.LYRICS && uiState.isPlaying,
        fallbackPositionMs = playbackPositionState
    )

    // Positions stay behind State rather than being unwrapped here. Unwrapping
    // at this level would tie every clock tick to a recomposition of the entire
    // modal; each leaf reads the value it needs instead.
    val seekBarPositionState = remember(playbackPositionState) {
        derivedStateOf {
            if (isScrubbing) scrubPositionMs.toLong() else playbackPositionState.value
        }
    }
    val lyricsPositionState = remember(lyricsClock) {
        derivedStateOf {
            if (isScrubbing) scrubPositionMs.toLong() else lyricsClock.value
        }
    }
    val totalDurationMs = if (uiState.durationMs > 0) uiState.durationMs else (track.duration * 1000L)

    val appearance = com.auralis.music.ui.theme.LocalAppearanceSettings.current
    val isCuratedPalette = remember(appearance.colorPalette) {
        com.auralis.music.ui.theme.CuratedPalettes.any { it.id == appearance.colorPalette }
    }
    val isDynamicAccent = !isCuratedPalette && (
        appearance.colorPalette == "Dynamic" ||
        appearance.colorPalette == "Dynamic (Material You)" ||
        appearance.dynamicTheme
    )

    // Shared Dynamic Artwork Palette via ArtworkPaletteCache derived from active carousel track
    val sharedPalette by com.auralis.music.ui.theme.ArtworkPaletteCache.currentPalette.collectAsState()
    var lastValidPalette by remember { mutableStateOf(com.auralis.music.ui.theme.ArtworkPaletteCache.currentPalette.value) }

    var activeTrackPalette by remember(activeTrack.id) {
        mutableStateOf(
            com.auralis.music.ui.theme.ArtworkPaletteCache.getOrCreatePalette(context, activeTrack)
        )
    }

    LaunchedEffect(activeTrack.id, activeTrack.thumbnail, isDynamicAccent) {
        if (isDynamicAccent) {
            val palette = com.auralis.music.ui.theme.ArtworkPaletteCache.extractPalette(
                context = context,
                key = activeTrack.id,
                artworkUrl = activeTrack.thumbnail
            )
            if (!palette.isDefault) {
                activeTrackPalette = palette
            }
        }
    }

    val extractedColors = remember(isDynamicAccent, activeTrackPalette, sharedPalette, appearance.colorPalette) {
        if (!isDynamicAccent) {
            // Static Accent Palette: artwork has ZERO effect on the background/theme
            val cur = com.auralis.music.ui.theme.CuratedPalettes.firstOrNull { it.id == appearance.colorPalette }
            if (cur != null) {
                com.auralis.music.ui.theme.ArtworkPalette(
                    primary = cur.primaryDark,
                    secondary = cur.secondaryDark,
                    tertiary = cur.tertiaryDark,
                    seedColor = cur.primaryDark,
                    isMonochrome = cur.id.contains("monochrome", ignoreCase = true),
                    glowColors = listOf(cur.primaryDark, cur.secondaryDark, cur.tertiaryDark)
                )
            } else {
                com.auralis.music.ui.theme.ArtworkPaletteCache.defaultPalette
            }
        } else {
            val candidate = activeTrackPalette.takeIf { !it.isDefault }
                ?: (if (com.auralis.music.ui.theme.ArtworkPaletteCache.isCurrentTrack(activeTrack.id) && !sharedPalette.isDefault) sharedPalette else null)
                ?: lastValidPalette.takeIf { !it.isDefault }
                ?: com.auralis.music.ui.theme.ArtworkPaletteCache.defaultPalette
            if (!candidate.isDefault) {
                lastValidPalette = candidate
            }
            candidate
        }
    }

    // Authoritative background state: strictly anchored to the committed activeTrack.
    // Partial/cancelled swipes move only the album card; the background remains stable
    // on the playing song and transitions exactly once upon a committed track change.
    val dynamicBgData = remember(
        activeTrack,
        extractedColors
    ) {
        DynamicBackgroundData(
            primaryArtworkUrl = activeTrack.thumbnail,
            secondaryArtworkUrl = null,
            swipeFraction = 0f,
            palette = extractedColors
        )
    }

    // Proactively pre-extract artwork palettes in parallel for current + upcoming queue tracks
    // so swiping or fast-skipping immediately hits memory cache with zero delay or color interruption.
    LaunchedEffect(currentTrackIndex, pagerState.currentPage, currentTab, queue, isDynamicAccent) {
        if (queue.isNotEmpty() && isDynamicAccent) {
            val cur = if (currentTab == NowPlayingTab.PLAYER) pagerState.currentPage else currentTrackIndex
            val nextTrack = queue.getOrNull(cur + 1)
            if (nextTrack != null) {
                val cached = com.auralis.music.ui.theme.ArtworkPaletteCache.getCached(nextTrack.id)
                if (cached == null || cached.isPlaceholder) {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        com.auralis.music.ui.theme.ArtworkPaletteCache.extractPalette(
                            context = context,
                            key = nextTrack.id,
                            artworkUrl = nextTrack.thumbnail
                        )
                    }
                }
            }
            // Delay remaining neighbor track extractions until after the background transition settles
            // to keep CPU and IO completely free during the transition animation
            kotlinx.coroutines.delay(350)
            val targets = listOf(-1, 2, 3).mapNotNull { offset -> queue.getOrNull(cur + offset) }.distinctBy { it.id }
            kotlinx.coroutines.coroutineScope {
                targets.forEach { targetTrack ->
                    launch(Dispatchers.IO) {
                        val cached = com.auralis.music.ui.theme.ArtworkPaletteCache.getCached(targetTrack.id)
                        if (cached == null || cached.isPlaceholder) {
                            com.auralis.music.ui.theme.ArtworkPaletteCache.extractPalette(
                                context = context,
                                key = targetTrack.id,
                                artworkUrl = targetTrack.thumbnail
                            )
                        }
                    }
                }
            }
        }
    }

    // Smooth continuous color interpolation executed on song/palette change, completely static while playing
    val colorSpec = tween<Color>(durationMillis = 320, easing = FastOutSlowInEasing)
    val animatedPrimaryColor by androidx.compose.animation.animateColorAsState(extractedColors.primary, colorSpec, label = "animPrimary")

    // Hoisted: transitionSpec is not a composable scope, so reduced-motion-aware
    // specs have to be built out here and captured.
    val favoriteEnter = auralisIconSwapEnter()
    val favoriteExit = auralisIconSwapExit()

    // Fast fade for secondary controls (buttons, scrubber, header) on collapse so
    // only the artwork and title/artist remain visible while contracting into the Mini Player.
    val controlsAlpha by animatedVisibilityScope?.transition?.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(durationMillis = 200, easing = AuralisEasing.Decelerate)
            } else {
                tween(durationMillis = PlayerMotion.ControlsExitDuration, easing = AuralisEasing.Standard)
            }
        },
        label = "nowPlayingControlsAlpha"
    ) { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    } ?: remember { mutableFloatStateOf(1f) }

    val playerBgStyle = remember(appearance.playerBackgroundStyle) {
        val style = PlayerBackgroundStyle.fromKey(appearance.playerBackgroundStyle)
        if (style == PlayerBackgroundStyle.APPLE_MUSIC) PlayerBackgroundStyle.GRADIENT else style
    }

    // Vibrant gradient palette derived from artwork colors
    val fullGradStops = remember(extractedColors) {
        PlayerGradientPalette.create(
            primary = extractedColors.primary,
            secondary = extractedColors.secondary,
            tertiary = extractedColors.tertiary,
            isMonochrome = extractedColors.isMonochrome
        )
    }

    val buttonTint = when (appearance.playerButtonColors) {
        "Accent Color" -> Color(0xFFEBA671)
        "Dynamic Artwork Vibrant" -> animatedPrimaryColor
        "Monochrome" -> Color(0xFFE0E0E0)
        else -> Color.White
    }

    val playPillBg = when (appearance.playerButtonColors) {
        "Accent Color" -> Color(0xFFEBA671)
        "Dynamic Artwork Vibrant" -> animatedPrimaryColor
        "Monochrome" -> Color(0xFFE0E0E0)
        else -> Color.White
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                clip = true
            }
            .then(
                if (renderBackground) {
                    Modifier.background(
                        when (playerBgStyle) {
                            PlayerBackgroundStyle.FOLLOW_THEME -> MaterialTheme.dynamicBackground
                            else -> Color(0xFF050505)
                        }
                    )
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {} // Intercept all clicks on empty player space to prevent bleed-through to underlying screens
            )
    ) {
        // ====================================================================
        // 1. DYNAMIC BACKGROUND RENDERING (Follow theme, Gradient, Blur, Glow Motion, Apple Music, Live Mesh)
        // ====================================================================
        if (renderBackground) {
            PlayerBackground(
                style = playerBgStyle,
                artworkUrl = dynamicBgData.primaryArtworkUrl,
                extractedColors = dynamicBgData.palette,
                secondaryArtworkUrl = dynamicBgData.secondaryArtworkUrl,
                swipeFraction = dynamicBgData.swipeFraction,
                modifier = Modifier.fillMaxSize(),
                isMiniPlayer = false,
                isPlaying = uiState.isPlaying
            )
        }

        // ====================================================================
        // 2. FOREGROUND CONTENT (MODERN VS CLASSIC DESIGN)
        // ====================================================================
        if (appearance.newPlayerDesign) {
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
                    .graphicsLayer { alpha = controlsAlpha }
                    .padding(top = 6.dp, bottom = 6.dp),
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

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                ) {
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
                        text = activeTrack.title,
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
                    .graphicsLayer { alpha = controlsAlpha }
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
                            .graphicsLayer { alpha = controlsAlpha }
                    ) {
                        SyncedLyricsView(
                            lyrics = uiState.lyrics,
                            positionState = lyricsPositionState,
                            onSeekTo = { posMs ->
                                onSeekTo(posMs)
                                if (!uiState.isPlaying) {
                                    onPlayPauseClick()
                                }
                            },
                            isLoading = uiState.isLoadingLyrics,
                            lyricsMode = com.auralis.music.domain.model.LyricsMode.CINEMA,
                            offsetMs = uiState.lyricsOffsetMs,
                            onOffsetChange = onLyricsOffsetChange,
                            onSearchManually = { showManualLyricsSearch = true },
                            track = uiState.currentTrack,
                            lyricsClockSource = lyricsClockSource,
                            isPlaying = uiState.isPlaying,
                            isBuffering = uiState.isBuffering,
                            audioLeadingSilenceMs = uiState.audioLeadingSilenceMs
                        )
                    }
                }

                // ============================================================
                // ≡♪ B. QUEUE VIEW
                // ============================================================
                NowPlayingTab.QUEUE -> {
                    // Hoist queue snapshot and index so item lambdas don't
                    // capture the entire uiState (avoids full-list recomposition
                    // when unrelated uiState fields change during playback).
                    val queueSnapshot = uiState.queue
                    val queueCurrentIndex = uiState.currentIndex
                    val queueItemShape = remember { RoundedCornerShape(14.dp) }
                    val queueArtworkCorner = remember { 8.dp }
                    val inactiveRowBg = remember { Color.White.copy(alpha = 0.08f) }
                    val subtitleColor = remember { Color.White.copy(alpha = 0.6f) }
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

                    val localQueue = remember {
                        queueSnapshot.map { track ->
                            createQueueTrackItem(track)
                        }.toMutableStateList()
                    }

                    val queueListState = rememberLazyListState()
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
                        if (fromIndex in localQueue.indices && toIndex in localQueue.indices) {
                            localQueue.add(toIndex, localQueue.removeAt(fromIndex))
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        }
                    }

                    LaunchedEffect(queueSnapshot) {
                        syncLocalQueueWithSnapshot(localQueue, queueSnapshot, reorderableLazyListState.isAnyItemDragging)
                    }

                    // Pre-cache palettes for visible tracks in the Queue so selecting any song hits cache instantly
                    LaunchedEffect(queueListState, localQueue.size, isDynamicAccent) {
                        if (isDynamicAccent) {
                            snapshotFlow {
                                val info = queueListState.layoutInfo
                                val start = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                                val end = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                                (start..end).mapNotNull { localQueue.getOrNull(it)?.track }
                            }.distinctUntilChanged()
                                .collect { visibleTracks ->
                                    withContext(Dispatchers.IO) {
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
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = controlsAlpha }
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "Up Next (${localQueue.size} songs)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )

                            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                LazyColumn(
                                    state = queueListState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(
                                        items = localQueue,
                                        key = { it.instanceId },
                                        contentType = { "queue_track" }
                                    ) { item ->
                                        val isCurrent = item.track.id == uiState.currentTrack?.id
                                        val primaryColor = MaterialTheme.colorScheme.primary

                                        ReorderableItem(
                                            state = reorderableLazyListState,
                                            key = item.instanceId,
                                            animateItemModifier = Modifier.animateItem(
                                                fadeInSpec = null,
                                                placementSpec = PlayerTransitionMotion.queuePlacement,
                                                fadeOutSpec = null
                                            )
                                        ) { isDragging ->
                                            val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp)
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .shadow(elevation, queueItemShape)
                                                    .clip(queueItemShape)
                                                    .background(
                                                        if (isDragging) {
                                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f)
                                                        } else if (isCurrent) {
                                                            primaryColor.copy(alpha = 0.20f)
                                                        } else {
                                                            inactiveRowBg
                                                        }
                                                    )
                                                    .then(
                                                        if (isDragging) {
                                                            Modifier.border(1.5.dp, primaryColor, queueItemShape)
                                                        } else {
                                                            Modifier
                                                        }
                                                    )
                                                    .clickable(enabled = !reorderableLazyListState.isAnyItemDragging && (System.currentTimeMillis() - lastDragEndTime > 450L)) {
                                                        if (System.currentTimeMillis() - lastDragEndTime <= 450L) return@clickable
                                                        val actualIndex = queueSnapshot.indexOfFirst { it.id == item.track.id }.takeIf { it >= 0 } ?: localQueue.indexOfFirst { it.instanceId == item.instanceId }
                                                        if (actualIndex >= 0) {
                                                            skipPagerAnimation = true
                                                            com.auralis.music.ui.theme.ArtworkPaletteCache.updateForTrack(context, item.track)
                                                            onSelectQueueTrack(actualIndex)
                                                            currentTab = NowPlayingTab.PLAYER
                                                        }
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                ArtworkCard(
                                                    url = item.track.thumbnail,
                                                    modifier = Modifier.size(44.dp),
                                                    cornerRadius = queueArtworkCorner,
                                                    contentDescription = item.track.title
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = item.track.title,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = if (isCurrent || isDragging) FontWeight.Bold else FontWeight.SemiBold,
                                                        color = if (isCurrent || isDragging) primaryColor else Color.White,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = item.track.artist,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = subtitleColor,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                if (isCurrent) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                                        contentDescription = "Playing",
                                                        tint = primaryColor,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                }

                                                // Drag Handle (comfortable 48dp hit area, visual grip)
                                                Box(
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .draggableHandle(
                                                            onDragStarted = {
                                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                                startDragIndex = localQueue.indexOfFirst { it.instanceId == item.instanceId }
                                                            },
                                                            onDragStopped = {
                                                                lastDragEndTime = System.currentTimeMillis()
                                                                val finalIdx = localQueue.indexOfFirst { it.instanceId == item.instanceId }
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
                                                        contentDescription = "Drag to reorder song",
                                                        tint = if (isDragging) primaryColor else Color.White.copy(alpha = 0.50f),
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                            }
                                        }
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
                            // Main Album Artwork Carousel (Native Jetpack Compose Horizontal Pager)
                            HorizontalPager(
                                state = pagerState,
                                key = { page -> queue.getOrNull(page)?.id ?: page },
                                userScrollEnabled = appearance.enableSwipeToChangeSong,
                                beyondViewportPageCount = 1,
                                flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                                    state = pagerState,
                                    snapPositionalThreshold = 0.35f
                                ),
                                modifier = Modifier
                                    .fillMaxWidth(0.98f)
                                    .aspectRatio(1f)
                                    .graphicsLayer {
                                        shadowElevation = 32.dp.toPx()
                                        shape = RoundedCornerShape(28.dp)
                                        clip = true
                                        ambientShadowColor = animatedPrimaryColor
                                        spotShadowColor = animatedPrimaryColor
                                    }
                            ) { page ->
                                val isCurrentPage = page == pagerState.currentPage
                                val pageTrack = if (queue.isNotEmpty() && page in queue.indices) {
                                    val qTrack = queue[page]
                                    if (qTrack.id == activeTrack.id) activeTrack else qTrack
                                } else {
                                    activeTrack
                                }
                                // Only the playing page claims the shared key — the pager
                                // keeps neighbours composed off-screen and two live layouts
                                // holding one key at once is undefined.
                                if (appearance.hidePlayerThumbnail) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.radialGradient(
                                                    listOf(
                                                        Color(0xFFEBA671).copy(alpha = 0.35f),
                                                        Color(0xFF221E1A),
                                                        Color(0xFF141210)
                                                    )
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = Color(0xFFEBA671),
                                            modifier = Modifier.size(68.dp)
                                        )
                                    }
                                } else {
                                    val isSharedArtworkActive = (page == (pendingTargetIndex ?: currentTrackIndex) && pageTrack.id == activeTrack.id)
                                    key(pageTrack.id) {
                                        ArtworkCard(
                                            url = pageTrack.thumbnail,
                                            fallbackTrack = pageTrack,
                                            modifier = playerSharedArtwork(
                                                sharedTransitionScope = if (isSharedArtworkActive) sharedTransitionScope else null,
                                                animatedVisibilityScope = if (isSharedArtworkActive) animatedVisibilityScope else null,
                                                enabled = isSharedArtworkActive
                                            ).fillMaxSize(),
                                            cornerRadius = playerArtworkCorner(
                                                animatedVisibilityScope = if (isCurrentPage) animatedVisibilityScope else null,
                                                expanded = true
                                            ),
                                            elevation = 0.dp,
                                            contentDescription = pageTrack.title,
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            highRes = true,
                                            // Smooth crossfade if artwork is loading asynchronously
                                            crossfade = true
                                        )
                                    }
                                }
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
                                    targetState = activeTrack,
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
                                        val targetIdx = queue.indexOfFirst { it.id == targetState.id }
                                        val initialIdx = queue.indexOfFirst { it.id == initialState.id }
                                        val isForward = if (targetIdx >= 0 && initialIdx >= 0) targetIdx >= initialIdx else true
                                        if (isForward) {
                                            (androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200)) +
                                                    androidx.compose.animation.slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 3 })
                                                .togetherWith(
                                                    androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(150)) +
                                                            androidx.compose.animation.slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 3 }
                                                )
                                        } else {
                                            (androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(200)) +
                                                    androidx.compose.animation.slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 3 })
                                                .togetherWith(
                                                    androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(150)) +
                                                            androidx.compose.animation.slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 3 }
                                                )
                                        }
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

                            Spacer(modifier = Modifier.width(8.dp))

                            // ── TRI-SEGMENTED CAPSULE PILL (PLAYLIST + DOWNLOAD + LIKE) ──
                            val downloadedIds by com.auralis.music.data.download.AuralisDownloadManager.downloadedTrackIds.collectAsState()
                            val isDownloaded = track.id in downloadedIds
                            val activeDownloads by com.auralis.music.data.download.AuralisDownloadManager.activeDownloads.collectAsState()
                            val isDownloading = track.id in activeDownloads

                            Row(
                                modifier = Modifier
                                    .height(38.dp)
                                    .graphicsLayer { alpha = controlsAlpha },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Left Segment: Add to Playlist (Pill curved on left)
                                Box(
                                    modifier = Modifier
                                        .size(width = 38.dp, height = 38.dp)
                                        .clip(RoundedCornerShape(topStart = 19.dp, bottomStart = 19.dp, topEnd = 5.dp, bottomEnd = 5.dp))
                                        .background(Color.White)
                                        .tactileBounce(scaleDown = 0.86f, onClick = { showPlaylistPicker = true }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                        contentDescription = "Add to Playlist",
                                        tint = Color.Black,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(3.dp))

                                // 2. Middle Segment: Download Button (Permanently visible)
                                Box(
                                    modifier = Modifier
                                        .size(width = 38.dp, height = 38.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color.White)
                                        .tactileBounce(
                                            scaleDown = 0.86f,
                                            onClick = {
                                                if (isDownloaded) {
                                                    com.auralis.music.data.download.AuralisDownloadManager.removeDownload(track.id)
                                                    Toast.makeText(context, "Download removed", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    com.auralis.music.data.download.AuralisDownloadManager.downloadTrack(track)
                                                    Toast.makeText(context, "Downloading song...", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AnimatedContent(
                                        targetState = if (isDownloading) 1 else if (isDownloaded) 2 else 0,
                                        transitionSpec = { favoriteEnter togetherWith favoriteExit },
                                        label = "nowPlayingDownload"
                                    ) { state ->
                                        when (state) {
                                            1 -> CircularProgressIndicator(
                                                modifier = Modifier.size(15.dp),
                                                color = Color.Black,
                                                strokeWidth = 1.8.dp
                                            )
                                            2 -> Icon(
                                                imageVector = Icons.Default.DownloadDone,
                                                contentDescription = "Downloaded",
                                                tint = Color.Black,
                                                modifier = Modifier.size(19.dp)
                                            )
                                            else -> Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "Download Song",
                                                tint = Color.Black,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(3.dp))

                                // 3. Right Segment: Like Heart Button (Pill curved on right)
                                Box(
                                    modifier = Modifier
                                        .size(width = 38.dp, height = 38.dp)
                                        .clip(RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 19.dp, bottomEnd = 19.dp))
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
                                            tint = if (favorited) Color(0xFFFF4081) else Color.Black,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // ── TIME SCRUBBER SLIDER & TIMESTAMPS (SQUIGGLY WAVEFORM) ──
                        // Wrapped so the per-tick position read happens inside a
                        // leaf composable's own restart scope. Read here, it
                        // would invalidate this entire player tab every frame.
                        PositionSlider(
                            positionState = seekBarPositionState,
                            totalDurationMs = totalDurationMs,
                            isPlaying = uiState.isPlaying,
                            sliderStyle = appearance.playerSliderStyle,
                            onValueChange = { frac ->
                                isScrubbing = true
                                scrubPositionMs = frac * totalDurationMs
                            },
                            onValueChangeFinished = {
                                isScrubbing = false
                                onSeekTo(scrubPositionMs.toLong())
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = controlsAlpha }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // ── MAIN PLAYBACK CONTROLS (PREVIOUS, WIDE THICK WHITE PLAY/PAUSE PILL, NEXT) ──
                        Row(
                            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = controlsAlpha },
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
                                     .tactileBounce(scaleDown = 0.84f, onClick = handlePrevious),
                                 contentAlignment = Alignment.Center
                             ) {
                                 Icon(
                                     imageVector = Icons.Default.SkipPrevious,
                                     contentDescription = "Previous",
                                     tint = buttonTint,
                                     modifier = Modifier.size(30.dp)
                                 )
                             }

                             // Wide Thick Play/Pause Button Pill with Bouncy Feedback
                             Box(
                                 modifier = Modifier
                                     .height(66.dp)
                                     .weight(1f)
                                     .padding(horizontal = 14.dp)
                                     .shadow(
                                         elevation = 16.dp,
                                         shape = CircleShape,
                                         ambientColor = Color.Black.copy(alpha = 0.40f),
                                         spotColor = Color.Black.copy(alpha = 0.40f)
                                     )
                                     .clip(CircleShape)
                                     .background(playPillBg)
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
                                             tint = if (playPillBg == Color.White) Color.Black else Color.Black,
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
                                     .tactileBounce(scaleDown = 0.84f, onClick = handleNext),
                                 contentAlignment = Alignment.Center
                             ) {
                                 Icon(
                                     imageVector = Icons.Default.SkipNext,
                                     contentDescription = "Next",
                                     tint = buttonTint,
                                     modifier = Modifier.size(30.dp)
                                 )
                             }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // ── BOTTOM UTILITY BAR (LEFT CAPSULE PILL: SLEEP/SHUFFLE/REPEAT & RIGHT QUEUE BUTTON) ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = controlsAlpha }
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
                                    active = uiState.sleepTimerSeconds > 0 || uiState.isSleepTimerEndOfSong,
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
} else {
            ClassicPlayerContainer(
                track = activeTrack,
                uiState = uiState,
                currentTab = currentTab,
                onTabChange = { currentTab = it },
                pagerState = pagerState,
                queue = queue,
                currentTrackIndex = currentTrackIndex,
                seekBarPositionState = seekBarPositionState,
                totalDurationMs = totalDurationMs,
                isScrubbing = isScrubbing,
                onScrubbing = { scrubbing, posMs ->
                    isScrubbing = scrubbing
                    if (scrubbing) scrubPositionMs = posMs.toFloat()
                },
                onSeekTo = { posMs ->
                    isScrubbing = false
                    onSeekTo(posMs)
                },
                onPlayPauseClick = onPlayPauseClick,
                onNextClick = handleNext,
                onPreviousClick = handlePrevious,
                onToggleFavorite = onToggleFavorite,
                onDismiss = onDismiss,
                onSelectQueueTrack = onSelectQueueTrack,
                onReorderQueue = onReorderQueue,
                onShowTrackOptions = { showTrackOptions = true },
                onShowSleepDialog = { showSleepDialog = true },
                lyricsPositionState = lyricsPositionState,
                lyricsClockSource = lyricsClockSource,
                onLyricsOffsetChange = onLyricsOffsetChange,
                onSearchLyricsManually = { showManualLyricsSearch = true },
                controlsAlpha = controlsAlpha,
                enableSwipeToChangeSong = appearance.enableSwipeToChangeSong,
                hidePlayerThumbnail = appearance.hidePlayerThumbnail,
                cropAlbumArt = appearance.cropAlbumArt,
                sliderStyle = appearance.playerSliderStyle,
                onArtistClick = onArtistClick
            )
        }
    }

    // Direct Track Options Bottom Sheet
    if (showTrackOptions) {
        TrackOptionsMenu(
            track = track,
            isFavorite = uiState.isFavorite,
            userPlaylists = userPlaylists,
            onToggleFavorite = onToggleFavorite,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onAddToPlaylist = { playlist ->
                onAddToPlaylist(playlist.id, track)
                Toast.makeText(context, "Added to ${playlist.title}", Toast.LENGTH_SHORT).show()
            },
            onCreatePlaylistAndAdd = { title ->
                onCreatePlaylistAndAdd(title, track)
                Toast.makeText(context, "Created and added to $title", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showTrackOptions = false }
        )
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
            isEndOfSongActive = uiState.isSleepTimerEndOfSong,
            onSelectMinutes = { minutes ->
                onSleepTimerSelect(minutes)
                showSleepDialog = false
            },
            onDismiss = { showSleepDialog = false }
        )
    }

    // Manual Lyrics Search Modal
    if (showManualLyricsSearch) {
        ManualLyricsSearchModal(
            initialTitle = track.title,
            initialArtist = track.artist,
            onDismiss = { showManualLyricsSearch = false },
            onSearch = { customTitle, customArtist ->
                onSearchLyricsManually?.invoke(customTitle, customArtist)
            }
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
 * Isolates the per-frame position read in its own recomposition scope.
 *
 * `AuralisPlayerSlider` needs the position as a plain `Long`, so somebody has to
 * unwrap the [State]. Doing it here means the clock invalidates only this
 * function; doing it at the call site would invalidate every control on the
 * player tab, sixty times a second.
 */
@Composable
private fun PositionSlider(
    positionState: State<Long>,
    totalDurationMs: Long,
    isPlaying: Boolean,
    sliderStyle: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val posMs = positionState.value
    AuralisPlayerSlider(
        value = if (totalDurationMs > 0) (posMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) else 0f,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        isPlaying = isPlaying,
        currentPosMs = posMs,
        totalDurationMs = totalDurationMs,
        sliderStyle = sliderStyle,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
        thumbColor = Color.White,
        textColor = Color.White.copy(alpha = 0.70f),
        modifier = modifier
    )
}

/**
 * Compatibility alias for NowPlayingSheet
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingSheet(
    uiState: PlayerUiState,
    playbackPositionState: State<Long>,
    lyricsClockSource: com.auralis.music.data.service.PlaybackClockSource? = null,
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
    onReorderQueue: ((Int, Int) -> Unit)? = null,
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
        playbackPositionState = playbackPositionState,
        lyricsClockSource = lyricsClockSource,
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
        onReorderQueue = onReorderQueue,
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
    isEndOfSongActive: Boolean = false,
    onSelectMinutes: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMinutes by remember {
        mutableIntStateOf(
            if (currentSeconds > 0 && !isEndOfSongActive) {
                ((currentSeconds + 59) / 60).toInt().coerceIn(5, 120)
            } else 30
        )
    }
    var isEndOfSong by remember { mutableStateOf(isEndOfSongActive) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp))
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

                Spacer(modifier = Modifier.height(6.dp))

                // 2. Active status badge or duration readout
                if (currentSeconds > 0) {
                    val m = currentSeconds / 60
                    val s = currentSeconds % 60
                    val timerStatus = if (isEndOfSongActive) {
                        "Active: Stops at end of song (${m}:${String.format("%02d", s)})"
                    } else {
                        "Active: ${m}:${String.format("%02d", s)} remaining"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFD4E157).copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = timerStatus,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFD4E157),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                val durationText = if (isEndOfSong) {
                    "Stop at end of current song"
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

                Spacer(modifier = Modifier.height(20.dp))

                // 3. Quick Preset Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(15, 30, 45, 60).forEach { mins ->
                        val isSelected = !isEndOfSong && selectedMinutes == mins
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFFD4E157) else Color.White.copy(alpha = 0.08f))
                                .clickable {
                                    selectedMinutes = mins
                                    isEndOfSong = false
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${mins}m",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) Color.Black else Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 4. Custom Dotted Slider Track
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

                            // Inactive Track
                            drawRoundRect(
                                color = Color(0xFF353C24),
                                size = Size(w, h),
                                cornerRadius = CornerRadius(h / 2, h / 2)
                            )

                            // Active Track
                            if (activeWidth > 0f) {
                                drawRoundRect(
                                    color = Color(0xFFD4E157),
                                    size = Size(activeWidth, h),
                                    cornerRadius = CornerRadius(h / 2, h / 2)
                                )
                            }

                            // Dotted Tick Marks
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

                Spacer(modifier = Modifier.height(20.dp))

                // 5. "End of song" Quick Preset Pill
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
                        text = "End of current song",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isEndOfSong) Color.Black else Color.White
                    )
                }

                Spacer(modifier = Modifier.height(26.dp))

                // 6. Bottom Buttons (Reset, Cancel, Start)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            onSelectMinutes(0)
                            onDismiss()
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
                                    onSelectMinutes(-1)
                                } else {
                                    onSelectMinutes(selectedMinutes)
                                }
                                onDismiss()
                            }
                        ) {
                            Text(
                                text = "Start",
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



