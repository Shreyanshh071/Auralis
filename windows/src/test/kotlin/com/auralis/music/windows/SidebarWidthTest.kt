package com.auralis.music.windows

import kotlin.test.Test
import kotlin.test.assertEquals

class SidebarWidthTest {
    @Test fun respectsDragLimits() {
        assertEquals(180f, sidebarWidth(-100f, 180f, 320f))
        assertEquals(320f, sidebarWidth(800f, 180f, 320f))
        assertEquals(232f, sidebarWidth(232f, 180f, 320f))
    }
    @Test fun respectsSpaceForMainContent() {
        assertEquals(300f, sidebarWidth(460f, 280f, 300f))
        assertEquals(280f, sidebarWidth(340f, 280f, 240f))
    }
}
