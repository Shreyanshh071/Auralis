package com.auralis.music.windows.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CanvasStageSourceTest {
    @Test fun readyCanvasDoesNotWaitForAlbumLookup() = runBlocking {
        val album = CompletableDeferred<String?>()
        val canvas = CompletableDeferred<String?>("canvas")
        val (first, alternate) = withTimeout(1_000) { firstStageSource(album, canvas) }
        assertEquals("canvas", first)
        assertSame(album, alternate)
    }

    @Test fun readyAlbumWinsWhenBothSourcesAreReady() = runBlocking {
        val album = CompletableDeferred<String?>("album")
        val canvas = CompletableDeferred<String?>("canvas")
        assertEquals("album" to canvas, firstStageSource(album, canvas))
    }

    @Test fun aMissDoesNotHideTheOtherSource() = runBlocking {
        val album = CompletableDeferred<String?>().apply { complete(null) }
        val canvas = CompletableDeferred<String?>("canvas")
        assertEquals("canvas" to null, firstStageSource(album, canvas))
    }
}
