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
import kotlinx.coroutines.delay

/** Compact sidebar layout using the same genuine word timing and renderer as the full player. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SidebarLyricsView(lyrics: LyricsData?, position: State<Long>, onSeek: (Long) -> Unit, durationMs: Long? = lyrics?.durationMs, lineGap: androidx.compose.ui.unit.Dp = 0.dp) {
    val store = com.auralis.music.ui.theme.LocalAppearanceSettingsStore.current
    val settings = store?.settingsFlow?.collectAsState(store.getInitialSettings())?.value ?: LocalAppearanceSettings.current
    CompositionLocalProvider(LocalAppearanceSettings provides settings) {
    SidebarLyricsContent(lyrics, position, onSeek, durationMs, lineGap)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SidebarLyricsContent(lyrics: LyricsData?, position: State<Long>, onSeek: (Long) -> Unit, durationMs: Long?, lineGap: androidx.compose.ui.unit.Dp) {
    val appearance = LocalAppearanceSettings.current
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
            com.auralis.music.ui.lyrics.SpicyLyricsPresentation.activeIndices(lines, position.value)
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
    val introDurationMs = if (synced) lines.first().time.takeIf { it >= 1500L } ?: 0L else 0L
    val introActive by remember(introDurationMs) { derivedStateOf { introDurationMs > 0L && position.value < introDurationMs } }
    val introTime = remember { derivedStateOf { position.value.coerceAtLeast(0L) } }
    val breaks = remember(lines, synced, durationMs) {
        if (synced) instrumentalBreakWindows(lines, durationMs) else emptyMap()
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
    val list = rememberLazyListState()
    var manualScroll by remember { mutableLongStateOf(0L) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(scrollTarget, manualScroll, lines, breaks, appearance.autoScrollLyrics) {
        if (!appearance.autoScrollLyrics) return@LaunchedEffect
        val remaining = 3000L - (System.currentTimeMillis() - manualScroll)
        if (remaining > 0) delay(remaining)
        val offset = -(list.layoutInfo.viewportSize.height * .30f).toInt()
        // A far jump lands at once; animating across most of the song is what left it adrift.
        if (kotlin.math.abs(scrollTarget - list.firstVisibleItemIndex) > 8) list.scrollToItem(scrollTarget, offset)
        else list.animateScrollToItem(scrollTarget, offset)
    }
    // Self-correcting: the line being sung is always back on screen within a second of the user
    // leaving the list alone (it was left showing the song's end after closing the full player).
    LaunchedEffect(lines, breaks, appearance.autoScrollLyrics) {
        if (!appearance.autoScrollLyrics) return@LaunchedEffect
        while (true) {
            delay(1000L)
            if (System.currentTimeMillis() - manualScroll < 3000L || list.isScrollInProgress) continue
            val target = scrollTarget
            val height = list.layoutInfo.viewportSize.height
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            // Off screen, or pushed far from its place (the card's upper third, e.g. at the bottom).
            if (item == null) list.scrollToItem(target, -(height * .30f).toInt())
            else if (kotlin.math.abs(item.offset - height * .30f) > height * .25f &&
                // The last lines can't reach the upper third: only move when the list can.
                (if (item.offset > height * .30f) list.canScrollForward else list.canScrollBackward))
                list.animateScrollToItem(target, -(height * .30f).toInt())
        }
    }
    Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).hoverable(hover)) {
        LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(lineGap),
            modifier = Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { manualScroll = System.currentTimeMillis() }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, .04f to Color.Black,
                        .80f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
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
                SidebarLyricLine(line.text, activeBreak == null && index in highlighted, synced,
                    onClick = { if (synced) { manualScroll = 0; onSeek(line.time) } },
                    line = line, position = position, syncType = lyrics?.syncType ?: SyncType.PLAIN,
                    blur = if (!appearance.standardLyricsBlur) 0.dp else if (mode == LyricsAnimationMode.SPICY_LYRICS)
                        with(androidx.compose.ui.platform.LocalDensity.current) {
                            com.auralis.music.ui.lyrics.SpicyLyricsPresentation.blurSigmaPx(index, focus).toDp()
                        } else (distance * 1.5f).coerceAtMost(4.5f).dp,
                    nextLineTime = lines.getOrNull(index + 1)?.time,
                    speakerSide = speakerStyles?.getOrNull(index)?.side)
              }
              breaks[index]?.let { (start, end) ->
                item(key = "break-$start-$end") {
                    LyricsIntervalIndicator(start, end, position.value,
                        visible = position.value >= start && position.value < end,
                        isMetroLyrics = mode == LyricsAnimationMode.METRO_LYRICS,
                        dotsAlignment = Alignment.Start, dotSize = 12.dp, compact = true,
                        modifier = Modifier.padding(horizontal = 10.dp),
                        onSkip = { if (appearance.changeLyricsOnTap) { manualScroll = 0; onSeek(end) } })
                }
              }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        if (hovered) VerticalScrollbar(rememberScrollbarAdapter(list),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 16.dp).width(5.dp))
    }
    com.auralis.music.ui.lyrics.LyricsCredits(lyrics, Modifier.padding(horizontal = 8.dp))
    }
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
                this.alpha = alpha.value
                val radius = blurAmount.value.toPx()
                renderEffect = if (radius > .1f) BlurEffect(radius, radius, TileMode.Decal) else null
                clip = false
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
