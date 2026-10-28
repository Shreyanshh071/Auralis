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
    @Test fun discardsWikipediaCacheAndUsesExactYouTubeMusicArtist() = runBlocking {
        val cache = Memory()
        cache.values["artist-v3:radiohead"] = """{"id":"UCold","photo":"file:/wikipedia.png"}"""
        val lookup = FamiliarArtistLookup(cache, { listOf(
            Artist("UCtribute", "Radiohead tribute", "https://wrong"),
            Artist("video", "Radiohead", "https://also-wrong"),
            Artist("UCcorrect", "Radiohead", "https://music-artist")) }, { "file:/verified.png" })
        assertNull(lookup.cached("Radiohead"))
        var found: Artist? = null
        lookup.resolve("Radiohead") { found = it }
        assertEquals("UCcorrect", found?.id)
        assertEquals("file:/verified.png", found?.thumbnail)
    }
    @Test fun validatedArtistPhotoSurvivesRestartWithoutMoreRequests() = runBlocking {
        val cache = Memory()
        val lookup = FamiliarArtistLookup(cache, { listOf(Artist("UC123", it, "https://artist.jpg")) })
        lookup.resolve("TV Girl") {}
        var requests = 0
        val reopened = FamiliarArtistLookup(cache, { requests++; emptyList() })
        var saved: Artist? = null
        reopened.resolve("TV Girl") { saved = it }
        assertEquals("https://artist.jpg", saved?.thumbnail)
        assertEquals("UC123", saved?.id)
        assertEquals(0, requests)
    }
    @Test fun brokenPhotosRetryAndCancellationDoesNotCacheResults() = runBlocking {
        val cache = Memory()
        var valid = false
        val lookup = FamiliarArtistLookup(cache, { listOf(Artist("UC1", it, "https://artist.jpg")) }, { if (valid) it else null })
        lookup.resolve("Sidney Gish") {}
        assertNull(lookup.cached("Sidney Gish")?.thumbnail)
        valid = true
        lookup.resolve("Sidney Gish") {}
        assertNotNull(lookup.cached("Sidney Gish")?.thumbnail)
        val started = CompletableDeferred<Unit>()
        val cancelled = FamiliarArtistLookup(Memory(), { started.complete(Unit); awaitCancellation() })
        val job = launch { cancelled.resolve("Tame Impala") { fail("Cancelled lookup cannot publish") } }
        started.await(); job.cancelAndJoin()
    }
}