package com.auralis.music

import com.auralis.music.data.sync.CloudSyncConflictResolver
import com.auralis.music.data.sync.SyncableItem
import org.junit.Assert.*
import org.junit.Test

class CloudSyncConflictTest {

    @Test
    fun `resolveConflict favors newer timestamp under Last-Write-Wins`() {
        val older = SyncableItem(id = "item_1", data = "Old Value", updatedAt = 1000L)
        val newer = SyncableItem(id = "item_1", data = "New Value", updatedAt = 2000L)

        val winner = CloudSyncConflictResolver.resolveConflict(local = older, remote = newer)
        assertNotNull(winner)
        assertEquals("New Value", winner?.data)

        val winnerLocal = CloudSyncConflictResolver.resolveConflict(local = newer, remote = older)
        assertNotNull(winnerLocal)
        assertEquals("New Value", winnerLocal?.data)
    }

    @Test
    fun `resolveConflict respects tombstone soft-deletion when deletion is newest`() {
        val local = SyncableItem(id = "item_1", data = "Live", updatedAt = 1000L, isDeleted = false)
        val remoteDeleted = SyncableItem(id = "item_1", data = "Live", updatedAt = 2000L, isDeleted = true)

        val resolved = CloudSyncConflictResolver.resolveConflict(local = local, remote = remoteDeleted)
        assertNull(resolved)
    }

    @Test
    fun `mergeCollections provides anti-wipe protection on initial sync with empty remote`() {
        val localItems = mapOf(
            "1" to SyncableItem(id = "1", data = "Playlist 1", updatedAt = 1000L),
            "2" to SyncableItem(id = "2", data = "Playlist 2", updatedAt = 1000L)
        )
        val remoteEmpty = emptyMap<String, SyncableItem<String>>()

        val merged = CloudSyncConflictResolver.mergeCollections(
            localItems = localItems,
            remoteItems = remoteEmpty,
            isInitialSync = true
        )

        assertEquals(2, merged.size)
        assertEquals("Playlist 1", merged["1"])
        assertEquals("Playlist 2", merged["2"])
    }
}
