package com.auralis.music

import com.auralis.music.data.network.AlbumMetadataResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumMetadataResolverTest {

    @Test
    fun testCleanTrackTitle() {
        assertEquals("Apna Bana Le", AlbumMetadataResolver.cleanTrackTitle("Apna Bana Le (From \"Bhediya\")"))
        assertEquals("Blinding Lights", AlbumMetadataResolver.cleanTrackTitle("Blinding Lights (Official Video)"))
        assertEquals("Starboy", AlbumMetadataResolver.cleanTrackTitle("Starboy (feat. Daft Punk)"))
        assertEquals("Levitating", AlbumMetadataResolver.cleanTrackTitle("Levitating (Remix)"))
    }

    @Test
    fun testExtractSoundtrackTag() {
        assertEquals("Bhediya", AlbumMetadataResolver.extractSoundtrackTag("Apna Bana Le (From \"Bhediya\")"))
        assertEquals("Brahmastra", AlbumMetadataResolver.extractSoundtrackTag("Kesariya (From \"Brahmastra\")"))
        assertEquals("Animal", AlbumMetadataResolver.extractSoundtrackTag("Satranga [From \"Animal\"]"))
        assertEquals(null, AlbumMetadataResolver.extractSoundtrackTag("Blinding Lights"))
    }

    @Test
    fun testIsRedundantOrSingle() {
        // Redundant / Singles (should return TRUE)
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle("Blinding Lights", "Blinding Lights"))
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle("Blinding Lights - Single", "Blinding Lights"))
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle("Blinding Lights (Single)", "Blinding Lights"))
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle("Blinding Lights - EP", "Blinding Lights"))
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle("Apna Bana Le", "Apna Bana Le (From \"Bhediya\")"))
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle("Apna Bana Le - Single", "Apna Bana Le (From \"Bhediya\")"))
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle(null, "Blinding Lights"))
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle("", "Blinding Lights"))
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle("3.6B plays", "Blinding Lights"))

        // Authentic Studio Albums / Soundtracks (should return FALSE)
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("After Hours", "Blinding Lights"))
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("After Hours (Deluxe)", "Blinding Lights"))
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("Bhediya (Original Motion Picture Soundtrack)", "Apna Bana Le (From \"Bhediya\")"))
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("Bhediya", "Apna Bana Le"))
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("Starboy", "I Feel It Coming"))
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("The Dark Side of the Moon", "Time"))
    }
}
