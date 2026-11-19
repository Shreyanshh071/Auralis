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
    fun correspondingSingleInCacheIsFoundBeforeAnyProviderLookup() = runBlocking {
        val resolver = SpotifyCanvasResolver({ error("No download needed") }, { _, _ -> error("No catalogue needed") },
            releaseAlbum = { if (it == lyricsId) "Original Television Soundtrack" else track.title },
            cachedLookup = { "/$it.mp4" })
        assertEquals(ResolvedCanvas(singleId, "/$singleId.mp4"),
            resolver.findCached(track, listOf(lyricsId, singleId), matchRelease = true))
    }

    @Test
    fun autoRejectsDifferentReleaseAndKeepsTheCorrespondingSingle() = runBlocking {
        val resolver = SpotifyCanvasResolver({ "/$it.mp4" }, { _, excluded ->
            equivalent.takeUnless { it.id in excluded }
        }, releaseAlbum = { "Original Television Soundtrack" })
        assertEquals(ResolvedCanvas(singleId, "/$singleId.mp4"), resolver.find(track, lyricsId))
        assertEquals(ResolvedCanvas(lyricsId, "/$lyricsId.mp4"), resolver.find(track, lyricsId, matchRelease = false))
    }

    @Test
    fun autoAllowsMatchingRemasterAndUnknownRelease() = runBlocking {
        for (album in listOf("PETAL (Remastered 2003)", null)) {
            val resolver = SpotifyCanvasResolver({ "/canvas.mp4" }, { _, _ -> null }, releaseAlbum = { album })
            assertEquals(ResolvedCanvas(lyricsId, "/canvas.mp4"), resolver.find(track, lyricsId))
        }
    }

    @Test
    fun publicMetadataIsBoundToTheRequestedTrack() {
        val html = """
            <meta property="og:url" content="https://open.spotify.com/track/$lyricsId"/>
            <meta name="music:album" content="https://open.spotify.com/album/$singleId"/>
            <meta property="og:description" content="Artist · Rock &amp; Roll · Song · 2025"/>
        """.trimIndent()
        assertEquals("Rock & Roll", spotifyPageAlbum(html, lyricsId))
        assertNull(spotifyPageAlbum(html, singleId))
    }

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
    fun freshInstallUsesImportedSpotifyRecordingWithoutLyricsOrCatalogue() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val resolver = SpotifyCanvasResolver({
            lookedUp += it
            if (it == singleId) "/imported.mp4" else null
        }, { _, _ -> error("The imported recording already identifies this song") })
        assertEquals(ResolvedCanvas(singleId, "/imported.mp4"), resolver.find(track, null, listOf(singleId)))
        assertEquals(listOf(singleId), lookedUp)
    }

    @Test
    fun importFinishingInvalidatesEarlierMissingCanvas() = runBlocking {
        val resolver = SpotifyCanvasResolver({ if (it == singleId) "/imported.mp4" else null }, { _, _ -> null })
        assertNull(resolver.find(track, null))
        assertEquals(ResolvedCanvas(singleId, "/imported.mp4"), resolver.find(track, null, listOf(singleId)))
    }

    @Test
    fun enablingTheAccountFallbackInvalidatesEarlierMissingCanvas() = runBlocking {
        var optedIn = false
        val resolver = SpotifyCanvasResolver({ if (optedIn) "/canvas.mp4" else null }, { _, _ -> null })
        assertNull(resolver.find(track, lyricsId))
        optedIn = true
        assertEquals(ResolvedCanvas(lyricsId, "/canvas.mp4"), resolver.find(track, lyricsId, accountAvailable = true))
    }

    @Test
    fun importedReleaseCanSupplyCanvasWhenLyricsReleaseHasNone() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val resolver = SpotifyCanvasResolver({
            lookedUp += it
            if (it == singleId) "/single.mp4" else null
        }, { _, _ -> error("No external catalogue needed") })
        assertEquals(ResolvedCanvas(singleId, "/single.mp4"),
            resolver.find(track, lyricsId, listOf(lyricsId, singleId)))
        assertEquals(listOf(lyricsId, singleId), lookedUp)
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
}
