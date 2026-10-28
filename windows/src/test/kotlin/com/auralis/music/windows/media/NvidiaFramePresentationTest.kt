package com.auralis.music.windows.media

import kotlin.test.*

class NvidiaFramePresentationTest {
    @Test fun forcedOffOverrideReturnsControlToTheApplication() {
        assertEquals(0x60925292, NvidiaFramePresentation.repair(0x08416747))
    }
    @Test fun otherDriverModesAndMissingValuesStayUntouched() {
        listOf(null, 0x60925292, 0x47814940, 0x18888888, 0x32610244).forEach {
            assertNull(NvidiaFramePresentation.repair(it))
        }
    }
}
