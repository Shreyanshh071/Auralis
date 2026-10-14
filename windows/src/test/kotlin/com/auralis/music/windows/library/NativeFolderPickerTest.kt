package com.auralis.music.windows.library

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull

class NativeFolderPickerTest {
    @Test
    fun testNativeFolderPickerClassLoads() {
        assertNotNull(NativeFolderPicker)
    }
}
