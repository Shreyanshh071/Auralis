package com.auralis.music.windows

import javax.swing.JPanel
import org.jetbrains.skiko.SkiaLayer
import kotlin.test.*

class WindowInputRestoreTest {
    @Test fun findsComposeSurfacePastUnrelatedFirstChild() = javax.swing.SwingUtilities.invokeAndWait {
        val root = JPanel()
        root.add(JPanel().apply { add(JPanel()) })
        val surface = SkiaLayer()
        surface.isFocusable = true
        root.add(JPanel().apply { add(surface) })
        try {
            assertSame(surface, composeInputSurface(root), "Activation must target the Skia surface, not the first focusable container")
            assertNull(composeInputSurface(JPanel()), "Do not give focus to an unrelated container")
        } finally { surface.dispose() }
    }
}
