package com.auralis.music.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsMode
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.AuralisKaraokeActive
import com.auralis.music.ui.theme.AuralisKaraokeInactive
import com.auralis.music.ui.theme.dynamicPalette
import com.auralis.music.ui.theme.motionTween

/**
 * Clean, Immersive 60fps Synced Lyrics View:
 * - Line-level and syllable-level highlights
 * - Smooth spring-physics auto-scrolling centering active line
 * - Explicit states for Loading, Word-Synced, Line-Synced, Plain, Instrumental, and Unavailable
 */
@Composable
fun SyncedLyricsView(
    lyrics: LyricsData?,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    lyricsMode: LyricsMode = LyricsMode.CINEMA,
    offsetMs: Long = 0,
    onOffsetChange: ((Long) -> Unit)? = null,
    onSearchManually: (() -> Unit)? = null
) {
    if (isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = MaterialTheme.dynamicPalette.tintA,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Syncing Lyrics...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val isInstrumental = lyrics != null && (lyrics.lines.all { it.isInstrumental } || lyrics.plainLyrics == "[Instrumental]")

    if (isInstrumental) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = "🎵",
                    fontSize = 42.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Instrumental Track",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "This composition appears to have no vocals.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    if (lyrics == null || lyrics.lines.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = "Lyrics aren't available for this song yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (onSearchManually != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = onSearchManually,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Search Lyrics Manually")
                    }
                }
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val isSynced = lyrics.syncType != SyncType.PLAIN
    val activeIndex = remember(currentPositionMs, offsetMs, lyrics.lines, isSynced) {
        if (isSynced) LyricsEngine.findActiveLyricIndex(lyrics.lines, currentPositionMs, offsetMs) else -1
    }

    // Smooth auto-scrolling to keep active lyric line centered
    LaunchedEffect(activeIndex, isSynced) {
        if (isSynced && activeIndex >= 0 && activeIndex < lyrics.lines.size) {
            val targetScroll = (activeIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(
                index = targetScroll,
                scrollOffset = 0
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 40.dp, bottom = 140.dp, start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (lyricsMode == LyricsMode.CINEMA) 26.dp else 18.dp)
        ) {
            itemsIndexed(
                items = lyrics.lines,
                key = { idx, line -> "${line.time}_$idx" }
            ) { index, line ->
                val isCurrent = isSynced && index == activeIndex
                val isPast = isSynced && index < activeIndex

                LyricLineRow(
                    line = line,
                    isCurrent = isCurrent,
                    isPast = isPast,
                    lyricsMode = lyricsMode,
                    syncType = lyrics.syncType,
                    currentTimeMs = currentPositionMs + offsetMs,
                    onClick = { if (isSynced) onSeekTo(line.time) }
                )
            }
        }
    }
}

/**
 * Lyric line row supporting syllable-level richsync highlights and depth blurs.
 */
@Composable
private fun LyricLineRow(
    line: LyricLine,
    isCurrent: Boolean,
    isPast: Boolean,
    lyricsMode: LyricsMode,
    syncType: SyncType,
    currentTimeMs: Long,
    onClick: () -> Unit
) {
    val dynamicPalette = MaterialTheme.dynamicPalette
    val isPlain = syncType == SyncType.PLAIN

    val targetAlpha = when {
        isPlain -> 0.90f
        isCurrent -> 1.0f
        isPast -> if (lyricsMode == LyricsMode.CINEMA) 0.35f else 0.45f
        else -> if (lyricsMode == LyricsMode.CINEMA) 0.28f else 0.40f
    }
    val animAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = motionTween(AuralisDuration.Standard, AuralisEasing.Standard),
        label = "LyricAlpha"
    )

    val fontSize = when {
        isPlain -> 20.sp
        isCurrent -> if (lyricsMode == LyricsMode.CINEMA) 30.sp else 24.sp
        else -> if (lyricsMode == LyricsMode.CINEMA) 24.sp else 20.sp
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .alpha(animAlpha)
            .padding(vertical = 4.dp)
    ) {
        if (syncType == SyncType.RICHSYNC && !line.words.isNullOrEmpty()) {
            RichSyncLine(
                words = line.words,
                currentTimeMs = currentTimeMs,
                fontSize = fontSize,
                activeGlow = dynamicPalette.tintA
            )
        } else {
            Text(
                text = line.text,
                fontSize = fontSize,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isCurrent) AuralisKaraokeActive else AuralisKaraokeInactive,
                lineHeight = (fontSize.value * 1.35f).sp
            )
        }
    }
}

/**
 * Renders syllable-by-syllable richsync karaoke text with real-time progressive fill.
 */
@Composable
private fun RichSyncLine(
    words: List<LyricWord>,
    currentTimeMs: Long,
    fontSize: androidx.compose.ui.unit.TextUnit,
    activeGlow: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        words.forEach { word ->
            val wordProgress = LyricsEngine.calculateWordProgress(word, currentTimeMs)
            val isWordActive = wordProgress > 0f && wordProgress < 1f
            val isWordCompleted = wordProgress >= 1f

            val wordColor by animateColorAsState(
                targetValue = when {
                    isWordCompleted -> AuralisKaraokeActive
                    isWordActive -> activeGlow
                    else -> AuralisKaraokeInactive
                },
                animationSpec = tween(120),
                label = "WordColor"
            )

            Text(
                text = word.word,
                fontSize = fontSize,
                fontWeight = if (isWordCompleted || isWordActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = wordColor,
                lineHeight = (fontSize.value * 1.35f).sp
            )
        }
    }
}
