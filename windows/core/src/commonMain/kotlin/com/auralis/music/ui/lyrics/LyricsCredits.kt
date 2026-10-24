package com.auralis.music.ui.lyrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsCredit
import com.auralis.music.domain.model.LyricsProvider

/** Older providers/cache entries may identify the provider without a separate credit list. */
fun lyricsCreditsForDisplay(lyrics: LyricsData?): List<LyricsCredit> {
    if (lyrics == null) return emptyList()
    if (lyrics.credits.isNotEmpty()) return lyrics.credits
    val name = when (lyrics.provider) {
        LyricsProvider.SPICY_LYRICS -> "Unknown source (Spicy Lyrics API)"
        LyricsProvider.UNISON -> "Unison"
        LyricsProvider.AMLL -> "AMLL"
        LyricsProvider.LRCLIB -> "LRCLIB"
        LyricsProvider.JIOSAAVN -> "JioSaavn"
        LyricsProvider.YOUTUBE -> "YouTube"
        LyricsProvider.YOUTUBE_CAPTIONS -> "YouTube captions"
        LyricsProvider.QQMUSIC -> "QQ Music"
        LyricsProvider.NETEASE -> "NetEase"
        else -> lyrics.provider.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }
    return listOf(LyricsCredit("Lyrics · $name", ""))
}

/** Stays visible with the lyrics, including previews; every supplied credit links to its source. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricsCredits(lyrics: LyricsData?, modifier: Modifier = Modifier) {
    val credits = lyricsCreditsForDisplay(lyrics)
    if (credits.isEmpty()) return
    val uri = LocalUriHandler.current
    FlowRow(modifier.background(Color.Black.copy(alpha = .48f), RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        credits.forEach { credit ->
            val linked = credit.url.startsWith("https://")
            Text(credit.label, style = MaterialTheme.typography.labelMedium, fontSize = 12.sp,
                color = Color.White.copy(alpha = .88f),
                textDecoration = if (linked) TextDecoration.Underline else TextDecoration.None,
                modifier = if (linked) Modifier.clickable { uri.openUri(credit.url) } else Modifier)
        }
    }
}
