package com.auralis.music

import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.TrackDeduplicator
import com.auralis.music.data.network.InnerTubeClient
import org.junit.Assert.*
import org.junit.Test

class QueueSongDiversityAndDeduplicationTest {

    private fun sampleTrack(id: String, title: String, artist: String = "Radiohead"): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            thumbnail = "https://example.com/thumb/$id.jpg",
            duration = 240L
        )
    }

    @Test
    fun `extractBaseSongTitle strips version suffixes, remasters, live, acoustic, and bracket tags`() {
        val baseCreep = TrackDeduplicator.extractBaseSongTitle("Creep")
        val creepRemastered = TrackDeduplicator.extractBaseSongTitle("Creep - Remastered")
        val creep2011Remaster = TrackDeduplicator.extractBaseSongTitle("Creep - 2011 Remaster")
        val creepAcoustic = TrackDeduplicator.extractBaseSongTitle("Creep (Acoustic)")
        val creepLive = TrackDeduplicator.extractBaseSongTitle("Creep - Live at the Astoria")
        val creepRadioEdit = TrackDeduplicator.extractBaseSongTitle("Creep - Radio Edit")

        assertEquals("creep", baseCreep)
        assertEquals("creep", creepRemastered)
        assertEquals("creep", creep2011Remaster)
        assertEquals("creep", creepAcoustic)
        assertEquals("creep", creepLive)
        assertEquals("creep", creepRadioEdit)

        val baseWalls = TrackDeduplicator.extractBaseSongTitle("Climbing Up the Walls")
        val wallsRemastered = TrackDeduplicator.extractBaseSongTitle("Climbing Up the Walls - Remastered")
        val wallsLive = TrackDeduplicator.extractBaseSongTitle("Climbing Up the Walls (Live at Glastonbury)")
        val wallsZero7Mix = TrackDeduplicator.extractBaseSongTitle("Climbing Up the Walls (Zero 7 Mix)")

        assertEquals("climbingupthewalls", baseWalls)
        assertEquals("climbingupthewalls", wallsRemastered)
        assertEquals("climbingupthewalls", wallsLive)
        assertEquals("climbingupthewalls", wallsZero7Mix)

        val baseOrange = TrackDeduplicator.extractBaseSongTitle("Something in the Orange")
        val orangeZeVersion = TrackDeduplicator.extractBaseSongTitle("Something in the Orange (Z&E's Version)")
        val orangeAcoustic = TrackDeduplicator.extractBaseSongTitle("Something in the Orange - Acoustic")

        assertEquals("somethingintheorange", baseOrange)
        assertEquals("somethingintheorange", orangeZeVersion)
        assertEquals("somethingintheorange", orangeAcoustic)
    }

    @Test
    fun `isDuplicateSong identifies alternate versions of the same song as duplicates when requested`() {
        val original = sampleTrack("id1", "Creep", "Radiohead")
        val live = sampleTrack("id2", "Creep - Live", "Radiohead")
        val acoustic = sampleTrack("id3", "Creep (Acoustic)", "Radiohead")
        val remaster = sampleTrack("id4", "Creep - 2011 Remaster", "Radiohead")
        val karmaPolice = sampleTrack("id5", "Karma Police", "Radiohead")

        // In queue diversity mode (matchAlternateVersions = true), alternate cuts are duplicates
        assertTrue(TrackDeduplicator.isDuplicateTrack(original, live, matchAlternateVersions = true))
        assertTrue(TrackDeduplicator.isDuplicateTrack(original, acoustic, matchAlternateVersions = true))
        assertTrue(TrackDeduplicator.isDuplicateTrack(original, remaster, matchAlternateVersions = true))
        assertTrue(TrackDeduplicator.isDuplicateTrack(live, acoustic, matchAlternateVersions = true))

        // In standard browsing mode (matchAlternateVersions = false), live/acoustic are distinct tracks
        assertFalse(TrackDeduplicator.isDuplicateTrack(original, live, matchAlternateVersions = false))
        assertFalse(TrackDeduplicator.isDuplicateTrack(original, acoustic, matchAlternateVersions = false))

        assertFalse(TrackDeduplicator.isDuplicateTrack(original, karmaPolice))
    }

    @Test
    fun `deduplicateTracks allows only 1 version of a song to survive in curated radio queue`() {
        val tracks = listOf(
            sampleTrack("id1", "Creep", "Radiohead"),
            sampleTrack("id2", "Creep (Acoustic)", "Radiohead"),
            sampleTrack("id3", "Creep - Live", "Radiohead"),
            sampleTrack("id4", "Karma Police", "Radiohead"),
            sampleTrack("id5", "Karma Police - Remastered", "Radiohead"),
            sampleTrack("id6", "No Surprises", "Radiohead")
        )

        val deduplicated = TrackDeduplicator.deduplicateTracks(tracks, matchAlternateVersions = true)
        assertEquals(3, deduplicated.size)
        val titles = deduplicated.map { TrackDeduplicator.extractBaseSongTitle(it.title) }
        assertTrue(titles.contains("creep"))
        assertTrue(titles.contains("karmapolice"))
        assertTrue(titles.contains("nosurprises"))
    }

    @Test
    fun `AudioQueueManager appendTracks rejects alternate versions of already queued songs`() {
        val manager = AudioQueueManager()
        val initial = listOf(
            sampleTrack("seed_1", "Creep", "Radiohead"),
            sampleTrack("seed_2", "Karma Police", "Radiohead")
        )
        manager.setQueue(initial, startIndex = 0)

        val candidates = listOf(
            sampleTrack("diff_id_1", "Creep - Live", "Radiohead"),
            sampleTrack("diff_id_2", "Creep (Acoustic)", "Radiohead"),
            sampleTrack("diff_id_3", "Karma Police - 2011 Remaster", "Radiohead"),
            sampleTrack("unique_1", "No Surprises", "Radiohead"),
            sampleTrack("unique_2", "High and Dry", "Radiohead")
        )

        val state = manager.appendTracks(candidates)

        // Only No Surprises and High and Dry should be added
        assertEquals(4, state.queue.size)
        assertEquals("Creep", state.queue[0].title)
        assertEquals("Karma Police", state.queue[1].title)
        assertEquals("No Surprises", state.queue[2].title)
        assertEquals("High and Dry", state.queue[3].title)
    }

    @Test
    fun `curateDiverseGenreQueue guarantees 100 percent song diversity with no duplicate cuts`() {
        val candidates = listOf(
            sampleTrack("c1", "Wonderwall", "Oasis"),
            sampleTrack("c2", "Wonderwall - Live", "Oasis"),
            sampleTrack("c3", "Wonderwall (Remastered)", "Oasis"),
            sampleTrack("c4", "Don't Look Back in Anger", "Oasis"),
            sampleTrack("c5", "Yellow", "Coldplay"),
            sampleTrack("c6", "Yellow (Live)", "Coldplay"),
            sampleTrack("c7", "Fix You", "Coldplay"),
            sampleTrack("c8", "The Scientist", "Coldplay"),
            sampleTrack("c9", "Creep - Acoustic", "Radiohead") // Seed version
        )

        val client = InnerTubeClient()
        val queue: List<Track> = client.curateDiverseGenreQueue(
            candidates = candidates,
            seedVideoId = "seed_creep",
            seedArtist = "Radiohead",
            seedTitle = "Creep"
        )

        val baseTitles: List<String> = queue.map { t -> TrackDeduplicator.extractBaseSongTitle(t.title) }

        // Seed song "Creep" should not appear in queue
        assertFalse(baseTitles.contains("creep"))

        // All base titles in curated queue must be distinct
        val distinctCount = baseTitles.distinct().size
        assertEquals(distinctCount, baseTitles.size)
    }

    @Test
    fun `curateDiverseGenreQueue eliminates lexical word repetition and rejects search hit patterns`() {
        val candidates = listOf(
            sampleTrack("m1", "Middle Of The Night", "Martin Jensen"),
            sampleTrack("m2", "Middle of the Ocean", "Drake"),
            sampleTrack("m3", "Middle of My Storm", "Bryann T"),
            sampleTrack("m4", "MIDDLE OF THE NIGHT (HARDSTYLE)", "SICK LEGEND"),
            sampleTrack("m5", "Middle Of The Night", "Jacquees"),
            sampleTrack("m6", "Middle of the Road", "Pretenders"),
            sampleTrack("d1", "Daylight", "David Kushner"),
            sampleTrack("d2", "Summertime Sadness", "Lana Del Rey"),
            sampleTrack("d3", "Take Me To Church", "Hozier"),
            sampleTrack("d4", "Him & I", "G-Eazy")
        )

        val client = InnerTubeClient()
        val queue = client.curateDiverseGenreQueue(
            candidates = candidates,
            seedVideoId = "seed_motn",
            seedArtist = "Elley Duhé",
            seedTitle = "MIDDLE OF THE NIGHT"
        )

        // None of the tracks starting with "Middle" should be chosen
        val titles = queue.map { it.title.lowercase() }
        for (title in titles) {
            assertFalse("Song title '$title' should not start with 'middle'", title.startsWith("middle"))
        }

        // Must include diverse songs like Daylight, Summertime Sadness, Take Me To Church, Him & I
        val artists = queue.map { it.artist }
        assertTrue(artists.contains("David Kushner"))
        assertTrue(artists.contains("Lana Del Rey"))
        assertTrue(artists.contains("Hozier"))
        assertTrue(artists.contains("G-Eazy"))
    }
}
