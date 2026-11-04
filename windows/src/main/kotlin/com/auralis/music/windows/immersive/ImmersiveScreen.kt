package com.auralis.music.windows.immersive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subject
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.i18n.desktopText
import com.auralis.music.ui.theme.ArtworkPalette
import com.auralis.music.windows.player.AnimatedCover
import com.auralis.music.windows.player.LyricsPanel
import com.auralis.music.windows.player.PlayerBackdrop
import com.auralis.music.windows.player.rememberMotionAura
import com.auralis.music.windows.player.rememberShownPosition
import com.auralis.music.windows.search.DesktopArtwork
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/** The controls and the pointer hide after this long without the mouse moving. */
private const val CONTROLS_HIDE_MS = 2_600L
/** The layout is designed at this short side and scales to fill any display, whatever its resolution. */
private const val DESIGN_SHORT_SIDE = 720f

private val BlankCursor: PointerIcon by lazy {
    PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
        java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "blank"))
}

/** Which arrangement this display's shape calls for. */
internal enum class ImmersiveShape { STANDARD, PORTRAIT, ULTRAWIDE }

internal fun immersiveShape(width: Float, height: Float) = when {
    height > width -> ImmersiveShape.PORTRAIT
    width / height >= 2f -> ImmersiveShape.ULTRAWIDE
    else -> ImmersiveShape.STANDARD
}

/** One display in immersive mode. Each display picks its own view; the look follows the player background. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ImmersiveScreen(
    view: ImmersiveView,
    onView: (ImmersiveView) -> Unit,
    playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController,
    artwork: String?,
    palette: ArtworkPalette,
    backgroundStyle: String,
    inlineLyric: Boolean,
    displays: List<ImmersiveDisplay>,
    shownDisplays: List<ImmersiveDisplay>,
    thisDisplay: ImmersiveDisplay,
    onToggleDisplay: (Int) -> Unit,
    onLeave: () -> Unit
) {
    val queue by playback.queue.collectAsState()
    val track = queue.current
    val lyricsState by lyrics.state.collectAsState()
    val hasLyrics = lyricsState.isLoading || lyricsState.lyrics?.lines?.isNotEmpty() == true
    // Without a song there's only the clock; without lyrics, the lyrics display falls back to the artwork.
    val shown = when {
        track == null -> ImmersiveView.CLOCK
        view == ImmersiveView.LYRICS && !hasLyrics -> ImmersiveView.ARTWORK
        else -> view
    }
    val highRes = artwork?.let { getHighResArtworkUrl(it) ?: it }
    var moves by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(false) }
    var lastPointer by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(moves) {
        if (moves == 0L) return@LaunchedEffect
        controlsVisible = true
        delay(CONTROLS_HIDE_MS)
        controlsVisible = false
    }
    // The window opens as plain black; the picture fades in over it.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(600)) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)
        .then(if (controlsVisible) Modifier else Modifier.pointerHoverIcon(BlankCursor))
        .onPointerEvent(PointerEventType.Move) { event ->
            val position = event.changes.first().position
            // The first event is often the window appearing under a still pointer.
            val moved = lastPointer?.let { (it - position).getDistance() > 3f } == true
            lastPointer = position
            if (moved) moves++
        }) {
        val window = LocalDensity.current
        val shape = immersiveShape(maxWidth.value, maxHeight.value)
        val shortSidePx = minOf(maxWidth.value, maxHeight.value) * window.density
        CompositionLocalProvider(LocalDensity provides Density(shortSidePx / DESIGN_SHORT_SIDE, window.fontScale)) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value }) {
                val canvasTrack = track?.takeIf {
                    shown == ImmersiveView.LYRICS && shape != ImmersiveShape.PORTRAIT
                }
                val motionAura = if (canvasTrack != null) rememberMotionAura(canvasTrack.id) else null
                val canvasReady by remember(motionAura) {
                    derivedStateOf { motionAura?.texture?.value != null }
                }
                PlayerBackdrop(if (canvasReady) "Dynamic" else backgroundStyle, highRes, palette, motionAura)
                if (canvasTrack != null && motionAura != null) {
                    AnimatedCover(
                        canvasTrack,
                        Modifier.fillMaxWidth(.54f).fillMaxHeight()
                            .graphicsLayer {
                                alpha = if (canvasReady) 1f else 0f
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    Brush.horizontalGradient(.62f to Color.Black, 1f to Color.Transparent),
                                    blendMode = BlendMode.DstIn
                                )
                            },
                        motionAura = motionAura
                    ) {}
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = .28f), .55f to Color.Black.copy(alpha = .12f), 1f to Color.Black.copy(alpha = .5f))))
                when {
                    shown == ImmersiveView.CLOCK -> ClockView(shape, track, highRes, playback, lyrics, inlineLyric, queue.tracks.getOrNull(queue.index + 1))
                    shown == ImmersiveView.LYRICS && track != null ->
                        LyricsView(track, highRes, playback, lyrics, canvasReady)
                    track != null -> ArtworkView(shape, track, highRes, playback)
                }
                AnimatedVisibility(controlsVisible, enter = fadeIn(tween(160)), exit = fadeOut(tween(300)),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)) {
                    ImmersiveControls(track, view, onView, playback, displays, shownDisplays, thisDisplay, onToggleDisplay, onLeave)
                }
            }
        }
    }
}

@Composable
private fun rememberNow() = produceState(LocalDateTime.now()) {
    while (true) {
        val now = LocalDateTime.now()
        value = now
        // Wake just after the minute turns over.
        delay(60_000L - now.second * 1_000L - now.nano / 1_000_000L + 20L)
    }
}

@Composable
private fun rememberPlaying(playback: DesktopPlaybackController): Boolean {
    val state by playback.state.collectAsState()
    val remote by playback.connectRemote.collectAsState()
    return remote?.playing ?: (state == VlcAudioPlayer.State.PLAYING)
}

@Composable
private fun BigClock(fontSize: Int, modifier: Modifier = Modifier) {
    val now by rememberNow()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(dateText(now), fontSize = (fontSize / 8).sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = .82f))
        Text(clockText(now), fontSize = fontSize.sp, lineHeight = fontSize.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

/** The small clock in a corner of the lyrics and artwork displays. */
@Composable
private fun BoxScope.CornerClock() {
    val now by rememberNow()
    Column(Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 36.dp), horizontalAlignment = Alignment.End) {
        Text(clockText(now), fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
        Text(dateText(now), fontSize = 13.sp, color = Color.White.copy(alpha = .72f))
    }
}

@Composable
private fun SongText(track: Track, width: Dp, titleSize: Int, align: TextAlign = TextAlign.Start) {
    Text(track.title, Modifier.width(width), fontSize = titleSize.sp, fontWeight = FontWeight.Bold, color = Color.White,
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
    Spacer(Modifier.height(2.dp))
    Text(track.artist, Modifier.width(width), fontSize = (titleSize * 2 / 3).sp, color = Color.White.copy(alpha = .75f),
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
}

@Composable
private fun ImmersiveProgress(playback: DesktopPlaybackController, track: Track, width: Dp) {
    val remote by playback.connectRemote.collectAsState()
    val length by playback.lengthMs.collectAsState()
    val position by rememberShownPosition(playback, remote)
    val duration = remote?.durationMs?.takeIf { it > 0 } ?: length.takeIf { it > 0 } ?: ((track.duration ?: 0L) * 1000L)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.width(width)) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = .24f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Color.White))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ImmersiveTime(position)
            ImmersiveTime(duration)
        }
    }
}

@Composable
private fun ImmersiveTime(ms: Long) {
    val seconds = (ms / 1000).coerceAtLeast(0)
    Text("%d:%02d".format(seconds / 60, seconds % 60), fontSize = 12.sp, color = Color.White.copy(alpha = .7f))
}

/** The line being sung, under the clock; crossfades as the song moves on. */
@Composable
private fun CurrentLine(playback: DesktopPlaybackController, lyrics: DesktopLyricsController, modifier: Modifier = Modifier) {
    val state by lyrics.state.collectAsState()
    val remote by playback.connectRemote.collectAsState()
    val position by rememberShownPosition(playback, remote)
    val lines = state.lyrics?.lines.orEmpty()
    val line by remember(lines) { derivedStateOf { currentLyricLine(lines, position)?.text.orEmpty() } }
    Crossfade(line, modifier, tween(260), label = "immersiveLine") { text ->
        Text(text, Modifier.fillMaxWidth(), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun UpNext(next: Track?, width: Dp) {
    if (next == null) return
    Spacer(Modifier.height(8.dp))
    Text(desktopText("Up next: %s").format(next.title), Modifier.width(width), fontSize = 13.sp,
        color = Color.White.copy(alpha = .62f), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun NowPlayingRow(track: Track, artwork: String?, playback: DesktopPlaybackController, next: Track?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        DesktopArtwork(artwork, Modifier.size(112.dp), 12.dp, track.title, highRes = true)
        Column {
            SongText(track, 420.dp, 24)
            Spacer(Modifier.height(12.dp))
            ImmersiveProgress(playback, track, 420.dp)
            UpNext(next, 420.dp)
        }
    }
}

/** The clock, big, with the song underneath, like a lock screen. */
@Composable
private fun ClockView(shape: ImmersiveShape, track: Track?, artwork: String?, playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController, inlineLyric: Boolean, next: Track?) {
    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 56.dp)) {
        when (shape) {
            ImmersiveShape.PORTRAIT -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly) {
                BigClock(136)
                if (track != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DesktopArtwork(artwork, Modifier.fillMaxWidth(.82f).aspectRatio(1f), 18.dp, track.title, highRes = true)
                    Spacer(Modifier.height(28.dp))
                    SongText(track, 540.dp, 30, TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    ImmersiveProgress(playback, track, 540.dp)
                } else NothingPlaying()
            }
            ImmersiveShape.ULTRAWIDE -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly) {
                BigClock(176)
                if (track != null) NowPlayingRow(track, artwork, playback, next) else NothingPlaying()
            }
            ImmersiveShape.STANDARD -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                BigClock(176)
                if (inlineLyric && track != null) {
                    Spacer(Modifier.height(20.dp))
                    CurrentLine(playback, lyrics, Modifier.fillMaxWidth().height(84.dp))
                }
                Spacer(Modifier.weight(1f))
                if (track != null) NowPlayingRow(track, artwork, playback, next) else NothingPlaying()
            }
        }
    }
}

@Composable
private fun NothingPlaying() {
    Text(desktopText("Nothing playing"), fontSize = 20.sp, color = Color.White.copy(alpha = .7f))
}

/** Big synced lyrics: the same word-by-word view as the full player, on a screen of its own. */
@Composable
private fun LyricsView(
    track: Track,
    artwork: String?,
    playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController,
    canvasReady: Boolean
) {
    Box(Modifier.fillMaxSize()) {
        val lyricsWidth = if (canvasReady) .55f else 1f
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(lyricsWidth).fillMaxHeight()
            .padding(start = if (canvasReady) 24.dp else 64.dp, end = 64.dp, top = 40.dp, bottom = 96.dp)) {
            LyricsPanel(playback, lyrics)
        }
        CornerClock()
        Row(Modifier.align(Alignment.BottomStart).padding(start = 36.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DesktopArtwork(artwork, Modifier.size(52.dp), 8.dp, track.title)
            Column { SongText(track, 340.dp, 16) }
        }
    }
}

/** The artwork as the centrepiece. */
@Composable
private fun ArtworkView(shape: ImmersiveShape, track: Track, artwork: String?, playback: DesktopPlaybackController) {
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 48.dp), contentAlignment = Alignment.Center) {
            if (shape == ImmersiveShape.PORTRAIT) {
                val side = minOf(maxWidth, maxHeight * .6f)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    DesktopArtwork(artwork, Modifier.size(side), 20.dp, track.title, highRes = true)
                    Spacer(Modifier.height(32.dp))
                    SongText(track, side, 32, TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    ImmersiveProgress(playback, track, side)
                }
            } else {
                val side = minOf(maxHeight, maxWidth * .5f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                    DesktopArtwork(artwork, Modifier.size(side), 20.dp, track.title, highRes = true)
                    Column {
                        SongText(track, 420.dp, 36)
                        Spacer(Modifier.height(20.dp))
                        ImmersiveProgress(playback, track, 420.dp)
                    }
                }
            }
        }
        CornerClock()
    }
}

@Composable
private fun ImmersiveControls(track: Track?, view: ImmersiveView, onView: (ImmersiveView) -> Unit, playback: DesktopPlaybackController,
    displays: List<ImmersiveDisplay>, shownDisplays: List<ImmersiveDisplay>, thisDisplay: ImmersiveDisplay,
    onToggleDisplay: (Int) -> Unit, onLeave: () -> Unit) {
    val playing = rememberPlaying(playback)
    Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (track != null) {
            ControlIcon(Icons.Rounded.SkipPrevious, "Previous", playback::previous)
            ControlIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", playback::togglePlayPause)
            ControlIcon(Icons.Rounded.SkipNext, "Next", playback::next)
            ControlDivider()
        }
        ImmersiveView.entries.forEach { option ->
            val selected = option == view
            Surface(onClick = { onView(option) }, shape = RoundedCornerShape(14.dp),
                color = if (selected) Color.White.copy(alpha = .18f) else Color.Transparent, contentColor = Color.White) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(when (option) {
                        ImmersiveView.CLOCK -> Icons.Rounded.Schedule
                        ImmersiveView.LYRICS -> Icons.Rounded.Subject
                        ImmersiveView.ARTWORK -> Icons.Rounded.Image
                    }, null, Modifier.size(18.dp))
                    Text(desktopText(option.label), fontSize = 13.sp)
                }
            }
        }
        if (displays.size > 1) {
            ControlDivider()
            displays.forEachIndexed { index, display ->
                val on = display in shownDisplays
                Surface(onClick = { onToggleDisplay(index) }, shape = CircleShape,
                    color = if (on) Color.White.copy(alpha = .22f) else Color.Transparent,
                    contentColor = if (on) Color.White else Color.White.copy(alpha = .55f)) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", fontSize = 13.sp, fontWeight = if (display == thisDisplay) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        ControlDivider()
        ControlIcon(Icons.Rounded.Close, "Leave immersive mode", onLeave)
    }
}

@Composable
private fun ControlIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick) { Icon(icon, desktopText(label), tint = Color.White, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun ControlDivider() {
    Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = .22f)))
}
