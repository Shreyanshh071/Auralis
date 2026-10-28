package com.auralis.music.windows.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.auralis.music.desktop.playback.*
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.theme.*
import com.auralis.music.windows.search.DesktopArtwork
import com.auralis.music.ui.components.clickable
import kotlinx.coroutines.delay

internal val LocalMiniPopupControlsInset = staticCompositionLocalOf<State<Dp>?> { null }

/** Keep the lyric viewport stable while the overlay and its credits move. */
@Composable
internal fun MiniPopupLyricsArea(controlsVisible: Boolean, controlsInset: Dp, content: @Composable () -> Unit) {
    val inset = animateDpAsState(if (controlsVisible) controlsInset else 0.dp,
        tween(300, easing = FastOutSlowInEasing), label = "mini-player lyrics inset")
    CompositionLocalProvider(LocalMiniPopupControlsInset provides inset) {
        Box(Modifier.fillMaxSize().padding(top = 12.dp)) { content() }
    }
}

internal class MiniPopupPosition(private val margin: Int, private val position: IntOffset? = null) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val desired = position ?: IntOffset(windowSize.width - popupContentSize.width - margin,
            windowSize.height - popupContentSize.height - margin)
        return IntOffset(desired.x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            desired.y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

/** Non-modal, square corner popup: no dim scrim, title bar, taskbar entry or separate app window. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun LyricsMiniPopup(track: Track, palette: ArtworkPalette, onClose: () -> Unit,
    onOpenPlayer: () -> Unit, playback: DesktopPlaybackController?, lyricContent: @Composable () -> Unit) {
    val density = LocalDensity.current
    val screen = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val margin = with(density) { 16.dp.roundToPx() }
    val minimum = with(density) { 220.dp.roundToPx() }
    val limits = IntRect(0, 0, screen.width.coerceAtLeast(1), screen.height.coerceAtLeast(1))
    var requestedBounds by remember { mutableStateOf<IntRect?>(null) }
    val initialSide = with(density) { 340.dp.roundToPx() }.coerceAtMost(minOf(limits.width, limits.height))
    val initialPosition = MiniPopupPosition(margin).calculatePosition(IntRect.Zero, screen, LayoutDirection.Ltr, IntSize(initialSide, initialSide))
    val bounds = fitMiniPopup(requestedBounds ?: IntRect(initialPosition, IntSize(initialSide, initialSide)), limits, minimum)
    val popupWidth = with(density) { bounds.width.toDp() }
    val popupHeight = with(density) { bounds.height.toDp() }
    val controlScale = (minOf(popupWidth.value, popupHeight.value) / 340f).coerceIn(.85f, 1.25f)
    val stackedControls = popupWidth - (24 * controlScale).dp < 520.dp
    val metadataHeight = (42 * controlScale).coerceAtLeast(40f)
    val controlsInset = if (playback == null) 90.dp else
        (metadataHeight + (if (stackedControls) 62f else 18f) * controlScale + 22f).dp
    var resizing by remember { mutableStateOf(false) }
    var playlistMenuOpen by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    var activity by remember { mutableLongStateOf(0) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(activity, hovered) { idle = false; if (hovered) { delay(2200); idle = true } }
    val visible = resizing || playlistMenuOpen || hovered && !idle
    val provider = remember(margin, bounds.topLeft) { MiniPopupPosition(margin, bounds.topLeft) }
    Popup(popupPositionProvider = provider, onDismissRequest = onClose,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
        Box(Modifier.size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() }).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF101014))
            .onPointerEvent(PointerEventType.Enter) { hovered = true; activity++ }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .onPointerEvent(PointerEventType.Move) { activity++ }) {
            PlayerBackdrop(LocalAppearanceSettings.current.playerBackgroundStyle, track.thumbnail, palette)
            MiniPopupLyricsArea(visible, controlsInset, lyricContent)
            AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(300)),
                modifier = Modifier.align(Alignment.BottomCenter)) {
                val state = playback?.state?.collectAsState()?.value
                val remote = playback?.connectRemote?.collectAsState()?.value
                MiniPopupControls(track, controlScale,
                    playing = if (playback == null) null else remote?.playing ?: (state == VlcAudioPlayer.State.PLAYING),
                    onOpenPlayer = { onClose(); onOpenPlayer() }, onPrevious = { playback?.previous() },
                    onToggle = { playback?.togglePlayPause() }, onNext = { playback?.next() },
                    onPlaylistMenu = { playlistMenuOpen = it }) {
                    if (playback != null) MiniPopupSeekBar(playback, track)
                }
            }
            if (visible) IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).size(30.dp)) {
                Icon(Icons.Rounded.Close, "Close mini-player", tint = Color.White, modifier = Modifier.size(17.dp))
            }
            MiniPopupResizeHandles(bounds, limits, minimum,
                onResizing = { resizing = it; activity++ },
                onResize = { requestedBounds = it; activity++ })
        }
    }
}

/** Separate hit strips leave lyric scrolling, seeking and playback controls untouched. */
@Composable
internal fun BoxScope.MiniPopupResizeHandles(bounds: IntRect, limits: IntRect, minimum: Int,
    onResizing: (Boolean) -> Unit, onResize: (IntRect) -> Unit) {
    val currentBounds by rememberUpdatedState(bounds)
    val resize by rememberUpdatedState(onResize)
    val resizing by rememberUpdatedState(onResizing)
    MiniPopupEdge.entries.forEach { edge ->
        val corner = (edge.left || edge.right) && (edge.top || edge.bottom)
        val alignment = when (edge) {
            MiniPopupEdge.LEFT -> Alignment.CenterStart; MiniPopupEdge.TOP -> Alignment.TopCenter
            MiniPopupEdge.RIGHT -> Alignment.CenterEnd; MiniPopupEdge.BOTTOM -> Alignment.BottomCenter
            MiniPopupEdge.TOP_LEFT -> Alignment.TopStart; MiniPopupEdge.TOP_RIGHT -> Alignment.TopEnd
            MiniPopupEdge.BOTTOM_LEFT -> Alignment.BottomStart; MiniPopupEdge.BOTTOM_RIGHT -> Alignment.BottomEnd
        }
        val cursor = when (edge) {
            MiniPopupEdge.LEFT, MiniPopupEdge.RIGHT -> java.awt.Cursor.E_RESIZE_CURSOR
            MiniPopupEdge.TOP, MiniPopupEdge.BOTTOM -> java.awt.Cursor.N_RESIZE_CURSOR
            MiniPopupEdge.TOP_LEFT, MiniPopupEdge.BOTTOM_RIGHT -> java.awt.Cursor.NW_RESIZE_CURSOR
            else -> java.awt.Cursor.NE_RESIZE_CURSOR
        }
        var coordinates by remember(edge) { mutableStateOf<LayoutCoordinates?>(null) }
        val size = if (corner) Modifier.size(12.dp) else if (edge.left || edge.right)
            Modifier.fillMaxHeight().padding(vertical = 12.dp).width(7.dp)
        else Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(7.dp)
        Box(Modifier.align(alignment).then(size)
            .onGloballyPositioned { coordinates = it }
            .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(cursor)))
            .pointerInput(edge, limits, minimum) {
                var start = IntRect.Zero
                var pointer = Offset.Zero
                var first = true
                try { detectDragGestures(onDragStart = { point ->
                    start = currentBounds
                    pointer = coordinates?.localToWindow(point) ?: point
                    first = true; resizing(true)
                }, onDragEnd = { resizing(false) }, onDragCancel = { resizing(false) }) { change, amount ->
                    change.consume()
                    if (first) { pointer -= amount; first = false }
                    val position = coordinates?.localToWindow(change.position) ?: change.position
                    resize(resizeMiniPopup(start, edge, position - pointer, limits, minimum))
                } } finally { resizing(false) }
            })
    }
}

@Composable
private fun MiniPopupSeekBar(playback: DesktopPlaybackController, track: Track) {
    val remote by playback.connectRemote.collectAsState()
    val localDuration by playback.lengthMs.collectAsState()
    val fallback = rememberShownPosition(playback, remote)
    val duration = remote?.durationMs?.takeIf { it > 0 } ?: localDuration.takeIf { it > 0 } ?: track.duration * 1000L
    val state by playback.state.collectAsState()
    val position by com.auralis.music.ui.lyrics.rememberLyricsClock(playback.clock,
        enabled = remote?.playing ?: (state == VlcAudioPlayer.State.PLAYING), fallbackPositionMs = fallback)
    var drag by remember(track.id) { mutableStateOf<Float?>(null) }
    HoverSlider(value = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
        onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { playback.seekTo((it * duration).toLong()) }; drag = null },
        enabled = duration > 0, thin = 2.5.dp, thick = 4.dp, hitSize = 22.dp,
        modifier = Modifier.fillMaxWidth())
}
