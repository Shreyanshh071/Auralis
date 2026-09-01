package com.auralis.music

import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.util.ArtworkProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkProcessorTest {

    @Test
    fun testYouTubeThumbnailUpgradesTo16By9WithoutBlackBars() {
        // YouTube video URL: should upgrade to hq720.jpg (16:9 without 4:3 letterbox black bars)
        val input = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        val upgraded = getHighResArtworkUrl(input)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", upgraded)

        // Video URL with default.jpg
        val input2 = "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg"
        val upgraded2 = getHighResArtworkUrl(input2)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", upgraded2)
    }

    @Test
    fun testGoogleCdnSquareArtworkPreserved() {
        val googleCdn = "https://lh3.googleusercontent.com/abc123xyz=w120-h120-l90-rj"
        val upgraded = getHighResArtworkUrl(googleCdn)
        assertEquals("https://lh3.googleusercontent.com/abc123xyz=w544-h544-l90-rj", upgraded)
    }

    @Test
    fun testCandidateArtworkPrioritization() {
        val ytUrl = "https://i.ytimg.com/vi/abc12345678/hqdefault.jpg"
        val candidates = ArtworkProcessor.getHighResArtworkCandidates(ytUrl)
        assertTrue(candidates.contains("https://i.ytimg.com/vi/abc12345678/maxresdefault.jpg"))
        assertTrue(candidates.contains("https://i.ytimg.com/vi/abc12345678/hq720.jpg"))
    }
}
