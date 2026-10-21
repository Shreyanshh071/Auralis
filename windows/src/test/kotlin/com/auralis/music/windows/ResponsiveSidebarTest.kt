package com.auralis.music.windows

import kotlin.test.*

class ResponsiveSidebarTest {
    @Test fun coverAndCardsFillTheSidebarUntilHeightStopsGrowth() {
        for (height in listOf(600f, 800f, 1000f)) {
            val limit = nowPlayingWidthLimit(1920f, height, 290f)
            assertEquals((height - 16f) * .40f + 32f, limit)
            assertEquals((height - 16f) * .40f, limit - 32f)
        }
    }
    @Test fun smallerWindowsReserveSpaceForThePage() {
        val limit = nowPlayingWidthLimit(1000f, 900f, 290f)
        assertTrue(limit + 290f + 360f + 32f <= 1000f)
        assertTrue(nowPlayingWidthLimit(800f, 600f, 290f) < 280f)
    }
}
