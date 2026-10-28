package com.auralis.music.ui.onboarding

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.SyncedLyricsView
import com.auralis.music.ui.player.NowPlayingModal
import com.auralis.music.ui.player.LocalPlayerDemoControls
import com.auralis.music.ui.player.PlayerDemoControls
import com.auralis.music.data.network.ConnectivityMonitor
import com.auralis.music.data.network.LyricsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import com.auralis.music.ui.theme.LocalAppearanceSettings
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.viewmodel.PlayerUiState
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A local, silent demo clock; never connects to the audio service. */
@Composable
internal fun rememberTourClock(running: Boolean, restartKey: String, durationMs: Long, startMs: Long = 1200L): MutableState<Long> {
    val position = remember(restartKey) { mutableStateOf(startMs) }
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
                    isPlaying = !reduced, lyricsMode = LyricsMode.CINEMA,
                    standardLyricsBlur = appearance.standardLyricsBlur,
                    modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** The same player composable used during playback, scaled from a phone-sized viewport. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
internal fun TourPlayerPreview(appearance: AppearanceSettings, active: Boolean, viewport: IntSize, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val sampleQueue = remember { listOf(
        Track("PvM79DJ2PmM", "The Less I Know the Better", "Tame Impala", album = "Currents", duration = 217, thumbnail = "https://i.ytimg.com/vi/PvM79DJ2PmM/hqdefault.jpg"),
        Track("rymYToIEL9o", "Borderline", "Tame Impala", album = "The Slow Rush", duration = 238, thumbnail = "https://i.ytimg.com/vi/rymYToIEL9o/hqdefault.jpg"),
        Track("NMRhx71bGo4", "Let It Happen", "Tame Impala", album = "Currents", duration = 468, thumbnail = "https://i.ytimg.com/vi/NMRhx71bGo4/hqdefault.jpg")
    ) }
    var queue by remember { mutableStateOf(sampleQueue) }
    var trackId by remember { mutableStateOf(sampleQueue.first().id) }
    val currentIndex = queue.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
    val track = queue[currentIndex]
    var playing by remember { mutableStateOf(true) }
    var shuffled by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(com.auralis.music.domain.model.RepeatMode.OFF) }
    var favorites by remember { mutableStateOf(emptySet<String>()) }
    var timerSeconds by remember { mutableStateOf(0L) }
    var notice by remember { mutableStateOf<String?>(null) }
    var resetVersion by remember { mutableIntStateOf(0) }
    val volume = remember { mutableStateOf(.55f) }
    val demoControls = remember { PlayerDemoControls(volume) { notice = it } }
    val position = rememberTourClock(active && playing && !reduced, track.id, track.duration * 1000, startMs = 30_000)
    fun select(index: Int) { queue.getOrNull(index)?.let { selected ->
        if (selected.id == trackId) position.value = 0L
        trackId = selected.id
    } }
    val online by ConnectivityMonitor.isOnline.collectAsState()
    val lyricsClient = remember { LyricsClient() }
    val lyricCache = remember { java.util.concurrent.ConcurrentHashMap<String, LyricsData>() }
    var lyrics by remember(track.id) { mutableStateOf(lyricCache[track.id]) }
    var loadingLyrics by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id, online, active) {
        if (!active || !online || lyrics != null) return@LaunchedEffect
        loadingLyrics = true
        try {
            val loaded = withTimeoutOrNull(15_000) {
                lyricsClient.getLyrics(track.title, track.artist, durationSec = track.duration,
                    videoId = track.id, album = track.album, onInterim = { candidate ->
                        if (candidate.syncType != SyncType.PLAIN) { lyricCache[track.id] = candidate; lyrics = candidate }
                    })
            }
            if (loaded != null) { lyricCache[track.id] = loaded; lyrics = loaded }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The player's existing unavailable-lyrics state offers retry. */ }
        finally { loadingLyrics = false }
    }
    val previewAppearance = appearance.copy(enableSwipeToChangeSong = false)
    val lifecycleOwner = LocalLifecycleOwner.current
    // The embedded player's BackHandler must never intercept the tour's Back gesture.
    val previewBackOwner = remember(lifecycleOwner) { object : OnBackPressedDispatcherOwner {
        override val lifecycle = lifecycleOwner.lifecycle
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    } }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val preview = fitPlayerPreview(viewport, IntSize(constraints.maxWidth, constraints.maxHeight))
        val (width, height, scale) = preview
        if (scale <= 0f) return@BoxWithConstraints
        // Measure at the real on-screen size. Scaling a larger layer after measurement made
        // Immersive compare scaled root coordinates against unscaled cover/control dimensions.
        Box(Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() })) {
            CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale),
                LocalAppearanceSettings provides previewAppearance,
                LocalOnBackPressedDispatcherOwner provides previewBackOwner,
                LocalPlayerDemoControls provides demoControls) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Color(0xFF111015))) {
                    Crossfade(targetState = appearance.playerDesign to resetVersion, animationSpec = tween(if (reduced) 0 else 260), label = "player design preview") { (design, _) ->
                        CompositionLocalProvider(LocalAppearanceSettings provides previewAppearance.copy(playerDesign = design, newPlayerDesign = design != "Old player")) {
                        NowPlayingModal(uiState = PlayerUiState(currentTrack = track, isPlaying = playing,
                            durationMs = track.duration * 1000, queue = queue, currentIndex = currentIndex,
                            lyrics = lyrics, isLoadingLyrics = loadingLyrics, isFavorite = track.id in favorites,
                            isShuffled = shuffled, repeatMode = repeat, sleepTimerSeconds = timerSeconds),
                            playbackPositionState = position, onPlayPauseClick = { playing = !playing },
                            onSeekTo = { position.value = it.coerceIn(0, track.duration * 1000) },
                            onNextClick = { select((currentIndex + 1) % queue.size) },
                            onPreviousClick = { select((currentIndex - 1 + queue.size) % queue.size) },
                            onToggleShuffle = {
                                shuffled = !shuffled
                                queue = if (shuffled) queue.shuffled() else sampleQueue.filter { sample -> queue.any { it.id == sample.id } }
                            },
                            onToggleRepeat = { repeat = when (repeat) {
                                com.auralis.music.domain.model.RepeatMode.OFF -> com.auralis.music.domain.model.RepeatMode.ALL
                                com.auralis.music.domain.model.RepeatMode.ALL -> com.auralis.music.domain.model.RepeatMode.ONE
                                com.auralis.music.domain.model.RepeatMode.ONE -> com.auralis.music.domain.model.RepeatMode.OFF
                            } },
                            onToggleFavorite = { favorites = if (track.id in favorites) favorites - track.id else favorites + track.id },
                            onSleepTimerSelect = { timerSeconds = it * 60L }, onSelectQueueTrack = ::select,
                            onReorderQueue = { from, to -> if (from in queue.indices && to in queue.indices) {
                                queue = queue.toMutableList().apply { add(to, removeAt(from)) }
                            } },
                            onRemoveQueueItem = { index -> if (queue.size > 1 && index in queue.indices && queue[index].id != trackId) {
                                queue = queue.filterIndexed { i, _ -> i != index }
                            } },
                            onSearchLyricsManually = { _, _ -> notice = "Song lyrics load automatically when you are online." },
                            onDismiss = { resetVersion++ })
                        }
                    }

                }
            }
        }
    }

    notice?.let { message ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { notice = null },
            title = { Text("Player demo") }, text = { Text(message) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { notice = null }) { Text("OK") } })
    }

}
