package com.auralis.music.ui.screens.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.launch

@Composable
fun KineticLyricsView(
    lyrics: LyricsData?,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    offsetMs: Long = 0,
    onOffsetChange: ((Long) -> Unit)? = null
) {
    if (lyrics == null || lyrics.lines.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No lyrics found for this track",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        return
    }

    val activeIndex = remember(lyrics.lines, currentPositionMs, offsetMs) {
        LyricsEngine.findActiveLyricIndex(lyrics.lines, currentPositionMs, offsetMs)
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Smooth kinetic auto-scroll to center active lyric line
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            coroutineScope.launch {
                listState.animateScrollToItem(
                    index = (activeIndex - 2).coerceAtLeast(0),
                    scrollOffset = 0
                )
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Offset Stepper Controls (±500ms)
        if (onOffsetChange != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sync Offset: ${offsetMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { onOffsetChange(offsetMs - 500) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "-500ms",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onOffsetChange(offsetMs + 500) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "+500ms",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 120.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                val isActive = index == activeIndex
                val isPast = index < activeIndex

                val targetAlpha = when {
                    isActive -> 1.0f
                    isPast -> 0.45f
                    else -> 0.35f
                }
                val alpha by animateFloatAsState(
                    targetValue = targetAlpha,
                    animationSpec = tween(300),
                    label = "lyricAlpha"
                )

                val textColor by animateColorAsState(
                    targetValue = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(300),
                    label = "lyricTextColor"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(alpha)
                        .clickable { onSeekTo(line.time) }
                ) {
                    if (lyrics.syncType == SyncType.RICHSYNC && line.words != null && isActive) {
                        // Word-by-Word karaoke rendering
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            line.words.forEach { word ->
                                val progress = LyricsEngine.calculateWordProgress(word, currentPositionMs, offsetMs)
                                val wordColor = if (progress >= 1.0f) {
                                    MaterialTheme.colorScheme.primary
                                } else if (progress > 0.0f) {
                                    Color.White
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }

                                Text(
                                    text = word.word,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = wordColor,
                                    lineHeight = 32.sp
                                )
                            }
                        }
                    } else {
                        // Standard line-synced rendering
                        Text(
                            text = line.text,
                            fontSize = if (isActive) 24.sp else 20.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            lineHeight = if (isActive) 32.sp else 28.sp
                        )
                    }

                    // Bilingual Translated line subtitle
                    if (!line.translatedText.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = line.translatedText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}
