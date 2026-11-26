package com.auralis.music.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistFastScrollerTest {
    @Test fun theHandleAndTheSongItLandsOnAgreeAcrossThePlaylist() {
        // 115 songs, 10 on screen: 105 positions to scroll through.
        assertEquals(0f, fastScrollFraction(0f, 115, 10), 0f)
        assertEquals(1f, fastScrollFraction(105f, 115, 10), 0f)
        assertEquals(1f, fastScrollFraction(200f, 115, 10), 0f)
        assertEquals(0, fastScrollTarget(0f, 115, 10))
        assertEquals(105, fastScrollTarget(1f, 115, 10))
        assertEquals(53, fastScrollTarget(.5f, 115, 10))
        // Out-of-range drags stay on real songs.
        assertEquals(0, fastScrollTarget(-.3f, 115, 10))
        assertEquals(105, fastScrollTarget(1.4f, 115, 10))
        // A position maps back to itself.
        for (song in listOf(0, 17, 52, 105)) assertEquals(song, fastScrollTarget(fastScrollFraction(song.toFloat(), 115, 10), 115, 10))
    }

    @Test fun theBubbleNamesExactlyTheRowLevelWithTheHandle() {
        // The screenshot's case: rows 132 and 133, the handle's middle in row 132's span.
        val rows = listOf(Triple(131, 400f, 497f), Triple(132, 497f, 594f), Triple(133, 594f, 691f))
        assertEquals(132, fastScrollRowAt(540f, rows))
        assertEquals(132, fastScrollRowAt(497f, rows))   // a row's own top edge belongs to it
        assertEquals(131, fastScrollRowAt(496.9f, rows))
        assertEquals(133, fastScrollRowAt(690f, rows))
        // Above or below every row (a gap or the list's end), the nearest row.
        assertEquals(131, fastScrollRowAt(120f, rows))
        assertEquals(133, fastScrollRowAt(900f, rows))
        assertEquals(null, fastScrollRowAt(500f, emptyList()))
    }
}
