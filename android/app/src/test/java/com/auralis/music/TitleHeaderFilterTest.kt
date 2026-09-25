package com.auralis.music

import com.auralis.music.data.parser.LyricsContentFilter
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleHeaderFilterTest {

    private fun lyrics(vararg texts: String) = LyricsData(
        syncType = SyncType.LINE_SYNC,
        lines = texts.mapIndexed { i, t -> LyricLine(time = i * 3_000L, text = t) },
        provider = LyricsProvider.NETEASE
    )

    private fun texts(d: LyricsData) = d.lines.map { it.text }

    @Test
    fun `an artists-dash-title header added by the source is removed`() {
        // Seen live: NetEase, with the Chinese list comma between artists.
        val d = lyrics("Labh Janjua、Sonu Kakkar、Neha Kakkar - London Thumakda", "Tu ho gayi one to two", "Oh kudiye what to do")
        assertEquals(listOf("Tu ho gayi one to two", "Oh kudiye what to do"), texts(LyricsContentFilter.removeTitleHeader(d, "LONDON THUMAKDA")))
    }

    @Test
    fun `a title-dash-artists header is removed too, and a title with a film suffix still matches`() {
        val d = lyrics("London Thumakda (From \"Queen\") - Labh Janjua", "Tu ho gayi one to two", "Oh kudiye what to do")
        assertEquals(listOf("Tu ho gayi one to two", "Oh kudiye what to do"), texts(LyricsContentFilter.removeTitleHeader(d, "London Thumakda")))
    }

    @Test
    fun `a first line that is just the title is a sung lyric and is kept`() {
        val d = lyrics("Tum hi ho", "Ab tum hi ho", "Zindagi ab tum hi ho")
        assertEquals(texts(d), texts(LyricsContentFilter.removeTitleHeader(d, "Tum Hi Ho")))
    }

    @Test
    fun `a chant that repeats the title on both sides of a dash is kept`() {
        val d = lyrics("Tum hi ho - tum hi ho", "Ab tum hi ho", "Zindagi ab tum hi ho")
        assertEquals(texts(d), texts(LyricsContentFilter.removeTitleHeader(d, "Tum Hi Ho")))
    }

    @Test
    fun `only the first few lines are checked`() {
        val d = lyrics("one", "two", "three", "four", "Somebody - London Thumakda")
        assertEquals(texts(d), texts(LyricsContentFilter.removeTitleHeader(d, "London Thumakda")))
    }

    @Test
    fun `music-note marker lines are dropped so the intro starts at the first sung line`() {
        // lrclib's "Do U Know": [00:00.57] 🎵 / [00:09.37] Hume Tumse... / [00:13.86] 🎵 / ...
        val d = lyrics("🎵", "Hume Tumse Mohabbat Hui Hai", "🎵", "Hume Tumse Mohabbat Hui Hai", "♪ ♫", "...", "Oh Jaan-e-mann! Do U Know?")
        val cleaned = LyricsContentFilter.cleanForDisplay(d, "Do U Know")
        assertEquals(listOf("Hume Tumse Mohabbat Hui Hai", "Hume Tumse Mohabbat Hui Hai", "Oh Jaan-e-mann! Do U Know?"), texts(cleaned))
        assertEquals(3_000L, cleaned.lines.first().time) // timing of kept lines is untouched
    }

    @Test
    fun `credit lines in Chinese and English are dropped, lyrics kept`() {
        val d = lyrics("人声：Mika Singh/Neha Kakkar", "作词：Amitabh Bhattacharya", "Lyrics by: Someone", "Jadoo ki jhappi", "Pappi jhappi")
        assertEquals(listOf("Jadoo ki jhappi", "Pappi jhappi"), texts(LyricsContentFilter.cleanForDisplay(d, "Jadoo Ki Jhappi")))
    }

    @Test
    fun `the placeholder line for an instrumental track is kept`() {
        val d = LyricsData(
            syncType = SyncType.PLAIN,
            lines = listOf(LyricLine(time = 0L, text = "♪ Instrumental ♪", isInstrumental = true)),
            provider = LyricsProvider.LRCLIB
        )
        assertEquals(texts(d), texts(LyricsContentFilter.cleanForDisplay(d, "Some Instrumental")))
    }

    @Test
    fun `a dash line about something else is kept`() {
        val d = lyrics("Ek do teen - chaar", "Ab tum hi ho", "Zindagi ab tum hi ho")
        assertEquals(texts(d), texts(LyricsContentFilter.removeTitleHeader(d, "Tum Hi Ho")))
    }
}
