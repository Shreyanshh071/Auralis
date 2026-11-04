package com.auralis.music.domain.lyrics

import com.auralis.music.domain.model.LyricsData

/** Script evidence only; safe to inspect on the UI thread without loading a dictionary. */
object LyricsRomanizationContext {
    fun hasKana(text: String): Boolean = text.any {
        it in 'ぁ'..'ゖ' || it in 'ァ'..'ヺ' || it in '\uFF66'..'\uFF9D'
    }

    fun isJapanese(lyrics: LyricsData?): Boolean = lyrics != null &&
        (hasKana(lyrics.plainLyrics.orEmpty()) || lyrics.lines.any { hasKana(it.text) })
}
