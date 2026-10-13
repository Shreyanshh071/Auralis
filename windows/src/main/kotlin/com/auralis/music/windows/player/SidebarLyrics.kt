package com.auralis.music.windows.player

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
internal fun SidebarLyricsView(lyrics: LyricsData?, position: State<Long>, onSeek: (Long) -> Unit, durationMs: Long? = lyrics?.durationMs) {
    val store = com.auralis.music.ui.theme.LocalAppearanceSettingsStore.current
    val settings = store?.settingsFlow?.collectAsState(store.getInitialSettings())?.value ?: LocalAppearanceSettings.current
    CompositionLocalProvider(LocalAppearanceSettings provides settings) {
    SidebarLyricsContent(lyrics, position, onSeek, durationMs)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SidebarLyricsContent(lyrics: LyricsData?, position: State<Long>, onSeek: (Long) -> Unit, durationMs: Long?) {
    val mode = LyricsAnimationMode.fromDisplayName(LocalAppearanceSettings.current.lyricsAnimation)
    val lines = remember(lyrics) {
        lyrics?.lines?.takeIf { it.isNotEmpty() }
            ?: lyrics?.plainLyrics?.lines()?.filter(String::isNotBlank)?.map { LyricLine(0, it) }.orEmpty()
    }
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text("No lyrics available", color = Color.White.copy(alpha = .7f))
        }
        return
    }
    val synced = lyrics?.syncType != SyncType.PLAIN
    val active by remember(lines, synced) { derivedStateOf {
        if (synced) LyricsEngine.findActiveLyricIndex(lines, position.value) else -1
    } }
    val singing by remember(lines, synced) { derivedStateOf {
        if (synced) LyricsEngine.findActiveLyricIndices(lines, position.value) else emptySet()
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
    val scrollTarget by remember(lines, breaks, introDurationMs) { derivedStateOf {
        val anchor = activeBreak ?: active
        if (anchor < 0) 0 else anchor + breaks.keys.count { it < anchor } +
            (if (introDurationMs > 0L) 1 else 0) + (if (activeBreak != null) 1 else 0)
    } }
    val list = rememberLazyListState()
    var manualScroll by remember { mutableLongStateOf(0L) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(scrollTarget, manualScroll, lines, breaks) {
        val remaining = 3000L - (System.currentTimeMillis() - manualScroll)
        if (remaining > 0) delay(remaining)
        list.animateScrollToItem(scrollTarget, -(list.layoutInfo.viewportSize.height * .30f).toInt())
    }
    Box(Modifier.fillMaxSize().hoverable(hover)) {
        LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
            modifier = Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Scroll) { manualScroll = System.currentTimeMillis() }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, .08f to Color.Black,
                        .80f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                }) {
            if (introDurationMs > 0L) item(key = "intro") {
                AnimatedVisibility(introActive, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    // Left, with the lyric text, as Spicy Lyrics places its dots.
                    InstrumentalIntroIndicator(introTime, introDurationMs, isMetroLyrics = mode == LyricsAnimationMode.METRO_LYRICS, horizontalAlignment = Alignment.Start,
                        dotSize = 16.dp,
                        onSkipIntro = { manualScroll = 0; onSeek(introDurationMs) })
                }
            }
            lines.forEachIndexed { index, line ->
              item(key = "line-$index") {
                // Spicy Lyrics' soft focus: lines blur and fade a little more the further they are
                // from the one being sung, so they blend into the background.
                val focus = if (active >= 0) active else 0
                val distance = if (mode == LyricsAnimationMode.METRO_LYRICS && synced) kotlin.math.abs(index - focus) else 0
                SidebarLyricLine(line.text, activeBreak == null && (if (line.hasWordTiming) index in singing else active == index), synced,
                    onClick = { if (synced) { manualScroll = 0; onSeek(line.time) } },
                    line = line, position = position, syncType = lyrics?.syncType ?: SyncType.PLAIN,
                    blur = (distance * 1.2f).coerceAtMost(3.5f).dp,
                    nextLineTime = lines.getOrNull(index + 1)?.time)
              }
              breaks[index]?.let { (start, end) ->
                item(key = "break-$start-$end") {
                    LyricsIntervalIndicator(start, end, position.value,
                        visible = position.value >= start && position.value < end,
                        isMetroLyrics = mode == LyricsAnimationMode.METRO_LYRICS,
                        dotsAlignment = Alignment.Start, dotSize = 16.dp,
                        modifier = Modifier.padding(horizontal = 10.dp),
                        onSkip = { manualScroll = 0; onSeek(end) })
                }
              }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        if (hovered) VerticalScrollbar(rememberScrollbarAdapter(list),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 16.dp).width(5.dp))
    }
}

@Composable
internal fun SidebarLyricLine(text: String, active: Boolean, synced: Boolean, onClick: () -> Unit,
    previewHovered: Boolean = false, line: LyricLine? = null, position: State<Long>? = null,
    syncType: SyncType = SyncType.PLAIN, blur: androidx.compose.ui.unit.Dp = 0.dp,
    nextLineTime: Long? = null) {
    val animationMode = LyricsAnimationMode.fromDisplayName(LocalAppearanceSettings.current.lyricsAnimation)
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    val hovered = hover || previewHovered
    val easing = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val alpha = animateFloatAsState(if (active || !synced) 1f else if (hovered) .55f else .38f, tween(260, easing = easing), label = "sidebarLineText")
    val blurAmount = androidx.compose.animation.core.animateDpAsState(if (active || hovered) 0.dp else blur, tween(320, easing = easing), label = "sidebarLineBlur")
    val background = animateFloatAsState(if (hovered) .10f else 0f, tween(260, easing = easing), label = "sidebarLineHover")
    val words = remember(line, syncType) {
        line?.let { resolveExperimentalWordTimestamps(it, syncType) }?.let(::presentationWordTimestamps)
    }
    Box(Modifier.fillMaxWidth()
            .padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp))
            .drawBehind { drawRect(Color.White.copy(alpha = background.value)) }
            .hoverable(interaction)
            .clickable(enabled = synced, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .graphicsLayer {
                // Hover changes drawing properties, keeping text layout and the layer stable.
                this.alpha = alpha.value
                val radius = blurAmount.value.toPx()
                renderEffect = if (radius > .1f) BlurEffect(radius, radius, TileMode.Decal) else null
                clip = false
            }) {
        val textSize = LocalAppearanceSettings.current.lyricsTextSize * (24f / 22f)
        val lineHeight = textSize * LocalAppearanceSettings.current.lyricsLineSpacing
        val lyricStyle = TextStyle(fontSize = textSize.sp, lineHeight = lineHeight.sp, fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.material3.MaterialTheme.typography.bodyLarge.fontFamily)
        if (synced && line != null && position != null && animationMode == LyricsAnimationMode.FLUID) {
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
                textAlign = TextAlign.Left, alignment = Alignment.Start,
                fontSizeSp = textSize, lineSpacingMultiplier = LocalAppearanceSettings.current.lyricsLineSpacing, fontFamily = lyricStyle.fontFamily,
                modifier = Modifier)
        } else if (active && !words.isNullOrEmpty() && position != null) {
            ExperimentalWordLevelLyrics(
                mainText = text.ifBlank { line?.words?.joinToString("") { it.word }.orEmpty() },
                words = words, isActiveLine = true, currentPositionState = position,
                lyricStyle = lyricStyle,
                lineColor = Color.White, expressiveAccent = Color.White,
                isBackground = line?.isBackground == true,
                focusedAlpha = .30f, alignment = TextAlign.Left)
        } else {
            Text(text, color = Color.White, fontSize = textSize.sp, lineHeight = lineHeight.sp,
                fontWeight = FontWeight.Bold)
        }
    }
}
