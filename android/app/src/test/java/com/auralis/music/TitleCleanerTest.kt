package com.auralis.music

import com.auralis.music.data.network.TitleCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TitleCleanerTest {

    @Test
    fun `cleanTitle removes extraneous video noise brackets`() {
        val raw4k = "Starboy [Official Video] [4K]"
        val cleaned4k = TitleCleaner.cleanTitle(raw4k)
        assertEquals("Starboy", cleaned4k)

        val rawLyric = "Save Your Tears (Lyrics / Lyric Video)"
        val cleanedLyric = TitleCleaner.cleanTitle(rawLyric)
        assertEquals("Save Your Tears", cleanedLyric)
    }

    @Test
    fun `cleanTitle preserves musical versions and movie attributions`() {
        val rawRemix = "Fix You (Live in Buenos Aires)"
        val cleanedRemix = TitleCleaner.cleanTitle(rawRemix)
        assertEquals("Fix You (Live)", cleanedRemix)

        val rawAcoustic = "Perfect (Acoustic Version) [Official Audio]"
        val cleanedAcoustic = TitleCleaner.cleanTitle(rawAcoustic)
        assertEquals("Perfect (Acoustic)", cleanedAcoustic)

        val rawRemix2 = "Levitating (Remix feat. DaBaby)"
        val cleanedRemix2 = TitleCleaner.cleanTitle(rawRemix2)
        assertEquals("Levitating (Remix)", cleanedRemix2)

        val rawMovie = "Kesariya (From \"Brahmastra\") [Official 4K Video]"
        val cleanedMovie = TitleCleaner.cleanTitle(rawMovie)
        assertEquals("Kesariya (From \"Brahmastra\")", cleanedMovie)
    }

    @Test
    fun `cleanTitle preserves pre-hyphen song title for Indian Movie tracks`() {
        val rawBhojpuri1 = "Sorry Sorry - From \"Bhojpuriya Raja\""
        assertEquals("Sorry Sorry - From \"Bhojpuriya Raja\"", TitleCleaner.cleanTitle(rawBhojpuri1))

        val rawBhojpuri2 = "Palang Sagwan Ke - From \"Doli Saja Ke Rakhna\""
        assertEquals("Palang Sagwan Ke - From \"Doli Saja Ke Rakhna\"", TitleCleaner.cleanTitle(rawBhojpuri2))

        val rawBhojpuri3 = "Chhalakata Hamro Jawaniya - From \"Bhojpuriya Raja\""
        assertEquals("Chhalakata Hamro Jawaniya - From \"Bhojpuriya Raja\"", TitleCleaner.cleanTitle(rawBhojpuri3))
    }

    @Test
    fun `cleanTitle strips Indian and Bhojpuri channel spam and noise`() {
        val rawBhojpuri = "Raja Ji Ke Dilwa | Pawan Singh | Bhojpuri Video Song 2024"
        val cleanedBhojpuri = TitleCleaner.cleanTitle(rawBhojpuri)
        assertEquals("Raja Ji Ke Dilwa", cleanedBhojpuri)

        val rawBhojpuri2 = "Lollipop Lagelu (Full Video Song) | Wave Music"
        val cleanedBhojpuri2 = TitleCleaner.cleanTitle(rawBhojpuri2)
        assertEquals("Lollipop Lagelu", cleanedBhojpuri2)
    }

    @Test
    fun `cleanTitle preserves genuine musical words and punctuation`() {
        val raw = "In-A-Gadda-Da-Vida"
        val cleaned = TitleCleaner.cleanTitle(raw)
        assertEquals("In-A-Gadda-Da-Vida", cleaned)

        val raw2 = "Song (Part 1)"
        val cleaned2 = TitleCleaner.cleanTitle(raw2)
        assertEquals("Song (Part 1)", cleaned2)

        val raw3 = "Song - Remix"
        assertEquals("Song - Remix", TitleCleaner.cleanTitle(raw3))
    }

    @Test
    fun `splitArtistAndTitle splits standard hyphenated video titles`() {
        val raw = "Daft Punk - Get Lucky (Official Audio)"
        val (artist, song) = TitleCleaner.splitArtistAndTitle(raw)
        assertEquals("Daft Punk", artist)
        assertEquals("Get Lucky", song)
    }

    @Test
    fun `splitArtistAndTitle preserves Movie Song title when fallback artist is provided`() {
        val raw = "Sorry Sorry - From \"Bhojpuriya Raja\""
        val (artist, song) = TitleCleaner.splitArtistAndTitle(raw, fallbackArtist = "Pawan Singh")
        assertEquals("Pawan Singh", artist)
        assertEquals("Sorry Sorry - From \"Bhojpuriya Raja\"", song)
    }

    @Test
    fun `splitArtistAndTitle falls back to channel title when no separator exists`() {
        val raw = "Bohemian Rhapsody"
        val (artist, song) = TitleCleaner.splitArtistAndTitle(raw, fallbackArtist = "Queen Official")
        assertEquals("Queen", artist)
        assertEquals("Bohemian Rhapsody", song)
    }

    @Test
    fun `extractVersion identifies key musical variations accurately`() {
        assertEquals("Remix", TitleCleaner.extractVersion("Song (Club Remix)"))
        assertEquals("Acoustic", TitleCleaner.extractVersion("Song [Acoustic Version]"))
        assertEquals("Live", TitleCleaner.extractVersion("Song (Live at Forum)"))
        assertEquals("Instrumental", TitleCleaner.extractVersion("Song [Instrumental]"))
    }
}
