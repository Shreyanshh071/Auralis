package com.auralis.music.windows.profile

import androidx.compose.ui.unit.*
import kotlin.math.roundToInt
import kotlin.test.*

class AccountMenuPositionTest {
    @Test fun menuStaysBelowTitleBarAtWindowsDisplayScales() {
        for (scale in listOf(1f, 1.25f, 1.5f, 2f)) {
            fun px(dp: Int) = (dp * scale).roundToInt()
            val anchor = IntRect(px(700), 0, px(880), px(44))
            val menu = IntSize(px(380), px(350))
            val position = AccountMenuPositionProvider(px(8), px(8))
                .calculatePosition(anchor, IntSize(px(1200), px(800)), LayoutDirection.Ltr, menu)
            assertEquals(anchor.bottom + px(8), position.y)
            assertEquals(anchor.right, position.x + menu.width)
        }
    }

    @Test fun narrowWindowKeepsTheMenuInsideTheContentEdges() {
        val anchor = IntRect(180, 0, 300, 44)
        val position = AccountMenuPositionProvider(8, 8)
            .calculatePosition(anchor, IntSize(400, 320), LayoutDirection.Ltr, IntSize(380, 260))
        assertEquals(8, position.x)
        assertEquals(52, position.y)
        assertTrue(position.x + 380 <= 392)
        assertTrue(position.y + 260 <= 320)
    }
}
