package com.auralis.music

import com.auralis.music.data.repository.isTitleTrackRelease
import com.auralis.music.data.repository.selectAlsoMatchingResult
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRunnerUpSelectorTest {
    @Test
    fun `another artist's exact song is also matching rather than its single release`() {
        val eminem = Track(id = "eminem", title = "Without Me", artist = "Eminem", views = "3.3B plays")
        val halsey = Track(id = "halsey", title = "Without Me", artist = "Halsey", views = "2.8B plays")
        val release = PlaylistResult(id = "single", title = "Without Me", author = "Halsey")

        assertTrue(isTitleTrackRelease(release, listOf(eminem, halsey), 2_800_000_000L, 1))
        assertEquals(
            SearchTopResult.SongResult(halsey),
            selectAlsoMatchingResult("without me", SearchTopResult.SongResult(eminem),
                listOf(eminem, halsey), release, 2_800_000_000L, true)
        )
    }

    @Test
    fun `title track release is not a duplicate album result`() {
        val song = Track(id = "m83", title = "Midnight City", artist = "M83", views = "787M plays")
        val release = PlaylistResult(id = "remix-ep", title = "Midnight City", author = "M83")

        assertTrue(isTitleTrackRelease(release, listOf(song), 790_000_000L, 5))
        assertNull(selectAlsoMatchingResult("midnight city", SearchTopResult.SongResult(song),
            listOf(song), release, 790_000_000L, true))
    }

    @Test
    fun `genuine album can remain also matching`() {
        val song = Track(id = "other", title = "After Hours", artist = "Other", views = "700M plays")
        val titleTrack = Track(id = "title", title = "After Hours", artist = "The Weeknd", views = "644M plays")
        val album = PlaylistResult(id = "album", title = "After Hours", author = "The Weeknd")

        assertFalse(isTitleTrackRelease(album, listOf(titleTrack), 8_900_000_000L, 14))
        assertEquals(
            SearchTopResult.AlbumResult(album),
            selectAlsoMatchingResult("after hours", SearchTopResult.SongResult(song),
                listOf(song), album, 8_900_000_000L, false)
        )
    }
}
