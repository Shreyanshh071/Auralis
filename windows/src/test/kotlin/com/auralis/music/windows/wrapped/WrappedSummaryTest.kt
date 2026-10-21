package com.auralis.music.windows.wrapped

import com.auralis.music.desktop.library.DesktopListeningStats.Listen
import com.auralis.music.domain.model.Track
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class WrappedSummaryTest {
    @Test fun usesMeasuredCurrentYearListensAndRanksByTimeListened() {
        val zone = ZoneId.of("Asia/Kolkata")
        fun date(year: Int) = LocalDate.of(year, 1, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        val a = Track("a", "Top song", "Artist A", album = "Album A")
        val b = Track("b", "Second song", "Artist B", album = "Album B")
        val events = listOf(Listen("1", a, date(2026), 60_000), Listen("2", a, date(2026), 60_000),
            Listen("3", b, date(2026), 90_000), Listen("6", b, date(2026), 10_000), Listen("7", b, date(2026), 10_000), Listen("4", b, date(2025), 9_000_000),
            Listen("5", b, date(2027), 9_000_000))
        val summary = wrappedState(events, 2026, zone)
        assertEquals(3, summary.totalMinutes)
        assertEquals(2, summary.uniqueSongCount)
        assertEquals(2, summary.uniqueAlbumCount)
        assertEquals(2, summary.uniqueArtistCount)
        assertEquals("a", summary.topSongs.first().track.id)
        assertEquals(2, summary.topSongs.first().playCount)
        assertEquals("Artist A", summary.topArtists.first().name)
        assertFalse(wrappedState(emptyList(), 2026, zone).hasData)
    }
}
