package com.auralis.music

import com.auralis.music.data.repository.selectAlsoMatching
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Also matching" is a real alternative to the top result, not whatever happens to rank second. */
class SearchAlsoMatchingTest {
    private fun song(t: Track) = SearchTopResult.SongResult(t)

    @Test fun `a low-play remix of the top song is not promoted`() {
        val original = Track("orig", "My Ordinary Life", "The Living Tombstone", views = "731M plays")
        val remix = Track("remix", "My Ordinary Life (Remix)", "Some Producer", views = "92K plays")
        assertNull(selectAlsoMatching("my ordinary life", song(original), listOf(song(original), song(remix)), emptyMap()))
    }

    @Test fun `a fancy-letter sped up upload is still a version`() {
        val original = Track("orig", "My Ordinary Life", "The Living Tombstone", views = "731M plays")
        val spedUp = Track("sped", "𝙢𝙮 𝙤𝙧𝙙𝙞𝙣𝙖𝙧𝙮 𝙡𝙞𝙛𝙚 (𝙨𝙥𝙚𝙚𝙙 𝙪𝙥)", "Someone", views = "89M plays")
        assertNull(selectAlsoMatching("my ordinary life", song(original), listOf(song(original), song(spedUp)), emptyMap()))
    }

    @Test fun `two popular different songs with the same title both get cards`() {
        val eminem = Track("eminem", "Without Me", "Eminem", views = "3.3B plays")
        val halsey = Track("halsey", "Without Me", "Halsey", views = "2.8B plays")
        assertEquals(song(halsey), selectAlsoMatching("without me", song(eminem), listOf(song(eminem), song(halsey)), emptyMap()))
    }

    @Test fun `a video upload is not a featured alternative`() {
        val top = Track("top", "Middle of the Night", "Elley Duhé", views = "1.3B plays")
        val video = Track("video", "Middle of the Night", "Loveless", views = "390M views")
        assertNull(selectAlsoMatching("middle of the night", song(top), listOf(song(top), song(video)), emptyMap()))
    }

    @Test fun `a cover far less played than a huge hit is not featured`() {
        val top = Track("top", "Believer", "Imagine Dragons", views = "7B plays")
        val cover = Track("cover", "Believer", "Bongo Cat", views = "52M plays")
        assertNull(selectAlsoMatching("believer", song(top), listOf(song(top), song(cover)), emptyMap()))
    }

    @Test fun `the same recording credited differently is not a second song`() {
        val top = Track("a", "Kya Hua Tera Vada", "Mohammed Rafi, Sushma Shrestha", views = "166M plays")
        val reupload = Track("b", "Kya Hua Tera Vada", "Mohd. Rafi & Sushma Shreshtha", views = "166M plays")
        assertNull(selectAlsoMatching("kya hua tera wada", song(top), listOf(song(top), song(reupload)), emptyMap()))
    }

    @Test fun `the top song's own single is not an alternative album`() {
        val top = Track("creep", "Creep", "Radiohead", albumId = "MPREcreep", views = "2.3B plays")
        val ownSingle = PlaylistResult("MPREcreep", "Creep", author = "Radiohead")
        assertNull(selectAlsoMatching("creep", song(top),
            listOf(song(top), SearchTopResult.AlbumResult(ownSingle)), mapOf("MPREcreep" to 2_302_000_000L)))
    }

    @Test fun `an album and another artist's song with the query's name are both featured`() {
        val album = PlaylistResult("MPREgrad", "Graduation", author = "Kanye West")
        val juice = Track("juice", "Graduation", "benny blanco & Juice WRLD", views = "143M plays")
        assertEquals(song(juice), selectAlsoMatching("graduation", SearchTopResult.AlbumResult(album),
            listOf(SearchTopResult.AlbumResult(album), song(juice)), mapOf("MPREgrad" to 2_200_000_000L)))
    }

    @Test fun `songs featured above are not listed again`() {
        val top = Track("top", "Graduation", "benny blanco & Juice WRLD", views = "143M plays")
        val sameSongOtherUpload = top.copy(id = "upload2")
        val version = Track("v", "Graduation (Sped Up)", "benny blanco & Juice WRLD", views = "2M plays")
        val other = Track("o", "Graduation", "Top Banks", views = "1.3M plays")
        val results = SearchResults(topResult = song(top), songs = listOf(top, sameSongOtherUpload, version, other, other))
        assertEquals(listOf(version, other), results.songsBelowFeatured())
    }
}
