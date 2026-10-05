package com.auralis.music.ui.player

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.data.download.AuralisDownloadManager
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.domain.model.QueueOperations
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.AudioOutputIcon
import com.auralis.music.ui.components.AuralisPlayerSlider
import com.auralis.music.ui.components.createQueueTrackItem
import com.auralis.music.ui.components.syncLocalQueueWithSnapshot
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.lyrics.SyncedLyricsView
import com.auralis.music.domain.model.Artist
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.player.PlayerTransitionMotion
import com.auralis.music.ui.theme.ArtworkPaletteCache
import com.auralis.music.ui.viewmodel.PlayerUiState
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Immersive Player Container:
 * A visually focused, cinematic now-playing experience that maximizes artwork prominence,
 * fluid ambient glowing highlights, floating translucent controls, and quick-access
 * dock navigation for lyrics, queue, timer, and audio routing.
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
    val palette by ArtworkPaletteCache.currentPalette.collectAsState()
    val dynamicAccent by animateColorAsState(
        targetValue = if (palette.primary != Color.Transparent) palette.primary else MaterialTheme.colorScheme.primary,
        animationSpec = tween(400),
        label = "ImmersiveDynamicAccent"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── TOP HEADER (MINIMALIST CINEMATIC BAR) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = controlsAlpha }
                .padding(top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (currentTab != NowPlayingTab.PLAYER) {
                        onTabChange(NowPlayingTab.PLAYER)
                    } else {
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .tactileBounce(scaleDown = 0.88f)
            ) {
                Icon(
                    imageVector = if (currentTab != NowPlayingTab.PLAYER) Icons.Default.Close else Icons.Default.KeyboardArrowDown,
                    contentDescription = str(R.string.dismiss),
                    tint = Color.White.copy(alpha = 0.90f),
                    modifier = Modifier.size(28.dp)
                )
            }

            // Minimalist Frosted Capsule Badge
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (currentTab) {
                        NowPlayingTab.LYRICS -> str(R.string.lyrics).uppercase()
                        NowPlayingTab.QUEUE -> str(R.string.queue_x, queue.size).uppercase()
                        NowPlayingTab.PLAYER -> "IMMERSIVE"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.90f),
                    letterSpacing = 2.0.sp,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }

            IconButton(
                onClick = onShowTrackOptions,
                modifier = Modifier
                    .size(40.dp)
                    .tactileBounce(scaleDown = 0.88f)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = str(R.string.more_options),
                    tint = Color.White.copy(alpha = 0.90f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── MAIN CONTENT (ANIMATED BETWEEN PLAYER, LYRICS, QUEUE) ──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                },
                label = "ImmersiveTabContent"
            ) { tab ->
                when (tab) {
                    NowPlayingTab.PLAYER -> {
                        ImmersiveMainPlayerView(
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
                            controlsAlpha = controlsAlpha,
                            enableSwipeToChangeSong = enableSwipeToChangeSong,
                            hidePlayerThumbnail = hidePlayerThumbnail,
                            cropAlbumArt = cropAlbumArt,
                            sliderStyle = sliderStyle,
                            dynamicAccent = dynamicAccent,
                            onArtistClick = onArtistClick,
                            onAddToPlaylist = onAddToPlaylist
                        )
                    }

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
                                onSearchManually = onSearchLyricsManually,
                                track = uiState.currentTrack,
                                lyricsClockSource = lyricsClockSource,
                                isPlaying = uiState.isPlaying,
                                isBuffering = uiState.isBuffering,
                                audioLeadingSilenceMs = uiState.audioLeadingSilenceMs
                            )
                        }
                    }

                    NowPlayingTab.QUEUE -> {
                        ImmersiveQueueListView(
                            uiState = uiState,
                            queue = queue,
                            currentTrackIndex = currentTrackIndex,
                            onSelectQueueTrack = onSelectQueueTrack,
                            onReorderQueue = onReorderQueue,
                            onShowQueueTrackOptions = onShowQueueTrackOptions,
                            controlsAlpha = controlsAlpha
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ── FLOATING IMMERSIVE DOCK (CAPSULE NAVIGATION) ──
        ImmersiveBottomDock(
            currentTab = currentTab,
            onTabChange = onTabChange,
            isTimerActive = uiState.sleepTimerSeconds > 0 || uiState.isSleepTimerEndOfSong,
            onShowSleepDialog = onShowSleepDialog,
            onShowOutputPicker = onShowOutputPicker,
            queueCount = queue.size,
            dynamicAccent = dynamicAccent,
            controlsAlpha = controlsAlpha
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * The primary music view of the Immersive Player:
 * Prominent hero album artwork with glowing aura, clean typography,
 * immediate favorite/playlist actions, tactile seeker slider, and sleek controls.
 */
@Composable
private fun ImmersiveMainPlayerView(
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
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    controlsAlpha: Float,
    enableSwipeToChangeSong: Boolean,
    hidePlayerThumbnail: Boolean,
    cropAlbumArt: Boolean,
    sliderStyle: String,
    dynamicAccent: Color,
    onArtistClick: ((Artist) -> Unit)?,
    onAddToPlaylist: () -> Unit
) {
    val context = LocalContext.current
    val downloadedIds by AuralisDownloadManager.downloadedTrackIds.collectAsState()
    val isDownloaded = track.id in downloadedIds
    val activeDownloads by AuralisDownloadManager.activeDownloads.collectAsState()
    val isDownloading = track.id in activeDownloads

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── HERO ARTWORK CAROUSEL WITH DYNAMIC GLOW ──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                key = { page -> "${queue.getOrNull(page)?.id.orEmpty()}_$page" },
                userScrollEnabled = enableSwipeToChangeSong,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .aspectRatio(1f)
                    .graphicsLayer {
                        shadowElevation = 32.dp.toPx()
                        shape = RoundedCornerShape(30.dp)
                        clip = true
                        ambientShadowColor = dynamicAccent
                        spotShadowColor = dynamicAccent
                    }
            ) { page ->
                val pageTrack = if (queue.isNotEmpty() && page in queue.indices) {
                    val qTrack = queue[page]
                    if (qTrack.id == track.id) track else qTrack
                } else {
                    track
                }

                val pageOffset = kotlin.math.abs((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                    .coerceIn(0f, 1f)
                val scale = 1f - (pageOffset * 0.12f)

                val pageModifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .drawWithContent {
                        drawContent()
                        if (pageOffset > 0.001f) {
                            drawRoundRect(
                                color = Color.Black,
                                alpha = (pageOffset * 0.65f).coerceIn(0f, 0.65f),
                                cornerRadius = CornerRadius(30.dp.toPx(), 30.dp.toPx())
                            )
                        }
                    }

                if (hidePlayerThumbnail) {
                    Box(
                        modifier = pageModifier.background(
                            Brush.radialGradient(
                                listOf(
                                    dynamicAccent.copy(alpha = 0.35f),
                                    Color(0xFF1E1B18),
                                    Color(0xFF12100E)
                                )
                            )
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = dynamicAccent,
                            modifier = Modifier.size(68.dp)
                        )
                    }
                } else {
                    ArtworkCard(
                        url = pageTrack.thumbnail,
                        fallbackTrack = pageTrack,
                        modifier = pageModifier,
                        cornerRadius = 30.dp,
                        elevation = 0.dp,
                        contentDescription = pageTrack.title,
                        contentScale = if (cropAlbumArt) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                        highRes = true,
                        crossfade = true
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── TRACK INFO & ACTION BUTTONS ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = controlsAlpha },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        onArtistClick?.invoke(Artist(id = "", name = track.artist, thumbnail = track.thumbnail))
                    }
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Actions Row: Playlist, Download, Favorite
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Add to playlist
                IconButton(
                    onClick = onAddToPlaylist,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .tactileBounce(scaleDown = 0.88f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = str(R.string.add_to_playlist_2),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Download
                IconButton(
                    onClick = {
                        if (isDownloaded) {
                            AuralisDownloadManager.removeDownload(track.id)
                            Toast.makeText(context, str(R.string.download_removed), Toast.LENGTH_SHORT).show()
                        } else {
                            AuralisDownloadManager.downloadTrack(track)
                            Toast.makeText(context, str(R.string.downloading_song), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .tactileBounce(scaleDown = 0.88f)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                            contentDescription = str(R.string.download_song),
                            tint = if (isDownloaded) dynamicAccent else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Favorite Heart with bounce
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .tactileBounce(scaleDown = 0.88f)
                ) {
                    Icon(
                        imageVector = if (uiState.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = str(R.string.favorite),
                        tint = if (uiState.isFavorite) Color(0xFFFF4081) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── TIME SCRUBBER TIMELINE ──
        ImmersivePositionSlider(
            positionState = seekBarPositionState,
            totalDurationMs = totalDurationMs,
            isPlaying = uiState.isPlaying,
            sliderStyle = sliderStyle,
            onValueChange = { frac ->
                onScrubbing(true, (frac * totalDurationMs).toLong())
            },
            onValueChangeFinished = {
                onScrubbing(false, seekBarPositionState.value)
                onSeekTo(seekBarPositionState.value)
            },
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = controlsAlpha }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── MAIN PLAYBACK CONTROLS (SHUFFLE, PREV, HERO PLAY/PAUSE, NEXT, REPEAT) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = controlsAlpha },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shuffle
            IconButton(
                onClick = onToggleShuffle,
                modifier = Modifier
                    .size(46.dp)
                    .tactileBounce(scaleDown = 0.88f)
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = str(R.string.shuffle),
                    tint = if (uiState.isShuffled) dynamicAccent else Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Previous
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    .tactileBounce(scaleDown = 0.90f, onClick = onPreviousClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = str(R.string.previous),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Hero Play/Pause Button (Vibrant Accent Glow)
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .shadow(
                        elevation = 18.dp,
                        shape = CircleShape,
                        ambientColor = dynamicAccent.copy(alpha = 0.45f),
                        spotColor = dynamicAccent.copy(alpha = 0.45f)
                    )
                    .clip(CircleShape)
                    .background(Color.White)
                    .tactileBounce(scaleDown = 0.88f, onClick = onPlayPauseClick),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = uiState.isPlaying,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.80f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)))
                            .togetherWith(fadeOut(animationSpec = tween(120)) + scaleOut(targetScale = 0.80f, animationSpec = tween(120)))
                    },
                    label = "ImmersivePlayPauseAnim"
                ) { isPlaying ->
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) str(R.string.pause) else str(R.string.play),
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // Next
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    .tactileBounce(scaleDown = 0.90f, onClick = onNextClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = str(R.string.next),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Repeat
            IconButton(
                onClick = onToggleRepeat,
                modifier = Modifier
                    .size(46.dp)
                    .tactileBounce(scaleDown = 0.88f)
            ) {
                Icon(
                    imageVector = when (uiState.repeatMode) {
                        RepeatMode.ONE -> Icons.Default.RepeatOne
                        else -> Icons.Default.Repeat
                    },
                    contentDescription = str(R.string.repeat),
                    tint = if (uiState.repeatMode != RepeatMode.OFF) dynamicAccent else Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Floating frosted glass dock at the bottom of the Immersive Player,
 * giving rapid 1-tap switching between Lyrics, Queue, Sleep Timer, and Output devices.
 */
@Composable
private fun ImmersiveBottomDock(
    currentTab: NowPlayingTab,
    onTabChange: (NowPlayingTab) -> Unit,
    isTimerActive: Boolean,
    onShowSleepDialog: () -> Unit,
    onShowOutputPicker: () -> Unit,
    queueCount: Int,
    dynamicAccent: Color,
    controlsAlpha: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = controlsAlpha }
            .shadow(
                elevation = 12.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.35f)
            )
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tab 1: Queue
        val isQueueActive = currentTab == NowPlayingTab.QUEUE
        IconButton(
            onClick = {
                onTabChange(if (isQueueActive) NowPlayingTab.PLAYER else NowPlayingTab.QUEUE)
            },
            modifier = Modifier.tactileBounce(scaleDown = 0.88f)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = str(R.string.queue_x, queueCount),
                tint = if (isQueueActive) dynamicAccent else Color.White.copy(alpha = 0.80f),
                modifier = Modifier.size(24.dp)
            )
        }

        // Action 2: Sleep timer
        IconButton(
            onClick = onShowSleepDialog,
            modifier = Modifier.tactileBounce(scaleDown = 0.88f)
        ) {
            Icon(
                imageVector = Icons.Default.Bedtime,
                contentDescription = str(R.string.sleep_timer),
                tint = if (isTimerActive) dynamicAccent else Color.White.copy(alpha = 0.80f),
                modifier = Modifier.size(22.dp)
            )
        }

        // Action 3: Audio output
        IconButton(
            onClick = onShowOutputPicker,
            modifier = Modifier.tactileBounce(scaleDown = 0.88f)
        ) {
            Icon(
                imageVector = AudioOutputIcon,
                contentDescription = str(R.string.audio_output_quality),
                tint = Color.White.copy(alpha = 0.80f),
                modifier = Modifier.size(22.dp)
            )
        }

        // Tab 4: Lyrics
        val isLyricsActive = currentTab == NowPlayingTab.LYRICS
        IconButton(
            onClick = {
                onTabChange(if (isLyricsActive) NowPlayingTab.PLAYER else NowPlayingTab.LYRICS)
            },
            modifier = Modifier.tactileBounce(scaleDown = 0.88f)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = str(R.string.lyrics),
                tint = if (isLyricsActive) dynamicAccent else Color.White.copy(alpha = 0.80f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Isolated position slider for the immersive player that avoids recomposing the entire view
 * on every 60fps clock tick.
 */
@Composable
private fun ImmersivePositionSlider(
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
 * Fullscreen up-next queue for the Immersive Player with reorderable list and clean styling.
 */
@Composable
private fun ImmersiveQueueListView(
    uiState: PlayerUiState,
    queue: List<Track>,
    currentTrackIndex: Int,
    onSelectQueueTrack: (Int) -> Unit,
    onReorderQueue: ((Int, Int) -> Unit)?,
    onShowQueueTrackOptions: (Track) -> Unit,
    controlsAlpha: Float
) {
    val queueSnapshot = uiState.queue
    val queueCurrentIndex = uiState.currentIndex
    val queueItemShape = remember { RoundedCornerShape(14.dp) }
    val haptic = LocalHapticFeedback.current

    val localQueue = remember {
        queueSnapshot.map { track ->
            createQueueTrackItem(track)
        }.toMutableStateList()
    }

    val initialScrollIndex = remember {
        val activeIdx = QueueOperations.findActiveTrackIndex(
            queue = queueSnapshot,
            currentTrack = uiState.currentTrack,
            currentIndex = queueCurrentIndex
        )
        if (activeIdx >= 0) {
            QueueOperations.calculateScrollIndex(
                targetIndex = activeIdx,
                visibleItemCount = 6,
                queueSize = queueSnapshot.size
            )
        } else {
            0
        }
    }

    val queueListState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)
    var startDragIndex by remember { mutableIntStateOf(-1) }
    var lastDragEndTime by remember { mutableLongStateOf(0L) }

    val reorderableLazyListState = rememberReorderableLazyListState(queueListState) { from, to ->
        val fromIndex = localQueue.indexOfFirst { it.instanceId == from.key }
        val toIndex = localQueue.indexOfFirst { it.instanceId == to.key }
        if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
            localQueue.add(toIndex, localQueue.removeAt(fromIndex))
        }
    }

    LaunchedEffect(queueSnapshot) {
        syncLocalQueueWithSnapshot(localQueue, queueSnapshot, reorderableLazyListState.isAnyItemDragging)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = controlsAlpha }
    ) {
        Text(
            text = str(R.string.up_next_x_songs, localQueue.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                state = queueListState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
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
                        var isRowHeld by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(queueItemShape)
                                .pointerInput(item.instanceId) {
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
                                .background(
                                    when {
                                        isDragging || isRowHeld -> Color.White.copy(alpha = 0.16f)
                                        isCurrent -> primaryColor.copy(alpha = 0.18f)
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable {
                                    val idx = queueSnapshot.indexOfFirst { it.id == item.track.id }
                                    if (idx != -1) onSelectQueueTrack(idx)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkCard(
                                url = item.track.thumbnail,
                                fallbackTrack = item.track,
                                modifier = Modifier.size(46.dp),
                                cornerRadius = 8.dp,
                                elevation = 0.dp,
                                contentDescription = item.track.title,
                                crossfade = true
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) primaryColor else Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = item.track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.60f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = { onShowQueueTrackOptions(item.track) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = str(R.string.more_options),
                                    tint = Color.White.copy(alpha = 0.70f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.50f),
                                modifier = Modifier
                                    .size(22.dp)
                                    .padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
