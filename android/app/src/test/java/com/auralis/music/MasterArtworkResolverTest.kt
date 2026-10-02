package com.auralis.music

import com.auralis.music.util.MasterArtworkResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterArtworkResolverTest {

    @Test
    fun testResolveMasterArtworkForBitterSweetSymphony() = runBlocking {
        val art = MasterArtworkResolver.resolveMasterArtworkUrl("Bitter Sweet Symphony", "The Verve", null)
        assertNotNull("Artwork should be resolved for Bitter Sweet Symphony", art)
        assertTrue("Artwork should be high-res Apple Music or official studio art: $art",
            art!!.contains("mzstatic.com") || art.contains("googleusercontent.com"))
        assertTrue("Artwork should be 1400x1400 or 1200x1200: $art",
            art.contains("1400x1400") || art.contains("=w1200-h1200"))
    }

    @Test
    fun testResolveMasterArtworkForBitterSweetSymphonyTopicChannel() = runBlocking {
        val art = MasterArtworkResolver.resolveMasterArtworkUrl("Bitter Sweet Symphony", "The Verve - Topic", null)
        assertNotNull("Artwork should be resolved for Bitter Sweet Symphony with Topic channel", art)
        assertTrue("Artwork should be high-res Apple Music or official studio art: $art",
            art!!.contains("mzstatic.com") || art.contains("googleusercontent.com"))
    }

    @Test
    fun testResolveMasterArtworkForWeAreThePeople() = runBlocking {
        val art = MasterArtworkResolver.resolveMasterArtworkUrl("We Are The People", "Empire Of The Sun", null)
        assertNotNull("Artwork should be resolved for We Are The People", art)
        assertTrue("Artwork should be high-res Apple Music or official studio art: $art",
            art!!.contains("mzstatic.com") || art.contains("googleusercontent.com"))
        assertTrue("Artwork should be 1400x1400 or 1200x1200: $art",
            art.contains("1400x1400") || art.contains("=w1200-h1200"))
    }
}
