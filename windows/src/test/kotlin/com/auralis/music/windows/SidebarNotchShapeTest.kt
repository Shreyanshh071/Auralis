package com.auralis.music.windows

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.*

class SidebarNotchShapeTest {
    @Test fun narrowRemaindersKeepTheirTileInsteadOfLeavingAGap() {
        for (remaining in listOf(1f, 4f, 12f, 24f, 32f, 80f)) {
            val outline = SidebarNotchShape(12.dp, (280f - remaining).dp, 64.dp)
                .createOutline(Size(280f, 400f), LayoutDirection.Ltr, Density(1f))
            assertTrue(outline is Outline.Generic, "Keep the side tile with $remaining px available")
            assertEquals(400f, outline.path.getBounds().bottom, .01f)
        }
        assertTrue(SidebarNotchShape(12.dp, 280.dp, 64.dp)
            .createOutline(Size(280f, 400f), LayoutDirection.Ltr, Density(1f)) is Outline.Rounded)
    }
}
