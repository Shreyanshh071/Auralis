package com.auralis.music.desktop.library

import com.auralis.music.desktop.home.musicHomeState
import com.auralis.music.domain.model.*
import com.auralis.music.ui.viewmodel.HomeUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class MusicHomeHistoryTest {
    private val song = Track("song", "Interlude", duration = 30)
    private val short = Track("short", "Short", duration = 30)
    private val filter: suspend (List<Track>) -> List<Track> = { tracks -> tracks.filterNot { it.id == short.id } }

    @Test fun recommendationsAndPinsAreFilteredWithoutRejectingShortAudio() = runBlocking {
        val pins = listOf(song, short).map { SpeedDialItem(it.id, it.title, SpeedDialType.TRACK, track = it) }
        val original = HomeUiState(speedDialPages = listOf(pins), pinnedItems = pins,
            quickPicks = listOf(short, song), keepListening = listOf(short, song),
            dynamicSections = listOf(HomeSection("home", "Home", items = listOf(short, song))),
            similarRecommendations = listOf(SimilarRecommendation("Similar", items = listOf(short, song))),
            dailyDiscover = listOf(DailyDiscoverItem(song, short), DailyDiscoverItem(song, song)))
        val shown = musicHomeState(original, filter)
        assertEquals(listOf(song), shown.quickPicks)
        assertEquals(listOf(song), shown.keepListening)
        assertEquals(listOf(song), shown.dynamicSections.single().items)
        assertEquals(listOf(song), shown.similarRecommendations.single().items)
        assertEquals(listOf(song.id), shown.pinnedItems.map { it.id })
        assertEquals(listOf(song.id), shown.speedDialPages.single().map { it.id })
        assertEquals(listOf(DailyDiscoverItem(song, song)), shown.dailyDiscover)
        assertEquals(2, original.quickPicks.size)
    }

    @Test fun historyProjectionPreservesRawListeningRecords() = runBlocking {
        val folder = Files.createTempDirectory("auralis-music-history").toFile()
        try {
            val raw = DesktopHistoryRepository(java.io.File(folder, "history.json"), DesktopLibraryRepository(java.io.File(folder, "library.json")))
            raw.addToHistory(song); raw.addToHistory(short)
            raw.recordPlay(song); raw.recordPlay(short)
            val shown = MusicHistoryRepository(raw, filter)
            assertEquals(listOf(song), shown.getHistory().first().map { it.track })
            assertEquals(listOf(song), shown.getTopPlayedTracks().first().map { it.track })
            assertEquals(listOf(song), shown.getPlayCounts().map { it.track })
            assertEquals(2, raw.getHistory().first().size)
        } finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }
}
