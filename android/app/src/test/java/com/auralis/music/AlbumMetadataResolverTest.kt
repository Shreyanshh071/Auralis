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

        // Mashups & Compound Collaborations (should return TRUE)
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle("The Less I Know The Better x Houdini", "The Less I Know The Better"))
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle("The Less I Know The Better vs Houdini", "The Less I Know The Better"))
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle("The Less I Know The Better (Mashup)", "The Less I Know The Better"))
        assertTrue(AlbumMetadataResolver.isRedundantOrSingle("Song A / Song B", "Song A"))

        // Authentic Studio Albums / Soundtracks (should return FALSE)
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("Currents", "The Less I Know The Better"))
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("After Hours", "Blinding Lights"))
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("After Hours (Deluxe)", "Blinding Lights"))
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("Bhediya (Original Motion Picture Soundtrack)", "Apna Bana Le (From \"Bhediya\")"))
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("Bhediya", "Apna Bana Le"))
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("Starboy", "I Feel It Coming"))
        assertFalse(AlbumMetadataResolver.isRedundantOrSingle("The Dark Side of the Moon", "Time"))
    }

    @Test
    fun testCleanAlbumTitle() {
        assertEquals("Bad Newz", AlbumMetadataResolver.cleanAlbumTitle("Bad Newz (Original Motion Picture Soundtrack)"))
        assertEquals("Lootera", AlbumMetadataResolver.cleanAlbumTitle("Lootera (Original Motion Picture Soundtrack)"))
        assertEquals("LOOTERA", AlbumMetadataResolver.cleanAlbumTitle("LOOTERA"))
        assertEquals("Ae Dil Hai Mushkil", AlbumMetadataResolver.cleanAlbumTitle("Ae Dil Hai Mushkil (Original Motion Picture Soundtrack)"))
        assertEquals("After Hours", AlbumMetadataResolver.cleanAlbumTitle("After Hours (Deluxe)"))
        assertEquals("Dawn FM", AlbumMetadataResolver.cleanAlbumTitle("Dawn FM (Special Edition)"))
    }

    @Test
    fun testDiagnoseReportedAlbumIssues() = kotlinx.coroutines.runBlocking {
        AlbumMetadataResolver.clearCache()
        val client = com.auralis.music.data.network.InnerTubeClient()

        println("=== RESOLVING TAUBA TAUBA ===")
        val taubaAlbum = AlbumMetadataResolver.resolveAlbum("Tauba Tauba", "Karan Aujla", client)
        println("Tauba Tauba resolved: $taubaAlbum")
        org.junit.Assert.assertNotNull(taubaAlbum)
        org.junit.Assert.assertTrue("Album title must contain Bad Newz", taubaAlbum!!.albumTitle.contains("Bad Newz", ignoreCase = true))
        org.junit.Assert.assertEquals("MPREb_hFPSSXaGuEv", taubaAlbum.albumId)
        org.junit.Assert.assertFalse("Must not match Sunny Sanskari", taubaAlbum.albumTitle.contains("Sunny Sanskari", ignoreCase = true))
        org.junit.Assert.assertFalse("Must not match Ae Dil Hai Mushkil", taubaAlbum.albumTitle.contains("Ae Dil Hai Mushkil", ignoreCase = true))

        println("\n=== RESOLVING LOOTERA ===")
        val looteraAlbum = AlbumMetadataResolver.resolveAlbum("Sawaar Loon", "Monali Thakur", client)
        println("Sawaar Loon resolved: $looteraAlbum")
        org.junit.Assert.assertNotNull(looteraAlbum)
        org.junit.Assert.assertTrue("Album title must contain Lootera", looteraAlbum!!.albumTitle.contains("Lootera", ignoreCase = true))
        org.junit.Assert.assertEquals("MPREb_3hi2og4HaHb", looteraAlbum.albumId)
    }

    @Test
    fun testPlaylistImportForResolvedAlbums() = kotlinx.coroutines.runBlocking {
        val importer = com.auralis.music.data.network.YouTubePlaylistImporter(com.auralis.music.data.network.NetworkClientProvider.okHttpClient)

        val badNewzPlaylist = importer.importPlaylistById("MPREb_hFPSSXaGuEv")
        org.junit.Assert.assertNotNull(badNewzPlaylist)
        org.junit.Assert.assertTrue("Bad Newz must contain Tauba Tauba", badNewzPlaylist!!.tracks.any { it.title.contains("Tauba Tauba", ignoreCase = true) })

        val looteraPlaylist = importer.importPlaylistById("MPREb_3hi2og4HaHb")
        org.junit.Assert.assertNotNull(looteraPlaylist)
        org.junit.Assert.assertEquals(6, looteraPlaylist!!.tracks.size)
        org.junit.Assert.assertTrue("Lootera must contain Sawaar Loon", looteraPlaylist.tracks.any { it.title.contains("Sawaar Loon", ignoreCase = true) })
    }

    @Test
    fun testResolveCurrentsAlbumNeverMatchesMashup() = kotlinx.coroutines.runBlocking {
        AlbumMetadataResolver.clearCache()
        val client = com.auralis.music.data.network.InnerTubeClient()

        val currents = AlbumMetadataResolver.resolveAlbum(
            trackTitle = "The Less I Know The Better",
            artistName = "Tame Impala",
            knownAlbum = "Currents",
            innerTubeClient = client
        )
        org.junit.Assert.assertNotNull(currents)
        org.junit.Assert.assertTrue("Resolved album must contain Currents", currents!!.albumTitle.contains("Currents", ignoreCase = true))
        org.junit.Assert.assertFalse("Must never match Houdini mashup", currents.albumTitle.contains("Houdini", ignoreCase = true))
        org.junit.Assert.assertFalse("Must never contain x", currents.albumTitle.contains(" x ", ignoreCase = true))
        org.junit.Assert.assertEquals("Tame Impala", currents.artistName)
    }
}
