package com.auralis.music.windows.dev

import com.auralis.music.data.network.*
import com.auralis.music.data.repository.*
import com.auralis.music.desktop.playback.DesktopStreamResolver
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/** Checks public search and actual audio bytes without loading credentials or playing sound. */
fun main() = runBlocking {
    check(!YouTubeSession.isSignedIn)
    val history = object : SearchHistoryStore {
        override fun recentQueries() = flowOf(emptyList<String>())
        override suspend fun add(query: String, timestampMs: Long) {}
        override suspend fun remove(query: String) {}
        override suspend fun clear() {}
    }
    val repository = SearchRepositoryImpl(InnerTubeClient(), SearchSuggestionsClient(), history)
    YouTubeBackoff.trip()
    for (query in listOf("The Less I Know The Better", "Radiohead No Surprises")) {
        val result = UserSearchRequest.run { repository.search(query) }
        println("GUEST SEARCH '$query': songs=${result.songs.size}, failed=${result.requestFailed}")
        check(result.songs.isNotEmpty())
        val song = result.songs.first()
        val url = checkNotNull(DesktopStreamResolver.resolve(song.id)) { "Guest audio resolution failed" }
        val request = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-65535").build()
        NetworkClientProvider.okHttpClient.newCall(request).execute().use {
            val bytes = it.body?.byteStream()?.use { stream -> stream.readNBytes(65536).size } ?: 0
            println("GUEST AUDIO '${song.title}': HTTP ${it.code}, bytes=$bytes")
            check(it.isSuccessful && bytes > 0)
        }
    }
    check(!YouTubeSession.isSignedIn)
}
