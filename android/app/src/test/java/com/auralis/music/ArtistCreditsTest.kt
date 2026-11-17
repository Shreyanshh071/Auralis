package com.auralis.music

import com.auralis.music.data.network.ArtistRuns
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackArtist
import com.auralis.music.domain.model.TrackArtists
import com.auralis.music.domain.stats.ArtistCredits
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistCreditsTest {
    @Test
    fun `splits multi-artist credit lines into each artist`() {
        assertEquals(listOf("Pritam", "Mohit Chauhan", "Irshad Kamil"), ArtistCredits.split("Pritam, Mohit Chauhan, Irshad Kamil"))
        assertEquals(listOf("Hasan Raheem", "Abdullah Kasumbi"), ArtistCredits.split("Hasan Raheem & Abdullah Kasumbi"))
        assertEquals(listOf("Kanye West", "Dwele"), ArtistCredits.split("Kanye West feat. Dwele"))
        assertEquals(listOf("Bayaan", "Sherazam"), ArtistCredits.split("Bayaan x Sherazam"))
        assertEquals(listOf("Drake", "Rihanna"), ArtistCredits.split("Drake (ft. Rihanna)"))
    }

    @Test
    fun `keeps single names whole and strips channel suffixes`() {
        assertEquals(listOf("Florence + The Machine"), ArtistCredits.split("Florence + The Machine"))
        assertEquals(listOf("The Local Train"), ArtistCredits.split("The Local Train - Topic"))
        assertEquals(listOf("Pritam"), ArtistCredits.split("Pritam, pritam"))
    }

    @Test
    fun `artists whose own name contains a separator stay whole`() {
        assertEquals(listOf("Tyler, The Creator"), ArtistCredits.split("Tyler, The Creator"))
        assertEquals(listOf("Tyler, The Creator", "A\$AP Rocky"), ArtistCredits.split("Tyler, The Creator & A\$AP Rocky"))
        assertEquals(listOf("Kanye West", "Tyler, the Creator"), ArtistCredits.split("Kanye West feat. Tyler, the Creator"))
        assertEquals(listOf("Earth, Wind and Fire"), ArtistCredits.split("Earth, Wind and Fire"))
        assertEquals(listOf("Simon & Garfunkel"), ArtistCredits.split("Simon & Garfunkel"))
        assertEquals(listOf("Lil Nas X", "Jack Harlow"), ArtistCredits.split("Lil Nas X feat. Jack Harlow"))
        assertEquals(listOf("Vishal & Shekhar", "Shilpa Rao"), ArtistCredits.split("Vishal & Shekhar, Shilpa Rao"))
        assertEquals(listOf("Samantha", "Dave"), ArtistCredits.split("Samantha & Dave"))
    }

    @Test
    fun `same artist from different spellings shares a key`() {
        assertEquals(ArtistCredits.key("Shilpa Rao"), ArtistCredits.key("Shilpa Rao - Topic"))
    }

    // ---- YouTube's per-artist runs ----

    private fun run(text: String, browseId: String? = null, pageType: String? = null) = JSONObject().put("text", text).apply {
        if (browseId != null) put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", browseId).apply {
            if (pageType != null) put("browseEndpointContextSupportedConfigs", JSONObject().put("browseEndpointContextMusicConfig", JSONObject().put("pageType", pageType)))
        }))
    }

    @Test
    fun `reads each linked artist from the subtitle runs`() {
        val runs = JSONArray()
            .put(run("Song"))
            .put(run(" • "))
            .put(run("Pritam", "UC1", "MUSIC_PAGE_TYPE_ARTIST"))
            .put(run(", "))
            .put(run("Mohit Chauhan", "UC2", "MUSIC_PAGE_TYPE_ARTIST"))
            .put(run(" & "))
            .put(run("Irshad Kamil", "UC3", "MUSIC_PAGE_TYPE_ARTIST"))
            .put(run(" • "))
            .put(run("Rockstar", "MPREx", "MUSIC_PAGE_TYPE_ALBUM"))
            .put(run(" • "))
            .put(run("4:12"))
        assertEquals(
            listOf(TrackArtist("Pritam", "UC1"), TrackArtist("Mohit Chauhan", "UC2"), TrackArtist("Irshad Kamil", "UC3")),
            ArtistRuns.fromRuns(runs)
        )
    }

    @Test
    fun `a linked name with a separator is one artist and is learned`() {
        val name = "Zorbo, The Unsplittable"
        val runs = JSONArray().put(run(name, "UC9", "MUSIC_PAGE_TYPE_ARTIST")).put(run(" • ")).put(run("3:00"))
        assertEquals(listOf(TrackArtist(name, "UC9")), ArtistRuns.fromRuns(runs))
        assertTrue(name in ArtistCredits.learnedNames())
        // Learned names stay whole in plain credit lines too.
        assertEquals(listOf(name, "Someone Else"), ArtistCredits.split("$name & Someone Else"))
    }

    @Test
    fun `unlinked artists inside the artist section are still split`() {
        val runs = JSONArray()
            .put(run("Arijit Singh", "UC1", "MUSIC_PAGE_TYPE_ARTIST"))
            .put(run(", Shreya Ghoshal & Sonu Nigam"))
            .put(run(" • "))
            .put(run("2:58"))
        assertEquals(listOf("Arijit Singh", "Shreya Ghoshal", "Sonu Nigam"), ArtistRuns.fromRuns(runs).map { it.name })
    }

    // ---- Label uploads ----

    @Test
    fun `recognises label channels`() {
        assertTrue(ArtistCredits.isLabel("T-Series"))
        assertTrue(ArtistCredits.isLabel("Zee Music Company"))
        assertTrue(ArtistCredits.isLabel("Mango Records"))
        assertFalse(ArtistCredits.isLabel("Arijit Singh"))
        assertTrue(ArtistCredits.isUploaderCredit(Track(id = "abcdefghijk", title = "Kesariya", artist = "T-Series")))
        assertFalse(ArtistCredits.isUploaderCredit(Track(id = "abcdefghijk", title = "Kesariya", artist = "Arijit Singh")))
    }

    @Test
    fun `finds singers in label-style titles`() {
        assertEquals(listOf("Arijit Singh", "Pritam"), ArtistCredits.fromTitle("Kesariya | Brahmastra | Arijit Singh | Pritam | T-Series"))
        assertEquals(listOf("Arijit Singh"), ArtistCredits.fromTitle("Kesariya | Arijit Singh | T-Series"))
        assertEquals(listOf("Arijit Singh"), ArtistCredits.fromTitle("Arijit Singh - Kesariya (Official Video)"))
        assertEquals(emptyList<String>(), ArtistCredits.fromTitle("Kesariya (Official Video)"))
        assertEquals(emptyList<String>(), ArtistCredits.fromTitle("Kesariya | Official Video | T-Series"))
    }

    @Test
    fun `a label upload takes the singers from its raw title`() {
        val runs = JSONArray().put(run("T-Series", "UCq-Fj5jknLsUf-MWSy4_brA", "MUSIC_PAGE_TYPE_ARTIST")).put(run(" • ")).put(run("1.2B views"))
        assertEquals(
            listOf("Arijit Singh", "Pritam"),
            ArtistRuns.forItem(runs, "Kesariya | Brahmastra | Arijit Singh | Pritam | T-Series").map { it.name }
        )
    }

    @Test
    fun `credits prefer the official song, then YouTube's list, then the title, then the split`() {
        val label = Track(id = "abcdefghijk", title = "Kesariya | Brahmastra | Arijit Singh | Pritam | T-Series", artist = "T-Series")
        assertEquals(listOf("Amitabh Bhattacharya"), ArtistCredits.forTrack(label, listOf(TrackArtist("Amitabh Bhattacharya", "UC5"))))
        assertEquals(listOf("Arijit Singh", "Pritam"), ArtistCredits.forTrack(label))

        val listed = Track(id = "abcdefghijk", title = "Song", artist = "Tyler, The Creator & Kali Uchis",
            artists = listOf(TrackArtist("Tyler, The Creator", "UC1"), TrackArtist("Kali Uchis", "UC2")))
        assertEquals(listOf("Tyler, The Creator", "Kali Uchis"), ArtistCredits.forTrack(listed))

        val plain = Track(id = "abcdefghijk", title = "Song", artist = "Pritam, Mohit Chauhan")
        assertEquals(listOf("Pritam", "Mohit Chauhan"), ArtistCredits.forTrack(plain))
    }

    @Test
    fun `artist lists survive encoding and don't change track equality`() {
        val artists = listOf(TrackArtist("Tyler, The Creator", "UC1"), TrackArtist("Kali Uchis"))
        assertEquals(artists, TrackArtists.decode(TrackArtists.encode(artists)))
        assertEquals(emptyList<TrackArtist>(), TrackArtists.decode("not json"))
        val track = Track(id = "a", title = "t", artist = "x")
        assertEquals(track, track.copy(artists = artists))
        assertEquals(track.hashCode(), track.copy(artists = artists).hashCode())
    }
}
