package com.auralis.music

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.repository.SearchHistoryStore
import com.auralis.music.data.repository.SearchRepositoryImpl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.assertTrue
import com.auralis.music.domain.model.SearchTopResult
import java.io.File

class SearchCompletenessDeviceTest {
    @Test fun inspectCompleteColdSearches() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "search-diagnostic").apply { mkdirs() }
        val client = NetworkClientProvider.okHttpClient.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.url.host == "music.youtube.com" && request.url.encodedPath.endsWith("/search") && request.body != null) {
                val buffer = okio.Buffer()
                request.body?.writeTo(buffer)
                val payload = JSONObject(buffer.readUtf8())
                val query = payload.optString("query").replace(Regex("[^a-zA-Z0-9]+"), "-")
                val type = when (payload.optString("params")) {
                    InnerTubeClient.FILTER_SONGS -> "songs"
                    InnerTubeClient.FILTER_ALBUMS -> "albums"
                    InnerTubeClient.FILTER_ARTISTS -> "artists"
                    else -> "general"
                }
                File(directory, "$query-$type.json").writeText(response.peekBody(4_000_000).string())
            }
            response
        }.build()
        val history = object : SearchHistoryStore {
            override fun recentQueries() = flowOf(emptyList<String>())
            override suspend fun add(query: String, timestampMs: Long) {}
            override suspend fun remove(query: String) {}
            override suspend fun clear() {}
        }
        for (query in listOf("we are the people", "graduation", "currents", "middle of the night", "misery", "faded", "the less i know the better")) {
            val repo = SearchRepositoryImpl(InnerTubeClient(client), SearchSuggestionsClient(client), history)
            val start = System.nanoTime()
            var first = 0L
            var firstSongs = 0L
            val result = withTimeout(40_000L) {
                repo.search(query) {
                    if (first == 0L) first = (System.nanoTime() - start) / 1_000_000
                    if (firstSongs == 0L && it.songs.isNotEmpty()) firstSongs = (System.nanoTime() - start) / 1_000_000
                }
            }
            Log.i("AuralisSearchComplete", "$query: first=${first}ms firstSongs=${firstSongs}ms total=${(System.nanoTime()-start)/1_000_000}ms songs=${result.songs.size} albums=${result.albums.size} top=${result.topResult} second=${result.runnerUp}")
            assertTrue("$query lost the song list", result.songs.size >= 5)
            assertTrue("$query waited ${first}ms for its first results", first in 1L..8_000L)
            assertTrue("$query waited ${firstSongs}ms for songs", firstSongs in 1L..8_000L)
            if (query == "we are the people") {
                assertTrue("Studio song was replaced by the music video", result.songs.any {
                    it.artist.contains("Empire Of The Sun", true) && !it.albumId.isNullOrBlank()
                })
            }
            if (query == "graduation") {
                val featured = listOfNotNull(result.topResult, result.runnerUp)
                assertTrue("Kanye's album must be featured", featured.any {
                    it is SearchTopResult.AlbumResult && it.album.author == "Kanye West"
                })
                assertTrue("The matching benny blanco song must also be featured", featured.any {
                    it is SearchTopResult.SongResult && it.track.artist.contains("benny", true)
                })
            }
        }
    }
}
