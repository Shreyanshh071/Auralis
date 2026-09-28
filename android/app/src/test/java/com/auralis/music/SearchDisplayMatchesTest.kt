package com.auralis.music

import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDisplayMatchesTest {
    @Test
    fun featuredSongsAreNotRepeatedInTheRemainingRows() {
        val strayKids = Track(id = "stray", title = "MANIAC", artist = "Stray Kids", views = "675M plays")
        val sembello = Track(id = "sembello", title = "Maniac", artist = "Michael Sembello", views = "350M plays")
        val conan = Track(id = "conan", title = "Maniac", artist = "Conan Gray", views = "334M plays")
        val results = SearchResults(
            topResult = SearchTopResult.SongResult(strayKids),
            runnerUp = SearchTopResult.SongResult(sembello),
            rankedMatches = listOf(strayKids, sembello, conan).map { SearchTopResult.SongResult(it) }
        )

        assertEquals(listOf(SearchTopResult.SongResult(conan)), results.remainingRankedMatches())
    }

    @Test
    fun shownAlbumIsRemovedByIdWithoutHidingAnotherArtistsSameNamedAlbum() {
        val halsey = PlaylistResult(id = "halsey", title = "Manic", author = "Halsey")
        val wageWar = PlaylistResult(id = "wage", title = "Manic", author = "Wage War")
        val results = SearchResults(
            primaryAlbum = halsey,
            rankedMatches = listOf(SearchTopResult.AlbumResult(halsey), SearchTopResult.AlbumResult(wageWar))
        )

        assertEquals(listOf(SearchTopResult.AlbumResult(wageWar)), results.remainingRankedMatches(halsey.id))
    }

    @Test
    fun noExtraSectionIsNeededWhenAllMatchesAreFeatured() {
        val song = Track(id = "only", title = "Only", artist = "Artist")
        val results = SearchResults(
            topResult = SearchTopResult.SongResult(song),
            songs = listOf(song)
        )

        assertTrue(results.remainingRankedMatches().isEmpty())
    }
}
