package com.auralis.music.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.LocalReducedMotion

/** A compact current-line preview in the artwork-to-title gap; tap for full lyrics. */
@Composable
internal fun InlinePlayerLyrics(
    lyrics: LyricsData?,
    positionState: State<Long>,
    offsetMs: Long,
    isLoading: Boolean,
    onOpenFullLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val loadingText = str(R.string.syncing_lyrics)
    val unavailableText = str(R.string.no_lyrics_available)
    val line by remember(lyrics, positionState, offsetMs, isLoading, loadingText, unavailableText) {
        derivedStateOf {
            when {
                isLoading -> loadingText
                lyrics == null -> unavailableText
                lyrics.lines.any { it.time > 0L } -> {
                    val current = lyrics.lines.lastOrNull { it.time <= positionState.value + offsetMs && !it.isBackground }
                    if (current == null || current.isInstrumental) "♪" else current.text
                }
                else -> lyrics.lines.firstOrNull { it.text.isNotBlank() }?.text
                    ?: lyrics.plainLyrics?.lineSequence()?.firstOrNull { it.isNotBlank() }
                    ?: unavailableText
            }
        }
    }
    val reducedMotion = LocalReducedMotion.current
    Box(modifier.fillMaxWidth().heightIn(min = 28.dp).clickable(onClick = onOpenFullLyrics),
        contentAlignment = Alignment.Center) {
        Crossfade(line, animationSpec = tween(if (reducedMotion) 0 else 140), label = "inlinePlayerLyric") { text ->
            Text(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
