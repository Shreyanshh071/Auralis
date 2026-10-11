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
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import com.auralis.music.ui.lyrics.ExperimentalWordLevelLyrics
import com.auralis.music.ui.lyrics.InstrumentalIntroIndicator
import com.auralis.music.ui.lyrics.resolveExperimentalWordTimestamps
import com.auralis.music.ui.lyrics.presentationWordTimestamps
import kotlinx.coroutines.delay

/** Compact sidebar layout using the same genuine word timing and renderer as the full player. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SidebarLyricsView(lyrics: LyricsData?, position: State<Long>, onSeek: (Long) -> Unit) {
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
    val introTime = remember { derivedStateOf { (position.value.coerceAtLeast(0L) / 50L) * 50L } }
    val list = rememberLazyListState()
    var manualScroll by remember { mutableLongStateOf(0L) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(active, manualScroll, lines, introDurationMs) {
        if (active < 0) return@LaunchedEffect
        val remaining = 3000L - (System.currentTimeMillis() - manualScroll)
        if (remaining > 0) delay(remaining)
        list.animateScrollToItem(active + if (introDurationMs > 0L) 1 else 0, -(list.layoutInfo.viewportSize.height * .30f).toInt())
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
                    InstrumentalIntroIndicator(introTime, introDurationMs, isMetroLyrics = true,
                        onSkipIntro = { manualScroll = 0; onSeek(introDurationMs) })
                }
            }
            itemsIndexed(lines) { index, line ->
                SidebarLyricLine(line.text, if (line.hasWordTiming) index in singing else active == index, synced,
                    onClick = { if (synced) { manualScroll = 0; onSeek(line.time) } },
                    line = line, position = position, syncType = lyrics?.syncType ?: SyncType.PLAIN)
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
    syncType: SyncType = SyncType.PLAIN) {
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    val hovered = hover || previewHovered
    val alpha by animateFloatAsState(if (active || hovered || !synced) 1f else .48f, tween(160), label = "sidebarLineText")
    val background by animateFloatAsState(if (hovered) .16f else 0f, tween(140), label = "sidebarLineHover")
    val words = remember(line, syncType) {
        line?.let { resolveExperimentalWordTimestamps(it, syncType) }?.let(::presentationWordTimestamps)
    }
    Box(Modifier.fillMaxWidth()
            .padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = background))
            .hoverable(interaction)
            .clickable(enabled = synced, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)) {
        if (active && !words.isNullOrEmpty() && position != null) {
            ExperimentalWordLevelLyrics(
                mainText = text.ifBlank { line?.words?.joinToString("") { it.word }.orEmpty() },
                words = words, isActiveLine = true, currentPositionState = position,
                lyricStyle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.material3.MaterialTheme.typography.bodyLarge.fontFamily),
                lineColor = Color.White, expressiveAccent = Color.White,
                isBackground = line?.isBackground == true,
                focusedAlpha = if (hovered) 1f else .30f, alignment = TextAlign.Left)
        } else {
            Text(text, color = Color.White.copy(alpha = alpha), fontSize = 24.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.Bold)
        }
    }
}
