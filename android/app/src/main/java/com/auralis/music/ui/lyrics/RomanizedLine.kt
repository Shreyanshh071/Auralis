package com.auralis.music.ui.lyrics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.auralis.music.data.datastore.ContentSettingsStore
import com.auralis.music.domain.lyrics.LyricsRomanizer
import com.auralis.music.domain.lyrics.LyricsRomanizationCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Song-wide kana evidence disambiguates all-kanji lines from Chinese. */
internal val LocalJapaneseRomanizationContext = staticCompositionLocalOf { false }

/**
 * Settings → Content → Romanize lyrics: the Latin spelling of [text] as a small second line.
 * Draws nothing when romanization is off, the line's script isn't selected, or there's nothing
 * to romanize. Display only: the original line keeps its timing and highlight untouched.
 */
@Composable
fun RomanizedLine(
    text: String,
    color: Color,
    fontSize: TextUnit,
    textAlign: TextAlign,
    modifier: Modifier = Modifier
) {
    val settings by ContentSettingsStore.current.collectAsState()
    if (!settings.romanizationEnabled) return
    val japaneseContext = LocalJapaneseRomanizationContext.current
    // A new key clears the previous line immediately; late jobs cannot display stale readings.
    key(text, settings.romanizedScripts, japaneseContext) {
        val romanized by produceState<String?>(initialValue = LyricsRomanizationCache.get(text, settings.romanizedScripts, japaneseContext)?.text) {
            value = withContext(Dispatchers.Default) {
                LyricsRomanizer.romanize(text, settings.romanizedScripts, japaneseContext)
            }
        }
        val displayText = romanized
        if (displayText != null) {
            Text(
                text = displayText,
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                color = color,
                textAlign = textAlign,
                modifier = modifier.fillMaxWidth().padding(top = 2.dp)
            )
        }
    }
}
