package com.auralis.music.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.LyricsMode
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.lyrics.SyncedLyricsView
import com.auralis.music.ui.viewmodel.PlayerUiState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Classic ViVi Player screen displayed when `appearance.newPlayerDesign == false`.
 * Faithful pixel-accurate reproduction of the classic player interface:
 * - Top: Centered "Now Playing" and album title
 * - Center: Square album art with rounded corners and swipe-to-skip pager
 * - Track info: Marquee title + artist with circular 3-dots and heart buttons on right
 * - Scrubber: Clean linear slider with timestamps directly below
 * - Controls: Fast rewind (<<), large floating Play/Pause, Fast forward (>>)
 * - Volume slider: In-player volume slider synced with AudioManager.STREAM_MUSIC
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
    modifier: Modifier = Modifier,
    onArtistClick: ((Artist) -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val dragOffsetY = remember { Animatable(0f) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .graphicsLayer {
                translationY = dragOffsetY.value
            }
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
                        if (dragOffsetY.value > 130f) {
                            onDismiss()
                        } else {
                            coroutineScope.launch {
                                dragOffsetY.animateTo(
                                    0f,
                                    spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch {
                            dragOffsetY.animateTo(
                                0f,
                                spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
                            )
                        }
                    }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── 1. TOP HEADER: "Now Playing" + Album Name ──
        ClassicTopBar(
            albumTitle = track.album?.takeIf { it.isNotBlank() } ?: track.artist,
            controlsAlpha = controlsAlpha,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp)
        )

        Spacer(modifier = Modifier.weight(0.5f))

        // ── 2. CENTER ALBUM ARTWORK ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!hidePlayerThumbnail) {
                val artworkShape = RoundedCornerShape(18.dp)

                if (queue.isNotEmpty()) {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = enableSwipeToChangeSong,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    ) { pageIndex ->
                        val pageTrack = queue.getOrNull(pageIndex) ?: track
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
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
                                model = getHighResArtworkUrl(pageTrack.thumbnail),
                                contentDescription = pageTrack.title,
                                contentScale = if (cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
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
                            model = getHighResArtworkUrl(track.thumbnail),
                            contentDescription = track.title,
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
                .graphicsLayer { alpha = controlsAlpha },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee()
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        onArtistClick?.invoke(
                            Artist(
                                id = "",
                                name = track.artist,
                                thumbnail = track.thumbnail
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
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .tactileBounce(scaleDown = 0.88f, onClick = onShowMoreOptions),
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
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .tactileBounce(scaleDown = 0.88f, onClick = onToggleFavorite),
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

        Spacer(modifier = Modifier.height(14.dp))

        // ── 4. TIMELINE SCRUBBER & TIMESTAMPS ──
        ClassicTimelineSlider(
            positionState = seekBarPositionState,
            totalDurationMs = totalDurationMs,
            isScrubbing = isScrubbing,
            onScrubbing = onScrubbing,
            onSeekTo = onSeekTo,
            controlsAlpha = controlsAlpha,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // ── 5. MAIN PLAYBACK CONTROLS (FAST REWIND, BIG PLAY/PAUSE, FAST FORWARD) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 30.dp)
                .graphicsLayer { alpha = controlsAlpha },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous / Fast Rewind
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
                    modifier = Modifier.size(38.dp)
                )
            }

            // Play / Pause (Clean floating icon, no pill)
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

            // Next / Fast Forward
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
                    modifier = Modifier.size(38.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── 6. IN-PLAYER VOLUME SLIDER ──
        InPlayerVolumeSlider(
            controlsAlpha = controlsAlpha,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
        )

        Spacer(modifier = Modifier.weight(0.6f))

        // ── 7. BOTTOM UTILITY BAR (QUEUE, DEVICE/TIMER CAPSULE, LYRICS) ──
        ClassicBottomBar(
            isQueueActive = false,
            isLyricsActive = false,
            isTimerActive = uiState.sleepTimerSeconds > 0 || uiState.isSleepTimerEndOfSong,
            onToggleQueue = onToggleQueue,
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

/**
 * Centered top header showing "Now Playing" and album title.
 */
@Composable
fun ClassicTopBar(
    albumTitle: String,
    controlsAlpha: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.graphicsLayer { alpha = controlsAlpha },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp)
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
                text = albumTitle,
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
 * Clean linear slider matching Image 2 with circular thumb and timestamps directly below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassicTimelineSlider(
    positionState: State<Long>,
    totalDurationMs: Long,
    isScrubbing: Boolean,
    onScrubbing: (Boolean, Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    controlsAlpha: Float,
    modifier: Modifier = Modifier
) {
    val currentPosMs = positionState.value
    var localDragFraction by remember { mutableFloatStateOf(0f) }

    val sliderFraction = remember(currentPosMs, totalDurationMs, isScrubbing, localDragFraction) {
        if (isScrubbing) {
            localDragFraction
        } else if (totalDurationMs > 0) {
            (currentPosMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    Column(
        modifier = modifier.graphicsLayer { alpha = controlsAlpha }
    ) {
        Slider(
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
            thumb = {
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.40f))
                        .clip(CircleShape)
                        .background(Color.White)
                )
            },
            track = { sliderState ->
                val fraction = sliderState.value
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
                ) {
                    val h = size.height
                    val w = size.width
                    val activeW = w * fraction
                    // Inactive track
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.25f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(h / 2, h / 2)
                    )
                    // Active track
                    if (activeW > 0f) {
                        drawRoundRect(
                            color = Color.White,
                            size = Size(activeW, h),
                            cornerRadius = CornerRadius(h / 2, h / 2)
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatClassicTime(if (isScrubbing) (localDragFraction * totalDurationMs).toLong() else currentPosMs),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.70f)
            )
            Text(
                text = formatClassicTime(totalDurationMs),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.70f)
            )
        }
    }
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
 * Reusable Classic Bottom Navigation Bar:
 * - Left: Queue button
 * - Center: Capsule pill (Speaker device + Sleep timer)
 * - Right: Lyrics button
 */
@Composable
fun ClassicBottomBar(
    isQueueActive: Boolean,
    isLyricsActive: Boolean,
    isTimerActive: Boolean,
    onToggleQueue: () -> Unit,
    onShowOutputPicker: () -> Unit,
    onShowSleepDialog: () -> Unit,
    onToggleLyrics: () -> Unit,
    controlsAlpha: Float,
    modifier: Modifier = Modifier
) {
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

        // Center: Capsule Pill (Device + Sleep Timer)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = 0.14f))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speaker / Device Output button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onShowOutputPicker() }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                SpeakerBoxIcon(
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Divider Line
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(Color.White.copy(alpha = 0.18f))
            )

            // Sleep Timer button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onShowSleepDialog() }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = "Sleep Timer",
                    tint = if (isTimerActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
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
 * Loudspeaker cabinet icon matching the center capsule in Image 2.
 */
@Composable
fun SpeakerBoxIcon(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeW = 1.8.dp.toPx()
        val corner = 3.dp.toPx()

        // Outer speaker enclosure
        drawRoundRect(
            color = tint,
            size = Size(w, h),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = strokeW)
        )
        // Tweeter (top circle)
        drawCircle(
            color = tint,
            radius = w * 0.14f,
            center = Offset(w * 0.5f, h * 0.33f)
        )
        // Woofer (bottom concentric circle)
        drawCircle(
            color = tint,
            radius = w * 0.26f,
            center = Offset(w * 0.5f, h * 0.70f),
            style = Stroke(width = strokeW)
        )
        drawCircle(
            color = tint,
            radius = w * 0.11f,
            center = Offset(w * 0.5f, h * 0.70f)
        )
    }
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
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    onSelectQueueTrack: (Int) -> Unit,
    onShowTrackOptions: () -> Unit,
    onShowSleepDialog: () -> Unit,
    lyricsPositionState: State<Long>,
    lyricsClockSource: PlaybackClockSource?,
    onLyricsOffsetChange: (Long) -> Unit,
    onSearchLyricsManually: () -> Unit,
    controlsAlpha: Float,
    enableSwipeToChangeSong: Boolean,
    hidePlayerThumbnail: Boolean,
    cropAlbumArt: Boolean,
    modifier: Modifier = Modifier,
    onArtistClick: ((Artist) -> Unit)? = null
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (currentTab) {
            NowPlayingTab.PLAYER -> {
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
                    onToggleFavorite = onToggleFavorite,
                    onDismiss = onDismiss,
                    onShowMoreOptions = onShowTrackOptions,
                    onShowSleepDialog = onShowSleepDialog,
                    onShowOutputPicker = { openAudioOutputSettings(context) },
                    onToggleQueue = { onTabChange(NowPlayingTab.QUEUE) },
                    onToggleLyrics = { onTabChange(NowPlayingTab.LYRICS) },
                    controlsAlpha = controlsAlpha,
                    enableSwipeToChangeSong = enableSwipeToChangeSong,
                    hidePlayerThumbnail = hidePlayerThumbnail,
                    cropAlbumArt = cropAlbumArt,
                    onArtistClick = onArtistClick
                )
            }
            NowPlayingTab.LYRICS -> {
                ClassicLyricsContent(
                    track = track,
                    uiState = uiState,
                    lyricsPositionState = lyricsPositionState,
                    lyricsClockSource = lyricsClockSource,
                    onLyricsOffsetChange = onLyricsOffsetChange,
                    onSearchLyricsManually = onSearchLyricsManually,
                    onSeekTo = onSeekTo,
                    onCloseLyrics = { onTabChange(NowPlayingTab.PLAYER) },
                    onShowOutputPicker = { openAudioOutputSettings(context) },
                    onShowSleepDialog = onShowSleepDialog,
                    onToggleQueue = { onTabChange(NowPlayingTab.QUEUE) },
                    controlsAlpha = controlsAlpha
                )
            }
            NowPlayingTab.QUEUE -> {
                ClassicQueueContent(
                    track = track,
                    uiState = uiState,
                    queue = queue,
                    onSelectQueueTrack = { index ->
                        onSelectQueueTrack(index)
                        onTabChange(NowPlayingTab.PLAYER)
                    },
                    onCloseQueue = { onTabChange(NowPlayingTab.PLAYER) },
                    onShowOutputPicker = { openAudioOutputSettings(context) },
                    onShowSleepDialog = onShowSleepDialog,
                    onToggleLyrics = { onTabChange(NowPlayingTab.LYRICS) },
                    controlsAlpha = controlsAlpha
                )
            }
        }
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
    onCloseLyrics: () -> Unit,
    onShowOutputPicker: () -> Unit,
    onShowSleepDialog: () -> Unit,
    onToggleQueue: () -> Unit,
    controlsAlpha: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
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

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer { alpha = controlsAlpha }
        ) {
            SyncedLyricsView(
                lyrics = uiState.lyrics,
                positionState = lyricsPositionState,
                onSeekTo = onSeekTo,
                isLoading = uiState.isLoadingLyrics,
                lyricsMode = LyricsMode.CINEMA,
                offsetMs = uiState.lyricsOffsetMs,
                onOffsetChange = onLyricsOffsetChange,
                onSearchManually = onSearchLyricsManually,
                track = uiState.currentTrack,
                lyricsClockSource = lyricsClockSource,
                isPlaying = uiState.isPlaying
            )
        }

        ClassicBottomBar(
            isQueueActive = false,
            isLyricsActive = true,
            isTimerActive = uiState.sleepTimerSeconds > 0 || uiState.isSleepTimerEndOfSong,
            onToggleQueue = onToggleQueue,
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

@Composable
private fun ClassicQueueContent(
    track: Track,
    uiState: PlayerUiState,
    queue: List<Track>,
    onSelectQueueTrack: (Int) -> Unit,
    onCloseQueue: () -> Unit,
    onShowOutputPicker: () -> Unit,
    onShowSleepDialog: () -> Unit,
    onToggleLyrics: () -> Unit,
    controlsAlpha: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
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

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer { alpha = controlsAlpha }
        ) {
            val queueSnapshot = uiState.queue
            val queueCurrentIndex = uiState.currentIndex
            val queueItemShape = remember { RoundedCornerShape(14.dp) }
            val queueArtworkCorner = remember { 8.dp }
            val inactiveRowBg = remember { Color.White.copy(alpha = 0.08f) }
            val subtitleColor = remember { Color.White.copy(alpha = 0.6f) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = queueSnapshot,
                    key = { index, item -> "${item.id}#$index" },
                    contentType = { _, _ -> "queue_track" }
                ) { index, item ->
                    val isCurrent = index == queueCurrentIndex
                    val primaryColor = MaterialTheme.colorScheme.primary
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(queueItemShape)
                            .background(if (isCurrent) primaryColor.copy(alpha = 0.20f) else inactiveRowBg)
                            .clickable {
                                onSelectQueueTrack(index)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkCard(
                            url = item.thumbnail,
                            modifier = Modifier.size(44.dp),
                            cornerRadius = queueArtworkCorner,
                            contentDescription = item.title
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (isCurrent) primaryColor else Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.artist,
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
                        }
                    }
                }
            }
        }

        ClassicBottomBar(
            isQueueActive = true,
            isLyricsActive = false,
            isTimerActive = uiState.sleepTimerSeconds > 0 || uiState.isSleepTimerEndOfSong,
            onToggleQueue = onCloseQueue,
            onShowOutputPicker = onShowOutputPicker,
            onShowSleepDialog = onShowSleepDialog,
            onToggleLyrics = onToggleLyrics,
            controlsAlpha = controlsAlpha,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
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
