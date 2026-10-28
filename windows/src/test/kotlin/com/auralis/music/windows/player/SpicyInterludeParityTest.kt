package com.auralis.music.windows.player

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.lyrics.spicyInterludeWindows
import com.auralis.music.ui.lyrics.LyricsDotsTransition
import kotlin.test.*

class SpicyInterludeParityTest {
    @Test fun qualifyingLeadGapStartsImmediatelyAtItsSuppliedEnd() {
        for (gap in listOf(2999L, 3000L, 4999L, 5000L)) {
            val lines = listOf(LyricLine(1000, "Lead", endTime = 4000), LyricLine(4000 + gap, "Next", endTime = 14000))
            val regular = spicyInterludeWindows(lines)
            if (gap < 3000) assertTrue(regular.isEmpty()) else assertEquals(mapOf(0 to (4000L to 4000L + gap)), regular)
            val minimal = spicyInterludeWindows(lines, minimalMode = true)
            if (gap < 5000) assertTrue(minimal.isEmpty()) else assertEquals(regular, minimal)
        }
    }

    @Test fun backingVocalsDoNotShortenDelayOrCreateTheLeadGap() {
        val lines = listOf(LyricLine(1000, "Lead", endTime = 4000),
            LyricLine(2500, "(Harmony)", isBackground = true, endTime = 10000),
            LyricLine(12000, "Next lead", endTime = 13000))
        assertEquals(mapOf(1 to (4000L to 12000L)), spicyInterludeWindows(lines),
            "Place dots after the background row but use the lead end, exactly as upstream")
        assertTrue(spicyInterludeWindows(lines.toMutableList().apply { this[2] = this[2].copy(time = 6000) }).isEmpty(),
            "A background start/end must never create its own interlude")
    }

    @Test fun unknownEndAndFinalTrackTailNeverManufactureInterludes() {
        assertTrue(spicyInterludeWindows(listOf(LyricLine(1000, "Unknown ending"), LyricLine(60000, "Next"))).isEmpty())
        assertTrue(spicyInterludeWindows(listOf(LyricLine(1000, "Last phrase", endTime = 4000))).isEmpty())
        val timed = listOf(LyricLine(1000, "Timed", words = listOf(LyricWord("Timed", 1000, 3000))), LyricLine(12000, "Next"))
        assertEquals(mapOf(0 to (4000L to 12000L)), spicyInterludeWindows(timed), "Genuine word end data is usable")
    }

    @Test fun earlierLeadOverlapDoesNotReplaceTheAdjacentLeadRule() {
        val lines = listOf(LyricLine(1000, "Older held voice", endTime = 15000),
            LyricLine(2000, "Current lead", endTime = 4000), LyricLine(10000, "Next lead", endTime = 11000))
        assertEquals(mapOf(1 to (4000L to 10000L)), spicyInterludeWindows(lines))
    }

    @Test fun dotsEnterAtTheGapAndBeginTheirExit500msBeforeTheNextLead() {
        assertEquals(0f, LyricsDotsTransition.scale(-1, 8001))
        assertEquals(1f, LyricsDotsTransition.scale(7500, 500), .0001f)
        assertTrue(LyricsDotsTransition.scale(7850, 150) < 1f)
        assertEquals(0f, LyricsDotsTransition.scale(7900, 100))
    }
}
