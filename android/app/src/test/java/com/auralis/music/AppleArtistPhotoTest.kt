package com.auralis.music

import com.auralis.music.data.network.AppleArtistPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleArtistPhotoTest {
    private val photo = "https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages211/v4/5a/ami-identity_cropped.png"

    @Test
    fun `the page's wide crop becomes the whole photo`() {
        val head = """<meta property="og:image" content="$photo/1200x630cw.png">"""
        assertEquals("$photo/1200x1200bb.jpg", AppleArtistPhoto.parsePhoto(head))
    }

    @Test
    fun `a record cover is not a photo`() {
        val cover = "https://is1-ssl.mzstatic.com/image/thumb/Music221/v4/aa/cover.jpg/1200x630cw.png"
        assertFalse(AppleArtistPhoto.isArtistPhoto(cover))
        assertNull(AppleArtistPhoto.parsePhoto("""<meta property="og:image" content="$cover">"""))
    }

    @Test
    fun `apple's two photo folders both count`() {
        assertTrue(AppleArtistPhoto.isArtistPhoto(photo))
        assertTrue(AppleArtistPhoto.isArtistPhoto("https://is1-ssl.mzstatic.com/image/thumb/Features115/v4/e1/pr_source.png/1200x630cw.png"))
    }

    @Test
    fun `names compare on letters and digits`() {
        assertEquals(AppleArtistPhoto.matchKey("Vishal & Shekhar"), AppleArtistPhoto.matchKey("Vishal-Shekhar"))
        assertEquals(AppleArtistPhoto.matchKey("KR\$NA"), AppleArtistPhoto.matchKey("Krsna"))
        assertEquals(AppleArtistPhoto.matchKey("Beyoncé"), AppleArtistPhoto.matchKey("Beyonce"))
    }

    @Test
    fun `titles drop what apple adds and prove nothing when short`() {
        assertEquals("blinding", AppleArtistPhoto.titleKey("Blinding - Single"))
        assertEquals("blinding", AppleArtistPhoto.titleKey("Blinding (feat. Someone)"))
        assertNull(AppleArtistPhoto.titleKey("War"))
    }
}
