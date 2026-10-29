package com.auralis.music.ui.lyrics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsCredit
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.LyricsAnimationMode
import com.auralis.music.ui.theme.LocalAppearanceSettings

val LocalLyricsProviderPicker = staticCompositionLocalOf<(() -> Unit)?> { null }

private fun LyricsCredit.isSongwriterCredit(): Boolean =
    label.startsWith("Written by ", ignoreCase = true) || label.startsWith("Writer:", ignoreCase = true)

/** Spicy lists every credited writer; only the main writers are shown: anyone who also
 *  performs the song, then the rest in Spicy's order (main writers come first), at most three. */
internal fun mainWritersCredit(credit: LyricsCredit, artist: String?): LyricsCredit {
    val prefix = "Written by "
    if (!credit.label.startsWith(prefix, ignoreCase = true)) return credit
    val names = credit.label.substring(prefix.length).split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val performers = artist.orEmpty().lowercase()
    val (performing, others) = names.partition { performers.isNotBlank() && performers.contains(it.lowercase()) }
    return credit.copy(label = prefix + (performing + others).take(3).joinToString(", "))
}

/** Community uploads sometimes carry a placeholder name such as "`"; those say nothing. */
private fun LyricsCredit.isEmptyUploaderCredit(): Boolean =
    label.startsWith("Uploaded by ", ignoreCase = true) && label.substring(12).none { it.isLetterOrDigit() }

/** Older providers/cache entries may identify the provider without a separate credit list. */
fun lyricsCreditsForDisplay(lyrics: LyricsData?, compact: Boolean = false): List<LyricsCredit> {
    if (lyrics == null) return emptyList()
    if (lyrics.credits.isNotEmpty()) {
        val source = lyrics.credits.first()
        val providerCredit = if (lyrics.provider == LyricsProvider.SPICY_LYRICS)
            LyricsCredit("Lyrics from Spicy Lyrics", "https://spicylyrics.org")
        else source.copy(label = "Lyrics from ${source.label}")
        val contributors = lyrics.credits.drop(1).filterNot { (compact && it.isSongwriterCredit()) || it.isEmptyUploaderCredit() }
            .map { mainWritersCredit(it, lyrics.artistName) }
        val viaSpicy = if (lyrics.provider == LyricsProvider.SPICY_LYRICS && source.label != "Spicy Lyrics")
            listOf(source.copy(label = "Source: ${source.label}")) else emptyList()
        return listOf(providerCredit) + contributors + viaSpicy
    }
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
    return listOf(LyricsCredit("Lyrics from $name", ""))
}

/** Stays visible with the lyrics, including previews; every supplied credit links to its source. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricsCredits(lyrics: LyricsData?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val sourceCredits = lyricsCreditsForDisplay(lyrics, compact)
    val spicyStyle = LyricsAnimationMode.fromDisplayName(LocalAppearanceSettings.current.lyricsAnimation) == LyricsAnimationMode.SPICY_LYRICS
    val credits = if (sourceCredits.isNotEmpty() && spicyStyle) sourceCredits + LyricsCredit(
        if (compact) "Spicy UI: Spikerko+" else "UI & animation inspired by Spicy Lyrics · Spikerko & contributors",
        "https://github.com/Spikerko/spicy-lyrics") else sourceCredits
    if (credits.isEmpty()) return
    val uri = LocalUriHandler.current
    val chooseProvider = LocalLyricsProviderPicker.current
    val creditStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = if (compact) 10.sp else 12.sp,
        lineHeight = if (compact) 13.sp else 16.sp,
        letterSpacing = 0.sp)
    if (compact) {
        // Keep source and action together; contributors share the row when they fit.
        FlowRow(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val source = credits.first()
            Text(source.label.removePrefix("Lyrics from ") + if (chooseProvider != null) "\u2002Change" else "",
                style = creditStyle, color = Color.White.copy(alpha = .88f),
                textDecoration = if (chooseProvider != null || source.url.startsWith("https://")) TextDecoration.Underline else TextDecoration.None,
                modifier = Modifier.alignByBaseline().then(
                if (chooseProvider != null) Modifier.clickable(onClick = chooseProvider)
                else credits.first().url.takeIf { it.startsWith("https://") }?.let { url ->
                    Modifier.clickable { uri.openUri(url) }
                } ?: Modifier))
            credits.drop(1).forEach { credit ->
                    Text(credit.label.replace("Uploaded by ", "Upload: ").replace("Made by ", "Sync: ")
                        .replace("Written by ", "Writer: "), style = creditStyle,
                        color = Color.White.copy(alpha = .78f),
                        textDecoration = if (credit.url.startsWith("https://")) TextDecoration.Underline else TextDecoration.None,
                        modifier = Modifier.alignByBaseline().then(
                            if (credit.url.startsWith("https://")) Modifier.clickable { uri.openUri(credit.url) } else Modifier))
            }
        }
        return
    }
    Column(modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            credits.filterNot { it.isSongwriterCredit() }.forEachIndexed { index, credit ->
                val linked = credit.url.startsWith("https://")
                if (index == 0 && chooseProvider != null) {
                    Text("${credit.label}\u2002Change", style = creditStyle, color = Color.White.copy(alpha = .88f),
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.alignByBaseline().clickable(onClick = chooseProvider))
                    return@forEachIndexed
                }
                Text(credit.label, style = creditStyle,
                    color = Color.White.copy(alpha = .88f),
                    textDecoration = if (linked) TextDecoration.Underline else TextDecoration.None,
                    modifier = if (index == 0 && chooseProvider != null) Modifier.clickable(onClick = chooseProvider)
                        else if (linked) Modifier.clickable { uri.openUri(credit.url) } else Modifier)
            }
        }
        credits.filter { it.isSongwriterCredit() }.forEach { credit ->
            val linked = credit.url.startsWith("https://")
            Text(credit.label, style = creditStyle, color = Color.White.copy(alpha = .72f),
                textDecoration = if (linked) TextDecoration.Underline else TextDecoration.None,
                modifier = Modifier.fillMaxWidth().then(if (linked) Modifier.clickable { uri.openUri(credit.url) } else Modifier))
        }
    }
}
