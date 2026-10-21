package com.auralis.music

import androidx.compose.ui.unit.IntSize
import com.auralis.music.ui.onboarding.fitPlayerPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPreviewSizeTest {
    @Test fun tallPhoneKeepsItsOwnAspectRatioInsteadOfTheOldReferencePhone() {
        val result = fitPlayerPreview(IntSize(1080, 2240), IntSize(976, 1400))
        assertEquals(675, result.width)
        assertEquals(1400, result.height)
        assertEquals(.625f, result.scale, .00001f)
    }

    @Test fun allWindowShapesFitWithoutStretchingOrTriggeringACompactPlayerLayout() {
        val windows = listOf(IntSize(1080, 2240), IntSize(1080, 1920),
            IntSize(720, 1440), IntSize(1600, 2400), IntSize(2240, 1080))
        val containers = listOf(IntSize(900, 1300), IntSize(600, 1200), IntSize(400, 300))
        for (window in windows) for (container in containers) {
            val result = fitPlayerPreview(window, container)
            assertTrue(result.width <= container.width && result.height <= container.height)
            // Dividing physical bounds by the scaled density recovers the full player's
            // original logical bounds, with at most half a pixel of measurement rounding.
            assertEquals(window.width.toFloat(), result.width / result.scale, .51f / result.scale)
            assertEquals(window.height.toFloat(), result.height / result.scale, .51f / result.scale)
        }
    }

    @Test fun widthLimitedPreviewUsesTheSameScaleForHeight() {
        val result = fitPlayerPreview(IntSize(1080, 2240), IntSize(540, 1500))
        assertEquals(540, result.width)
        assertEquals(1120, result.height)
        assertEquals(.5f, result.scale, .00001f)
    }

    @Test fun emptyPreviewSpaceDoesNotInventAnOversizedMinimumScale() {
        val result = fitPlayerPreview(IntSize(1080, 2240), IntSize(0, 0))
        assertEquals(0, result.width)
        assertEquals(0, result.height)
        assertEquals(0f, result.scale, 0f)
    }
}
