package com.auralis.music.windows.home

import com.auralis.music.domain.model.Artist
import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.*
import kotlin.test.*

class FamiliarArtistLookupTest {
    private class Memory : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String, default: String) = values[key] ?: default
        override fun putStrings(values: Map<String, String>) { this.values.putAll(values) }
        override fun clear() = values.clear()
    }
    @Test fun portraitAppearsWhileYouTubeIsWaitingAndSurvivesRestart() = runBlocking {
        val cache = Memory()
        val release = CompletableDeferred<Unit>()
        val displayed = CompletableDeferred<Artist>()
        val lookup = FamiliarArtistLookup(cache, { release.await(); listOf(Artist("UC123", it)) }, { "https://portrait/$it.jpg" })
        val updates = mutableListOf<Artist>()
        val job = launch { lookup.resolve("TV Girl") { updates += it; displayed.complete(it) } }
        assertNotNull(withTimeout(1000) { displayed.await() }.thumbnail)
        assertFalse(job.isCompleted, "Portrait must appear without waiting for YouTube's cooldown")
        release.complete(Unit); job.join()
        assertEquals("UC123", updates.last().id)
        assertNotNull(updates.last().thumbnail, "Channel-only update must preserve the independent portrait")
        var requests = 0
        val reopened = FamiliarArtistLookup(cache, { requests++; emptyList() }, { requests++; null })
        var saved: Artist? = null
        reopened.resolve("TV Girl") { saved = it }
        assertNotNull(saved?.thumbnail)
        assertEquals("UC123", saved?.id)
        assertEquals(0, requests)
    }
    @Test fun failuresCanRetryAndCancellationIsNotCached() = runBlocking {
        val cache = Memory()
        var succeeds = false
        val lookup = FamiliarArtistLookup(cache, { emptyList() }, { if (succeeds) "https://portrait.jpg" else null })
        lookup.resolve("Sidney Gish") {}
        assertNull(lookup.cached("Sidney Gish"))
        succeeds = true
        lookup.resolve("Sidney Gish") {}
        assertNotNull(lookup.cached("Sidney Gish")?.thumbnail)
        val cancellationCache = Memory()
        val started = CompletableDeferred<Unit>()
        val cancel = FamiliarArtistLookup(cancellationCache, { started.complete(Unit); awaitCancellation() }, { awaitCancellation() })
        val job = launch { cancel.resolve("Tame Impala") {} }
        started.await()
        job.cancelAndJoin()
        assertNull(cancel.cached("Tame Impala"))
    }
    @Test fun wikipediaReturnsDecodablePortraitsForScreenshotArtists() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AURALIS_VERIFY_ARTIST_PHOTOS") == "1")
        val client = com.auralis.music.data.network.InnerTubeClient()
        for (name in listOf("Tame Impala", "Sidney Gish", "TV Girl", "PAWAN SINGH", "Ravyn Lenae", "The Police")) {
            val photo = assertNotNull(client.fetchWikipediaArtistPortrait(artistPortraitQuery(name)), name)
            val req = okhttp3.Request.Builder().url(photo).header("User-Agent", "AuralisMusicApp/1.0 (contact@auralis.app)").build()
            com.auralis.music.data.network.NetworkClientProvider.okHttpClient.newCall(req).execute().use { response ->
                assertTrue(response.isSuccessful, "$name image HTTP ${response.code}")
                val image = assertNotNull(javax.imageio.ImageIO.read(response.body!!.byteStream()), name)
                assertTrue(image.width >= 250 && image.height >= 250, "$name must have a usable poster image")
                println("$name: decoded ${image.width}x${image.height}")
            }
        }
    }
}
