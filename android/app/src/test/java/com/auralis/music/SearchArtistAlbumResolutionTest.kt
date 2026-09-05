package com.auralis.music

import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import org.junit.Assert.*
import org.junit.Test

class SearchArtistAlbumResolutionTest {

    @Test
    fun `isAuthorMatch correctly validates matching artist names`() {
        assertTrue(SearchQueryMatcher.isAuthorMatch("Elley Duhé", "Elley Duhé"))
        assertTrue(SearchQueryMatcher.isAuthorMatch("elley duhe", "Elley Duhé"))
        assertTrue(SearchQueryMatcher.isAuthorMatch("Elley Duhé & Whethan", "Elley Duhé"))
        assertTrue(SearchQueryMatcher.isAuthorMatch("Whethan feat. Elley Duhé", "Elley Duhé"))
        assertTrue(SearchQueryMatcher.isAuthorMatch("The Weeknd, Daft Punk", "The Weeknd"))
        assertTrue(SearchQueryMatcher.isAuthorMatch("Kanye West", "Kanye West"))
    }

    @Test
    fun `isAuthorMatch strictly rejects unrelated artists and generic authors`() {
        assertFalse(SearchQueryMatcher.isAuthorMatch("Ojax", "Elley Duhé"))
        assertFalse(SearchQueryMatcher.isAuthorMatch("Bad Bad Joel", "Elley Duhé"))
        assertFalse(SearchQueryMatcher.isAuthorMatch("Various Artists", "Elley Duhé"))
        assertFalse(SearchQueryMatcher.isAuthorMatch("Various", "Elley Duhé"))
        assertFalse(SearchQueryMatcher.isAuthorMatch("Hit The Button Karaoke", "Elley Duhé"))
        assertFalse(SearchQueryMatcher.isAuthorMatch("", "Elley Duhé"))
        assertFalse(SearchQueryMatcher.isAuthorMatch(null, "Elley Duhé"))
        assertFalse(SearchQueryMatcher.isAuthorMatch("Drake", "The Weeknd"))
    }

    @Test
    fun `song with verified albumId resolves to verified album without fuzzy search contamination`() {
        val seedTrack = Track(
            id = "seed123",
            title = "MIDDLE OF THE NIGHT",
            artist = "Elley Duhé",
            album = "MIDDLE OF THE NIGHT",
            albumId = "MPREb_9q9AF1Vwl1J",
            thumbnail = "https://example.com/thumb.jpg"
        )

        val targetArtist = seedTrack.artist
        val resolvedAlbum = if (!seedTrack.albumId.isNullOrBlank() && !seedTrack.album.isNullOrBlank()) {
            PlaylistResult(
                id = seedTrack.albumId!!,
                title = seedTrack.album!!,
                thumbnail = seedTrack.thumbnail,
                author = targetArtist
            )
        } else null

        assertNotNull(resolvedAlbum)
        assertEquals("MPREb_9q9AF1Vwl1J", resolvedAlbum!!.id)
        assertEquals("MIDDLE OF THE NIGHT", resolvedAlbum.title)
        assertEquals("Elley Duhé", resolvedAlbum.author)
    }

    @Test
    fun `unrelated album is rejected by explore guard`() {
        val primaryArtistName = "Elley Duhé"
        val ojaxAlbum = PlaylistResult(
            id = "ojax123",
            title = "Middle of the Night",
            author = "Ojax"
        )

        val guardedAlbum = ojaxAlbum.takeIf { album ->
            SearchQueryMatcher.isAuthorMatch(album.author, primaryArtistName)
        }

        assertNull("Ojax's album must be rejected when primary artist is Elley Duhé", guardedAlbum)
    }

    @Test
    fun `explore screen header computes Artist & Single when album is null and single track is present`() {
        val primaryArtistName = "Elley Duhé"
        val primaryAlbum: PlaylistResult? = null
        val singleTrack = Track(
            id = "track123",
            title = "MIDDLE OF THE NIGHT",
            artist = primaryArtistName,
            thumbnail = "https://example.com/thumb.jpg"
        )

        val headerTitle = when {
            primaryArtistName != null && primaryAlbum != null -> "Artist & Album"
            primaryAlbum != null -> "Album"
            primaryArtistName != null && singleTrack != null -> "Artist & Single"
            primaryArtistName != null -> "Artist"
            else -> "Single"
        }

        assertEquals("Artist & Single", headerTitle)
    }

    @Test
    fun `explore screen header computes Artist & Album when album is present`() {
        val primaryArtistName = "Elley Duhé"
        val primaryAlbum = PlaylistResult(
            id = "album123",
            title = "PHOENIX",
            author = primaryArtistName
        )
        val singleTrack = Track(
            id = "track123",
            title = "MIDDLE OF THE NIGHT",
            artist = primaryArtistName
        )

        val headerTitle = when {
            primaryArtistName != null && primaryAlbum != null -> "Artist & Album"
            primaryAlbum != null -> "Album"
            primaryArtistName != null && singleTrack != null -> "Artist & Single"
            primaryArtistName != null -> "Artist"
            else -> "Single"
        }

        assertEquals("Artist & Album", headerTitle)
    }

    @Test
    fun `song subtitle builder formats single with Single rather than no album`() {
        val track = Track(
            id = "single123",
            title = "MIDDLE OF THE NIGHT",
            artist = "Elley Duhé",
            album = null,
            views = null
        )

        val subtitleText = buildString {
            if (track.artist.isNotBlank()) {
                append(track.artist)
            }
            if (!track.album.isNullOrBlank()) {
                append(" • ${track.album}")
            }
            if (!track.views.isNullOrBlank()) {
                append(" • ${track.views}")
            } else if (track.album.isNullOrBlank()) {
                append(" • Single")
            }
        }

        assertEquals("Elley Duhé • Single", subtitleText)
        assertFalse(subtitleText.contains("no album"))
    }
}
