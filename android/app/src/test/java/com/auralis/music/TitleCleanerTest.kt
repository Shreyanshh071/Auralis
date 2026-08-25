package com.auralis.music

import com.auralis.music.data.network.TitleCleaner
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanerTest {

    @Test
    fun `cleanTitle removes extraneous video noise brackets`() {
        val raw = "The Weeknd - Blinding Lights (Official Music Video)"
        val cleaned = TitleCleaner.cleanTitle(raw)
        assertEquals("The Weeknd - Blinding Lights", cleaned)

        val raw4k = "Starboy [Official Video] [4K]"
        val cleaned4k = TitleCleaner.cleanTitle(raw4k)
        assertEquals("Starboy", cleaned4k)

        val rawLyric = "Save Your Tears (Lyrics / Lyric Video)"
        val cleanedLyric = TitleCleaner.cleanTitle(rawLyric)
        assertEquals("Save Your Tears", cleanedLyric)
    }

    @Test
    fun `cleanTitle preserves genuine musical words and punctuation`() {
        val raw = "In-A-Gadda-Da-Vida"
        val cleaned = TitleCleaner.cleanTitle(raw)
        assertEquals("In-A-Gadda-Da-Vida", cleaned)

        val raw2 = "Song (Part 1)"
        val cleaned2 = TitleCleaner.cleanTitle(raw2)
        assertEquals("Song (Part 1)", cleaned2)
    }

    @Test
    fun `splitArtistAndTitle splits standard hyphenated video titles`() {
        val raw = "Daft Punk - Get Lucky (Official Audio)"
        val (artist, song) = TitleCleaner.splitArtistAndTitle(raw)
        assertEquals("Daft Punk", artist)
        assertEquals("Get Lucky", song)
    }

    @Test
    fun `splitArtistAndTitle falls back to channel title when no separator exists`() {
        val raw = "Bohemian Rhapsody"
        val (artist, song) = TitleCleaner.splitArtistAndTitle(raw, fallbackArtist = "Queen Official")
        assertEquals("Queen Official", artist)
        assertEquals("Bohemian Rhapsody", song)
    }
}
