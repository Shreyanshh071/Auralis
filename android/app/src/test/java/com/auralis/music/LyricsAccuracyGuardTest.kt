package com.auralis.music

import com.auralis.music.data.parser.LyricsMatcher
import org.junit.Assert.*
import org.junit.Test

class LyricsAccuracyGuardTest {

    @Test
    fun testMashupAndMedleyRejection() {
        // 1. Searching for single track "Raanjhanaa" MUST reject mashups/medleys
        val confMxmMedley = LyricsMatcher.calculateConfidence(
            queryTitle = "Raanjhanaa",
            queryArtist = "Shiraz Uppal",
            candidateTitle = "Koi Mil Gaya / Raanjhanaa (Sangeet Mix)",
            candidateArtist = "Jatin - Lalit feat. A. R. Rahman & Shiraz Uppal",
            queryDurationSec = 345L,
            candidateDurationSec = 350L
        )
        assertEquals("Mashup of Koi Mil Gaya / Raanjhanaa must be rejected with 0%", 0, confMxmMedley)

        // 2. Searching for "Apna Bana Le" must reject "Apna Bana Le / Kesariya Mashup"
        val confApnaMashup = LyricsMatcher.calculateConfidence(
            queryTitle = "Apna Bana Le",
            queryArtist = "Arijit Singh",
            candidateTitle = "Apna Bana Le / Kesariya (Acoustic Mashup)",
            candidateArtist = "Arijit Singh",
            queryDurationSec = 260L,
            candidateDurationSec = 300L
        )
        assertEquals("Mashup with multiple tracks must be rejected", 0, confApnaMashup)

        // 3. Searching for "Tum Hi Ho" must reject "Tum Hi Ho - Galliyan Medley"
        val confMedley = LyricsMatcher.calculateConfidence(
            queryTitle = "Tum Hi Ho",
            queryArtist = "Arijit Singh",
            candidateTitle = "Tum Hi Ho - Galliyan Medley",
            candidateArtist = "Arijit Singh, Ankit Tiwari",
            queryDurationSec = 262L,
            candidateDurationSec = 360L
        )
        assertEquals("Medley must be rejected", 0, confMedley)
    }

    @Test
    fun testAuthenticSongsAcceptedWithHighConfidence() {
        // 1. Official Raanjhanaa
        val confOfficial = LyricsMatcher.calculateConfidence(
            queryTitle = "Raanjhanaa (From \"Raanjhanaa\")",
            queryArtist = "Shiraz Uppal, Jaswinder Singh, A.R. Rahman",
            candidateTitle = "Raanjhanaa",
            candidateArtist = "Shiraz Uppal, Jaswinder Singh, A. R. Rahman",
            queryDurationSec = 345L,
            candidateDurationSec = 345L
        )
        assertTrue("Official Raanjhanaa must have >= 75% confidence, got $confOfficial", confOfficial >= 75)

        // 2. Official Kesariya
        val confKesariya = LyricsMatcher.calculateConfidence(
            queryTitle = "Kesariya (From \"Brahmastra\")",
            queryArtist = "Arijit Singh, Pritam, Amitabh Bhattacharya",
            candidateTitle = "Kesariya",
            candidateArtist = "Pritam, Arijit Singh",
            queryDurationSec = 268L,
            candidateDurationSec = 268L
        )
        assertTrue("Official Kesariya must have >= 75% confidence, got $confKesariya", confKesariya >= 75)

        // 3. Transliteration variant (Ranjhana vs Raanjhanaa)
        val confTranslit = LyricsMatcher.calculateConfidence(
            queryTitle = "Raanjhanaa",
            queryArtist = "Shiraz Uppal",
            candidateTitle = "Ranjhana",
            candidateArtist = "Shiraz Uppal",
            queryDurationSec = 345L,
            candidateDurationSec = 344L
        )
        assertTrue("Transliteration variant must match >= 70%, got $confTranslit", confTranslit >= 70)
    }

    @Test
    fun testCompletelyUnrelatedSongsRejected() {
        // 1. Searching for "Sawaar Loon", candidate is "Mon Manjihi"
        val conf1 = LyricsMatcher.calculateConfidence(
            queryTitle = "Sawaar Loon",
            queryArtist = "Monali Thakur",
            candidateTitle = "Mon Majhi Re",
            candidateArtist = "Arijit Singh",
            queryDurationSec = 254L,
            candidateDurationSec = 254L
        )
        assertEquals("Unrelated title must be 0%", 0, conf1)

        // 2. Searching for "Tauba Tauba", candidate is "Tauba Tauba (Old 1990 song)"
        val conf2 = LyricsMatcher.calculateConfidence(
            queryTitle = "Tauba Tauba",
            queryArtist = "Karan Aujla",
            candidateTitle = "Tauba Tauba",
            candidateArtist = "Gurdas Maan",
            queryDurationSec = 208L,
            candidateDurationSec = 320L
        )
        assertTrue("Different artist and 100s duration mismatch must be < 50%, got $conf2", conf2 < 50)
    }
}
