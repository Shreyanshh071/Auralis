package com.auralis.music.windows.profile

import com.auralis.music.desktop.library.*
import com.auralis.music.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import kotlin.test.*

class HistorySyncTest {
    private fun entry(id: String, time: Long, title: String = id) = HistoryEntry(Track(id, title, "Artist"), time)

    @Test fun incomingHistoryKeepsListenTimesAndNeverMovesAnOlderListenAboveANewerOne() {
        val newer = entry("a", 500)
        assertEquals(listOf(newer, entry("b", 200)), mergeSyncedHistory(listOf(newer),
            listOf(entry("a", 100), entry("b", 200), entry("b", 200))))
    }

    @Test fun repeatedPullsDeduplicateAlternateUploadsAndKeepTheNewestHundred() {
        val cloud = (1..110).map { entry("song$it", it.toLong(), "Distinct melody number $it") }
        val first = mergeSyncedHistory(emptyList(), cloud)
        assertEquals(100, first.size)
        assertEquals(first, mergeSyncedHistory(first, cloud))
        val merged = mergeSyncedHistory(listOf(entry("video1", 100, "Same song")), listOf(entry("video2", 200, "Same song")))
        assertEquals(listOf("video2"), merged.map { it.track.id })
    }

    @Test fun importingHistoryUpdatesTheHistoryFlowWithoutIncreasingPlayCounts() = runBlocking {
        val dir = Files.createTempDirectory("history-sync").toFile()
        val repository = DesktopHistoryRepository(java.io.File(dir, "history.json"),
            DesktopLibraryRepository(java.io.File(dir, "library.json")))
        val entries = listOf(entry("a", 500), entry("b", 200))
        repository.importHistory(entries)
        repository.importHistory(entries)
        assertEquals(entries, repository.getHistory().first())
        assertTrue(repository.getPlayCounts().isEmpty())
        repository.removeFromHistory("a")
        repository.importHistory(entries)
        assertEquals(listOf(entries[1]), repository.getHistory().first(), "Repeated sync must respect local removals")
        repository.clearHistory()
        repository.importHistory(entries)
        assertTrue(repository.getHistory().first().isEmpty())
        repository.importHistory(listOf(entry("a", 900)))
        assertEquals(listOf(entry("a", 900)), repository.getHistory().first(), "A genuinely newer listen appears again")
    }

    @Test fun changingAccountsRemovesImportedHistoryButPreservesLocalListens() = runBlocking {
        val dir = Files.createTempDirectory("history-account").toFile()
        val repository = DesktopHistoryRepository(java.io.File(dir, "history.json"),
            DesktopLibraryRepository(java.io.File(dir, "library.json")))
        repository.addToHistory(Track("local", "Local melody", "Local artist"))
        repository.importHistory(listOf(entry("phone", 500, "Remote melody")), "account-a")
        assertEquals(2, repository.getHistory().first().size)
        repository.setHistoryAccount(null)
        assertEquals(listOf("local"), repository.getHistory().first().map { it.track.id })
        repository.importHistory(listOf(entry("other", 600, "Another melody")), "account-b")
        assertEquals(setOf("local", "other"), repository.getHistory().first().map { it.track.id }.toSet())
    }
}
