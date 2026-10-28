package com.auralis.music.windows.home

import com.auralis.music.desktop.library.DesktopListeningStats
import com.auralis.music.domain.model.Track
import kotlin.test.*

class FamiliarArtistPatternTest {
    @Test fun listeningTimeWinsEvenWithFewerPlaysAndSecondsBreakDisplayedMinuteTies() {
        val artists = listOf(
            FamiliarArtist("Many plays", 100, listenedMs = 60 * 60_000L),
            FamiliarArtist("Long listens", 10, listenedMs = 2 * 60 * 60_000L),
            FamiliarArtist("Extra second", 1, listenedMs = 60 * 60_000L + 1000L),
            FamiliarArtist("Equal time more plays", 20, listenedMs = 2 * 60 * 60_000L))
        val expected = listOf("Equal time more plays", "Long listens", "Extra second", "Many plays")
        assertEquals(expected, rankFamiliarArtists(artists).map { it.name })
        val stats = artists.map { FamiliarArtistStats(it.name, it.totalPlays, 0, 0, 0.0, listenedMs = it.listenedMs) }
        assertEquals(expected, rankFamiliarArtistStats(stats).map { it.name })
    }

    private fun artistStats(name: String, ms: Long) = FamiliarArtistStats(name, 2, 2, 2, 2.0, listenedMs = ms)

    @Test fun rowRequiresFiveArtistsWithFiveMinutesEach() {
        val four = (1..4).map { artistStats("Artist $it", 300_000L) }
        assertTrue(eligibleFamiliarArtistStats(four).isEmpty())
        assertTrue(eligibleFamiliarArtistStats(four + artistStats("Artist 5", 299_999L)).isEmpty())
        assertEquals(5, eligibleFamiliarArtistStats(four + artistStats("Artist 5", 300_000L)).size)
    }

    @Test fun shortListensAndDuplicateArtistNamesCannotUnlockPosters() {
        val four = (1..4).map { artistStats("Artist $it", 600_000L) }
        assertTrue(eligibleFamiliarArtistStats(four + artistStats("ARTIST 1 - Topic", 300_000L)).isEmpty())
        val qualified = four + artistStats("Artist 5", 300_000L) + artistStats("Brief preview", 20_000L)
        assertEquals(5, eligibleFamiliarArtistStats(qualified).size)
        assertFalse(eligibleFamiliarArtistStats(qualified).any { it.name == "Brief preview" })
    }

    @Test fun measuredSessionsAccumulateAcrossSongsAndRespectBlockedArtists() {
        val listens = (1..5).flatMap { artist -> (1..2).map { song ->
            listen("$artist-$song", track("$artist-$song", "Artist $artist"), 1000L, 150_000L)
        } }
        assertEquals(5, eligibleFamiliarArtistStats(computeFamiliarArtistStats(listens)).size)
        assertTrue(eligibleFamiliarArtistStats(computeFamiliarArtistStats(listens,
            blockedTracks = { it.artist == "Artist 5" })).isEmpty())
    }

    private fun track(id: String, artist: String, duration: Long = 180L) = Track(id = id, title = "Title $id", artist = artist, duration = duration)

    private fun listen(
        id: String,
        track: Track,
        startedAt: Long,
        timeMs: Long
    ) = DesktopListeningStats.Listen(id = id, track = track, startedAt = startedAt, timeMs = timeMs)

    @Test
    fun recentListensReceiveHigherPatternScoreThanOlderListens() {
        val now = 1_700_000_000_000L
        val oneDayAgo = now - 86_400_000L
        val sixtyDaysAgo = now - 60L * 86_400_000L

        val recentArtistTrack = track("1", "Ravyn Lenae")
        val oldArtistTrack = track("2", "PAWAN SINGH")

        // 5 plays yesterday for Ravyn Lenae
        val recentListens = (1..5).map {
            listen("r$it", recentArtistTrack, oneDayAgo, 60_000L)
        }
        // 5 plays 60 days ago for PAWAN SINGH
        val oldListens = (1..5).map {
            listen("o$it", oldArtistTrack, sixtyDaysAgo, 60_000L)
        }

        val stats = computeFamiliarArtistStats(
            listens = recentListens + oldListens,
            now = now
        )

        val ravyn = stats.first { it.name == "Ravyn Lenae" }
        val pawan = stats.first { it.name == "PAWAN SINGH" }

        assertEquals(5, ravyn.totalPlays)
        assertEquals(5, ravyn.recentPlays)
        assertEquals(5, ravyn.weekPlays)

        assertEquals(5, pawan.totalPlays)
        assertEquals(0, pawan.recentPlays)
        assertEquals(0, pawan.weekPlays)

        assertTrue(ravyn.patternScore > pawan.patternScore, "Recent active rotation must outscore old history")
    }

    @Test
    fun artistNamesNormalizeAndStripFeaturedArtists() {
        val now = 1_700_000_000_000L
        val t1 = track("1", "The Weeknd feat. Daft Punk")
        val t2 = track("2", "The Weeknd & Kendrick Lamar")
        val t3 = track("3", "The Weeknd - Topic")

        val listens = listOf(
            listen("1", t1, now - 1000L, 60_000L),
            listen("2", t2, now - 2000L, 60_000L),
            listen("3", t3, now - 3000L, 60_000L)
        )

        val stats = computeFamiliarArtistStats(listens = listens, now = now)
        assertEquals(1, stats.size)
        assertEquals("The Weeknd", stats.first().name)
        assertEquals(3, stats.first().totalPlays)
    }

    @Test
    fun intentionalShortPlaysQualifyForRecentPattern() {
        val now = 1_700_000_000_000L
        val t = track("1", "TV Girl", duration = 200)

        // 20-second listen: intentional sampling/preview, qualifies as intentional listening pattern
        val listens = listOf(
            listen("1", t, now - 5000L, 20_000L)
        )

        val stats = computeFamiliarArtistStats(listens = listens, now = now)
        assertEquals(1, stats.size)
        val tvGirl = stats.first()
        assertEquals(1, tvGirl.recentPlays)
        assertEquals(1, tvGirl.weekPlays)
        assertTrue(tvGirl.patternScore > 0.0)
    }

    @Test
    fun blockedTracksAreExcludedFromStats() {
        val now = 1_700_000_000_000L
        val t1 = track("1", "Blocked Artist")
        val t2 = track("2", "Allowed Artist")

        val listens = listOf(
            listen("1", t1, now - 1000L, 60_000L),
            listen("2", t2, now - 2000L, 60_000L)
        )

        val stats = computeFamiliarArtistStats(
            listens = listens,
            blockedTracks = { it.artist.contains("Blocked") },
            now = now
        )

        assertEquals(1, stats.size)
        assertEquals("Allowed Artist", stats.first().name)
    }
}
