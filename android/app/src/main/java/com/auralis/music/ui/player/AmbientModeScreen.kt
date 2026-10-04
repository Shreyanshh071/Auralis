package com.auralis.music.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.auralis.music.domain.model.RepeatMode
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.auralis.music.R
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.lyrics.SyncedLyricsView
import com.auralis.music.ui.lyrics.rememberLyricsClock
import com.auralis.music.ui.theme.ArtworkPaletteCache
import com.auralis.music.ui.theme.LocalAppearanceSettings
import com.auralis.music.ui.viewmodel.PlayerUiState

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Landscape artwork and synchronized lyrics, using the full player's background renderer. */
@Composable
fun AmbientModeScreen(
    uiState: PlayerUiState,
    positionState: State<Long>,
    clockSource: PlaybackClockSource?,
    onDismiss: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit
) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { previousOrientation?.let { activity?.requestedOrientation = it } }
    }
    key(LocalConfiguration.current.orientation) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    val attributes = it.attributes
                    attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    it.attributes = attributes
                }
                it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.getInsetsController(it, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        val appearance = LocalAppearanceSettings.current
        val palette by ArtworkPaletteCache.currentPalette.collectAsState()
        val style = PlayerBackgroundStyle.fromKey(appearance.playerBackgroundStyle).let {
            if (it == PlayerBackgroundStyle.APPLE_MUSIC) PlayerBackgroundStyle.GRADIENT else it
        }
        val clock = rememberLyricsClock(clockSource, true, positionState)
        val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
        val layoutDirection = LocalLayoutDirection.current
        val landscapeSideInset = maxOf(
            cutoutPadding.calculateLeftPadding(layoutDirection),
            cutoutPadding.calculateRightPadding(layoutDirection)
        )
        Box(Modifier.fillMaxSize()) {
            PlayerBackground(style, uiState.currentTrack?.thumbnail, palette,
                modifier = Modifier.fillMaxSize(), isPlaying = uiState.isPlaying)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
            Row(Modifier.fillMaxSize().padding(horizontal = landscapeSideInset).padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val coverSize = minOf(maxHeight - 100.dp, maxWidth - 64.dp).coerceAtLeast(80.dp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Box(Modifier.width(48.dp).height(coverSize), contentAlignment = Alignment.Center) {
                            AmbientVolumeBar(Modifier.offset(x = 10.dp, y = 16.dp).width(48.dp).height(coverSize * 0.62f))
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            AmbientArtworkPager(uiState, onPrevious, onNext, Modifier.size(coverSize))
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth(0.9f)) {
                                AmbientSeekBar(positionState, uiState.durationMs, uiState.currentTrack?.id, onSeekTo)
                            }
                            Row(Modifier.fillMaxWidth().offset(y = (-4).dp), horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onShuffle, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Default.Shuffle, str(R.string.shuffle), tint = Color.White.copy(alpha = if (uiState.isShuffled) 1f else 0.55f), modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp).semantics { contentDescription = str(R.string.previous) }) {
                                    ClassicSkipGlyph(false, Color.White, Modifier.size(38.dp, 25.dp))
                                }
                                IconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp).semantics { contentDescription = if (uiState.isPlaying) str(R.string.pause) else str(R.string.play) }) {
                                    if (uiState.isPlaying) ClassicPauseGlyph(Color.White, Modifier.size(28.dp, 32.dp))
                                    else ClassicPlayGlyph(Color.White, Modifier.size(30.dp, 34.dp))
                                }
                                IconButton(onClick = onNext, modifier = Modifier.size(56.dp).semantics { contentDescription = str(R.string.next) }) {
                                    ClassicSkipGlyph(true, Color.White, Modifier.size(38.dp, 25.dp))
                                }
                                IconButton(onClick = onRepeat, modifier = Modifier.size(44.dp)) {
                                    Icon(if (uiState.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                        str(R.string.repeat), tint = Color.White.copy(alpha = if (uiState.repeatMode != RepeatMode.OFF) 1f else 0.55f), modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(28.dp))
                key(uiState.currentTrack?.id) {
                    SyncedLyricsView(
                        lyrics = uiState.lyrics, positionState = clock, onSeekTo = onSeekTo,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        isLoading = uiState.isLoadingLyrics, offsetMs = uiState.lyricsOffsetMs,
                        track = uiState.currentTrack, lyricsClockSource = clockSource,
                        isPlaying = uiState.isPlaying, isBuffering = uiState.isBuffering,
                        audioLeadingSilenceMs = uiState.audioLeadingSilenceMs, readingFocusFraction = 0.5f
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(horizontal = landscapeSideInset).padding(8.dp)) {
                Icon(Icons.Default.Close, str(R.string.close), tint = Color.White)
            }

        }
    }
    }
}
