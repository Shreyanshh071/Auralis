package com.auralis.music

import androidx.compose.foundation.lazy.LazyListState
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsScrollCenteringTest {

    @Test
    fun testLazyListStateOffsetCalculation() {
        val state = LazyListState(firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = 0)
        assertEquals(5, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }

    @Test
    fun testExactCenteringMath() {
        val viewportHeight = 800
        val viewportCenter = viewportHeight / 2 // 400

        // Single line lyric (height = 44px, offset = 550px)
        val singleLineHeight = 44
        val singleLineOffset = 550
        val singleLineCenter = singleLineOffset + (singleLineHeight / 2) // 572
        val deltaSingle = (singleLineCenter - viewportCenter).toFloat() // 172
        assertEquals(172f, deltaSingle, 0.01f)

        // After scrolling by deltaSingle:
        val newOffsetSingle = singleLineOffset - deltaSingle.toInt() // 378
        val newCenterSingle = newOffsetSingle + (singleLineHeight / 2) // 378 + 22 = 400
        assertEquals(viewportCenter, newCenterSingle)

        // Multi-line lyric (height = 112px, offset = 550px)
        val multiLineHeight = 112
        val multiLineOffset = 550
        val multiLineCenter = multiLineOffset + (multiLineHeight / 2) // 606
        val deltaMulti = (multiLineCenter - viewportCenter).toFloat() // 206
        assertEquals(206f, deltaMulti, 0.01f)

        // After scrolling by deltaMulti:
        val newOffsetMulti = multiLineOffset - deltaMulti.toInt() // 344
        val newCenterMulti = newOffsetMulti + (multiLineHeight / 2) // 344 + 56 = 400
        assertEquals(viewportCenter, newCenterMulti)
    }

    @Test
    fun testLazyListStateScrollOffsetConstraints() {
        try {
            val state = LazyListState(firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = -100)
            println("Negative scrollOffset accepted: " + state.firstVisibleItemScrollOffset)
        } catch (e: Throwable) {
            println("Negative scrollOffset rejected: " + e.message)
        }
    }
}
