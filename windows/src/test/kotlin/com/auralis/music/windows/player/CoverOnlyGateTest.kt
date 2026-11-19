package com.auralis.music.windows.player

import androidx.compose.ui.graphics.ImageBitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverOnlyGateTest {
    @Test fun slowArtworkDownloadDoesNotRejectFreshInstallCanvas() {
        var now = 1000L
        val artwork = AtomicReference<IntArray?>(null)
        val loading = AtomicBoolean(true)
        val gate = CoverOnlyGate("20jbSiX29FDX4oQxBXyUEi", artwork, loading) { now }
        val frame = ImageBitmap(16, 16)
        assertEquals(false, gate.allow(frame))
        now += 20_000L
        assertEquals(false, gate.allow(frame), "An outstanding download must keep checking, not reject the video")
        assertTrue(gate.checking)

        artwork.set(IntArray(CoverOnlyCanvas.COVER_SIDE * CoverOnlyCanvas.COVER_SIDE))
        loading.set(false)
        assertEquals(false, gate.allow(frame), "Sampling starts when the reference artwork is ready")
        assertTrue(gate.checking)
    }

    @Test fun failedArtworkLookupStillRejectsAnUnverifiableCanvas() {
        var now = 1000L
        val loading = AtomicBoolean(true)
        val gate = CoverOnlyGate("20jbSiX29FDX4oQxBXyUEi", AtomicReference<IntArray?>(null), loading) { now }
        val frame = ImageBitmap(16, 16)
        assertEquals(false, gate.allow(frame))
        loading.set(false)
        assertEquals(false, gate.allow(frame))
        now += 1501L
        assertEquals(null, gate.allow(frame))
    }
}
