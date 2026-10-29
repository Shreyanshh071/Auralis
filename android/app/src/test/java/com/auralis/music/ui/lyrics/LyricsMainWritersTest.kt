package com.auralis.music.ui.lyrics

import com.auralis.music.domain.model.LyricsCredit
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMainWritersTest {
    @Test fun onlyMainWritersAndRealUploadersAreShown() {
        val data = LyricsData(SyncType.PLAIN, emptyList(), provider = LyricsProvider.SPICY_LYRICS, artistName = "Ravyn Lenae",
            credits = listOf(LyricsCredit("Spicy Lyrics", "https://spicylyrics.org"), LyricsCredit("Uploaded by `", ""),
                LyricsCredit("Written by Dacoury Natche, Ravyn Lenae, Spencer Eugene Stewart, Dominic Angelella, Paige IV", "")))
        val labels = lyricsCreditsForDisplay(data).map { it.label }
        assertTrue(labels.toString(), "Written by Ravyn Lenae, Dacoury Natche, Spencer Eugene Stewart" in labels)
        assertTrue(labels.none { it.startsWith("Uploaded by") })
    }

    @Test fun shortWriterListsAreUnchanged() {
        val credit = LyricsCredit("Written by A, B", "")
        assertEquals(credit, mainWritersCredit(credit, "C"))
    }
}
