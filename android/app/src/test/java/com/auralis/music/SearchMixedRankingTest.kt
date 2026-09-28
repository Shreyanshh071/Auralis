package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.local.dao.SearchHistoryDao
import com.auralis.music.data.repository.SearchRepositoryImpl
import com.auralis.music.data.repository.rankMixedSearchResults
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchMixedRankingTest {
    @Test
    fun sameNamedAlbumsFromDifferentArtistsBothSurviveParsing() {
        val client = InnerTubeClient()
        val albums = mutableListOf<PlaylistResult>()
        listOf("MPRE-wage" to "Wage War", "MPRE-halsey" to "Halsey").forEach { (id, artist) ->
            val item = JSONObject("""{
              "navigationEndpoint":{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{
                "browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Manic"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                  {"text":"Album"},{"text":" • "},{"text":"$artist"}]}}}
              ]
            }""")
            client.parseMusicListItem(item, mutableListOf(), mutableListOf(), albums)
        }

        assertEquals(listOf("MPRE-wage", "MPRE-halsey"), albums.map { it.id })
        assertEquals(listOf("Wage War", "Halsey"), albums.map { it.author })
    }

    @Test
    fun exactTitleSongsAndAlbumsSharePopularityRanking() {
        val wageSong = Track(id = "wage-song", title = "Manic", artist = "Wage War", views = "11M plays")
        val laytoSong = Track(id = "layto-song", title = "manic", artist = "Layto", views = "1.7M plays")
        val halseyAlbum = PlaylistResult(id = "MPRE-halsey", title = "Manic", author = "Halsey")
        val wageAlbum = PlaylistResult(id = "MPRE-wage", title = "Manic", author = "Wage War")

        val ranked = rankMixedSearchResults(
            "manic", listOf(wageSong, laytoSong), listOf(wageAlbum, halseyAlbum),
            mapOf("MPRE-halsey" to 600_000_000L, "MPRE-wage" to 11_000_000L)
        )

        assertEquals(SearchTopResult.AlbumResult(halseyAlbum), ranked.first())
        assertEquals(SearchTopResult.SongResult(laytoSong), ranked.last())
        assertEquals(4, ranked.size)
    }

    @Test
    fun searchKeepsMorePopularSameNameAlbumInTopResultAndMixedList() = runBlocking {
        val client = mockk<InnerTubeClient>()
        val suggestions = mockk<SearchSuggestionsClient>()
        val repository = SearchRepositoryImpl(client, suggestions, mockk<SearchHistoryDao>())
        val wageSong = Track(id = "wage-song", title = "Manic", artist = "Wage War", views = "11M plays")
        val wageAlbum = PlaylistResult(id = "MPREwage", title = "Manic", author = "Wage War")
        val halseyAlbum = PlaylistResult(id = "MPREhalsey", title = "Manic", author = "Halsey")
        coEvery { client.search(any(), any()) } returns SearchResults()
        coEvery { client.search("manic", InnerTubeClient.FILTER_SONGS) } returns SearchResults(songs = listOf(wageSong))
        coEvery { client.search("manic", InnerTubeClient.FILTER_ALBUMS) } returns SearchResults(albums = listOf(wageAlbum, halseyAlbum))
        coEvery { client.getAlbumPlays("MPREwage") } returns (11_000_000L to 11)
        coEvery { client.getAlbumPlays("MPREhalsey") } returns (600_000_000L to 16)
        coEvery { suggestions.getSuggestions("manic") } returns emptyList()

        val results = repository.search("manic")

        assertEquals(SearchTopResult.AlbumResult(halseyAlbum), results.topResult)
        assertEquals(SearchTopResult.AlbumResult(halseyAlbum), results.rankedMatches.first())
        assertEquals(setOf("MPREwage", "MPREhalsey"), results.albums.map { it.id }.toSet())
    }
}
