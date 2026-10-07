package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import org.junit.Assert.*
import org.junit.Test

/** Candidates are the real YouTube "Videos" results for the song (2026-10-06). */
class SpotifyFanEditMatchTest {
    private val spotify = Track(id = "sp_4q9BYSc96mLCnwTyYVCyUy", title = "CARNIVAL x FEIN - Sped Up", artist = "Kemi not a Kid", album = "CARNIVAL x FEIN", duration = 129)

    private val videos = listOf(
        Track(id = "uKv1cVC6yPM", title = "CARNIVAL x FEIN", artist = "Kemi not a Kid", duration = 210),
        Track(id = "YwvMm3A6L3A", title = "Carnival x Fein (Noah Baine Full TikTok Remix) [made by purple drip boy]", artist = "purple drip boy", duration = 210),
        Track(id = "2B-elRVUmMI", title = "Carnival x Fein - (sped up)", artist = "Awzann (sped up)", duration = 128),
        Track(id = "GJWs6qj-0aY", title = "Carnival x Fein (Slowed + Malenia “I have never known defeat”)", artist = "Your.A.Foo1", duration = 188),
        Track(id = "UYFJeXVUibU", title = "Carnival x Fein - (Speed Up)", artist = "cckl", duration = 175),
    )

    @Test
    fun `sped up edit with no upload by its artist resolves to the same-length reupload`() {
        assertNull("the strict matcher rejects every candidate", SearchQueryMatcher.findBestCandidateForTrack(spotify, videos))
        assertEquals("2B-elRVUmMI", SearchQueryMatcher.findReuploadOfEdit(spotify, videos)?.id)
    }

    @Test
    fun `credited artist upload wins over a closer length`() {
        val own = Track(id = "own", title = "CARNIVAL x FEIN (Sped Up)", artist = "Kemi not a Kid", duration = 131)
        assertEquals("own", SearchQueryMatcher.findReuploadOfEdit(spotify, videos + own)?.id)
    }

    @Test
    fun `original songs never fall back to someone else's upload`() {
        val original = Track(id = "sp_o", title = "CARNIVAL x FEIN", artist = "Kemi not a Kid", duration = 210)
        assertFalse(SearchQueryMatcher.isFanEdit(original.title))
        assertNull(SearchQueryMatcher.findReuploadOfEdit(original, videos.map { it.copy(artist = "someone") }))
    }

    @Test
    fun `wrong length or a different edit is not accepted`() {
        val slowed = spotify.copy(title = "CARNIVAL x FEIN - Slowed", duration = 129)
        // "slowed" and "sped up" are the same marker family, so length is what separates them.
        assertNull(SearchQueryMatcher.findReuploadOfEdit(spotify.copy(duration = 160), videos))
        assertNull(SearchQueryMatcher.findReuploadOfEdit(slowed.copy(title = "Other Song - Slowed"), videos))
    }
}
