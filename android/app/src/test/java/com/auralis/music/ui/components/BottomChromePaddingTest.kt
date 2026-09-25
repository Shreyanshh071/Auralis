package com.auralis.music.ui.components

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomChromePaddingTest {
    private val miniPlayerHeight = 68.dp
    private val dock = 68.dp

    @Test
    fun bottomClearsDockAndMiniPlayerByTheGapOnly() {
        val shown = mutableFloatStateOf(1f)
        val chrome = BottomChrome { dock + miniPlayerHeight * shown.floatValue }
        val padding = ChromePaddingValues(16.dp, 8.dp, 16.dp, 16.dp, chrome, null, Density(1f))

        assertEquals(dock + miniPlayerHeight + 16.dp, padding.calculateBottomPadding())
        assertEquals(8.dp, padding.calculateTopPadding())
        assertEquals(16.dp, padding.calculateLeftPadding(LayoutDirection.Ltr))
    }

    @Test
    fun samePaddingInstanceFollowsTheMiniPlayerGlide() {
        // One instance for the page's lifetime: the list re-measures as the mini player
        // glides in/out, the page itself never has to recompose.
        val shown = mutableFloatStateOf(1f)
        val chrome = BottomChrome { dock + miniPlayerHeight * shown.floatValue }
        val padding = ChromePaddingValues(0.dp, 0.dp, 0.dp, 16.dp, chrome, null, Density(1f))

        shown.floatValue = 0.5f
        assertEquals(dock + 34.dp + 16.dp, padding.calculateBottomPadding())
        shown.floatValue = 0f
        // Mini player closed: only the dock (+gap) remains, no leftover mini-player gap.
        assertEquals(dock + 16.dp, padding.calculateBottomPadding())
    }

    @Test
    fun withoutAppChromeOnlyTheGapRemains() {
        val padding = ChromePaddingValues(0.dp, 0.dp, 0.dp, 16.dp, null, null, Density(1f))
        assertEquals(16.dp, padding.calculateBottomPadding())
    }
}
