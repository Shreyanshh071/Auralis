package com.auralis.music.windows.player

import androidx.compose.ui.geometry.Offset
import kotlin.test.*
class CoverPointerActivityTest {
    @Test fun stationarySyntheticEventsDoNotWakeControls() {
        val activity=CoverPointerActivity()
        assertTrue(activity.moved(Offset(20f,30f)))
        repeat(100) { assertFalse(activity.moved(Offset(20f,30f))) }
        assertTrue(activity.moved(Offset(20.1f,30f)))
        activity.exited()
        assertTrue(activity.moved(Offset(20.1f,30f)))
        assertFalse(activity.moved(Offset(Float.NaN,0f)))
    }
}
