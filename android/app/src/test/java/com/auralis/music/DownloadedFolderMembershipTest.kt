package com.auralis.music

import com.auralis.music.data.download.*
import org.junit.Assert.*
import org.junit.Test

class DownloadedFolderMembershipTest {
    @Test fun playlistRemovalPreservesDirectAndSharedDownloads() {
        assertFalse(canRemovePlaylistDownload("song", emptySet(), setOf("song")))
        assertFalse(canRemovePlaylistDownload("song", setOf("song"), emptySet()))
        assertTrue(canRemovePlaylistDownload("song", emptySet(), emptySet()))
    }

    @Test fun individualSongIsNotClaimedJustBecauseItIsInAPlaylistSnapshot() {
        val membership = downloadedFolderMembership(
            listOf("song", "missing"), emptyList(), setOf("song"), setOf("song"), false
        )
        assertFalse(membership.visible)
        assertTrue(membership.accountedIds.isEmpty())
    }

    @Test fun genuinePlaylistMembershipDoesNotRemoveAnIndividualDownloadFromItsOwnGroup() {
        val membership = downloadedFolderMembership(
            listOf("song"), listOf(TrackDownloadResult("song", DownloadOutcome.SUCCESS)),
            setOf("song"), setOf("song"), false
        )
        assertTrue(membership.visible)
        assertEquals(setOf("song"), membership.downloadedIds)
        assertTrue(membership.accountedIds.isEmpty())
    }

    @Test fun explicitlyReusedSongIsAvailableInPlaylistAndRemainsAnIndividualDownload() {
        val membership = downloadedFolderMembership(
            listOf("song"), listOf(TrackDownloadResult("song", DownloadOutcome.ALREADY_DOWNLOADED)),
            setOf("song"), setOf("song"), false
        )
        assertTrue(membership.visible)
        assertEquals(setOf("song"), membership.downloadedIds)
        assertTrue(membership.accountedIds.isEmpty())
    }

    @Test fun removedFilesDoNotLeaveEmptyDownloadedFolders() {
        val membership = downloadedFolderMembership(
            listOf("removed"), listOf(TrackDownloadResult("removed", DownloadOutcome.SUCCESS)),
            emptySet(), emptySet(), false
        )
        assertFalse(membership.visible)
    }

    @Test fun activePlaylistDoesNotFlickerAnIndividualFolderForItsNewlySavedSong() {
        val membership = downloadedFolderMembership(
            listOf("song", "direct"), emptyList(), setOf("song", "direct"), setOf("direct"), true
        )
        assertTrue(membership.visible)
        assertEquals(setOf("song"), membership.accountedIds)
    }
}
