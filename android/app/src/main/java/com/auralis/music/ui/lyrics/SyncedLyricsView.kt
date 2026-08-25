package com.auralis.music.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.auralis.music.ui.components.auralisGlass
import com.auralis.music.ui.components.specularHighlight
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import com.auralis.music.ui.theme.AuralisKaraokeActive
import com.auralis.music.ui.theme.AuralisKaraokeHighlightGlow
import com.auralis.music.ui.theme.AuralisKaraokeInactive
import com.auralis.music.ui.theme.AuralisKaraokeTranslation
import com.auralis.music.ui.theme.AuralisPrimary
import com.auralis.music.ui.theme.AuralisSurfaceElevated
import com.auralis.music.ui.theme.GlassBorderHairline
import com.auralis.music.ui.theme.dynamicPalette

/**
 * 60fps Syllable-Level Synced Lyrics View supporting AMLL RichSync & LRCLIB Line-Sync,
 * smooth spring-physics auto-scrolling, translation toggles, and 3 display modes (Classic, Spicy, Cinema).
 */
@Composable
fun SyncedLyricsView(
    lyrics: LyricsData?,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    lyricsMode: LyricsMode = LyricsMode.SPICY,
    offsetMs: Long = 0,
    onOffsetChange: ((Long) -> Unit)? = null
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

    if (lyrics == null || lyrics.lines.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No synced lyrics available for this track",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val activeIndex = remember(currentPositionMs, offsetMs, lyrics.lines) {
        LyricsEngine.findActiveLyricIndex(lyrics.lines, currentPositionMs, offsetMs)
    }

    var showTranslation by remember { mutableStateOf(true) }
    var showOffsetControls by remember { mutableStateOf(false) }

    // Smooth Spring-driven auto-scrolling to keep active lyric line centered
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && activeIndex < lyrics.lines.size) {
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
            contentPadding = PaddingValues(top = 80.dp, bottom = 120.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (lyricsMode == LyricsMode.CINEMA) 24.dp else 16.dp)
        ) {
            itemsIndexed(
                items = lyrics.lines,
                key = { idx, line -> "${line.time}_$idx" }
            ) { index, line ->
                val isCurrent = index == activeIndex
                val isPast = index < activeIndex

                LyricLineRow(
                    line = line,
                    isCurrent = isCurrent,
                    isPast = isPast,
                    lyricsMode = lyricsMode,
                    syncType = lyrics.syncType,
                    currentTimeMs = currentPositionMs + offsetMs,
                    showTranslation = showTranslation,
                    onClick = { onSeekTo(line.time) }
                )
            }
        }

        // Top Utility Floating Pill (Translation toggle & timing offset adjuster)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Translation Toggle Button
            if (lyrics.lines.any { it.translatedText != null }) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .auralisGlass(
                            blurRadius = 16.dp,
                            alpha = 0.70f,
                            backgroundColor = AuralisSurfaceElevated,
                            borderColor = GlassBorderHairline,
                            shape = CircleShape
                        )
                        .clickable { showTranslation = !showTranslation }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.GTranslate,
                            contentDescription = "Translate",
                            tint = if (showTranslation) AuralisPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showTranslation) "EN" else "ORIG",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Sync Offset Adjuster Toggle
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .auralisGlass(
                        blurRadius = 16.dp,
                        alpha = 0.70f,
                        backgroundColor = AuralisSurfaceElevated,
                        borderColor = GlassBorderHairline,
                        shape = CircleShape
                    )
                    .clickable { showOffsetControls = !showOffsetControls }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Timing Offset",
                        tint = if (offsetMs != 0L) AuralisPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    if (offsetMs != 0L) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${if (offsetMs > 0) "+" else ""}${offsetMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AuralisPrimary
                        )
                    }
                }
            }
        }

        // Floating Offset Adjustment Controls
        if (showOffsetControls && onOffsetChange != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .auralisGlass(
                        blurRadius = 24.dp,
                        alpha = 0.85f,
                        backgroundColor = AuralisSurfaceElevated,
                        borderColor = GlassBorderHairline,
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(
                    onClick = { onOffsetChange(offsetMs - 250) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Delay -250ms", tint = Color.White)
                }
                Text(
                    text = "Offset: ${offsetMs}ms",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                IconButton(
                    onClick = { onOffsetChange(offsetMs + 250) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Advance +250ms", tint = Color.White)
                }
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
    showTranslation: Boolean,
    onClick: () -> Unit
) {
    val dynamicPalette = MaterialTheme.dynamicPalette

    // Kinetic blur & opacity animation based on active state and display mode
    val targetBlur = when {
        isCurrent -> 0.dp
        lyricsMode == LyricsMode.SPICY -> 1.5.dp
        else -> 0.dp
    }
    val animBlur by animateFloatAsState(
        targetValue = targetBlur.value,
        animationSpec = tween(400),
        label = "LyricBlur"
    )

    val targetAlpha = when {
        isCurrent -> 1.0f
        isPast -> if (lyricsMode == LyricsMode.CINEMA) 0.30f else 0.45f
        else -> if (lyricsMode == LyricsMode.CINEMA) 0.25f else 0.40f
    }
    val animAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(400),
        label = "LyricAlpha"
    )

    val fontSize = when (lyricsMode) {
        LyricsMode.CINEMA -> 28.sp
        LyricsMode.SPICY -> 22.sp
        LyricsMode.CLASSIC -> 18.sp
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .alpha(animAlpha)
            .then(if (animBlur > 0f) Modifier.blur(animBlur.dp) else Modifier)
    ) {
        if (syncType == SyncType.RICHSYNC && line.words != null && isCurrent) {
            // Syllable-level 60fps word highlight animator
            RichSyncLine(
                words = line.words,
                currentTimeMs = currentTimeMs,
                fontSize = fontSize,
                activeGlow = dynamicPalette.tintA
            )
        } else {
            // Standard Line-sync text
            Text(
                text = line.text,
                fontSize = fontSize,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isCurrent) AuralisKaraokeActive else AuralisKaraokeInactive,
                lineHeight = (fontSize.value * 1.3f).sp
            )
        }

        // Sub-text translation line
        if (showTranslation && !line.translatedText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = line.translatedText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isCurrent) AuralisKaraokeTranslation else AuralisKaraokeInactive.copy(alpha = 0.35f),
                fontWeight = FontWeight.Medium
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
                lineHeight = (fontSize.value * 1.3f).sp
            )
        }
    }
}
