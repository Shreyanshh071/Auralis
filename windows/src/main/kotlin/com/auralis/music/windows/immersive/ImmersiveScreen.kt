package com.auralis.music.windows.immersive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
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
import com.auralis.music.ui.lyrics.ExperimentalLyricsLine
import com.auralis.music.ui.lyrics.lyricsBlurLayer
import com.auralis.music.ui.lyrics.rememberLyricsClock
import com.auralis.music.ui.theme.ArtworkPalette
import com.auralis.music.ui.theme.LocalAppearanceSettings
import com.auralis.music.windows.LiquidPopupSurface
import com.auralis.music.windows.LocalPopupGlassSource
import com.auralis.music.windows.recordPopupGlass
import com.auralis.music.windows.rememberPopupGlassSource
import com.auralis.music.windows.player.LyricsPanel
import com.auralis.music.windows.player.PlayerBackdrop
import com.auralis.music.windows.player.rememberPlayerLyricsAppearance
import com.auralis.music.windows.player.rememberShownPosition
import com.auralis.music.windows.search.DesktopArtwork
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.floor

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
    onLeave: () -> Unit,
    animateEntry: Boolean = true
) {
    val queue by playback.queue.collectAsState()
    val track = queue.current
    val lyricsState by lyrics.state.collectAsState()
    val hasLyrics = lyricsState.isLoading || lyricsState.lyrics?.lines?.isNotEmpty() == true
    // Without a song there's only the clock; without lyrics, the lyrics display falls back to the clock.
    val shown = when {
        track == null -> ImmersiveView.CLOCK
        view == ImmersiveView.LYRICS && !hasLyrics -> ImmersiveView.CLOCK
        else -> view
    }
    val highRes = artwork?.let { getHighResArtworkUrl(it) ?: it }
    var moves by remember { mutableLongStateOf(0L) }
    var cursorVisible by remember { mutableStateOf(false) }
    var lastPointer by remember { mutableStateOf<Offset?>(null) }
    val dockInteraction = remember { MutableInteractionSource() }
    val dockHovered by dockInteraction.collectIsHoveredAsState()
    LaunchedEffect(moves) {
        if (moves == 0L) return@LaunchedEffect
        cursorVisible = true
        delay(CONTROLS_HIDE_MS)
        cursorVisible = false
    }
    // The window opens as plain black; the picture fades in over it.
    val reveal = remember { Animatable(if (animateEntry) 0f else 1f) }
    LaunchedEffect(animateEntry) {
        if (animateEntry) reveal.animateTo(1f, tween(160))
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)
        .then(if (cursorVisible || dockHovered) Modifier else Modifier.pointerHoverIcon(BlankCursor))
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
            val dockBackdrop = rememberPopupGlassSource()
            Box(Modifier.fillMaxSize().drawWithContent {
                drawContent()
                // Fade from black without making the fullscreen scene a translucent layer.
                val shade = 1f - reveal.value
                if (shade > 0f) drawRect(Color.Black.copy(alpha = shade))
            }) {
                Box(Modifier.matchParentSize().recordPopupGlass(dockBackdrop).background(Color.Black)) {
                    PlayerBackdrop(backgroundStyle, highRes, palette)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = .28f), .55f to Color.Black.copy(alpha = .12f), 1f to Color.Black.copy(alpha = .5f))))
                }
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            shown == ImmersiveView.CLOCK -> ClockView(shape, track, playback, lyrics, inlineLyric)
                            shown == ImmersiveView.LYRICS && track != null -> LyricsView(playback, lyrics)
                        }
                    }
                    CompositionLocalProvider(LocalPopupGlassSource provides dockBackdrop) {
                        ImmersiveControls(
                            track, view, onView, playback, displays, shownDisplays, thisDisplay, onToggleDisplay, onLeave,
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp)
                                .hoverable(dockInteraction),
                            dockTrack = track?.takeIf { shown == ImmersiveView.CLOCK },
                            artwork = highRes
                        )
                    }
                }
                if (shown == ImmersiveView.LYRICS && track != null) {
                    Row(
                        Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 16.dp)
                            .fillMaxWidth(.28f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DesktopArtwork(highRes, Modifier.size(52.dp), 8.dp, track.title)
                        Column(Modifier.weight(1f)) {
                            Text(track.title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, fontSize = 11.sp, color = Color.White.copy(alpha = .75f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
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

/** The small clock in a corner of the lyrics display. */
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
    Text(track.title, Modifier.width(width), fontSize = titleSize.sp, lineHeight = (titleSize * 1.2f).sp, fontWeight = FontWeight.Bold, color = Color.White,
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
    Spacer(Modifier.height(2.dp))
    Text(track.artist, Modifier.width(width), fontSize = (titleSize * 2 / 3).sp, lineHeight = (titleSize * .85f).sp, color = Color.White.copy(alpha = .75f),
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
}

@Composable
private fun ImmersiveProgress(playback: DesktopPlaybackController, track: Track, width: Dp) {
    val remote by playback.connectRemote.collectAsState()
    val length by playback.lengthMs.collectAsState()
    val position by rememberShownPosition(playback, remote)
    val duration = remote?.durationMs?.takeIf { it > 0 } ?: length.takeIf { it > 0 } ?: ((track.duration ?: 0L) * 1000L)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    var dragging by remember(track.id) { mutableStateOf(false) }
    var draft by remember(track.id) { mutableFloatStateOf(0f) }
    val shownPosition = if (dragging) (draft * duration).toLong() else position
    Column(Modifier.width(width)) {
        com.auralis.music.windows.player.HoverSlider(
            value = if (dragging) draft else fraction,
            onValueChange = { draft = it; dragging = true },
            onValueChangeFinished = {
                if (duration > 0) playback.seekTo((draft * duration).toLong())
                dragging = false
            },
            enabled = duration > 0,
            thin = 4.dp,
            thick = 6.dp,
            hitSize = 24.dp,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ImmersiveTime(shownPosition)
            ImmersiveTime(duration)
        }
    }
}

@Composable
private fun ImmersiveTime(ms: Long) {
    val seconds = (ms / 1000).coerceAtLeast(0)
    Text("%d:%02d".format(seconds / 60, seconds % 60), fontSize = 12.sp, lineHeight = 15.sp, color = Color.White.copy(alpha = .7f))
}

private val ClockLyricRowHeight = 58.dp

/** Three slots centered on the lead vocal, using the same line renderer as the player. */
@Composable
private fun ClockLyrics(playback: DesktopPlaybackController, lyrics: DesktopLyricsController, modifier: Modifier = Modifier) {
    val state by lyrics.state.collectAsState()
    val data = state.lyrics ?: return
    val lines = remember(data.lines, data.provider) {
        data.lines.filter { !it.isBackground && !it.isInstrumental && it.text.isNotBlank() }
            .map { line ->
                if (data.provider == com.auralis.music.domain.model.LyricsProvider.SPICY_LYRICS) line
                else com.auralis.music.data.parser.WordTiming.splitMergedWordsInLine(line)
            }.sortedBy { it.time }
    }
    if (lines.none { it.time > 0 }) return

    val remote by playback.connectRemote.collectAsState()
    val position = rememberLyricsClock(playback.clock, rememberPlaying(playback), rememberShownPosition(playback, remote))
    val confirmedPosition = rememberShownPosition(playback, remote)
    val latest by remember(lines, confirmedPosition) {
        // Native timing selects rows. Interpolated time still animates the words,
        // but corrections around a boundary must not reverse the row transition.
        derivedStateOf { lines.indexOfLast { it.time <= confirmedPosition.value } }
    }

    val breaks = remember(lines) { com.auralis.music.ui.lyrics.phoneInstrumentalBreaks(lines) }
    val gap by remember(lines, breaks, position) {
        derivedStateOf {
            val now = position.value
            val firstStart = lines.firstOrNull()?.time ?: 0L
            if (firstStart >= 1_500L && now < firstStart) 0L to firstStart
            else breaks.values.firstOrNull { (start, end) -> now >= start && now < end }
        }
    }
    val scroll = remember(state.trackId, lines) { Animatable(latest.toFloat()) }
    LaunchedEffect(scroll, latest) {
        val target = latest.toFloat()
        if (abs(scroll.value - target) > 2f) scroll.snapTo(target)
        else scroll.animateTo(target, tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val firstVisible by remember(scroll) { derivedStateOf { floor(scroll.value).toInt() - 1 } }
    val appearance = rememberPlayerLyricsAppearance()
    val spicy = com.auralis.music.domain.model.LyricsAnimationMode.fromDisplayName(appearance.lyricsAnimation) ==
        com.auralis.music.domain.model.LyricsAnimationMode.SPICY_LYRICS

    CompositionLocalProvider(
        LocalAppearanceSettings provides appearance,
        com.auralis.music.ui.lyrics.LocalLyricsGlyphMotion provides true
    ) {
        Box(
            modifier.height(ClockLyricRowHeight * 3).clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent, .12f to Color.Black,
                            .88f to Color.Black, 1f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            for (index in firstVisible..firstVisible + 3) {
                val line = lines.getOrNull(index) ?: continue
                key(state.trackId, index) {
                    val distance = abs(index - scroll.value).coerceAtMost(1f)
                    // Spicy already animates glyph scale. A second row zoom compounds
                    // that motion as the line arrives at the center.
                    val scale = if (spicy) 1f else 1f - .20f * distance
                    val blur = 1.4f * distance
                    Box(
                        Modifier.widthIn(max = 760.dp).fillMaxWidth(.92f).height(ClockLyricRowHeight)
                            .graphicsLayer {
                                translationY = (index - scroll.value) * ClockLyricRowHeight.toPx()
                                scaleX = scale
                                scaleY = scale
                                alpha = if (gap != null && index == latest) 0f else 1f - .46f * distance
                            }.lyricsBlurLayer(true, false, overflow = 20.dp) { blur.dp.toPx() },
                        contentAlignment = Alignment.Center
                    ) {
                        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val measurer = rememberTextMeasurer()
                            val fontFamily = MaterialTheme.typography.bodyLarge.fontFamily
                            val density = LocalDensity.current
                            // Match the renderer's typography and subtract its 24dp side padding.
                            // Swelling glyphs need additional room beyond the static paragraph.
                            val measureWidth = ((constraints.maxWidth - with(density) { 48.dp.toPx() }) / 1.15f).toInt().coerceAtLeast(1)
                            val measureHeight = (with(density) { (ClockLyricRowHeight - 16.dp).toPx() } / 1.15f).toInt().coerceAtLeast(1)
                            val fontSize = remember(measurer, line.text, fontFamily, density, measureWidth, measureHeight) {
                                (27 downTo 8).firstOrNull { size ->
                                    val layout = measurer.measure(
                                        line.text,
                                        style = TextStyle(
                                            fontSize = size.sp,
                                            lineHeight = (size * 1.1f).sp,
                                            letterSpacing = (-.5).sp,
                                            fontFamily = fontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        maxLines = 2,
                                        constraints = Constraints(maxWidth = measureWidth)
                                    )
                                    !layout.hasVisualOverflow && layout.size.height <= measureHeight
                                } ?: 8
                            }
                            ExperimentalLyricsLine(
                                index = index,
                                line = line,
                                isSynced = true,
                                isActiveLine = true,
                                bgVisible = false,
                                isSelected = false,
                                isSelectionModeActive = false,
                                currentPositionState = position,
                                lyricsTextSize = fontSize.toFloat(),
                                lyricsLineSpacing = 1f,
                                expressiveAccent = Color.White,
                                lyricsTextPosition = "Center",
                                isAutoScrollEnabled = true,
                                displayedCurrentLineIndex = latest,
                                syncType = data.syncType,
                                onSizeChanged = {},
                                onClick = { playback.seekTo(line.time) },
                                onLongClick = {},
                                nextLineTime = lines.getOrNull(index + 1)?.time
                            )
                        }
                    }
                }
            }
            val rest = gap
            if (rest != null) {
                com.auralis.music.ui.lyrics.LyricsIntervalIndicator(
                    gapStartMs = rest.first,
                    gapEndMs = rest.second,
                    currentPositionMs = position.value,
                    visible = true,
                    isMetroLyrics = true,
                    dotSize = 10.dp,
                    compact = true,
                    onSkip = { playback.seekTo(rest.second) },
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun NowPlayingRow(track: Track, artwork: String?, playback: DesktopPlaybackController) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DesktopArtwork(artwork, Modifier.size(48.dp), 8.dp, track.title, highRes = true)
        Column {
            SongText(track, 300.dp, 16)
            Spacer(Modifier.height(2.dp))
            ImmersiveProgress(playback, track, 300.dp)
        }
    }
}

@Composable
private fun ClockView(shape: ImmersiveShape, track: Track?, playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController, inlineLyric: Boolean) {
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp),
        contentAlignment = Alignment.Center
    ) {
        val showLyrics = shape == ImmersiveShape.STANDARD && inlineLyric && track != null
        val lyricHeight = if (showLyrics) ClockLyricRowHeight * 3 + 8.dp else 0.dp
        val maxClockSize = if (shape == ImmersiveShape.PORTRAIT) 136 else 176
        val clockSize = ((maxHeight - lyricHeight).value / 1.4f).toInt().coerceIn(44, maxClockSize)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BigClock(clockSize)
            if (showLyrics) {
                Spacer(Modifier.height(8.dp))
                ClockLyrics(playback, lyrics, Modifier.fillMaxWidth())
            }
            if (track == null) NothingPlaying()
        }
    }
}

@Composable
private fun NothingPlaying() {
    Text(desktopText("Nothing playing"), fontSize = 20.sp, color = Color.White.copy(alpha = .7f))
}

/** Big synced lyrics: the same word-by-word view as the full player, on a screen of its own. */
@Composable
private fun LyricsView(playback: DesktopPlaybackController, lyrics: DesktopLyricsController) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(start = 32.dp, end = 40.dp, top = 40.dp, bottom = 12.dp)) {
            LyricsPanel(playback, lyrics, showCredits = false, textScale = .72f)
        }
        CornerClock()

    }
}

@Composable
private fun ImmersiveControls(
    track: Track?, view: ImmersiveView, onView: (ImmersiveView) -> Unit, playback: DesktopPlaybackController,
    displays: List<ImmersiveDisplay>, shownDisplays: List<ImmersiveDisplay>, thisDisplay: ImmersiveDisplay,
    onToggleDisplay: (Int) -> Unit, onLeave: () -> Unit, modifier: Modifier = Modifier,
    dockTrack: Track? = null, artwork: String? = null
) {
    val playing = rememberPlaying(playback)
    LiquidPopupSurface(modifier, frosted = false) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (dockTrack != null) {
                NowPlayingRow(dockTrack, artwork, playback)
                Spacer(Modifier.height(2.dp))
            }
            if (track != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControlIcon(Icons.Rounded.SkipPrevious, "Previous", playback::previous)
                    ControlIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", playback::togglePlayPause)
                    ControlIcon(Icons.Rounded.SkipNext, "Next", playback::next)
                }
                Spacer(Modifier.height(2.dp))
            }
            Row(
                Modifier.width(360.dp).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ImmersiveView.entries.forEach { option ->
                    val selected = option == view
                    DockTile(
                        icon = if (option == ImmersiveView.CLOCK) Icons.Rounded.Schedule else Icons.AutoMirrored.Rounded.Subject,
                        label = option.label,
                        modifier = Modifier.weight(1f),
                        selected = selected,
                        onClick = { onView(option) }
                    )
                    Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = .28f)))
                }
                DockTile(Icons.Rounded.Close, "Close", Modifier.weight(1f), onClick = onLeave)
            }
            if (displays.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    displays.forEachIndexed { index, display ->
                        val on = display in shownDisplays
                        Surface(onClick = { onToggleDisplay(index) }, shape = CircleShape,
                            color = if (on) Color.White.copy(alpha = .20f) else Color.Transparent,
                            contentColor = if (on) Color.White else Color.White.copy(alpha = .55f)) {
                            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                                Text("${index + 1}", fontSize = 12.sp, fontWeight = if (display == thisDisplay) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 44.dp, height = 36.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desktopText(label), tint = Color.White, modifier = Modifier.size(25.dp))
    }
}

@Composable
private fun DockTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    selected: Boolean? = null,
    onClick: () -> Unit
) {
    Row(
        modifier.height(32.dp).clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = if (selected == true) .18f else 0f))
            .then(if (selected == null) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier.selectable(selected, role = Role.Tab, onClick = onClick)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = Color.White)
        Text(desktopText(label), fontSize = 12.sp, lineHeight = 14.sp, color = Color.White, maxLines = 1)
    }
}