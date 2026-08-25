package com.auralis.music

import com.auralis.music.data.network.YouTubePlaylistImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePlaylistImporterTest {

    @Test
    fun extractPlaylistId_fromDirectId_returnsSameId() {
        val id = "PL1234567890abcdef"
        assertEquals(id, YouTubePlaylistImporter.extractPlaylistId(id))
    }

    @Test
    fun extractPlaylistId_fromYouTubeMusicUrl_extractsListParam() {
        val url = "https://music.youtube.com/playlist?list=PLrAlXl54T_V9j87H7A4mZf2q3w5e6r7t"
        assertEquals("PLrAlXl54T_V9j87H7A4mZf2q3w5e6r7t", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromStandardYouTubeUrl_extractsListParam() {
        val url = "https://www.youtube.com/playlist?list=OLAK5uy_k1234567890"
        assertEquals("OLAK5uy_k1234567890", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromVideoInPlaylistUrl_extractsListParam() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890abcdef"
        assertEquals("PL1234567890abcdef", YouTubePlaylistImporter.extractPlaylistId(url))
    }

    @Test
    fun extractPlaylistId_fromInvalidUrl_returnsNull() {
        val url = "https://example.com/not-a-playlist"
        assertNull(YouTubePlaylistImporter.extractPlaylistId(url))
    }
}
