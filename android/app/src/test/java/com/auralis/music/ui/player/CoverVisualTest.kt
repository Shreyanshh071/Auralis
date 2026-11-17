package com.auralis.music.ui.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CoverVisualTest {
    @Test fun canvasWinsWithoutLookingUpAnimatedArtwork() = runBlocking {
        val video = resolveCoverVideo({ ResolvedCanvas("spotify-id", "/canvas.mp4") }, { error("Unexpected fallback") })
        assertEquals(CoverVideo("/canvas.mp4", "spotify-id"), video)
    }

    @Test fun missingOrFailedCanvasFallsBackToAnimatedArtwork() = runBlocking {
        assertEquals(CoverVideo("cover.m3u8"), resolveCoverVideo({ null }, { "cover.m3u8" }))
        assertEquals(CoverVideo("cover.mp4"), resolveCoverVideo({ throw java.io.IOException() }, { "cover.mp4" }))
    }

    @Test fun noVideoLeavesStaticArtworkVisible() = runBlocking {
        assertNull(resolveCoverVideo({ null }, { null }))
        assertNull(resolveCoverVideo({ null }, { throw java.io.IOException() }))
    }

    @Test fun skippingASongDoesNotStartItsFallback() = runBlocking {
        assertFailsWith<CancellationException> {
            resolveCoverVideo({ throw CancellationException() }, { error("A skipped song should not keep loading") })
        }
        Unit
    }
}
