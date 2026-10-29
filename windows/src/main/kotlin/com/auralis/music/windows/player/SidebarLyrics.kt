package com.auralis.music.windows.player

import com.auralis.music.ui.i18n.desktopText

import com.auralis.music.ui.components.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.SyncType
import com.auralis.music.domain.model.LyricsAnimationMode
import com.auralis.music.ui.theme.LocalAppearanceSettings
import com.auralis.music.ui.lyrics.renderers.FluidLyricLine
import com.auralis.music.ui.lyrics.renderers.AppleMusicLyricsLine
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import com.auralis.music.ui.lyrics.ExperimentalWordLevelLyrics
import com.auralis.music.ui.lyrics.InstrumentalIntroIndicator
import com.auralis.music.ui.lyrics.LyricsIntervalIndicator
import com.auralis.music.ui.lyrics.instrumentalBreakWindows
import com.auralis.music.ui.lyrics.resolveExperimentalWordTimestamps
import com.auralis.music.ui.lyrics.presentationWordTimestamps
import com.auralis.music.ui.lyrics.smoothCenterLyric
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Compact sidebar layout using the same genuine word timing and renderer as the full player. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SidebarLyricsView(lyrics: LyricsData?, position: State<Long>, onSeek: (Long) -> Unit, durationMs: Long? = lyrics?.durationMs, lineGap: androidx.compose.ui.unit.Dp = 0.dp,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    followAnchorFraction: Float = .50f,
    introAnchorFraction: Float = followAnchorFraction) {
    val store = com.auralis.music.ui.theme.LocalAppearanceSettingsStore.current
    val settings = store?.settingsFlow?.collectAsState(store.getInitialSettings())?.value ?: LocalAppearanceSettings.current
    CompositionLocalProvider(LocalAppearanceSettings provides settings) {
    SidebarLyricsContent(lyrics, position, onSeek, durationMs, lineGap, listState, followAnchorFraction, introAnchorFraction)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SidebarLyricsContent(lyrics: LyricsData?, position: State<Long>, onSeek: (Long) -> Unit, durationMs: Long?, lineGap: androidx.compose.ui.unit.Dp,
    list: androidx.compose.foundation.lazy.LazyListState, lyricsAnchorFraction: Float, introAnchorFraction: Float) {
    val appearance = LocalAppearanceSettings.current
    val popupControlsInset = LocalMiniPopupControlsInset.current
    val mode = LyricsAnimationMode.fromDisplayName(appearance.lyricsAnimation)
    val lines = remember(lyrics) {
        lyrics?.lines?.takeIf { it.isNotEmpty() }
            ?: lyrics?.plainLyrics?.lines()?.filter(String::isNotBlank)?.map { LyricLine(0, it) }.orEmpty()
    }
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(desktopText("No lyrics available"), color = Color.White.copy(alpha = .7f))
        }
        return
    }
    val synced = lyrics?.syncType != SyncType.PLAIN
    val spicyVocalTimeline = remember(lines) { com.auralis.music.ui.lyrics.SpicyVocalTimeline(lines) }
    // Duets: each singer's lines keep to their side, as in the full player.
    val speakerStyles = remember(lines, appearance.lyricsAnimation) {
        com.auralis.music.ui.lyrics.MetroSpeakerLayout.forAppearance(lines, appearance)
    }
    val active by remember(lines, synced) { derivedStateOf {
        if (synced) LyricsEngine.findActiveLyricIndex(lines, position.value) else -1
    } }
    val highlighted by remember(lines, synced, position, mode) { derivedStateOf {
        // Fullscreen keeps the current phrase lit through ordinary breaths, without
        // extending any word timestamps. Preserve its paired lead/background display too.
        if (synced && mode == LyricsAnimationMode.SPICY_LYRICS) {
            spicyVocalTimeline.activeIndices(position.value)
        } else if (synced) {
            val visual = com.auralis.music.ui.lyrics.findExperimentalActiveLineIndices(lines, position.value)
            visual.toMutableSet().apply {
                for (index in visual) if (lines[index].isBackground) {
                    (index - 1 downTo 0).firstOrNull { !lines[it].isBackground }?.let { add(it) }
                }
            }
        } else emptySet()
    } }
    // Same intro countdown the full player shows before the first synced line.
    val introDurationMs = if (synced) (lines.firstOrNull { mode != LyricsAnimationMode.SPICY_LYRICS || !it.isBackground }?.time ?: 0L).takeIf {
        it >= if (mode == LyricsAnimationMode.SPICY_LYRICS) com.auralis.music.ui.lyrics.SPICY_INTERLUDE_MINIMUM_MS else 1500L
    } ?: 0L else 0L
    val introActive by remember(introDurationMs) { derivedStateOf { introDurationMs > 0L && position.value < introDurationMs } }
    // Opening dots sit near the header; sung lines follow in the middle of the card.
    val followAnchorFraction = if (introActive) introAnchorFraction else lyricsAnchorFraction
    val introTime = remember { derivedStateOf { position.value.coerceAtLeast(0L) } }
    val breaks = remember(lines, synced, durationMs, mode) {
        if (!synced) emptyMap() else if (mode == LyricsAnimationMode.SPICY_LYRICS)
            com.auralis.music.ui.lyrics.spicyInterludeWindows(lines) else instrumentalBreakWindows(lines, durationMs)
    }
    val activeBreak by remember(breaks) { derivedStateOf {
        breaks.entries.firstOrNull { position.value >= it.value.first && position.value < it.value.second }?.key
    } }
    val scrollTarget by remember(lines, breaks, introDurationMs, mode) { derivedStateOf {
        val anchor = activeBreak ?: if (mode == LyricsAnimationMode.SPICY_LYRICS)
            com.auralis.music.ui.lyrics.SpicyLyricsPresentation.scrollAnchor(lines, position.value, active) else active
        if (anchor < 0) 0 else anchor + breaks.keys.count { it < anchor } +
            (if (introDurationMs > 0L) 1 else 0) + (if (activeBreak != null) 1 else 0)
    } }
    val rowHeights = remember(lines, breaks) { mutableMapOf<Int, Int>() }
    val followingMotion = remember(lines) { com.auralis.music.ui.lyrics.LyricsScrollSpring() }
    val rowGapPx = with(androidx.compose.ui.platform.LocalDensity.current) { lineGap.roundToPx() }
    var manualScroll by remember { mutableLongStateOf(0L) }
    var followingScrollJob by remember { mutableStateOf<Job?>(null) }
    val followingScope = rememberCoroutineScope()
    val beginManualScroll: () -> Unit = {
        followingScrollJob?.cancel()
        followingMotion.reset()
        manualScroll = System.currentTimeMillis()
    }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var firstAnchor by remember(lines, list) { mutableStateOf(true) }
    LaunchedEffect(lines, breaks, list, appearance.autoScrollLyrics, followAnchorFraction) {
        if (!appearance.autoScrollLyrics) return@LaunchedEffect
        snapshotFlow { Triple(scrollTarget, manualScroll, list.layoutInfo.viewportSize) }
            .distinctUntilChanged().collectLatest { (target, lastInput, viewport) ->
            followingScrollJob?.cancel()
            val remaining = 1500L - (System.currentTimeMillis() - lastInput)
            if (remaining > 0) delay(remaining)
            snapshotFlow { list.isScrollInProgress }.first { !it }
            if (viewport.height <= 0) return@collectLatest
            val motion = followingScope.launch {
            if (introActive) {
                // The dots lead the list naturally below the header. Centering
                // them creates a scroll offset that gets unwound DOWNWARD when
                // the first phrase starts, fighting the dots' upward collapse.
                if (firstAnchor && lastInput == 0L) list.scrollToItem(0)
                else list.animateScrollToItem(0)
                firstAnchor = false
                return@launch
            }
            if (firstAnchor && lastInput == 0L) {
                // Content padding already supplies the anchor. Applying it again as a
                // negative offset can put the active row below the visible viewport.
                list.scrollToItem(target)
                withFrameNanos { }
                val info = list.layoutInfo
                info.visibleItemsInfo.firstOrNull { it.index == target }?.let { item ->
                    val anchor = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) * followAnchorFraction
                    list.scroll { scrollBy(item.offset + item.size / 2f - anchor) }
                }
            }
            firstAnchor = false
            while (currentCoroutineContext().isActive) {
                list.smoothCenterLyric(target, followAnchorFraction, rowHeights, followingMotion)
                // A wrapped row, disappearing intro, or font change can move the
                // sung phrase after the spring settles without changing its index.
                // Sleep until its layout changes, then restore the same anchor.
                snapshotFlow {
                    val info = list.layoutInfo
                    val row = info.visibleItemsInfo.firstOrNull { it.index == target }
                    Triple(info.viewportStartOffset, info.viewportEndOffset, row?.let { it.offset to it.size })
                }.drop(1).first()
            }
            }
            followingScrollJob = motion
            try { motion.join() } finally { motion.cancel() }
        }
    }
    Column(Modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.weight(1f).hoverable(hover)) {
        // Keep the opening inset stable when the dots disappear. Increasing it
        // to the sung-line anchor pushes the first phrase DOWN into empty space.
        // The opening phrase stays near the header; later phrases can center as
        // preceding rows scroll away. Trailing padding lets the final line center.
        LazyColumn(state = list, contentPadding = PaddingValues(start = 8.dp, end = 8.dp,
                top = if (appearance.autoScrollLyrics) maxHeight * minOf(introAnchorFraction, lyricsAnchorFraction) else 8.dp,
                bottom = if (appearance.autoScrollLyrics) maxHeight * (1f - lyricsAnchorFraction) else 8.dp),
            verticalArrangement = Arrangement.spacedBy(lineGap),
            modifier = Modifier.fillMaxSize()
                .then(lyricsWheelScroll(list, beginManualScroll))
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                    drawContent()
                    val lyricBottom = (size.height - (popupControlsInset?.value?.toPx() ?: 0f)).coerceAtLeast(1f)
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, .04f to Color.Black,
                        .80f to Color.Black, 1f to Color.Transparent, endY = lyricBottom), blendMode = BlendMode.DstIn)
                }) {
            if (introDurationMs > 0L) item(key = "intro") {
                androidx.compose.animation.AnimatedVisibility(introActive, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    // Left, with the lyric text, as Spicy Lyrics places its dots.
                    InstrumentalIntroIndicator(introTime, introDurationMs, isMetroLyrics = mode == LyricsAnimationMode.METRO_LYRICS, horizontalAlignment = Alignment.Start,
                        dotSize = 12.dp, compact = true,
                        onSkipIntro = { if (appearance.changeLyricsOnTap) { manualScroll = 0; onSeek(introDurationMs) } })
                }
            }
            lines.forEachIndexed { index, line ->
              item(key = "line-$index") {
                // Spicy Lyrics' soft focus: lines blur and fade a little more the further they are
                // from the one being sung, so they blend into the background.
                val focus = if (active >= 0) active else 0
                // Every lyrics style, as in the full player (it was Metro only, so the setting did nothing here).
                val distance = if (synced) kotlin.math.abs(index - focus) else 0
                val listIndex = index + breaks.keys.count { it < index } + if (introDurationMs > 0L) 1 else 0
                Box(Modifier.onSizeChanged { rowHeights[listIndex] = it.height + rowGapPx }) {
                SidebarLyricLine(line.text, (activeBreak == null || mode == LyricsAnimationMode.SPICY_LYRICS) && index in highlighted, synced,
                    onClick = { if (synced) { followingMotion.reset(); manualScroll = 0; onSeek(line.time) } },
                    line = line, position = position, syncType = lyrics?.syncType ?: SyncType.PLAIN,
                    blur = if (!appearance.standardLyricsBlur) 0.dp else (distance * 1.5f).coerceAtMost(4.5f).dp,
                    nextLineTime = lines.getOrNull(index + 1)?.time,
                    speakerSide = speakerStyles?.getOrNull(index)?.side)
                }
              }
              breaks[index]?.let { (start, end) ->
                item(key = "break-$start-$end") {
                    SidebarRestIndicator(start, end, position, mode,
                        onSkip = { if (appearance.changeLyricsOnTap) { manualScroll = 0; onSeek(end) } })
                }
              }
            }
        }
        if (hovered) VerticalScrollbar(rememberScrollbarAdapter(list),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 16.dp).width(5.dp))
    }
    // Measure wrapped credits outside the lyric viewport instead of clipping them
    // to a single fixed-height strip. Popup controls retain their masked inset.
    Box(Modifier.fillMaxWidth().heightIn(min = 36.dp).offset {
        androidx.compose.ui.unit.IntOffset(0, -(popupControlsInset?.value?.roundToPx() ?: 0))
    }.padding(horizontal = 8.dp),
        contentAlignment = Alignment.TopStart) {
        com.auralis.music.ui.lyrics.LyricsCredits(lyrics, compact = true)
    }
    }
}

/** Keep the frame-rate clock read out of the LazyColumn's item composition. */
@Composable
private fun SidebarRestIndicator(start: Long, end: Long, position: State<Long>, mode: LyricsAnimationMode, onSkip: () -> Unit) {
    val visible by remember(start, end, position) { derivedStateOf { position.value in start until end } }
    val indicatorPosition by remember(start, end, position) {
        derivedStateOf { position.value.coerceIn(start - 300L, end + 1L) }
    }
    // Retain the indicator across its exit so its existing fade/shrink can finish.
    LyricsIntervalIndicator(start, end, indicatorPosition, visible = visible,
        isMetroLyrics = mode == LyricsAnimationMode.METRO_LYRICS,
        dotsAlignment = Alignment.Start, dotSize = 12.dp, compact = true,
        modifier = Modifier.padding(horizontal = 10.dp), onSkip = onSkip)
}

@Composable
internal fun SidebarLyricLine(text: String, active: Boolean, synced: Boolean, onClick: () -> Unit,
    previewHovered: Boolean = false, line: LyricLine? = null, position: State<Long>? = null,
    syncType: SyncType = SyncType.PLAIN, blur: androidx.compose.ui.unit.Dp = 0.dp,
    nextLineTime: Long? = null,
    speakerSide: com.auralis.music.ui.lyrics.MetroSpeakerSide? = null) {
    val appearance = LocalAppearanceSettings.current
    val animationMode = LyricsAnimationMode.fromDisplayName(appearance.lyricsAnimation)
    val textAlignment = when (speakerSide) {
        com.auralis.music.ui.lyrics.MetroSpeakerSide.START -> TextAlign.Left
        com.auralis.music.ui.lyrics.MetroSpeakerSide.END -> TextAlign.Right
        com.auralis.music.ui.lyrics.MetroSpeakerSide.CENTER -> TextAlign.Center
        null -> when (appearance.lyricsTextPosition.lowercase()) {
            "right" -> TextAlign.Right
            "center", "centre" -> TextAlign.Center
            else -> TextAlign.Left
        }
    }
    // The other singer's side stays a little open, so a duet reads as two voices.
    val sideInset = 24.dp
    val horizontalAlignment = when (textAlignment) {
        TextAlign.Right -> Alignment.End
        TextAlign.Center -> Alignment.CenterHorizontally
        else -> Alignment.Start
    }
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    val hovered = hover || previewHovered
    val easing = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val isSpicy = animationMode == LyricsAnimationMode.SPICY_LYRICS
    // Derived so the playback clock only recomposes this line when it crosses its start.
    val started by remember(line, position) { derivedStateOf { line != null && position != null && position.value >= line.time } }
    val spicyAlpha = if (started) .497f else .51f
    val alpha = animateFloatAsState(if (active || !synced) 1f else if (hovered) { if (isSpicy) 1f else .55f } else if (isSpicy) spicyAlpha else .38f,
        tween(if (isSpicy) 200 else 260, easing = if (isSpicy) CubicBezierEasing(.61f, 1f, .88f, 1f) else easing), label = "sidebarLineText")
    val blurAmount = androidx.compose.animation.core.animateDpAsState(if (active || hovered || !appearance.standardLyricsBlur) 0.dp else blur, tween(320, easing = easing), label = "sidebarLineBlur")
    val background = animateFloatAsState(if (hovered) .10f else 0f, tween(260, easing = easing), label = "sidebarLineHover")
    val words = remember(line, syncType) {
        line?.let { resolveExperimentalWordTimestamps(it, syncType) }?.let(::presentationWordTimestamps)
    }
    Box(Modifier.fillMaxWidth()
            // The highlight is drawn rounded rather than clipped: a clip also cut each blurred
            // line's soft edge into a hard-edged box.
            .drawBehind { drawRoundRect(Color.White.copy(alpha = background.value), cornerRadius = CornerRadius(16.dp.toPx())) }
            .hoverable(interaction)
            .clickable(enabled = synced && appearance.changeLyricsOnTap, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .padding(
                start = if (speakerSide == com.auralis.music.ui.lyrics.MetroSpeakerSide.END) sideInset else 0.dp,
                end = if (speakerSide == com.auralis.music.ui.lyrics.MetroSpeakerSide.START) sideInset else 0.dp)
            .graphicsLayer {
                // Hover changes drawing properties, keeping text layout and the layer stable.
                compositingStrategy = CompositingStrategy.Offscreen
                this.alpha = alpha.value
                val radius = blurAmount.value.toPx()
                renderEffect = if (radius > .1f) BlurEffect(radius, radius, TileMode.Decal) else null
                clip = false
            }.drawWithContent {
                drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                drawContent()
            }) {
        val textSize = LocalAppearanceSettings.current.lyricsTextSize * (24f / 22f) * if (isSpicy && line?.isBackground == true) .70f else 1f
        val lineHeight = textSize * LocalAppearanceSettings.current.lyricsLineSpacing * if (isSpicy) (1.1818182f / 1.3f) else 1f
        val lyricStyle = TextStyle(fontSize = textSize.sp, lineHeight = lineHeight.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = if (isSpicy && line?.isBackground == true) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
            textAlign = textAlignment,
            fontFamily = androidx.compose.material3.MaterialTheme.typography.bodyLarge.fontFamily)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = horizontalAlignment) {
        if (synced && line != null && position != null && animationMode == LyricsAnimationMode.SPICY_LYRICS) {
            com.auralis.music.ui.lyrics.renderers.SpicyLyricsLine(line.copy(text = text),
                words = if (syncType == SyncType.RICHSYNC) line.words else null,
                positionMs = position, style = lyricStyle, isActive = active,
                nextLineTime = nextLineTime,
                inactiveColor = Color.White.copy(alpha = if (!started) .35f else if (line.isBackground) 1f else .85f))
        } else if (synced && line != null && position != null && animationMode == LyricsAnimationMode.FLUID) {
            FluidLyricLine(line!!.copy(text = text),
                words = if (syncType == SyncType.RICHSYNC) line.words else null,
                positionMs = position!!, style = lyricStyle, isActive = active,
                modifier = Modifier)
        } else if (synced && line != null && position != null && animationMode == LyricsAnimationMode.APPLE_MUSIC_V2) {
            AppleMusicLyricsLine(isV2 = true, line = line!!.copy(text = text),
                nextLineTime = line.effectiveEndTime ?: nextLineTime,
                words = if (syncType == SyncType.RICHSYNC) line.words else null,
                isActive = active, effectivePlaybackPosition = position!!.value,
                lineColor = Color.White, accentColor = Color.White,
                textAlign = textAlignment, alignment = horizontalAlignment,
                fontSizeSp = textSize, lineSpacingMultiplier = LocalAppearanceSettings.current.lyricsLineSpacing, fontFamily = lyricStyle.fontFamily,
                modifier = Modifier)
        } else if (active && !words.isNullOrEmpty() && position != null) {
            ExperimentalWordLevelLyrics(
                mainText = text.ifBlank { line?.words?.joinToString("") { it.word }.orEmpty() },
                words = words, isActiveLine = true, currentPositionState = position,
                lyricStyle = lyricStyle,
                lineColor = Color.White, expressiveAccent = Color.White,
                isBackground = line?.isBackground == true,
                focusedAlpha = .30f, alignment = textAlignment)
        } else {
            Text(text, color = Color.White, fontSize = textSize.sp, lineHeight = lineHeight.sp,
                fontWeight = FontWeight.Bold, textAlign = textAlignment, modifier = Modifier.fillMaxWidth())
        }
        DesktopLyricsUi.romanizedLine(text, Color.White.copy(alpha = .65f), (textSize * .65f).sp, textAlignment, Modifier.fillMaxWidth())
        line?.translatedText?.takeIf { it.isNotBlank() && it != text }?.let { translated ->
            Text(translated, fontSize = (textSize * .65f).sp, color = Color.White.copy(alpha = .75f),
                textAlign = textAlignment, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        }
    }
}
