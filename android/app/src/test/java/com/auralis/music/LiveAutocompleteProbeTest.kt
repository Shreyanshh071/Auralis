package com.auralis.music

import com.auralis.music.data.network.ContentLocale
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.repository.SearchHistoryStore
import com.auralis.music.data.repository.SearchRepositoryImpl
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in probe; ordinary tests never depend on the network. */
class LiveAutocompleteProbeTest {
    @Test fun measureFirstSongResponse() = runBlocking {
        assumeTrue(System.getenv("AURALIS_LIVE_SEARCH") == "1")
        val previousLanguage = ContentLocale.appLanguageTag
        ContentLocale.appLanguageTag = { "en-IN" }
        try {
            val repository = SearchRepositoryImpl(InnerTubeClient(), SearchSuggestionsClient(), mockk<SearchHistoryStore>())
            val started = System.nanoTime()
            val updates = mutableListOf<String>()
            repository.searchLiveSongs("middle of the night") { songs ->
                updates += "${(System.nanoTime() - started) / 1_000_000}ms: ${songs.size} songs; first=${songs.first().title} by ${songs.first().artist}"
            }
            File(System.getenv("AURALIS_SEARCH_OUT")).writeText(updates.joinToString("\n"))
            assertTrue("The real query must produce song recommendations", updates.isNotEmpty())
        } finally { ContentLocale.appLanguageTag = previousLanguage }
    }
}
