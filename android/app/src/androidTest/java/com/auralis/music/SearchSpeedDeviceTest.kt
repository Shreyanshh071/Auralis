package com.auralis.music

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.repository.SearchHistoryStore
import com.auralis.music.data.repository.SearchRepositoryImpl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the phone's actual network and the same repository as submitted searches. */
@RunWith(AndroidJUnit4::class)
class SearchSpeedDeviceTest {
    @Test fun differentQueriesReturnVisibleResultsWithoutWaitingForAllMetadata() = runBlocking {
        val history = object : SearchHistoryStore {
            override fun recentQueries(): Flow<List<String>> = flowOf(emptyList())
            override suspend fun add(query: String, timestampMs: Long) {}
            override suspend fun remove(query: String) {}
            override suspend fun clear() {}
        }
        val repo = SearchRepositoryImpl(InnerTubeClient(), SearchSuggestionsClient(), history)
        for (query in listOf("middle of the night", "we are the people", "misery", "currents")) {
            val started = System.nanoTime()
            val first = CompletableDeferred<Long>()
            val search = launch(Dispatchers.IO) {
                repo.search(query) { result ->
                    if (result.isNotEmpty()) first.complete((System.nanoTime() - started) / 1_000_000)
                }
            }
            try {
                val ms = withTimeout(12_000L) { first.await() }
                Log.i("AuralisSearchSpeed", "$query: first results ${ms}ms")
                assertTrue("$query took ${ms}ms", ms < 8_000L)
            } finally { search.cancelAndJoin() }
        }
    }
}
