package com.auralis.music.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.delay
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.ui.lyrics.renderers.MetroLyricsLine
import com.auralis.music.ui.theme.LocalReducedMotion
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.auralis.music.R
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsMode
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.AuralisPlayerSlider
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.lyrics.SyncedLyricsView
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import com.auralis.music.ui.viewmodel.LyricsProviderPicks
import com.auralis.music.ui.viewmodel.PlayerUiState
import kotlin.math.roundToInt

/** How much of the full-bleed sleeve, from its bottom edge, dissolves into the backdrop. */
private const val HERO_FADE_FRACTION = 0.42f

/**
 * How far the player sheet has slid open (0 = mini player, 1 = full screen), read in the
 * draw phase. The immersive player grows its cover out of the mini player's thumbnail
 * with it and fades everything else in behind.
 */
val LocalPlayerSheetProgress = androidx.compose.runtime.staticCompositionLocalOf<() -> Float> { { 1f } }

/** Size and inset of the mini player's thumbnail the cover grows out of. */
private val MINI_THUMB_SIZE = 48.dp
private val MINI_THUMB_INSET = 14.dp
private val SIDE_GUTTER = 28.dp
private val ACTION_SIZE = 44.dp
private val SKIP_GLYPH_SIZE = 53.dp
private val PLAY_GLYPH_SIZE = 74.dp
private val PLAY_TOUCH_SIZE = 92.dp
private val PLAY_TOUCH_SIZE_TIGHT = 78.dp
/** The expanded title starts no higher than this fraction of the sleeve (its faded foot). */
private const val TITLE_MIN_SLEEVE_FRACTION = 0.86f
private const val DECK_MAX_STEP = 1 + PlayerFit.MAX_STEP
private val HANDLE_STRIP_HEIGHT = 32.dp
/** Rows fade out over this band above the controls, and are fully hidden beneath them. */
private val CONTROLS_FADE_ABOVE = 36.dp
private val PREVIEW_LINE_HEIGHT = 60.dp
private val COMPACT_ART_SIZE = 54.dp
private val COMPACT_ART_CORNER = 6.dp

/**
 * Immersive Player:
 * Full-bleed artwork that dissolves into the backdrop, a live lyric line under the title,
 * hairline scrubber and volume bars, and one action row that swaps the player body between
 * the artwork, the lyrics and the queue.
 */
@Composable
fun ImmersivePlayerContainer(
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
    onRemoveQueueItem: ((Int) -> Unit)? = null,
    onShowTrackOptions: () -> Unit,
    onShowQueueTrackOptions: (Track) -> Unit = {},
    onShowSleepDialog: () -> Unit,
    onShowOutputPicker: () -> Unit,
    onOpenListenTogether: () -> Unit = {},
    lyricsProviders: List<LyricsProvider> = emptyList(),
    lyricsProviderPicks: LyricsProviderPicks = LyricsProviderPicks(),
    onPickLyricsProvider: (LyricsProvider) -> Unit = {},
    lyricsPositionState: State<Long>,
    lyricsClockSource: PlaybackClockSource?,
    onLyricsOffsetChange: (Long) -> Unit,
    onSearchLyricsManually: () -> Unit,
    controlsAlpha: Float = 1f,
    enableSwipeToChangeSong: Boolean = true,
    hidePlayerThumbnail: Boolean = false,
    cropAlbumArt: Boolean = true,
    sliderStyle: String = "Wavy",
    standardLyricsBlur: Boolean = false,
    onArtistClick: ((Artist) -> Unit)? = null,
    onAddToPlaylist: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Same tab motion as the classic player: the sleeve flies into the compact header on the
    // hero curve while the incoming body fades and glides up and the outgoing one fades out.
    val motion = rememberClassicPlayerMotion(currentTab, LocalReducedMotion.current)
    val sheetProgress = LocalPlayerSheetProgress.current
    val heroProgress = motion.compactHeaderProgress
    val compactActionsEnabled by remember(heroProgress) { derivedStateOf { heroProgress.value > 0.5f } }
    val heroAtRest by remember(heroProgress) { derivedStateOf { heroProgress.value <= 0.001f } }
    val expandedTitleShown by remember(heroProgress) {
        derivedStateOf { ClassicPlayerViewportMotion.expandedMetadataAlpha(heroProgress.value) > 0.001f }
    }

    var containerOriginInRoot by remember { mutableStateOf(Offset.Zero) }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    // How far the deck under the sleeve has tightened so the title stays on the sleeve's faded
    // foot instead of climbing over the cover: 1 = gaps, 2-4 = the shared PlayerFit text steps.
    // Only ever rises for one screen size / font scale / lyric state, so it cannot oscillate.
    val fontScale = density.fontScale
    var deckStep by remember(containerWidthPx, containerHeightPx, fontScale, uiState.showInlineLyrics) {
        mutableIntStateOf(0)
    }
    val deckTight = deckStep >= 1
    val playerFit = PlayerFit((deckStep - 1).coerceAtLeast(0))
    var compactArtOriginInRoot by remember { mutableStateOf<Offset?>(null) }
    var compactArtSizePx by remember { mutableIntStateOf(0) }
    var showLyricsProviders by remember { mutableStateOf(false) }

    // Playback controls on lyrics and the queue: same rules as the classic player.
    var controlsShown by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    var lyricsUserScrolling by remember { mutableStateOf(false) }
    var controlsHeightPx by remember { mutableIntStateOf(0) }
    val controlsHeightDp = with(density) { controlsHeightPx.toDp() }
    val controlsVisible = currentTab == NowPlayingTab.PLAYER || controlsShown
    val previewSpec = remember(playerFit.step) {
        PreviewLyricSpec(scale = playerFit.lyricsScale, maxLines = if (playerFit.step >= PlayerFit.MAX_STEP) 1 else 3)
    }
    val previewLineHeight = if (previewSpec.maxLines == 1) {
        with(density) { (BasePreviewLyricStyle.lineHeight * previewSpec.scale).toDp() } + 10.dp
    } else PREVIEW_LINE_HEIGHT
    val previewHeight by animateDpAsState(
        targetValue = if (currentTab == NowPlayingTab.LYRICS || uiState.showInlineLyrics) previewLineHeight
            else if (deckTight) 12.dp else 24.dp,
        animationSpec = if (LocalReducedMotion.current) snap() else tween(250, easing = FastOutSlowInEasing),
        label = "immersivePreviewHeight"
    )
    val controlsReveal = animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(
            if (controlsVisible) ClassicPlayerViewportMotion.ControlsEnterDurationMillis
            else ClassicPlayerViewportMotion.ControlsExitDurationMillis,
            easing = FastOutSlowInEasing
        ),
        label = "immersiveControlsReveal"
    )
    LaunchedEffect(currentTab) {
        controlsShown = true
        controlsInteraction++
    }
    LaunchedEffect(currentTab, controlsInteraction, controlsShown, isScrubbing, lyricsUserScrolling) {
        if (currentTab != NowPlayingTab.LYRICS || !controlsShown || isScrubbing || lyricsUserScrolling) return@LaunchedEffect
        delay(ClassicPlayerViewportMotion.LyricsControlsTimeoutMillis)
        controlsShown = false
    }
    val lyricsScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (available.y < -2f) {
                        controlsShown = false
                        lyricsUserScrolling = true
                    } else if (available.y > 2f) {
                        controlsShown = true
                        lyricsUserScrolling = true
                        controlsInteraction++
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                lyricsUserScrolling = false
                controlsInteraction++
                return Velocity.Zero
            }
        }
    }
    var pendingProviderPick by remember { mutableStateOf<LyricsProvider?>(null) }
    // The sheet closes once the source the user picked is the one on screen.
    val shownProvider = uiState.lyrics?.provider
    LaunchedEffect(shownProvider) {
        if (pendingProviderPick != null && pendingProviderPick == shownProvider) {
            pendingProviderPick = null
            showLyricsProviders = false
        }
    }
    // Hoisted so the queue keeps its scroll position across tab switches.
    val queueListState = rememberLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                containerOriginInRoot = it.positionInRoot()
                containerWidthPx = it.size.width
                containerHeightPx = it.size.height
            }
    ) {
        // The cover's colours carried on below it; the seam rides the cover's bottom edge.
        ImmersiveColorField(
            artworkUrl = track.thumbnail,
            seamPx = {
                if (hidePlayerThumbnail) 0f
                else containerWidthPx * (1f - heroProgress.value.coerceIn(0f, 1f))
            },
            modifier = Modifier.graphicsLayer { alpha = sheetBackdropAlpha(sheetProgress()) }
        )

        // ── FULL-BLEED SLEEVE AT REST (runs up behind the status bar, swipes between songs) ──
        if (heroAtRest && !hidePlayerThumbnail) {
            HorizontalPager(
                state = pagerState,
                key = { page -> "${queue.getOrNull(page)?.id.orEmpty()}_$page" },
                userScrollEnabled = enableSwipeToChangeSong && currentTab == NowPlayingTab.PLAYER,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        val p = sheetProgress().coerceIn(0f, 1f)
                        if (p < 1f) {
                            val thumb = MINI_THUMB_SIZE.toPx() / size.width.coerceAtLeast(1f)
                            val grow = FastOutSlowInEasing.transform(p)
                            val s = thumb + (1f - thumb) * grow
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = s
                            scaleY = s
                            translationX = MINI_THUMB_INSET.toPx() * (1f - grow)
                            translationY = 8.dp.toPx() * (1f - grow)
                            shape = RoundedCornerShape((8.dp.toPx() / s) * (1f - grow))
                            clip = true
                        }
                    }
                    // A plain square while it is small; the banner's fade only once it is open.
                    .heroFadeMask { 1f - sheetProgress().coerceIn(0f, 1f) }
            ) { page ->
                val pageTrack = queue.getOrNull(page)?.let { if (it.id == track.id) track else it } ?: track
                ArtworkCard(
                    url = pageTrack.thumbnail,
                    fallbackTrack = pageTrack,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 0.dp,
                    elevation = 0.dp,
                    contentDescription = pageTrack.title,
                    contentScale = if (cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                    highRes = true,
                    crossfade = true
                )
            }
        }

        // Status-bar scrim so the clock and icons read over a white sleeve.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Behind the status bar and the handle strip, so both read on a white cover.
                .windowInsetsTopHeight(WindowInsets.statusBars.add(WindowInsets(top = HANDLE_STRIP_HEIGHT + 20.dp)))
                .graphicsLayer { alpha = (1f - 0.6f * heroProgress.value) * sheetChromeAlpha(sheetProgress()) }
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.40f), Color.Black.copy(alpha = 0.18f), Color.Transparent)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = sheetChromeAlpha(sheetProgress()) }
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            ImmersiveHandleStrip(
                originText = str(
                    R.string.playing_from_x,
                    uiState.queueSourceTitle?.takeIf { it.isNotBlank() }
                        ?: track.album?.takeIf { it.isNotBlank() }
                        ?: str(R.string.queue)
                ),
                heroProgress = heroProgress,
                onDismiss = onDismiss,
                modifier = Modifier.graphicsLayer { alpha = controlsAlpha }
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Player body: the large title fades out as soon as the cover starts to move.
                if (expandedTitleShown) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = SIDE_GUTTER, end = SIDE_GUTTER, bottom = controlsHeightDp)
                            .graphicsLayer {
                                alpha = controlsAlpha * ClassicPlayerViewportMotion.expandedMetadataAlpha(heroProgress.value)
                            }
                    ) {
                        Spacer(Modifier.weight(1f))
                        ImmersiveTitleBlock(
                            track = track,
                            fit = playerFit,
                            onShowTrackOptions = onShowTrackOptions,
                            onArtistClick = onArtistClick,
                            modifier = Modifier.onGloballyPositioned { coords ->
                                // The title may sit on the sleeve's faded foot, not over the cover
                                // itself. Measured only at rest on the player, where the deck is up.
                                if (!heroAtRest || currentTab != NowPlayingTab.PLAYER || controlsHeightPx == 0 ||
                                    containerWidthPx == 0 || hidePlayerThumbnail
                                ) return@onGloballyPositioned
                                val titleTop = coords.positionInRoot().y - containerOriginInRoot.y
                                val composedStep = deckStep
                                if (titleTop < containerWidthPx * TITLE_MIN_SLEEVE_FRACTION &&
                                    composedStep < DECK_MAX_STEP
                                ) deckStep = composedStep + 1
                            }
                        )
                        Spacer(Modifier.height(if (deckTight) 8.dp else 18.dp))
                    }
                }

                val lyricsLayerShown by motion.showLyricsLayer
                val queueLayerShown by motion.showQueueLayer
                if (lyricsLayerShown || queueLayerShown || !heroAtRest) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = controlsAlpha }
                    ) {
                        ImmersiveCompactHeader(
                            track = track,
                            heroProgress = heroProgress,
                            enabled = compactActionsEnabled,
                            drawOwnArtwork = hidePlayerThumbnail,
                            onArtworkPositioned = { origin, sizePx ->
                                compactArtOriginInRoot = origin
                                compactArtSizePx = sizePx
                            },
                            onClick = { onTabChange(NowPlayingTab.PLAYER) },
                            onShowTrackOptions = onShowTrackOptions
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clipToBounds()
                                // Lyrics and the queue run on under the controls; while those are up
                                // the rows fade out where the panel starts and stay hidden beneath it.
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    val panel = controlsHeightPx.toFloat()
                                    val reveal = controlsReveal.value
                                    if (reveal > 0.001f && panel > 0f) {
                                        val top = size.height - panel
                                        val fade = CONTROLS_FADE_ABOVE.toPx()
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - reveal)),
                                                startY = top - fade,
                                                endY = top
                                            ),
                                            blendMode = BlendMode.DstIn
                                        )
                                    }
                                }
                        ) {
                            if (lyricsLayerShown) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(if (currentTab == NowPlayingTab.LYRICS) 1f else 0f)
                                        .graphicsLayer {
                                            alpha = motion.lyricsAlpha.value
                                            translationY = motion.lyricsTranslationYDp.value.dp.toPx()
                                        }
                                        .padding(horizontal = SIDE_GUTTER - 8.dp)
                                        .nestedScroll(lyricsScrollConnection)
                                        .pointerInput(Unit) {
                                            detectTapGestures(onTap = {
                                                controlsShown = !controlsShown
                                                if (controlsShown) controlsInteraction++
                                            })
                                        }
                                ) {
                                    SyncedLyricsView(
                                        lyrics = uiState.lyrics,
                                        positionState = lyricsPositionState,
                                        onSeekTo = { posMs ->
                                            controlsShown = true
                                            controlsInteraction++
                                            onSeekTo(posMs)
                                            if (!uiState.isPlaying) onPlayPauseClick()
                                        },
                                        isLoading = uiState.isLoadingLyrics,
                                        lyricsMode = LyricsMode.CINEMA,
                                        offsetMs = uiState.lyricsOffsetMs,
                                        onOffsetChange = onLyricsOffsetChange,
                                        onSearchManually = onSearchLyricsManually,
                                        track = uiState.currentTrack,
                                        lyricsClockSource = lyricsClockSource,
                                        isPlaying = uiState.isPlaying,
                                        isBuffering = uiState.isBuffering,
                                        audioLeadingSilenceMs = uiState.audioLeadingSilenceMs
                                    )
                                }
                            }
                            if (queueLayerShown) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(if (currentTab == NowPlayingTab.QUEUE) 1f else 0f)
                                        .graphicsLayer {
                                            alpha = motion.queueAlpha.value
                                            translationY = motion.queueTranslationYDp.value.dp.toPx()
                                        }
                                ) {
                                    // The classic player's queue, whole: History, Continue Playing,
                                    // lock-to-reorder and its quick tiles. The deck below stays the
                                    // transport, so the queue's own controls are left out.
                                    ClassicQueueContent(
                                        track = track,
                                        uiState = uiState,
                                        queue = queue,
                                        onSelectQueueTrack = onSelectQueueTrack,
                                        onReorderQueue = onReorderQueue,
                                        onRemoveQueueItem = onRemoveQueueItem,
                                        onShowTrackOptions = onShowQueueTrackOptions,
                                        onToggleShuffle = onToggleShuffle,
                                        onToggleRepeat = onToggleRepeat,
                                        onQueueControlsVisibilityChange = { visible ->
                                            if (currentTab == NowPlayingTab.QUEUE) controlsShown = visible
                                        },
                                        onCloseQueue = { onTabChange(NowPlayingTab.PLAYER) },
                                        onShowOutputPicker = onShowOutputPicker,
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
                                        controlsAlpha = 1f,
                                        sliderStyle = sliderStyle,
                                        showHeader = false,
                                        applyStatusBarPadding = false,
                                        showBottomBar = false,
                                        showPlaybackControls = false,
                                        queueListState = queueListState,
                                        listBottomPadding = controlsHeightDp + CONTROLS_FADE_ABOVE,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }

                // ── PLAYBACK CONTROLS: always up on the player; on lyrics and the queue they hide
                // and come back the way the classic player's do (idle, scroll direction, tap). ──
                androidx.compose.animation.AnimatedVisibility(
                    visible = controlsVisible,
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
                            // Keep the last measured height, so nothing above resizes as they hide.
                            .onSizeChanged { if (it.height > 0) controlsHeightPx = it.height }
                            .padding(horizontal = SIDE_GUTTER)
                            .graphicsLayer { alpha = controlsAlpha }
                            // Any touch on the controls restarts the idle timer, without taking the touch.
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.any { it.changedToDown() }) controlsInteraction++
                                    }
                                }
                            }
                    ) {
                        AnimatedContent(
                            targetState = currentTab == NowPlayingTab.LYRICS,
                            transitionSpec = { fadeIn(tween(220, delayMillis = 80)) togetherWith fadeOut(tween(160)) },
                            contentAlignment = Alignment.CenterStart,
                            label = "immersiveDeckCaption",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(previewHeight)
                        ) { lyricsOpen ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                CompositionLocalProvider(LocalPreviewLyricSpec provides previewSpec) {
                                if (lyricsOpen) {
                                    LyricsSourceCaption(lyrics = uiState.lyrics, onChange = { showLyricsProviders = true })
                                } else if (uiState.showInlineLyrics) {
                                    LyricPreviewLine(
                                        lyrics = uiState.lyrics,
                                        isLoading = uiState.isLoadingLyrics,
                                        trackId = track.id,
                                        positionState = lyricsPositionState,
                                        offsetMs = uiState.lyricsOffsetMs,
                                        isPlaying = uiState.isPlaying,
                                        isBuffering = uiState.isBuffering,
                                        onClick = { onTabChange(NowPlayingTab.LYRICS) }
                                    )
                                }
                                }
                            }
                        }

                        ImmersiveScrubber(
                            positionState = seekBarPositionState,
                            durationMs = totalDurationMs,
                            isPlaying = uiState.isPlaying,
                            sliderStyle = sliderStyle,
                            onScrubbing = onScrubbing,
                            onSeekTo = onSeekTo
                        )

                        Spacer(Modifier.height(if (deckTight) 6.dp else 18.dp))

                        ImmersiveTransportRow(
                            isPlaying = uiState.isPlaying,
                            isLoading = uiState.isBuffering && !uiState.isPlaying,
                            onPrevious = onPreviousClick,
                            onPlayPause = onPlayPauseClick,
                            onNext = onNextClick,
                            // Same 74dp glyph; only the invisible touch padding around it shrinks.
                            playTouchSize = if (deckTight) PLAY_TOUCH_SIZE_TIGHT else PLAY_TOUCH_SIZE
                        )

                        Spacer(Modifier.height(if (deckTight) 8.dp else 20.dp))

                        ImmersiveVolumeRow()

                        Spacer(Modifier.height(if (deckTight) 4.dp else 10.dp))
                    }
                }
            }

            ImmersiveActionRow(
                lyricsOpen = currentTab == NowPlayingTab.LYRICS,
                queueOpen = currentTab == NowPlayingTab.QUEUE,
                onToggleLyrics = {
                    onTabChange(if (currentTab == NowPlayingTab.LYRICS) NowPlayingTab.PLAYER else NowPlayingTab.LYRICS)
                },
                onToggleQueue = {
                    onTabChange(if (currentTab == NowPlayingTab.QUEUE) NowPlayingTab.PLAYER else NowPlayingTab.QUEUE)
                },
                modifier = Modifier
                    .padding(horizontal = SIDE_GUTTER)
                    .graphicsLayer { alpha = controlsAlpha }
            )

            // Room the reference keeps under the row (its output caption sits here); the first
            // thing a short screen gives back.
            Spacer(Modifier.height(if (deckTight) 12.dp else 44.dp))
        }

        if (showLyricsProviders) {
            val trackId = uiState.currentTrack?.id
            LyricsProviderSheet(
                providers = lyricsProviders,
                currentProvider = shownProvider?.takeIf { uiState.lyrics?.lines?.isNotEmpty() == true },
                currentSyncType = uiState.lyrics?.syncType,
                status = if (lyricsProviderPicks.trackId == trackId) lyricsProviderPicks.status else emptyMap(),
                syncTypes = if (lyricsProviderPicks.trackId == trackId) lyricsProviderPicks.syncTypes else emptyMap(),
                onPick = { provider ->
                    pendingProviderPick = provider
                    onPickLyricsProvider(provider)
                },
                onDismiss = {
                    pendingProviderPick = null
                    showLyricsProviders = false
                }
            )
        }

        // ── SLEEVE IN FLIGHT: one cover morphing between the full-bleed banner and the header ──
        val compactOrigin = compactArtOriginInRoot
        if (!heroAtRest && !hidePlayerThumbnail && containerWidthPx > 0 && compactOrigin != null && compactArtSizePx > 0) {
            val compactCornerPx = with(density) { COMPACT_ART_CORNER.toPx() }
            ArtworkCard(
                url = track.thumbnail,
                fallbackTrack = track,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .zIndex(4f)
                    .graphicsLayer {
                        val p = heroProgress.value.coerceIn(0f, 1f)
                        val endScale = compactArtSizePx.toFloat() / containerWidthPx
                        val s = 1f + (endScale - 1f) * p
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = s
                        scaleY = s
                        translationX = (compactOrigin.x - containerOriginInRoot.x) * p
                        translationY = (compactOrigin.y - containerOriginInRoot.y) * p
                        // The layer's shape is drawn before scaling, so divide out the scale.
                        shape = RoundedCornerShape((compactCornerPx / s) * p)
                        clip = true
                    }
                    .heroFadeMask { heroProgress.value },
                cornerRadius = 0.dp,
                elevation = 0.dp,
                contentDescription = track.title,
                contentScale = if (cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                highRes = true,
                crossfade = false
            )
        }
    }
}

/** The backdrop arrives with the sheet, ahead of the controls. */
private fun sheetBackdropAlpha(p: Float): Float = (p / 0.7f).coerceIn(0f, 1f)

/** Title, controls and chrome fade in over the second half of the slide, behind the cover. */
private fun sheetChromeAlpha(p: Float): Float = ((p - 0.35f) / 0.6f).coerceIn(0f, 1f)

/**
 * Dissolves the banner's lower edge into the backdrop. [solidity] firms that edge back up
 * (0 = full fade, 1 = none) as the cover shrinks into a plain thumbnail.
 */
private fun Modifier.heroFadeMask(solidity: () -> Float): Modifier = this
    .graphicsLayer {
        val firm = solidity().coerceIn(0f, 1f)
        compositingStrategy = if (firm < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val firm = solidity().coerceIn(0f, 1f)
        if (firm < 1f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Black.copy(alpha = firm)),
                    startY = size.height * (1f - HERO_FADE_FRACTION),
                    endY = size.height
                ),
                blendMode = BlendMode.DstIn
            )
        }
    }

/**
 * The grab handle and "Playing from …": the strip that pulls the player down. The caption
 * fades out as the cover flies into the compact header and the handle settles into the
 * middle of the emptied strip.
 */
@Composable
private fun ImmersiveHandleStrip(
    originText: String,
    heroProgress: State<Float>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val dismiss by rememberUpdatedState(onDismiss)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HANDLE_STRIP_HEIGHT)
            .pointerInput(Unit) {
                var dragged = 0f
                val threshold = with(density) { 56.dp.toPx() }
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged > threshold) dismiss() },
                    onVerticalDrag = { change, amount ->
                        dragged += amount
                        change.consume()
                    }
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset {
                    val p = heroProgress.value.coerceIn(0f, 1f)
                    val top = 6.dp.toPx()
                    val centred = (HANDLE_STRIP_HEIGHT - 5.dp).toPx() / 2f
                    IntOffset(0, (top + (centred - top) * p).roundToInt())
                }
                .width(38.dp)
                .height(5.dp)
                .shadow(2.dp, RoundedCornerShape(3.dp), clip = false)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.70f))
        )
        Text(
            text = originText,
            style = MaterialTheme.typography.labelSmall.copy(
                shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 1f), blurRadius = 4f)
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = SIDE_GUTTER, end = SIDE_GUTTER, bottom = 1.dp)
                .graphicsLayer { alpha = 1f - heroProgress.value.coerceIn(0f, 1f) }
        )
    }
}

@Composable
private fun ImmersiveTitleBlock(
    track: Track,
    fit: PlayerFit,
    onShowTrackOptions: () -> Unit,
    onArtistClick: ((Artist) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = Color.White,
                fontSize = 20.sp * fit.titleScale,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 18.sp * fit.artistScale,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onArtistClick?.invoke(Artist(id = "", name = track.artist, thumbnail = track.thumbnail))
                }
            )
        }
        Spacer(Modifier.width(12.dp))
        ImmersiveCircleGlyph(
            icon = Icons.Rounded.MoreHoriz,
            contentDescription = str(R.string.more_options),
            onClick = onShowTrackOptions
        )
    }
}

/**
 * Small sleeve, title and menu: where the artwork lands while lyrics or the queue are up.
 * The sleeve itself is the flying cover drawn by the container; this only reserves and
 * reports its slot, and fades the text in once the cover has mostly landed.
 */
@Composable
private fun ImmersiveCompactHeader(
    track: Track,
    heroProgress: State<Float>,
    enabled: Boolean,
    drawOwnArtwork: Boolean,
    onArtworkPositioned: (Offset, Int) -> Unit,
    onClick: () -> Unit,
    onShowTrackOptions: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SIDE_GUTTER, vertical = 8.dp)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(COMPACT_ART_SIZE)
                .onGloballyPositioned { onArtworkPositioned(it.positionInRoot(), it.size.width) }
        ) {
            if (drawOwnArtwork) {
                ArtworkCard(
                    url = track.thumbnail,
                    fallbackTrack = track,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = ClassicPlayerViewportMotion.compactMetadataAlpha(heroProgress.value) },
                    cornerRadius = COMPACT_ART_CORNER,
                    elevation = 0.dp,
                    contentDescription = track.title,
                    crossfade = true
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = ClassicPlayerViewportMotion.compactMetadataAlpha(heroProgress.value) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            ImmersiveCircleGlyph(
                icon = Icons.Rounded.MoreHoriz,
                contentDescription = str(R.string.more_options),
                enabled = enabled,
                onClick = onShowTrackOptions
            )
        }
    }
}

@Composable
private fun ImmersiveCircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

/**
 * What the strip under the title is showing. Lines carry their index; the rest are fillers.
 * A sung slot carries its own line: AnimatedContent keeps composing the outgoing slot after
 * the song (and so the lyrics list) has changed, so it must never index back into the list.
 */
private sealed interface PreviewSlot {
    data object Loading : PreviewSlot
    data object Unsynced : PreviewSlot
    data object Intro : PreviewSlot
    data class Break(val index: Int) : PreviewSlot
    data class Sung(val index: Int, val line: LyricLine) : PreviewSlot
}

/**
 * The line being sung, drawn by the MetroLyrics word renderer (per-letter sweep, lift and
 * glow). When the line changes, the old line drifts up and dissolves while the next one
 * rises into its place, and the trailing chevron glides to the new line's end.
 *
 * Between lines it is never blank: a loading line while lyrics are fetched, one of the
 * intro lines before the first sung line, "Instrumental" through a break, and a plain
 * "Lyrics" link when the lyrics have no timing.
 */
@Composable
internal fun LyricPreviewLine(
    lyrics: LyricsData?,
    isLoading: Boolean,
    trackId: String?,
    positionState: State<Long>,
    offsetMs: Long,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit
) {
    val syncType = lyrics?.syncType ?: SyncType.PLAIN
    val lines = remember(lyrics) {
        if (lyrics == null || lyrics.syncType == SyncType.PLAIN) emptyList()
        else lyrics.lines
            .map { WordTiming.splitMergedWordsInLine(it) }
            .filter { !it.isBackground && (it.isInstrumental || it.text.isNotBlank()) }
    }
    val firstSung = remember(lines) { lines.indexOfFirst { !it.isInstrumental } }
    // One filler per song, picked once so the clock never reshuffles it.
    val introText = remember(trackId) { fillerLine(R.array.lyrics_intro_lines) }
    val loadingText = remember(trackId) { fillerLine(R.array.lyrics_loading_lines) }
    val hasPlainText = lyrics != null && lines.isEmpty() && lyrics.lines.any { it.text.isNotBlank() }

    val slot by remember(lines, offsetMs, isLoading, hasPlainText, firstSung) {
        derivedStateOf {
            when {
                lines.isEmpty() && isLoading -> PreviewSlot.Loading
                lines.isEmpty() && hasPlainText -> PreviewSlot.Unsynced
                lines.isEmpty() -> null
                else -> {
                    val index = LyricsEngine.findActiveLyricIndex(lines, positionState.value, offsetMs)
                    val line = lines.getOrNull(index)
                    when {
                        line == null || (firstSung >= 0 && index < firstSung) -> PreviewSlot.Intro
                        line.isInstrumental -> PreviewSlot.Break(index)
                        else -> {
                            val next = lines.getOrNull(index + 1)
                            when (gapPhaseAfter(line, next, positionState.value + offsetMs)) {
                                GapPhase.Break -> PreviewSlot.Break(index)
                                // The break is ending: show the line about to be sung, never the
                                // one from before the break.
                                GapPhase.Upcoming -> if (next != null && !next.isInstrumental) PreviewSlot.Sung(index + 1, next)
                                    else PreviewSlot.Break(index)
                                GapPhase.None -> PreviewSlot.Sung(index, line)
                            }
                        }
                    }
                }
            }
        }
    }
    val current = slot ?: return

    key(trackId) {
    val reducedMotion = LocalReducedMotion.current
    val lineTransition = updateTransition(targetState = current, label = "previewWordMorph")
    val animatedLineIndex by lineTransition.animateFloat(
        transitionSpec = { if (reducedMotion) snap() else tween(620, easing = FastOutSlowInEasing) },
        label = "previewLineIndex"
    ) { state -> (state as? PreviewSlot.Sung)?.index?.toFloat() ?: -1f }
    val fromLine = lineTransition.currentState as? PreviewSlot.Sung
    val toLine = lineTransition.targetState as? PreviewSlot.Sung
    if (!reducedMotion && fromLine != null && toLine != null && fromLine.index != toLine.index) {
        val morphProgress = ((animatedLineIndex - fromLine.index) / (toLine.index - fromLine.index))
            .coerceIn(0f, 1f)
        Row(
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetroPreviewWordMorph(
                fromText = fromLine.line.text.trim(),
                toLine = toLine.line,
                progress = morphProgress,
                positionMs = positionState.value + offsetMs,
                hasWordTiming = syncType == SyncType.RICHSYNC
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = str(R.string.lyrics),
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(16.dp)
            )
        }
    } else AnimatedContent(
        targetState = current,
        transitionSpec = {
            val from = (initialState as? PreviewSlot.Sung)?.index ?: (initialState as? PreviewSlot.Break)?.index ?: -1
            val to = (targetState as? PreviewSlot.Sung)?.index ?: (targetState as? PreviewSlot.Break)?.index ?: -1
            val forward = to >= from
            val enter = slideInVertically(tween(560, delayMillis = 70, easing = FastOutSlowInEasing)) {
                if (forward) it else -it
            } + fadeIn(tween(420, delayMillis = 110, easing = LinearOutSlowInEasing)) +
                scaleIn(tween(560, delayMillis = 70, easing = FastOutSlowInEasing), initialScale = 0.94f, transformOrigin = TransformOrigin(0f, 0.5f))
            val exit = slideOutVertically(tween(520, easing = FastOutSlowInEasing)) {
                if (forward) -it else it
            } + fadeOut(tween(340, easing = FastOutLinearInEasing)) +
                scaleOut(tween(520, easing = FastOutSlowInEasing), targetScale = 0.94f, transformOrigin = TransformOrigin(0f, 0.5f))
            (enter togetherWith exit).using(SizeTransform(clip = false) { _, _ -> tween(520, easing = FastOutSlowInEasing) })
        },
        contentAlignment = Alignment.CenterStart,
        label = "immersiveLyricLine"
    ) { shown ->
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (shown) {
                is PreviewSlot.Sung -> MetroPreviewText(
                    line = shown.line,
                    syncType = syncType,
                    positionMs = { positionState.value + offsetMs },
                    isPlaying = isPlaying,
                    isBuffering = isBuffering
                )
                PreviewSlot.Loading -> FillerText(loadingText, withNote = false)
                PreviewSlot.Unsynced -> FillerText(str(R.string.lyrics), withNote = true)
                PreviewSlot.Intro -> FillerText(introText, withNote = true)
                is PreviewSlot.Break -> FillerText(str(R.string.instrumental_break), withNote = true)
            }
            if (shown != PreviewSlot.Loading) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = str(R.string.lyrics),
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
    }
}

private enum class GapPhase { None, Break, Upcoming }

/**
 * Where [positionMs] sits relative to a long gap after [line]: a guitar solo or outro the lyrics
 * don't mark as instrumental (the next line, or the song's end when [next] is null, is far off).
 * [GapPhase.Break] once the line has been sung; [GapPhase.Upcoming] in the last moment before
 * [next] starts, when the preview shows [next] early. Without this the preview kept the last
 * sung line through the whole break.
 */
private fun gapPhaseAfter(line: LyricLine, next: LyricLine?, positionMs: Long): GapPhase {
    val sungUntil = lineEndMs(line)
    val nextStart = next?.time ?: Long.MAX_VALUE
    if (nextStart - sungUntil < MIN_BREAK_MS) return GapPhase.None
    return when {
        // Hold the line a moment after it ends.
        positionMs < sungUntil + BREAK_ENTER_DELAY_MS -> GapPhase.None
        nextStart - positionMs > BREAK_EXIT_LEAD_MS -> GapPhase.Break
        else -> GapPhase.Upcoming
    }
}

/** When [line] stops being sung: its stated end, its last word's end, or an estimate from its length. */
private fun lineEndMs(line: LyricLine): Long {
    line.endTime?.takeIf { it > line.time }?.let { return it }
    val words = line.words.orEmpty()
    words.mapNotNull { it.endTime }.maxOrNull()?.takeIf { it > line.time }?.let { return it }
    words.lastOrNull()?.let { return it.time + 900L }
    val wordCount = line.text.split(' ').count { it.isNotBlank() }.coerceAtLeast(1)
    return line.time + (1_200L + wordCount * 450L).coerceIn(2_500L, 9_000L)
}

private const val MIN_BREAK_MS = 6_000L
private const val BREAK_ENTER_DELAY_MS = 1_000L
private const val BREAK_EXIT_LEAD_MS = 1_500L

private fun fillerLine(arrayRes: Int): String =
    runCatching {
        val arr = com.auralis.music.ui.i18n.AppLanguage.context().resources.getStringArray(arrayRes)
        if (arr.isNotEmpty()) arr.random() else ""
    }.getOrDefault("")

@Composable
private fun FillerText(text: String, withNote: Boolean) {
    val PreviewLyricStyle = previewLyricStyle()
    if (withNote) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
    }
    Text(
        text = text,
        style = PreviewLyricStyle.copy(color = Color.White.copy(alpha = 0.75f), fontWeight = FontWeight.Medium),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Lets a host with less room (the inline player preview) shrink and cap the preview line. */
internal class PreviewLyricSpec(val scale: Float = 1f, val maxLines: Int = 3)

internal val LocalPreviewLyricSpec = androidx.compose.runtime.compositionLocalOf { PreviewLyricSpec() }

@Composable
private fun previewLyricStyle(): TextStyle {
    val scale = LocalPreviewLyricSpec.current.scale
    return remember(scale) {
        if (scale == 1f) BasePreviewLyricStyle
        else BasePreviewLyricStyle.copy(
            fontSize = BasePreviewLyricStyle.fontSize * scale,
            lineHeight = BasePreviewLyricStyle.lineHeight * scale
        )
    }
}

internal val BasePreviewLyricStyle = TextStyle(
    fontSize = 17.sp,
    fontWeight = FontWeight.ExtraBold,
    lineHeight = 21.sp,
    letterSpacing = (-0.5).sp,
    textAlign = TextAlign.Left,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    )
)

/** One line through the Metro word renderer, sized to its own text so the chevron can follow it. */
@Composable
private fun MetroPreviewText(
    line: LyricLine,
    syncType: SyncType,
    positionMs: () -> Long,
    isPlaying: Boolean,
    isBuffering: Boolean
) {
    val text = line.text.trim()
    val PreviewLyricStyle = previewLyricStyle()
    val previewMaxLines = LocalPreviewLyricSpec.current.maxLines
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints {
        val availablePx = with(density) { (maxWidth - 22.dp).roundToPx() }.coerceAtLeast(1)
        val textWidthPx = remember(text, availablePx) {
            (measurer.measure(text, PreviewLyricStyle, softWrap = false).size.width + 2).coerceAtMost(availablePx)
        }
        Box(Modifier.width(with(density) { textWidthPx.toDp() })) {
            // The word renderer wraps freely; a one-line host gets the plain, ellipsized line.
            if (syncType == SyncType.RICHSYNC && !line.words.isNullOrEmpty() && previewMaxLines > 1) {
                MetroLyricsLine(
                    line = line,
                    words = line.words,
                    isActive = true,
                    distanceFromCurrent = 0,
                    effectivePlaybackPosition = positionMs(),
                    lineColor = Color.White.copy(alpha = 0.5f),
                    accentColor = Color.White,
                    textAlign = TextAlign.Left,
                    alignment = Alignment.Start,
                    fontSizeSp = PreviewLyricStyle.fontSize.value,
                    lineSpacingMultiplier = 1.22f,
                    isPlaying = isPlaying && !isBuffering
                )
            } else {
                Text(text = text, style = PreviewLyricStyle.copy(color = Color.White), maxLines = previewMaxLines, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private data class PreviewMorphWord(val text: String, val start: Int)

private fun previewMorphWords(text: String): List<PreviewMorphWord> =
    Regex("\\S+").findAll(text).map { PreviewMorphWord(it.value, it.range.first) }.toList()

private fun pairedWordIndex(index: Int, sourceCount: Int, targetCount: Int): Int {
    if (targetCount <= 1 || sourceCount <= 1) return 0
    return ((index.toFloat() / (sourceCount - 1)) * (targetCount - 1)).roundToInt()
        .coerceIn(0, targetCount - 1)
}

/** Moves each outgoing word toward its counterpart while the next line takes its shape. */
@Composable
private fun MetroPreviewWordMorph(
    fromText: String,
    toLine: LyricLine,
    progress: Float,
    positionMs: Long,
    hasWordTiming: Boolean
) {
    val toText = toLine.text.trim()
    val PreviewLyricStyle = previewLyricStyle()
    val previewMaxLines = LocalPreviewLyricSpec.current.maxLines
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fromWords = remember(fromText) { previewMorphWords(fromText) }
    val toWords = remember(toText) { previewMorphWords(toText) }
    val timedWords = if (hasWordTiming) toLine.words.orEmpty() else emptyList()
    if (fromWords.isEmpty() || toWords.isEmpty()) {
        Text(toText, style = PreviewLyricStyle.copy(color = Color.White), maxLines = previewMaxLines, overflow = TextOverflow.Ellipsis)
        return
    }
    BoxWithConstraints {
        val availablePx = with(density) { (maxWidth - 22.dp).roundToPx() }.coerceAtLeast(1)
        val widthPx = remember(fromText, toText, availablePx) {
            maxOf(
                textMeasurer.measure(fromText, PreviewLyricStyle, softWrap = false).size.width,
                textMeasurer.measure(toText, PreviewLyricStyle, softWrap = false).size.width
            ).coerceAtMost(availablePx).coerceAtLeast(1)
        }
        val fromLayout = remember(fromText, widthPx) {
            textMeasurer.measure(fromText, PreviewLyricStyle, constraints = Constraints(maxWidth = widthPx))
        }
        val toLayout = remember(toText, widthPx) {
            textMeasurer.measure(toText, PreviewLyricStyle, constraints = Constraints(maxWidth = widthPx))
        }
        val fromAnchors = remember(fromLayout, fromWords) {
            fromWords.map { fromLayout.getBoundingBox(it.start).topLeft }
        }
        val toAnchors = remember(toLayout, toWords) {
            toWords.map { toLayout.getBoundingBox(it.start).topLeft }
        }
        val fromGlyphs = remember(fromWords) {
            fromWords.map { textMeasurer.measure(it.text, PreviewLyricStyle, softWrap = false) }
        }
        val toGlyphs = remember(toWords) {
            toWords.map { textMeasurer.measure(it.text, PreviewLyricStyle, softWrap = false) }
        }
        val heightPx = maxOf(fromLayout.size.height, toLayout.size.height)
            .coerceAtMost(with(density) {
                minOf(58.dp.roundToPx(), (PreviewLyricStyle.lineHeight * previewMaxLines).roundToPx())
            })
        Canvas(Modifier.width(with(density) { widthPx.toDp() }).height(with(density) { heightPx.toDp() })) {
            val p = progress.coerceIn(0f, 1f)
            fromWords.indices.forEach { index ->
                val target = toAnchors[pairedWordIndex(index, fromWords.size, toWords.size)]
                val source = fromAnchors[index]
                val x = source.x + (target.x - source.x) * p
                val y = source.y + (target.y - source.y) * p
                withTransform({
                    translate(x, y)
                    scale(1f - 0.05f * p, 1f - 0.05f * p, pivot = Offset.Zero)
                }) {
                    drawText(fromGlyphs[index], color = Color.White.copy(alpha = 1f - p))
                }
            }
            toWords.indices.forEach { index ->
                val source = fromAnchors[pairedWordIndex(index, toWords.size, fromWords.size)]
                val target = toAnchors[index]
                val x = source.x + (target.x - source.x) * p
                val y = source.y + (target.y - source.y) * p
                // The Metro renderer starts unsung letters at 35% opacity. Match that
                // baseline during the morph, then reveal only words their timestamps
                // have reached so the renderer does not make the new line flash white.
                val incomingAlpha = if (timedWords.isEmpty()) 1f else {
                    val word = timedWords[pairedWordIndex(index, toWords.size, timedWords.size)]
                    val durationMs = (word.duration ?: 200L).coerceAtLeast(100L)
                    val sungProgress = ((positionMs - word.time).toFloat() / durationMs).coerceIn(0f, 1f)
                    val highlightReveal = ((p - 0.5f) * 2f).coerceIn(0f, 1f)
                    0.35f + 0.65f * sungProgress * highlightReveal
                }
                withTransform({
                    translate(x, y)
                    scale(0.95f + 0.05f * p, 0.95f + 0.05f * p, pivot = Offset.Zero)
                }) {
                    drawText(toGlyphs[index], color = Color.White.copy(alpha = p * incomingAlpha))
                }
            }
        }
    }
}

/** "Lyrics by <provider>  Change" — shown in the lyric line's place while the lyrics are up. */
@Composable
internal fun LyricsSourceCaption(lyrics: LyricsData?, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (lyrics != null) {
            Text(
                text = str(R.string.lyrics_by_x, lyrics.provider.label()),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = str(R.string.change),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onChange
            )
        )
    }
}

/**
 * A hairline bar that thickens while held. Dragging moves the value relative to where the
 * finger landed, so grabbing the bar never makes the value jump to the touch point.
 */
@Composable
private fun ImmersiveThinBar(
    value: () -> Float,
    label: String,
    onChange: (Float) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    idleHeight: Dp = 7.dp,
    activeHeight: Dp = 12.dp
) {
    var touching by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(
        targetValue = if (touching) activeHeight else idleHeight,
        animationSpec = tween(160),
        label = "immersiveBarThickness"
    )
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinish)
    val current by rememberUpdatedState(value)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(activeHeight + 22.dp)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value().coerceIn(0f, 1f), 0f..1f)
                if (enabled) setProgress { change(it.coerceIn(0f, 1f)); finish(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    touching = true
                    val width = size.width.coerceAtLeast(1).toFloat()
                    val start = current().coerceIn(0f, 1f)
                    var lastValue = start
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointer.pressed) break
                            lastValue = (start + (pointer.position.x - down.position.x) / width).coerceIn(0f, 1f)
                            change(lastValue)
                            pointer.consume()
                        }
                    } finally {
                        touching = false
                        finish()
                    }
                }
            }
    ) {
        val barHeight = thickness.toPx()
        val top = (size.height - barHeight) / 2f
        val radius = CornerRadius(barHeight / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.22f),
            topLeft = Offset(0f, top),
            size = Size(size.width, barHeight),
            cornerRadius = radius
        )
        val fraction = value().coerceIn(0f, 1f)
        if (fraction > 0f) {
            drawRoundRect(
                color = Color.White.copy(alpha = if (touching) 1f else 0.9f),
                topLeft = Offset(0f, top),
                size = Size((size.width * fraction).coerceAtLeast(barHeight), barHeight),
                cornerRadius = radius
            )
        }
    }
}

/**
 * The app's own seek bar, so it follows the Player slider style setting (Wavy, Squiggly, Slim…)
 * and seeks to wherever it is tapped, not only where it is dragged.
 */
@Composable
private fun ImmersiveScrubber(
    positionState: State<Long>,
    durationMs: Long,
    isPlaying: Boolean,
    sliderStyle: String,
    onScrubbing: (Boolean, Long) -> Unit,
    onSeekTo: (Long) -> Unit
) {
    val posMs = positionState.value
    // Where the finger last was. The position state only reports the scrub point while
    // scrubbing is on, so it can't be read back after scrubbing has been switched off.
    var targetMs by remember { mutableStateOf<Long?>(null) }
    AuralisPlayerSlider(
        value = if (durationMs > 0) (posMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
        onValueChange = { fraction ->
            val ms = (fraction * durationMs).toLong()
            targetMs = ms
            onScrubbing(true, ms)
        },
        onValueChangeFinished = {
            val ms = targetMs ?: return@AuralisPlayerSlider
            targetMs = null
            onScrubbing(false, ms)
            onSeekTo(ms)
        },
        isPlaying = isPlaying,
        currentPosMs = posMs,
        totalDurationMs = durationMs,
        sliderStyle = sliderStyle,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
        thumbColor = Color.White,
        textColor = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ImmersiveTransportRow(
    isPlaying: Boolean,
    isLoading: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    playTouchSize: Dp = PLAY_TOUCH_SIZE
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransportGlyph(
            icon = R.drawable.ic_transport_previous,
            contentDescription = str(R.string.previous),
            size = SKIP_GLYPH_SIZE,
            heightScale = 0.85f,
            onClick = onPrevious
        )
        if (isLoading) {
            Box(Modifier.size(playTouchSize), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(38.dp))
            }
        } else {
            TransportGlyph(
                icon = if (isPlaying) R.drawable.ic_transport_pause else R.drawable.ic_transport_play,
                contentDescription = if (isPlaying) str(R.string.pause) else str(R.string.play),
                size = PLAY_GLYPH_SIZE,
                touchSize = playTouchSize,
                onClick = onPlayPause
            )
        }
        TransportGlyph(
            icon = R.drawable.ic_transport_next,
            contentDescription = str(R.string.next),
            size = SKIP_GLYPH_SIZE,
            heightScale = 0.85f,
            onClick = onNext
        )
    }
}

@Composable
private fun TransportGlyph(
    @androidx.annotation.DrawableRes icon: Int,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
    touchSize: Dp = size,
    heightScale: Float = 1f
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "transportPress"
    )
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            pressed = it is androidx.compose.foundation.interaction.PressInteraction.Press
        }
    }
    Box(
        modifier = Modifier
            .size(touchSize)
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(icon),
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale * heightScale
                }
        )
    }
}

/** System media volume between its two speaker glyphs, kept in step with the hardware keys. */
@Composable
private fun ImmersiveVolumeRow() {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) }
    var dragging by remember { mutableStateOf(false) }
    DisposableEffect(context, audio) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!dragging) volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply {
            addAction("android.media.STREAM_DEVICES_CHANGED_ACTION")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.VolumeDown,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        ImmersiveThinBar(
            value = { volume },
            label = str(R.string.volume),
            idleHeight = 6.dp,
            activeHeight = 10.dp,
            onChange = {
                dragging = true
                volume = it
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).roundToInt(), 0)
            },
            onFinish = { dragging = false },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Lyrics on one end, the queue on the other. */
@Composable
private fun ImmersiveActionRow(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundActionGlyph(
            icon = LyricsBubbleIcon,
            contentDescription = str(R.string.lyrics),
            highlighted = lyricsOpen,
            onClick = onToggleLyrics
        )
        RoundActionGlyph(
            icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
            contentDescription = str(R.string.queue),
            highlighted = queueOpen,
            onClick = onToggleQueue
        )
    }
}

@Composable
private fun RoundActionGlyph(
    icon: ImageVector,
    contentDescription: String,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val disc by animateFloatAsState(if (highlighted) 0.20f else 0f, tween(180), label = "actionDisc")
    Box(
        modifier = Modifier
            .size(ACTION_SIZE)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = disc))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (highlighted) 1f else 0.75f),
            modifier = Modifier.size(26.dp)
        )
    }
}

/** Speech bubble with a pair of quote marks — the lyrics control. */
private val LyricsBubbleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ImmersiveLyricsBubble",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 3.8f)
            lineTo(18f, 3.8f)
            quadTo(21f, 3.8f, 21f, 6.8f)
            lineTo(21f, 13.6f)
            quadTo(21f, 16.6f, 18f, 16.6f)
            lineTo(11.6f, 16.6f)
            lineTo(7.6f, 20.2f)
            lineTo(7.6f, 16.6f)
            lineTo(6f, 16.6f)
            quadTo(3f, 16.6f, 3f, 13.6f)
            lineTo(3f, 6.8f)
            quadTo(3f, 3.8f, 6f, 3.8f)
            close()
        }
        for (dx in listOf(0f, 5f)) {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7.6f + dx, 9.1f)
                quadTo(7.6f + dx, 7.9f, 8.8f + dx, 7.9f)
                lineTo(9.3f + dx, 7.9f)
                quadTo(10.5f + dx, 7.9f, 10.5f + dx, 9.1f)
                lineTo(10.5f + dx, 10.1f)
                quadTo(10.5f + dx, 12.2f, 8.7f + dx, 12.9f)
                lineTo(8.3f + dx, 12.1f)
                quadTo(9.3f + dx, 11.6f, 9.4f + dx, 10.8f)
                lineTo(8.8f + dx, 10.8f)
                quadTo(7.6f + dx, 10.8f, 7.6f + dx, 9.6f)
                close()
            }
        }
    }.build()
}
