package com.auralis.music.ui.onboarding

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.SyncedLyricsView
import com.auralis.music.ui.player.NowPlayingModal
import com.auralis.music.ui.theme.LocalAppearanceSettings
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.viewmodel.PlayerUiState
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A local, silent demo clock; never connects to the audio service. */
@Composable
private fun rememberTourClock(running: Boolean, restartKey: String, durationMs: Long): MutableState<Long> {
    val position = remember(restartKey) { mutableStateOf(1200L) }
    LaunchedEffect(running, restartKey) {
        if (!running) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            position.value = (position.value + (now - previous) / 1_000_000).mod(durationMs)
            previous = now
        }
    }
    return position
}

@Composable
internal fun TourLyricsPreview(appearance: AppearanceSettings, active: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val position = rememberTourClock(active && !reduced, appearance.lyricsAnimation, 24_000)
    val lyrics = remember {
        // Original demo text with deliberately authored word timing, not provider data.
        val text = listOf("A song for every moment", "Let your music shine", "Find a sound to call your own", "Every word comes alive")
        LyricsData(SyncType.RICHSYNC, text.mapIndexed { index, line ->
            val start = index * 6000L
            val words = line.split(" ")
            LyricLine(start, line, words = words.mapIndexed { wordIndex, word ->
                LyricWord(word + if (wordIndex < words.lastIndex) " " else "", start + wordIndex * 780L, 740L)
            }, endTime = start + 5500L)
        }, provider = LyricsProvider.LOCAL, durationMs = 24_000)
    }
    CompositionLocalProvider(LocalAppearanceSettings provides appearance) {
        Column(modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xFF101016))) {
            Text(appearance.lyricsAnimation, color = Color.White.copy(alpha = .5f), fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(18.dp))
            key(appearance.lyricsAnimation) {
                SyncedLyricsView(lyrics = lyrics, positionState = position, onSeekTo = { position.value = it },
                    isPlaying = active && !reduced, lyricsMode = LyricsMode.CINEMA,
                    standardLyricsBlur = appearance.standardLyricsBlur,
                    modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** The same player composable used during playback, scaled from a phone-sized viewport. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
internal fun TourPlayerPreview(appearance: AppearanceSettings, active: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    var playing by remember { mutableStateOf(true) }
    val position = rememberTourClock(active && playing && !reduced, "player", 180_000)
    val track = remember { Track(id = "auralis-tour-local", title = "Let your music shine", artist = "Auralis · Player preview",
        duration = 180, thumbnail = "android.resource://${context.packageName}/drawable/ic_auralis_logo") }
    val previewAppearance = appearance.copy(enableSwipeToChangeSong = false)
    val lifecycleOwner = LocalLifecycleOwner.current
    // The embedded player's BackHandler must never intercept the tour's Back gesture.
    val previewBackOwner = remember(lifecycleOwner) { object : OnBackPressedDispatcherOwner {
        override val lifecycle = lifecycleOwner.lifecycle
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    } }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed && !reduced) .98f else 1f,
        animationSpec = spring(dampingRatio = .6f, stiffness = 400f), label = "preview press")
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val virtualWidth = with(density) { 390.dp.roundToPx() }
        val virtualHeight = with(density) { 740.dp.roundToPx() }
        val scale = minOf(constraints.maxWidth.toFloat() / virtualWidth, constraints.maxHeight.toFloat() / virtualHeight)
        val width = (virtualWidth * scale).toInt().coerceAtLeast(1)
        val height = (virtualHeight * scale).toInt().coerceAtLeast(1)
        Layout(modifier = Modifier.graphicsLayer { scaleX = pressScale; scaleY = pressScale }, content = {
            CompositionLocalProvider(LocalAppearanceSettings provides previewAppearance,
                LocalOnBackPressedDispatcherOwner provides previewBackOwner) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Color(0xFF111015))) {
                    Crossfade(targetState = appearance.playerDesign, animationSpec = tween(if (reduced) 0 else 260), label = "player design preview") { design ->
                        CompositionLocalProvider(LocalAppearanceSettings provides previewAppearance.copy(playerDesign = design, newPlayerDesign = design != "Old player")) {
                        NowPlayingModal(uiState = PlayerUiState(currentTrack = track, isPlaying = playing && active && !reduced,
                            durationMs = 180_000, queue = listOf(track), currentIndex = 0),
                            playbackPositionState = position, onPlayPauseClick = { playing = !playing },
                            onSeekTo = { position.value = it }, onNextClick = {}, onPreviousClick = {},
                            onToggleShuffle = {}, onToggleRepeat = {}, onToggleFavorite = {},
                            onSleepTimerSelect = {}, onSelectQueueTrack = {}, onDismiss = {})
                        }
                    }
                    // The preview stays local: no output pickers, real volume changes or playback actions.
                    Box(Modifier.matchParentSize().clickable(interactionSource = interactions, indication = null,
                        onClickLabel = if (playing) "Pause demo" else "Play demo") { playing = !playing })
                }
            }
        }) { children, _ ->
            val child = children.single().measure(Constraints.fixed(virtualWidth, virtualHeight))
            layout(width, height) {
                child.placeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
            }
        }
    }
}
