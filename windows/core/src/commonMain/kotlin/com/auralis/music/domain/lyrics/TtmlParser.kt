package com.auralis.music.domain.lyrics

import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider

/**
 * Domain-facing alias of the single TTML implementation in
 * [com.auralis.music.data.parser.TtmlParser].
 *
 * Two independent copies of this parser used to exist and drifted apart; the
 * timing rules (genuine word ends only, no gap filling) live in one place now.
 */
object TtmlParser {

    fun parse(ttmlXml: String, provider: LyricsProvider = LyricsProvider.AMLL): LyricsData =
        com.auralis.music.data.parser.TtmlParser.parse(ttmlXml, provider)

    fun parseTimestamp(timestamp: String?): Long =
        com.auralis.music.data.parser.TtmlParser.parseTimestamp(timestamp)
}
