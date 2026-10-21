package com.auralis.music.windows.home

import com.auralis.music.data.network.*
import com.auralis.music.data.repository.*
import com.auralis.music.domain.model.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class HomeAlbumResolutionTest {
    private val history = object : SearchHistoryStore {
        override fun recentQueries() = flowOf(emptyList<String>())
        override suspend fun add(query: String, timestampMs: Long) {}
        override suspend fun remove(query: String) {}
        override suspend fun clear() {}
    }
    private val album = PlaylistResult("album:abcdefghijk", "Example Album", author = "Example Artist")
    private val song = Track("abcdefghijk", "Example Song", "Example Artist", album = album.title, albumId = "MPRE-test")

    @Test fun everyAlbumCanResolveDuringABackgroundSearchCooldown() = runBlocking<Unit> {
        YouTubeBackoff.trip()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        var calls = 0
        val client = object : InnerTubeClient() {
            override suspend fun getQueue(videoIds: List<String>): List<Track> {
                http.searchBody(Request.Builder().url("https://music.youtube.com/test").build(), 1000)
                calls++
                return listOf(song)
            }
        }
        val repository = SearchRepositoryImpl(client, SearchSuggestionsClient(), history) { id ->
            Playlist(id, album.title, tracks = listOf(song))
        }
        assertEquals(listOf(song), repository.getAlbumTracks(album))
        assertEquals(1, calls)
        assertFailsWith<java.io.IOException> {
            http.searchBody(Request.Builder().url("https://music.youtube.com/background").build(), 1000)
        }
    }

    @Test fun historyCardResolvesExactAlbumWithoutTextSearch() = runBlocking<Unit> {
        var importedId = ""
        val client = object : InnerTubeClient() {
            override suspend fun getQueue(videoIds: List<String>): List<Track> {
                assertEquals(listOf(song.id), videoIds)
                return listOf(song)
            }
            override suspend fun search(query: String, params: String?): SearchResults = error("Text search should not be needed")
        }
        val tracks = listOf(song, song.copy(id = "second", title = "Not Allowed"))
        val repository = SearchRepositoryImpl(client, SearchSuggestionsClient(), history) { id ->
            importedId = id
            Playlist(id, album.title, tracks = tracks)
        }
        assertEquals(tracks, repository.getAlbumTracks(album))
        assertEquals("MPRE-test", importedId)
        importedId = ""
        assertEquals(tracks, repository.getAlbumTracks(album))
        assertEquals("", importedId, "Reopening a loaded release needs no network lookup")
    }

    @Test fun mismatchedRecordingAlbumCannotReplaceTheSelectedAlbum() = runBlocking<Unit> {
        var imports = 0
        val client = object : InnerTubeClient() {
            override suspend fun getQueue(videoIds: List<String>) = listOf(song.copy(album = "French Exit"))
            override suspend fun search(query: String, params: String?) = SearchResults()
        }
        val repository = SearchRepositoryImpl(client, SearchSuggestionsClient(), history) { imports++; null }
        assertTrue(repository.getAlbumTracks(album).isEmpty())
        assertEquals(0, imports)
    }

    @Test fun emptyReleaseAnswersAreNeverCachedAndRetryCanRecover() = runBlocking<Unit> {
        var ready = false
        val client = object : InnerTubeClient() {
            override suspend fun getQueue(videoIds: List<String>) = if (ready) listOf(song) else emptyList()
            override suspend fun search(query: String, params: String?) = SearchResults()
        }
        val repository = SearchRepositoryImpl(client, SearchSuggestionsClient(), history) { id ->
            if (ready) Playlist(id, album.title, tracks = listOf(song)) else null
        }
        assertTrue(repository.getAlbumTracks(album).isEmpty())
        ready = true
        assertEquals(listOf(song), repository.getAlbumTracks(album))
    }

    @Test fun failedFirstSearchDoesNotDiscardLaterAlbumMatches() = runBlocking<Unit> {
        var searches = 0
        val client = object : InnerTubeClient() {
            override suspend fun getQueue(videoIds: List<String>) = emptyList<Track>()
            override suspend fun search(query: String, params: String?): SearchResults {
                if (++searches == 1) throw java.io.IOException("Temporary failure")
                return SearchResults(albums = listOf(album.copy(id = "MPRE-recovered")))
            }
        }
        val repository = SearchRepositoryImpl(client, SearchSuggestionsClient(), history) { id ->
            assertEquals("MPRE-recovered", id)
            Playlist(id, album.title, tracks = listOf(song))
        }
        assertEquals(listOf(song), repository.getAlbumTracks(album))
        assertEquals(2, searches)
    }
}
