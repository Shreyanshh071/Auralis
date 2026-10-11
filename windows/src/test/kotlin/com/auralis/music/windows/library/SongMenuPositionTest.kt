package com.auralis.music.windows.library

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class SongMenuPositionTest {
    @Test fun opensAtSongClick() {
        assertEquals(IntOffset(400, 120), songMenuPosition(IntOffset(400, 120), IntSize(1200, 800), IntSize(280, 560)))
    }
    @Test fun flipsAtBottomRightEdge() {
        assertEquals(IntOffset(890, 210), songMenuPosition(IntOffset(1170, 770), IntSize(1200, 800), IntSize(280, 560)))
    }
    @Test fun remainsInsideSmallWindow() {
        assertEquals(IntOffset.Zero, songMenuPosition(IntOffset(40, 40), IntSize(240, 400), IntSize(280, 560)))
    }
}
