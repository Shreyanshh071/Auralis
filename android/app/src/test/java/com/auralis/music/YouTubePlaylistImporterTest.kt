package com.auralis.music

import com.auralis.music.data.network.YouTubePlaylistImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaylistImporterTest {

    @Test
    fun extractPlaylistId_fromYouTubeMusicUrl_extractsListParam() {
        val url = "https://music.youtube.com/playlist?list=PLrAlXl54T_V9j87H7A4mZf2q3w5e6r7t"
        assertEquals("PLrAlXl54T_V9j87H7A4mZf2q3w5e6r7t", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromYouTubeMusicWatchUrl_extractsListParam() {
        val url = "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890abcdef"
        assertEquals("PL1234567890abcdef", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromYouTubeMusicBrowseUrl_extractsBrowseParam() {
        val url = "https://music.youtube.com/browse/VLOLAK5uy_k1234567890"
        assertEquals("OLAK5uy_k1234567890", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromStandardYouTubeUrl_returnsNull() {
        val url = "https://www.youtube.com/playlist?list=OLAK5uy_k1234567890"
        assertNull("Standard YouTube playlist URL must be rejected", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromStandardYouTubeWatchUrl_returnsNull() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890abcdef"
        assertNull("Standard YouTube watch URL with playlist must be rejected", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromShortYouTubeUrl_returnsNull() {
        val url = "https://youtu.be/dQw4w9WgXcQ?list=PL1234567890abcdef"
        assertNull("Short youtu.be URL must be rejected", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromDirectIdWithoutDomain_returnsNull() {
        val id = "PL1234567890abcdef"
        assertNull("Direct playlist ID without music.youtube.com must be rejected", YouTubePlaylistImporter.extractPlaylistId(id))
    }

    @Test
    fun extractPlaylistId_fromInvalidUrl_returnsNull() {
        val url = "https://example.com/not-a-playlist"
        assertNull(YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun isYouTubeMusicUrl_identifiesCorrectHosts() {
        assertTrue(YouTubePlaylistImporter.isYouTubeMusicUrl("https://music.youtube.com/playlist?list=PL123"))
        assertTrue(YouTubePlaylistImporter.isYouTubeMusicUrl("music.youtube.com/playlist?list=PL123"))
        assertFalse(YouTubePlaylistImporter.isYouTubeMusicUrl("https://www.youtube.com/playlist?list=PL123"))
        assertFalse(YouTubePlaylistImporter.isYouTubeMusicUrl("https://youtube.com/playlist?list=PL123"))
        assertFalse(YouTubePlaylistImporter.isYouTubeMusicUrl("https://youtu.be/abc"))
    }

    @Test
    fun isStandardYouTubeUrl_identifiesStandardHosts() {
        assertTrue(YouTubePlaylistImporter.isStandardYouTubeUrl("https://www.youtube.com/playlist?list=PL123"))
        assertTrue(YouTubePlaylistImporter.isStandardYouTubeUrl("https://youtube.com/playlist?list=PL123"))
        assertTrue(YouTubePlaylistImporter.isStandardYouTubeUrl("https://m.youtube.com/playlist?list=PL123"))
        assertTrue(YouTubePlaylistImporter.isStandardYouTubeUrl("https://youtu.be/abc"))
        assertFalse(YouTubePlaylistImporter.isStandardYouTubeUrl("https://music.youtube.com/playlist?list=PL123"))
    }
}
