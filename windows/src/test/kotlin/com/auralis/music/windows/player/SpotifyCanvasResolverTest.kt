package com.auralis.music.windows.player

import com.auralis.music.data.network.SpicyRecording
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SpotifyCanvasResolverTest {
    private val lyricsId = "3pY25LQJWfvvxdHhSg8ucj"
    private val singleId = "20jbSiX29FDX4oQxBXyUEi"
    private val track = Track(id = "-YOpbOA8-5o", title = "hate that i made you love me",
        artist = "Ariana Grande", album = "petal", duration = 198)
    private val equivalent get() = SpicyRecording(singleId, track.title, track.artist, 197949, track.title)

    @Test
    fun findsCanvasWithoutSpotifyLyricsOrASpotifyPlaybackId() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val resolver = SpotifyCanvasResolver({ lookedUp += it; "/canvas.mp4" }, { _, _ -> equivalent })
        assertEquals(ResolvedCanvas(singleId, "/canvas.mp4"), resolver.find(track, null))
        assertEquals(listOf(singleId), lookedUp)
    }

    @Test
    fun missingCanvasOnLyricsReleaseTriesMeasuredSingleAndRetainsItsIdForDecoderFallback() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val albums = mutableListOf<String?>()
        val resolver = SpotifyCanvasResolver({
            lookedUp += it
            if (it == singleId) "/single.mp4" else null
        }, { query, excluded ->
            albums += query.album
            if (query.album == track.title && singleId !in excluded) equivalent else null
        })
        assertEquals(ResolvedCanvas(singleId, "/single.mp4"), resolver.find(track, lyricsId))
        assertEquals(listOf(lyricsId, singleId), lookedUp)
        assertEquals(listOf<String?>("petal", track.title), albums)
    }

    @Test
    fun alternateReleaseMustMatchVersionArtistAndMeasuredDuration() = runBlocking {
        for (wrong in listOf(equivalent.copy(title = "${track.title} (Live)"),
            equivalent.copy(title = "${track.title} (Mixed)"), equivalent.copy(artist = "Someone Else"),
            equivalent.copy(durationMs = 300091))) {
            val lookedUp = mutableListOf<String>()
            val resolver = SpotifyCanvasResolver({ lookedUp += it; "/wrong.mp4" }, { _, _ -> wrong })
            assertNull(resolver.find(track, null))
            assertTrue(lookedUp.isEmpty(), "An unverified release must not reach the canvas provider")
        }
    }

    @Test
    fun nativeSpotifyTrackNeedsNeitherLyricsNorMetadataLookup() = runBlocking {
        val resolver = SpotifyCanvasResolver({ "/native.mp4" }, { _, _ -> error("Unnecessary metadata lookup") })
        assertEquals(ResolvedCanvas(singleId, "/native.mp4"), resolver.find(track.copy(id = "spotify:track:$singleId"), null))
    }

    @Test
    fun missesExpireAndCancellationIsNotRememberedAsNoCanvas() = runBlocking {
        var now = 1000L
        var available = false
        var calls = 0
        val resolver = SpotifyCanvasResolver({
            calls++
            if (available) "/canvas.mp4" else null
        }, { _, _ -> null }, { now })
        val native = track.copy(id = singleId)
        assertNull(resolver.find(native, null))
        available = true
        assertNull(resolver.find(native, null))
        assertEquals(1, calls)
        now += 60_001
        assertNotNull(resolver.find(native, null))

        var cancelled = true
        val cancellable = SpotifyCanvasResolver({ if (cancelled) throw CancellationException() else "/canvas.mp4" }, { _, _ -> null })
        assertFailsWith<CancellationException> { cancellable.find(native, null) }
        cancelled = false
        assertNotNull(cancellable.find(native, null))
        Unit
    }
    @Test
    fun persistedRecordingMatchFindsLocalCanvasAfterRestartAndLateLyricsHint() = runBlocking {
        val folder = java.nio.file.Files.createTempDirectory("canvas-recordings").toFile()
        val file = java.io.File(folder, "canvas.mp4").apply { writeBytes(byteArrayOf(1)) }
        val index = java.io.File(folder, "recordings.properties")
        try {
            val store = com.auralis.music.util.PropertiesFileKeyValueStore(index, synchronous = true)
            val first = SpotifyCanvasResolver({ file.absolutePath }, { _, _ -> equivalent }, mappings = store)
            assertEquals(singleId, first.find(track, null)?.spotifyId)
            val restarted = SpotifyCanvasResolver(
                { error("A cached canvas must not contact its provider") },
                { _, _ -> error("A persisted recording must not repeat metadata matching") },
                cachedLookup = { if (it == singleId) file.absolutePath else null },
                mappings = com.auralis.music.util.PropertiesFileKeyValueStore(index))
            assertEquals(file.absolutePath, restarted.cached(track, null)?.path)
            assertEquals(file.absolutePath, restarted.find(track, lyricsId)?.path,
                "A late lyrics ID must retain the already verified canvas recording")
            assertNull(restarted.cached(track.copy(title = "${track.title} (Live)"), null),
                "Changed recording metadata must not reuse the saved match")
            file.delete()
            assertNull(restarted.cached(track, null), "Missing video files must not be exposed as cache hits")
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun fastLyricsHintCacheHitAlsoPersistsItsRecordingMatch() = runBlocking {
        val folder = java.nio.file.Files.createTempDirectory("canvas-hint").toFile()
        val file = java.io.File(folder, "canvas.mp4").apply { writeBytes(byteArrayOf(1)) }
        val index = java.io.File(folder, "recordings.properties")
        try {
            val store = com.auralis.music.util.PropertiesFileKeyValueStore(index, synchronous = true)
            val resolver = SpotifyCanvasResolver({ error("Already downloaded") }, { _, _ -> error("Already identified") },
                cachedLookup = { if (it == lyricsId) file.absolutePath else null }, mappings = store)
            assertEquals(file.absolutePath, resolver.find(track, lyricsId)?.path)
            val restarted = SpotifyCanvasResolver({ error("Must reuse disk") }, { _, _ -> error("Must reuse identity") },
                cachedLookup = { if (it == lyricsId) file.absolutePath else null },
                mappings = com.auralis.music.util.PropertiesFileKeyValueStore(index))
            assertEquals(file.absolutePath, restarted.find(track, null)?.path)
        } finally { folder.deleteRecursively() }
    }

}
