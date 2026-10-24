package com.auralis.music.ui.lyrics

import com.auralis.music.domain.model.LyricLine
import kotlin.test.*

class SingerLayoutTest {
    @Test fun dominantSingerGoesLeftSecondRightAndThirdCenter() {
        val lines = listOf(
            LyricLine(0, "Guest", agent = "v1"),
            LyricLine(1000, "Main", agent = "v2"),
            LyricLine(1500, "Backing", isBackground = true),
            LyricLine(2000, "Third", agent = "v3"),
            LyricLine(3000, "Main again", agent = "v2"),
            LyricLine(4000, "Guest again", agent = "v1"),
            LyricLine(5000, "Main finish", agent = "v2"),
            LyricLine(6000, "Together", agent = "v1000"))
        val styles = MetroSpeakerLayout.build(lines)!!
        assertEquals(listOf(MetroSpeakerSide.END, MetroSpeakerSide.START, MetroSpeakerSide.START,
            MetroSpeakerSide.CENTER, MetroSpeakerSide.START, MetroSpeakerSide.END,
            MetroSpeakerSide.START, MetroSpeakerSide.CENTER), styles.map { it.side })
        assertEquals(styles[1].slot, styles[2].slot, "Unattributed backing stays with its lead")
        assertEquals(listOf(0L, 1000L, 1500L, 2000L, 3000L, 4000L, 5000L, 6000L), lines.map { it.time })
    }

    @Test fun tiesStayStableAndAdlibsDoNotInventDuets() {
        val tie = listOf(LyricLine(0, "Second opens", agent = "v2"), LyricLine(1000, "First", agent = "v1"))
        assertEquals(listOf(MetroSpeakerSide.END, MetroSpeakerSide.START), MetroSpeakerLayout.build(tie)!!.map { it.side })
        val solo = listOf(LyricLine(0, "Solo", agent = "v1"), LyricLine(500, "Adlib", agent = "v2", isBackground = true))
        assertNull(MetroSpeakerLayout.build(solo))
        assertNull(MetroSpeakerLayout.build(listOf(LyricLine(0, "No singer metadata"))))
    }
}
