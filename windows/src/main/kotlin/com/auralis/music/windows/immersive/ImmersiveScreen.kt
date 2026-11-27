package com.auralis.music.windows.immersive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.i18n.desktopText
import com.auralis.music.ui.lyrics.ExperimentalLyricsLine
import com.auralis.music.ui.lyrics.lyricsBlurLayer
import com.auralis.music.ui.lyrics.rememberLyricsClock
import com.auralis.music.ui.player.ClassicPauseGlyph
import com.auralis.music.ui.player.ClassicPlayGlyph
import com.auralis.music.ui.player.ClassicSkipGlyph
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
    animateEntry: Boolean = true,
    onOpenInApp: (ImmersiveBrowse) -> Unit = {}
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
                ImmersiveBackdropLayer(
                    backgroundStyle = backgroundStyle,
                    artwork = highRes,
                    palette = palette,
                    modifier = Modifier.matchParentSize().recordPopupGlass(dockBackdrop)
                )
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(
                        targetState = shown,
                        transitionSpec = {
                            // One sideways move, like the bead in the switch: Lyrics sits to the right of Clock.
                            val toRight = targetState == ImmersiveView.LYRICS
                            val slide = tween<IntOffset>(420, easing = FastOutSlowInEasing)
                            (slideInHorizontally(slide) { if (toRight) it else -it } + fadeIn(tween(260, delayMillis = 60)))
                                .togetherWith(slideOutHorizontally(slide) { if (toRight) -it else it } + fadeOut(tween(200)))
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        label = "ImmersiveViewSwitch"
                    ) { targetView ->
                        Box(Modifier.fillMaxSize()) {
                            when {
                                targetView == ImmersiveView.CLOCK -> ClockView(shape, track, playback, lyrics, inlineLyric)
                                targetView == ImmersiveView.LYRICS && track != null -> LyricsView(playback, lyrics)
                            }
                        }
                    }
                    CompositionLocalProvider(LocalPopupGlassSource provides dockBackdrop) {
                        ImmersiveControls(
                            track, view, onView, playback, displays, shownDisplays, thisDisplay, onToggleDisplay, onLeave,
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp)
                                .hoverable(dockInteraction),
                            artwork = highRes,
                            onOpenInApp = onOpenInApp
                        )
                    }
                }
            }
        }
    }
}

/** Song-synced full-screen backdrop with the contrast gradient shared by Immersive and Lock Screen modes. */
@Composable
internal fun ImmersiveBackdropLayer(
    backgroundStyle: String,
    artwork: String?,
    palette: ArtworkPalette,
    modifier: Modifier = Modifier
) {
    Box(modifier.background(Color.Black)) {
        PlayerBackdrop(backgroundStyle, artwork, palette)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = .28f),
                    .55f to Color.Black.copy(alpha = .12f),
                    1f to Color.Black.copy(alpha = .5f)
                )
            )
        )
    }
}

@Composable
internal fun rememberNow() = produceState(LocalDateTime.now()) {
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
internal fun BigClock(
    fontSize: Int,
    modifier: Modifier = Modifier,
    dateFontSize: Int = fontSize / 8,
    timeSpacing: Dp = 0.dp,
    liquidGlass: Boolean = false
) {
    val now by rememberNow()
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(timeSpacing)
    ) {
        if (liquidGlass) {
            LiquidClockText(dateText(now), MaterialTheme.typography.bodyLarge.copy(
                fontSize = dateFontSize.sp, fontWeight = FontWeight.Medium
            ))
            LiquidClockText(clockText(now), MaterialTheme.typography.displayLarge.copy(
                fontSize = fontSize.sp, lineHeight = fontSize.sp,
                fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"
            ))
        } else {
            Text(dateText(now), fontSize = dateFontSize.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = .82f))
            Text(clockText(now), fontSize = fontSize.sp, lineHeight = fontSize.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1)
        }
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
    Text(track.title, Modifier.width(width), fontSize = titleSize.sp, lineHeight = (titleSize * 1.15f).sp, fontWeight = FontWeight.Bold, color = Color.White,
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
    Spacer(Modifier.height(1.dp))
    Text(track.artist, Modifier.width(width), fontSize = (titleSize * 2 / 3).sp, lineHeight = (titleSize * .8f).sp, color = Color.White.copy(alpha = .75f),
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
}

@Composable
private fun ImmersiveProgress(playback: DesktopPlaybackController, track: Track, modifier: Modifier = Modifier) {
    val remote by playback.connectRemote.collectAsState()
    val length by playback.lengthMs.collectAsState()
    val position by rememberShownPosition(playback, remote)
    val duration = remote?.durationMs?.takeIf { it > 0 } ?: length.takeIf { it > 0 } ?: ((track.duration ?: 0L) * 1000L)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    var dragging by remember(track.id) { mutableStateOf(false) }
    var draft by remember(track.id) { mutableFloatStateOf(0f) }
    val shownPosition = if (dragging) (draft * duration).toLong() else position
    // Times at the ends of the bar, so the card stays one compact block under the clock.
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ImmersiveTime(shownPosition, Modifier.widthIn(min = 34.dp), TextAlign.End)
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
            hitSize = 16.dp,
            modifier = Modifier.weight(1f)
        )
        ImmersiveTime(duration, Modifier.widthIn(min = 34.dp))
    }
}

@Composable
private fun ImmersiveTime(ms: Long, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    val seconds = (ms / 1000).coerceAtLeast(0)
    Text("%d:%02d".format(seconds / 60, seconds % 60), modifier, fontSize = 11.sp, lineHeight = 13.sp,
        color = Color.White.copy(alpha = .62f), textAlign = align,
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"))
}

private val ClockLyricRowHeight = 58.dp

/** Three slots centered on the lead vocal, using the same line renderer as the player. */
@Composable
internal fun ClockLyrics(
    playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController,
    modifier: Modifier = Modifier,
    rowHeight: Dp = ClockLyricRowHeight
) {
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
    // As in the full player, each break is a row of its own after its line: the sung line scrolls
    // up and the dots arrive in the centre. Hiding the line under dots that popped in on top of it
    // looked broken mid-entry. Rows are positioned by slot, where a break takes one slot.
    val slots = remember(lines, breaks) {
        var extra = 0
        IntArray(lines.size) { index -> (index + extra).also { if (index in breaks) extra++ } }
    }
    val target by remember(lines, breaks, slots, confirmedPosition) {
        derivedStateOf {
            val now = confirmedPosition.value
            val sung = lines.indexOfLast { it.time <= now }
            when {
                sung < 0 -> -1f
                breaks[sung]?.let { (start, end) -> now >= start && now < end } == true -> slots[sung] + 1f
                else -> slots[sung].toFloat()
            }
        }
    }
    // The break after the current line (or the intro before the first), with the slot its dots occupy.
    val rest = when {
        latest < 0 -> lines.first().time.takeIf { it >= 1_500L }?.let { Triple(0L, it, -1f) }
        else -> breaks[latest]?.let { (start, end) -> Triple(start, end, slots[latest] + 1f) }
    }
    val scroll = remember(state.trackId, lines) { Animatable(target) }
    LaunchedEffect(scroll, target) {
        if (abs(scroll.value - target) > 2f) scroll.snapTo(target)
        else scroll.animateTo(target, tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    val visibleRows by remember(slots, scroll) {
        derivedStateOf { slots.indices.filter { abs(slots[it] - scroll.value) < 2f } }
    }
    val appearance = rememberPlayerLyricsAppearance()
    val spicy = com.auralis.music.domain.model.LyricsAnimationMode.fromDisplayName(appearance.lyricsAnimation) ==
        com.auralis.music.domain.model.LyricsAnimationMode.SPICY_LYRICS

    CompositionLocalProvider(
        LocalAppearanceSettings provides appearance,
        com.auralis.music.ui.lyrics.LocalLyricsGlyphMotion provides true
    ) {
        Box(
            modifier.height(rowHeight * 3).clipToBounds()
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
            for (index in visibleRows) {
                val line = lines[index]
                key(state.trackId, index) {
                    val distance = abs(slots[index] - scroll.value).coerceAtMost(1f)
                    // Spicy already animates glyph scale. A second row zoom compounds
                    // that motion as the line arrives at the center.
                    val scale = if (spicy) 1f else 1f - .20f * distance
                    val blur = 1.4f * distance
                    Box(
                        Modifier.widthIn(max = 760.dp).fillMaxWidth(.92f).height(rowHeight)
                            .graphicsLayer {
                                translationY = (slots[index] - scroll.value) * rowHeight.toPx()
                                scaleX = scale
                                scaleY = scale
                                alpha = 1f - .46f * distance
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
                            val measureHeight = (with(density) { (rowHeight - 16.dp).toPx() } / 1.15f).toInt().coerceAtLeast(1)
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
            if (rest != null) key(state.trackId, rest.first) {
                val (start, end, slot) = rest
                ClockRestDots(start, end, position, lead = if (spicy) 0L else 300L,
                    onSkip = { playback.seekTo(end) },
                    modifier = Modifier.align(Alignment.Center).graphicsLayer {
                        val offset = slot - scroll.value
                        translationY = offset * rowHeight.toPx()
                        alpha = 1f - .46f * abs(offset).coerceAtMost(1f)
                    })
            }
        }
    }
}

/**
 * The full player's break dots: composed ahead of the break and kept through it, so the dots
 * play their own clock-driven entry and exit. The per-frame clock read stays in this scope.
 */
@Composable
private fun ClockRestDots(start: Long, end: Long, position: androidx.compose.runtime.State<Long>, lead: Long,
    onSkip: () -> Unit, modifier: Modifier) {
    val visible by remember(start, end, lead, position) { derivedStateOf { position.value in (start - lead) until end } }
    val shown by remember(start, end, lead, position) { derivedStateOf { position.value.coerceIn(start - lead, end + 1L) } }
    com.auralis.music.ui.lyrics.LyricsIntervalIndicator(
        gapStartMs = start,
        gapEndMs = end,
        currentPositionMs = shown,
        visible = visible,
        isMetroLyrics = true,
        dotSize = 10.dp,
        compact = true,
        onSkip = onSkip,
        modifier = modifier
    )
}

/** Width of the dock card's content: the now-playing row, the divider and the tabs line up to it. */
private val DockWidth = 440.dp

@Composable
internal fun NowPlayingRow(track: Track, artwork: String?, playback: DesktopPlaybackController) {
    Column(Modifier.width(DockWidth).padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DesktopArtwork(artwork, Modifier.size(56.dp), 12.dp, track.title, highRes = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, fontSize = 13.sp, lineHeight = 16.sp, color = Color.White.copy(alpha = .68f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            TransportControls(playback, emphasized = true)
        }
        Spacer(Modifier.height(10.dp))
        ImmersiveProgress(playback, track, Modifier.fillMaxWidth())
    }
}

/** Previous, play/pause and next. Emphasized puts play in a solid white circle, the card's focal point. */
@Composable
private fun TransportControls(playback: DesktopPlaybackController, emphasized: Boolean, modifier: Modifier = Modifier) {
    val queue by playback.queue.collectAsState()
    val hasNext = queue.hasNext
    val state by playback.state.collectAsState()
    val remote by playback.connectRemote.collectAsState()
    val isPlaying = remote?.playing ?: (state == VlcAudioPlayer.State.PLAYING || state == VlcAudioPlayer.State.BUFFERING)
    val skipWidth = if (emphasized) 38.dp else 44.dp
    val skipGlyph = if (emphasized) DpSize(24.dp, 15.dp) else DpSize(28.dp, 17.dp)
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (emphasized) 6.dp else 4.dp, Alignment.CenterHorizontally)
    ) {
        TransportButton(onClick = playback::previous, width = skipWidth) { hovered ->
            ClassicSkipGlyph(forward = false, color = Color.White.copy(alpha = if (hovered) 1f else .86f),
                modifier = Modifier.size(skipGlyph))
        }
        if (emphasized) {
            TransportButton(onClick = playback::togglePlayPause, width = 44.dp, height = 44.dp, filled = true) {
                val ink = Color(0xFF111114)
                if (isPlaying) ClassicPauseGlyph(color = ink, modifier = Modifier.size(width = 15.dp, height = 18.dp))
                else ClassicPlayGlyph(color = ink, modifier = Modifier.offset(x = 1.5.dp).size(width = 16.dp, height = 19.dp))
            }
        } else {
            TransportButton(onClick = playback::togglePlayPause, width = 50.dp, height = 36.dp) { hovered ->
                val ink = Color.White.copy(alpha = if (hovered) 1f else .95f)
                if (isPlaying) ClassicPauseGlyph(color = ink, modifier = Modifier.size(width = 22.dp, height = 26.dp))
                else ClassicPlayGlyph(color = ink, modifier = Modifier.size(width = 23.dp, height = 27.dp))
            }
        }
        TransportButton(onClick = playback::next, width = skipWidth, enabled = hasNext) { hovered ->
            ClassicSkipGlyph(forward = true,
                color = Color.White.copy(alpha = if (hasNext) (if (hovered) 1f else .86f) else .35f),
                modifier = Modifier.size(skipGlyph))
        }
    }
}

/** One transport button: swells on hover, dips on press; [filled] draws the white play circle. */
@Composable
private fun TransportButton(
    onClick: () -> Unit, width: Dp, height: Dp = 32.dp, enabled: Boolean = true, filled: Boolean = false,
    content: @Composable (hovered: Boolean) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .90f else if (hovered && enabled) 1.08f else 1f,
        spring(stiffness = Spring.StiffnessMediumLow)
    )
    Box(
        Modifier
            .size(width = width, height = height)
            .scale(scale)
            .then(if (filled) Modifier.background(Color.White.copy(alpha = if (hovered) 1f else .94f), CircleShape) else Modifier)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content(hovered) }
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

/**
 * The dock is one slim glass row, identical on both views so switching never moves it:
 * cover, song and timeline, transport, then the Clock/Lyrics switch and close.
 */
/** The song's text column: long titles ellipsize instead of widening the dock. */
private val DockTextWidth = 190.dp
private val DockRadius = 26.dp
private val DockInset = 10.dp
private val DockControlHeight = 36.dp

/**
 * The phone's liquid glass, as its floating dock draws it (Android `liquidGlass` with `glassBarTint`):
 * a 9% white tint, 8dp blur, a 24dp lens over at least 30% of the short side, no dispersion,
 * vibrancy, the directional half-dp rim and Kyant's default shadow.
 */
private val PhoneGlassTint = Color.White.copy(alpha = .09f)
/** The phone dock's selected tab: a solid 14% white bead with a faint 10% white edge. */
private val PhoneIndicatorTint = Color.White.copy(alpha = .14f)
private val PhoneIndicatorEdge = Color.White.copy(alpha = .10f)

@Composable
private fun ImmersiveControls(
    track: Track?, view: ImmersiveView, onView: (ImmersiveView) -> Unit, playback: DesktopPlaybackController,
    displays: List<ImmersiveDisplay>, shownDisplays: List<ImmersiveDisplay>, thisDisplay: ImmersiveDisplay,
    onToggleDisplay: (Int) -> Unit, onLeave: () -> Unit, modifier: Modifier = Modifier, artwork: String? = null,
    onOpenInApp: (ImmersiveBrowse) -> Unit = {}
) {
    LiquidPopupSurface(
        modifier, tint = PhoneGlassTint, cornerRadius = DockRadius,
        blurDp = 8f, lensHeightDp = 24f, lensAmountDp = 24f, lensBand = .3f, chromaticAberration = 0f,
        shadowElevation = 12.dp
    ) {
        Row(
            Modifier.padding(start = DockInset, top = DockInset, bottom = DockInset, end = DockInset + 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (track != null) {
                // The box's corner minus its inset keeps the cover's corner concentric with the glass.
                DesktopArtwork(artwork, Modifier.size(60.dp), DockRadius - DockInset, track.title, highRes = true)
                Spacer(Modifier.width(14.dp))
                // Shrinks on a narrow (portrait) display rather than pushing the controls off.
                Column(Modifier.weight(1f, fill = false).widthIn(max = DockTextWidth)) {
                    // Title opens the album, each artist their page, in the main window (leaving immersive).
                    val album = com.auralis.music.windows.player.rememberTrackAlbum(track)
                    com.auralis.music.windows.player.BarLink(track.title,
                        album?.let { { onOpenInApp(ImmersiveBrowse.OpenAlbum(it)) } },
                        androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
                        Color.White)
                    com.auralis.music.windows.player.TrackArtistLinks(track, { onOpenInApp(ImmersiveBrowse.OpenArtist(it)) },
                        androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 15.sp), Color.White.copy(alpha = .7f))
                    Spacer(Modifier.height(4.dp))
                    ImmersiveProgress(playback, track, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(8.dp))
                TransportControls(playback, emphasized = false)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = .14f)))
                Spacer(Modifier.width(8.dp))
            }
            ImmersiveViewSwitch(view, onView)
            if (displays.size > 1) {
                Spacer(Modifier.width(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    displays.forEachIndexed { index, display ->
                        DockCircleButton(
                            onClick = { onToggleDisplay(index) },
                            selected = display in shownDisplays,
                            size = DockControlHeight
                        ) { alpha ->
                            Text("${index + 1}", fontSize = 12.sp, color = Color.White.copy(alpha = alpha),
                                fontWeight = if (display == thisDisplay) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            DockCircleButton(onClick = onLeave, selected = false, size = DockControlHeight, description = desktopText("Close")) { alpha ->
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = Color.White.copy(alpha = alpha))
            }
        }
    }
}

/** Clock / Lyrics like the phone dock's tabs: the selected one sits on a bead that glides between them. */
@Composable
private fun ImmersiveViewSwitch(selected: ImmersiveView, onSelect: (ImmersiveView) -> Unit) {
    val segment = 82.dp
    val index by animateFloatAsState(
        ImmersiveView.entries.indexOf(selected).toFloat(),
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "view_switch_bead"
    )
    Box(Modifier.width(segment * ImmersiveView.entries.size).height(DockControlHeight).selectableGroup()) {
        Box(
            Modifier
                .offset { IntOffset((index * segment.toPx()).roundToInt(), 0) }
                .width(segment)
                .height(DockControlHeight)
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawRoundRect(PhoneIndicatorTint, cornerRadius = CornerRadius(size.height / 2f))
                    drawRoundRect(
                        PhoneIndicatorEdge,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(size.height / 2f - stroke / 2f),
                        style = Stroke(stroke)
                    )
                }
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            ImmersiveView.entries.forEach { option ->
                val isSelected = option == selected
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val pressed by interaction.collectIsPressedAsState()
                val alpha by animateFloatAsState(if (isSelected) 1f else if (hovered) .9f else .62f, tween(140))
                val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(stiffness = Spring.StiffnessMediumLow))
                Row(
                    Modifier
                        .width(segment)
                        .fillMaxHeight()
                        .scale(scale)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .selectable(isSelected, interactionSource = interaction, indication = null, role = Role.Tab,
                            onClick = { onSelect(option) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    val icon = if (option == ImmersiveView.CLOCK) Icons.Rounded.Schedule else Icons.AutoMirrored.Rounded.Subject
                    Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White.copy(alpha = alpha))
                    Text(desktopText(option.label), fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = Color.White.copy(alpha = alpha))
                }
            }
        }
    }
}

/** A round button on the glass: the phone's indicator bead when [selected], a fainter one on hover. */
@Composable
private fun DockCircleButton(
    onClick: () -> Unit, selected: Boolean, size: Dp, description: String? = null,
    content: @Composable (alpha: Float) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateFloatAsState(if (selected) .14f else if (hovered) .10f else 0f, tween(120))
    val alpha by animateFloatAsState(if (selected || hovered) 1f else .72f, tween(140))
    val scale by animateFloatAsState(if (pressed) .9f else 1f, spring(stiffness = Spring.StiffnessMediumLow))
    Box(
        Modifier
            .size(size)
            .scale(scale)
            .drawBehind { if (fill > 0f) drawCircle(Color.White.copy(alpha = fill)) }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center
    ) { content(alpha) }
}
