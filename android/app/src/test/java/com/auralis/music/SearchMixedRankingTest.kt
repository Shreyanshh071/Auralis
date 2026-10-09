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
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMixedRankingTest {
    @Test fun singleWithUnavailableTrackCountDoesNotDuplicateItsSong() = runBlocking {
        val client = mockk<InnerTubeClient>()
        val suggestions = mockk<SearchSuggestionsClient>()
        val song = Track("song", "Same Name", "First Artist", album = "Studio", albumId = "MPREstudio", views = "100M plays")
        val other = song.copy(id = "other", artist = "Second Artist", views = "20M plays")
        val single = PlaylistResult("MPREsingle", "Same Name", author = "First Artist")
        coEvery { client.search(any(), any()) } returns SearchResults()
        coEvery { client.search("same name", InnerTubeClient.FILTER_SONGS) } returns SearchResults(songs = listOf(song, other))
        coEvery { client.search("same name", InnerTubeClient.FILTER_ALBUMS) } returns SearchResults(albums = listOf(single))
        coEvery { client.getAlbumPlays(any()) } returns (0L to 0)
        coEvery { suggestions.getSuggestions(any()) } returns emptyList()
        val result = SearchRepositoryImpl(client, suggestions, mockk<com.auralis.music.data.repository.SearchHistoryStore>()).search("same name")
        assertEquals(SearchTopResult.SongResult(song), result.topResult)
        assertEquals(SearchTopResult.SongResult(other), result.runnerUp)
    }

    @Test fun duplicateEditionsCannotTakeBothFeaturedSlots() {
        val album = PlaylistResult("explicit", "Graduation", author = "Kanye West")
        val clean = album.copy(id = "clean")
        val song = Track("song", "Graduation", "benny blanco & Juice WRLD", views = "143M plays")
        val ranked = rankMixedSearchResults("graduation", listOf(song), listOf(clean, album),
            mapOf("clean" to 1_000_000_000L, "explicit" to 3_000_000_000L))
        assertEquals(listOf(SearchTopResult.AlbumResult(album), SearchTopResult.SongResult(song)), ranked)
    }

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
    fun restoredSearchKeepsPopularAlbumFeaturedAndSongAsRunnerUp() = runBlocking {
        val client = mockk<InnerTubeClient>()
        val suggestions = mockk<SearchSuggestionsClient>()
        val repository = SearchRepositoryImpl(client, suggestions, com.auralis.music.data.repository.RoomSearchHistoryStore(mockk<SearchHistoryDao>()))
        val wageSong = Track(id = "wage-song", title = "Manic", artist = "Wage War", views = "11M plays")
        val wageAlbum = PlaylistResult(id = "MPREwage", title = "Manic", author = "Wage War")
        val halseyAlbum = PlaylistResult(id = "MPREhalsey", title = "Manic", author = "Halsey")
        coEvery { client.search(any(), any()) } returns SearchResults()
        coEvery { client.search("manic", InnerTubeClient.FILTER_SONGS) } returns SearchResults(songs = listOf(wageSong))
        coEvery { client.search("manic", InnerTubeClient.FILTER_ALBUMS) } returns SearchResults(albums = listOf(wageAlbum, halseyAlbum))
        coEvery { client.getAlbumPlays("MPREwage") } returns (20_000_000L to 11)
        coEvery { client.getAlbumPlays("MPREhalsey") } returns (600_000_000L to 16)
        coEvery { suggestions.getSuggestions("manic") } returns emptyList()

        val results = repository.search("manic")

        assertEquals(SearchTopResult.AlbumResult(halseyAlbum), results.topResult)
        assertEquals(SearchTopResult.AlbumResult(wageAlbum), results.runnerUp)
        assertEquals(halseyAlbum, results.primaryAlbum)
        assertEquals(listOf(halseyAlbum), results.albums)
        assertTrue(results.rankedMatches.isEmpty())
    }

    @Test fun twoPopularSongsCanOccupyBothFeaturedSlots() = runBlocking {
        val client = mockk<InnerTubeClient>()
        val suggestions = mockk<SearchSuggestionsClient>()
        val first = Track("first", "Same Name", "First Artist", album = "Studio", albumId = "MPREstudio", views = "100M plays")
        val second = first.copy(id = "second", artist = "Second Artist", views = "50M plays")
        coEvery { client.search(any(), any()) } returns SearchResults()
        coEvery { client.search("same name", InnerTubeClient.FILTER_SONGS) } returns SearchResults(songs = listOf(second, first))
        coEvery { suggestions.getSuggestions(any()) } returns emptyList()
        val result = SearchRepositoryImpl(client, suggestions, mockk<com.auralis.music.data.repository.SearchHistoryStore>()).search("same name")
        assertEquals(SearchTopResult.SongResult(first), result.topResult)
        assertEquals(SearchTopResult.SongResult(second), result.runnerUp)
    }

    @Test fun popularAlbumAndSongAreFeaturedTogether() = runBlocking {
        val client = mockk<InnerTubeClient>()
        val suggestions = mockk<SearchSuggestionsClient>()
        val song = Track("song", "Graduation", "benny blanco & Juice WRLD", album = "Friends Keep Secrets", albumId = "MPREfriends", views = "143M plays")
        val album = PlaylistResult("MPREgrad", "Graduation", author = "Kanye West")
        coEvery { client.search(any(), any()) } returns SearchResults()
        coEvery { client.search("graduation", InnerTubeClient.FILTER_SONGS) } returns SearchResults(songs = listOf(song))
        coEvery { client.search("graduation", InnerTubeClient.FILTER_ALBUMS) } returns SearchResults(albums = listOf(album))
        coEvery { client.getAlbumPlays("MPREgrad") } returns (3_000_000_000L to 14)
        coEvery { suggestions.getSuggestions(any()) } returns emptyList()
        val result = SearchRepositoryImpl(client, suggestions, mockk<com.auralis.music.data.repository.SearchHistoryStore>()).search("graduation")
        assertEquals(SearchTopResult.AlbumResult(album), result.topResult)
        assertEquals(SearchTopResult.SongResult(song), result.runnerUp)
        assertEquals(3_000_000_000L, result.albumPlayCounts[album.id])
    }
}
