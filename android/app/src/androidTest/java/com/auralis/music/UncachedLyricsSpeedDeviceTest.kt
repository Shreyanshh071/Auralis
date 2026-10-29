package com.auralis.music

import android.util.Log
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.*
import org.junit.Test

/** Times the player's lyric path for songs with no saved lyrics, on the phone's real network. */
class UncachedLyricsSpeedDeviceTest {
    private val songs = listOf(
        Triple("Riptide", "Vance Joy", 204L),
        Triple("Take Me to Church", "Hozier", 241L),
        Triple("Pompeii", "Bastille", 214L),
        Triple("Ophelia", "The Lumineers", 160L),
        Triple("Kesariya", "Arijit Singh", 268L),
        Triple("Heat Waves", "Glass Animals", 239L),
        Triple("Sweater Weather", "The Neighbourhood", 240L),
        Triple("Daylight", "David Kushner", 213L)
    )

    @Test fun searchedSongsShowTimedLyricsQuickly() = runBlocking {
        val history = object : com.auralis.music.data.repository.SearchHistoryStore {
            override fun recentQueries() = kotlinx.coroutines.flow.flowOf(emptyList<String>())
            override suspend fun add(query: String, timestampMs: Long) {}
            override suspend fun remove(query: String) {}
            override suspend fun clear() {}
        }
        val search = com.auralis.music.data.repository.SearchRepositoryImpl(
            com.auralis.music.data.network.InnerTubeClient(), com.auralis.music.data.network.SearchSuggestionsClient(), history)
        for ((q, a, _) in songs) {
            val t = withContext(Dispatchers.IO) { search.searchSongs("$q $a").firstOrNull() } ?: continue
            val repo = LyricsRepositoryImpl(LyricsClient())
            val start = System.nanoTime()
            fun ms() = (System.nanoTime() - start) / 1_000_000
            var firstInterim = -1L
            var firstProvider: Any? = null
            val silence = if (songs.indexOfFirst { it.first == q } % 2 == 0) 0L else 180L
            val spicy = async(Dispatchers.IO) {
                val r = withTimeoutOrNull(30_000) { repo.upgradeSpicy(t.title, t.artist, t.duration, t.id, t.album,
                    t.channelTitle, t.duration * 1000L, silence, com.auralis.music.data.network.SpicyRecordingMatcher.spotifyId(t.id)) }
                "${ms()}ms(${r?.provider})"
            }
            val final = withContext(Dispatchers.IO) {
                withTimeoutOrNull(30_000) {
                    repo.getLyricsWithInterim(title = t.title, artist = t.artist, durationSec = t.duration, videoId = t.id,
                        forceRefresh = true, album = t.album, channelTitle = t.channelTitle, durationMs = t.duration * 1000L,
                        audioLeadingSilenceMs = silence) { d: LyricsData ->
                        if (firstInterim < 0 && d.lines.isNotEmpty() && d.syncType != SyncType.PLAIN) {
                            firstInterim = ms(); firstProvider = d.provider
                        }
                    }
                }
            }
            Log.i("AuralisUncached", "REAL ${t.title} / ${t.artist} id=${t.id} album=${t.album} dur=${t.duration}: interim=${firstInterim}ms($firstProvider) final=${ms()}ms(${final?.provider},${final?.syncType}) silence=$silence spicy=${spicy.await()}")
        }
    }

    @Test fun uncachedSongsShowTimedLyricsQuickly() = runBlocking {
        for ((title, artist, sec) in songs) {
            val repo = LyricsRepositoryImpl(LyricsClient())
            val start = System.nanoTime()
            fun ms() = (System.nanoTime() - start) / 1_000_000
            var firstInterim = -1L
            var firstProvider: Any? = null
            val spicy = async(Dispatchers.IO) {
                val r = withTimeoutOrNull(30_000) { repo.upgradeSpicy(title, artist, sec, null, null, null, sec * 1000L, null, null) }
                ms() to r?.provider
            }
            val final = withContext(Dispatchers.IO) {
                withTimeoutOrNull(30_000) {
                    repo.getLyricsWithInterim(title = title, artist = artist, durationSec = sec, videoId = null,
                        forceRefresh = true, album = null, channelTitle = null, durationMs = sec * 1000L,
                        audioLeadingSilenceMs = null) { d: LyricsData ->
                        if (firstInterim < 0 && d.lines.isNotEmpty() && d.syncType != SyncType.PLAIN) {
                            firstInterim = ms(); firstProvider = d.provider
                        }
                    }
                }
            }
            val finalMs = ms()
            val (spicyMs, spicyProvider) = spicy.await()
            Log.i("AuralisUncached", "$title: interim=${firstInterim}ms($firstProvider) final=${finalMs}ms(${final?.provider},${final?.syncType}) spicy=${spicyMs}ms($spicyProvider)")
        }
    }
}
