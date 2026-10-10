package com.auralis.music

import com.auralis.music.data.network.AlbumMetadataResolver
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.ResolvedAlbum
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.repository.SearchHistoryStore
import com.auralis.music.data.repository.SearchRepositoryImpl
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The release card next to the artist: the song's album as early as possible, or a confirmed Single. */
class SearchReleaseCardTest {
    @After fun clearLookups() = AlbumMetadataResolver.clearCache()

    private fun repository(songs: List<Track>, releaseType: String?): SearchRepositoryImpl {
        val client = mockk<InnerTubeClient>()
        coEvery { client.search(any(), any()) } returns SearchResults()
        coEvery { client.search(any(), InnerTubeClient.FILTER_SONGS) } returns SearchResults(songs = songs)
        coEvery { client.getAlbumPlays(any()) } returns (0L to 0)
        coEvery { client.getQueue(any()) } returns emptyList()
        coEvery { client.getReleaseType(any()) } returns releaseType
        val suggestions = mockk<SearchSuggestionsClient>()
        coEvery { suggestions.getSuggestions(any()) } returns emptyList()
        return SearchRepositoryImpl(client, suggestions, mockk<SearchHistoryStore>())
    }

    private fun rememberLookup(artist: String, title: String, answer: ResolvedAlbum) = synchronized(AlbumMetadataResolver.memoryCache) {
        AlbumMetadataResolver.memoryCache.put("${artist.lowercase()}::${AlbumMetadataResolver.cleanTrackTitle(title).lowercase()}::", answer)
    }

    @Test fun `the album of another upload of the same song shows with the first results`() = runBlocking {
        val single = Track("single", "Night Drive", "Some Artist", album = "Night Drive", albumId = "MPREsingle", views = "500M plays")
        val onAlbum = Track("album", "Night Drive", "Some Artist", album = "Long Way Home", albumId = "MPREalbum", views = "90M plays")
        rememberLookup("Some Artist", "Night Drive", ResolvedAlbum("Long Way Home", "MPREalbum"))
        val first = mutableListOf<SearchResults>()
        repository(listOf(single, onAlbum), releaseType = "single").search("night drive") { if (it.isComplete) first += it }
        assertEquals("MPREalbum", first.first().primaryAlbum?.id)
        assertFalse(first.first().primarySingle)
    }

    @Test fun `a confirmed single gets a Single card that is not listed as an album`() = runBlocking {
        val song = Track("song", "Middle Of The Night", "Elley Duhé", album = "Middle Of The Night", albumId = "MPREmotn", views = "1.3B plays")
        rememberLookup("Elley Duhé", "Middle Of The Night", ResolvedAlbum("Middle Of The Night - Single", isSingle = true))
        val result = repository(listOf(song), releaseType = "single").search("middle of the night")
        assertTrue(result.primarySingle)
        assertEquals("MPREmotn", result.primaryAlbum?.id)
        assertTrue(result.albums.none { it.id == "MPREmotn" })
    }

    @Test fun `no Single card unless the release page also says single`() = runBlocking {
        val song = Track("song", "Middle Of The Night", "Elley Duhé", album = "Middle Of The Night", albumId = "MPREmotn", views = "1.3B plays")
        rememberLookup("Elley Duhé", "Middle Of The Night", ResolvedAlbum("Middle Of The Night - Single", isSingle = true))
        val result = repository(listOf(song), releaseType = null).search("middle of the night")
        assertFalse(result.primarySingle)
        assertNull(result.primaryAlbum)
    }
}
