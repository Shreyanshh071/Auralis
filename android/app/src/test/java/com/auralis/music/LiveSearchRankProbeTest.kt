package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Writes YouTube Music's song results for AURALIS_SEARCH_QUERY and the app's ranking of them to AURALIS_SEARCH_OUT. */
class LiveSearchRankProbeTest {
    @Test
    fun printRanking(): Unit = runBlocking {
        Assume.assumeTrue(System.getenv("AURALIS_LIVE_LYRICS") == "1")
        val q = System.getenv("AURALIS_SEARCH_QUERY") ?: return@runBlocking
        val out = System.getenv("AURALIS_SEARCH_OUT") ?: return@runBlocking
        // JVM probes have no Android system Resources; use an explicit app region.
        val previousLanguage = com.auralis.music.data.network.ContentLocale.appLanguageTag
        com.auralis.music.data.network.ContentLocale.appLanguageTag = { "en-IN" }
        try {
        val client = InnerTubeClient()
        val songs = client.search(q, InnerTubeClient.FILTER_SONGS).songs
        val general = client.search(q)
        val lines = mutableListOf<String>()
        songs.forEachIndexed { i, t -> lines += "[SONGS#$i] ${t.id} \"${t.title}\" | ${t.artist} | ${t.album} | ${t.duration}s | ${t.views}" }
        general.songs.forEachIndexed { i, t -> lines += "[GENERAL#$i] ${t.id} \"${t.title}\" | ${t.artist} | ${t.album} | ${t.duration}s | ${t.views}" }
        lines += "[YTM TOP] ${general.topResult}"
        val all = (songs + general.songs).distinctBy { it.id }
        val (matched, recs) = SearchQueryMatcher.partitionResults(all, q, 3)
        matched.forEachIndexed { i, t -> lines += "[RANKED#$i] ${t.id} \"${t.title}\" | ${t.artist} | ${t.duration}s | ${t.views} | tier=${SearchQueryMatcher.evaluateMatch(t, q)?.tier}" }
        val started = System.nanoTime()
        val repository = com.auralis.music.data.repository.SearchRepositoryImpl(
            client, com.auralis.music.data.network.SearchSuggestionsClient(),
            io.mockk.mockk<com.auralis.music.data.repository.SearchHistoryStore>()
        )
        val result = repository.search(q)
        lines += "[REPOSITORY] ${(System.nanoTime() - started) / 1_000_000}ms; ${result.songs.size} songs; top=${result.topResult}"
        org.junit.Assert.assertTrue("Live query must return matches", result.isNotEmpty())
        File(out).writeText(lines.joinToString("\n"))
        } finally {
            com.auralis.music.data.network.ContentLocale.appLanguageTag = previousLanguage
        }
    }
}
