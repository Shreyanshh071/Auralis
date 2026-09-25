package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.network.TitleCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seen live: the Housefull 2 album's "Anarkali Disco Chali (Hyper Mix)[Remix By Dj Shiva]" (283s)
 * was shown the album cut's lyrics. lrclib lists the album cut at 285s, so any check that trusts
 * a close length over the version tag hands a remix the wrong words and timing.
 */
class RemixLyricsVersionTest {

    private val hyperMix = "Anarkali Disco Chali (Hyper Mix)[Remix By Dj Shiva]"

    @Test
    fun `album cut lyrics clash with a remix`() {
        assertTrue(LyricsClient.isVersionClash(hyperMix, "Anarkali Disco Chali"))
        assertTrue(LyricsClient.isVersionClash("Right Now Now (Remix By Dj Khushi)", "Right Now Now"))
        // A result that doesn't name its track can't vouch for being the remix.
        assertTrue(LyricsClient.isVersionClash(hyperMix, null))
        // Nor does remix lyrics fit the album cut.
        assertTrue(LyricsClient.isVersionClash("Anarkali Disco Chali", "Anarkali Disco Chali (Hyper Mix)"))
    }

    @Test
    fun `matching versions and plain titles never clash`() {
        assertFalse(LyricsClient.isVersionClash(hyperMix, "Anarkali Disco Chali (Hyper Mix) [Remix By Dj Shiva]"))
        assertFalse(LyricsClient.isVersionClash("Anarkali Disco Chali", "Anarkali Disco Chali"))
        assertFalse(LyricsClient.isVersionClash("Anarkali Disco Chali", null))
        // Same-arrangement tags aren't versions that change the timing.
        assertFalse(LyricsClient.isVersionClash("Jadoo Ki Jhappi (Jhankar)", "Jadoo Ki Jhappi"))
    }

    @Test
    fun `unnamed results are labelled without our query's tags`() {
        assertEquals("Anarkali Disco Chali", TitleCleaner.withoutBracketedTags(hyperMix))
        assertEquals("Jadoo Ki Jhappi", TitleCleaner.withoutBracketedTags("Jadoo Ki Jhappi (Jhankar)"))
        assertEquals("Do U Know", TitleCleaner.withoutBracketedTags("Do U Know"))
        // Never blank: a title that is only a tag keeps itself.
        assertEquals("(Intro)", TitleCleaner.withoutBracketedTags("(Intro)"))
    }
}
