package com.auralis.music.windows.profile

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class YouTubeArtistSearchTest {
    @Test fun artistImagesComeFromMusicArtistResultsRatherThanSongArtwork() = runBlocking {
        val api = YouTubeMusicClient(cookie = "", transport = { url, body, _ ->
            assertTrue(url.contains("/search"))
            assertEquals(com.auralis.music.data.network.InnerTubeClient.FILTER_ARTISTS, body!!.getString("params"))
            """{"musicResponsiveListItemRenderer":{
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Radiohead"}]}}}],
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UCofficial"}},
                "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/music-artist.jpg","width":544,"height":544}]}}}
            }}"""
        })
        val artist = api.searchArtists("Radiohead").single()
        assertEquals("UCofficial", artist.id)
        assertEquals("https://example.com/music-artist.jpg", artist.thumbnail)
    }
}
