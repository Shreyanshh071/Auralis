package com.auralis.music.windows.home

import com.auralis.music.domain.model.*
import com.auralis.music.ui.viewmodel.HomeUiState
import kotlin.test.*

class DesktopHomeContentTest {
    private fun song(id: String) = Track(id = id, title = id, artist = "Artist")

    @Test fun listeningReplacesStarterRotationWithoutInventingMoreHistory() {
        val starter = HomeUiState()
        assertEquals(8, desktopHomeContent(starter).shortcuts.size)
        assertFalse(desktopHomeContent(starter).personalized)
        val heard = song("heard")
        val personal = desktopHomeContent(starter.copy(recentTracks = listOf(HistoryEntry(heard))))
        assertTrue(personal.personalized)
        assertEquals(listOf("heard"), personal.shortcuts.mapNotNull { it.track?.id })
    }

    @Test fun pinsSurviveWhileDuplicatesStayOutOfOtherShelves() {
        val pinned = song("pinned")
        val heard = song("heard")
        val pick = song("pick")
        val discover = song("discover")
        val album = PlaylistResult("album", "Saved album")
        val state = HomeUiState(
            speedDialPages = listOf(listOf(
                SpeedDialItem("track-pinned", "pinned", SpeedDialType.TRACK, track = pinned),
                SpeedDialItem("album-album", "Saved album", SpeedDialType.ALBUM, album = album, isPinned = true)
            )),
            pinnedSpeedDialIds = setOf("pinned"),
            recentTracks = listOf(HistoryEntry(heard), HistoryEntry(pinned), HistoryEntry(heard)),
            quickPicks = listOf(heard, pinned, pick, pick),
            dailyDiscover = listOf(DailyDiscoverItem(heard, discover)),
            similarRecommendations = listOf(SimilarRecommendation("Artist", items = listOf(pick, discover, song("related")))),
            dynamicSections = listOf(HomeSection("feed", "More", items = listOf(heard, song("feed")), albums = listOf(album)))
        )
        val content = desktopHomeContent(state)
        assertEquals(listOf("track-pinned", "album-album", "track-heard"), content.shortcuts.map { it.id })
        assertEquals(listOf("pick"), content.picks.map { it.id })
        val songs = content.shortcuts.mapNotNull { it.track?.id } + content.picks.map { it.id } +
            content.shelves.flatMap { it.tracks }.map { it.id }
        assertEquals(songs.distinct(), songs)
        assertTrue(content.shelves.flatMap { it.albums }.isEmpty())
        assertTrue("related" in songs && "feed" in songs)
    }

    @Test fun moodFilterShowsItsFeedRatherThanUnrelatedRotation() {
        val content = desktopHomeContent(HomeUiState(
            selectedChip = HomeChip("Focus", params = "focus"),
            quickPicks = listOf(song("unrelated")),
            dynamicSections = listOf(HomeSection("focus", "Focus music", items = listOf(song("focus"))))))
        assertTrue(content.shortcuts.isEmpty())
        assertTrue(content.picks.isEmpty())
        assertEquals(listOf("focus"), content.shelves.flatMap { it.tracks }.map { it.id })
    }
}
