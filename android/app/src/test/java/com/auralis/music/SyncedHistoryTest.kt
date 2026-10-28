package com.auralis.music

import com.auralis.music.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SyncedHistoryTest {
    @Test fun newestListenWinsWithoutDuplicatingRepeatedCloudDownloads() {
        val a = Track("a", "First song", "Artist")
        val b = Track("b", "Second song", "Artist")
        val local = listOf(HistoryEntry(a, 500))
        val cloud = listOf(HistoryEntry(a, 100), HistoryEntry(b, 200))
        val merged = mergeSyncedHistory(local, cloud)
        assertEquals(listOf(HistoryEntry(a, 500), HistoryEntry(b, 200)), merged)
        assertEquals(merged, mergeSyncedHistory(merged, cloud))
    }

    @Test fun alternateUploadsKeepTheNewestOccurrenceAndInvalidEntriesAreIgnored() {
        val old = HistoryEntry(Track("old", "Same song", "Artist"), 100)
        val fresh = HistoryEntry(Track("new", "Same song", "Artist"), 200)
        assertEquals(listOf(fresh), mergeSyncedHistory(listOf(old),
            listOf(fresh, HistoryEntry(Track("", "Bad", "Artist"), 300))))
    }
}
