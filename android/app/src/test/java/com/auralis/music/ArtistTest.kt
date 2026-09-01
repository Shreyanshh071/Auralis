package com.auralis.music

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.domain.model.Artist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class ArtistTest {
    @Test
    fun testKanyeWestPortraitResolution() = runBlocking {
        val client = InnerTubeClient()
        val artist = Artist(id = "Kanye West", name = "Kanye West")
        val page = client.getArtistPage(artist)
        assertNotNull(page)
        assertNotNull(page?.bannerUrl)
        assertFalse(page?.bannerUrl.isNullOrBlank())
        assertFalse(page?.bannerUrl?.contains("IFlc3sf6sHV3TAZ_5vhyHQiKb9D4AdSlDkiTSgsRiicnzLASXwVr1n22EEg6Vtd2XBlyJslm8xlYiA") == true)
    }

    @Test
    fun testWikipediaArtistPortrait() = runBlocking {
        val client = InnerTubeClient()
        val portrait = client.fetchWikipediaArtistPortrait("Kanye West")
        assertNotNull(portrait)
        assertFalse(portrait.isNullOrBlank())
    }
}
