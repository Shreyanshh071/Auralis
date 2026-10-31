package com.auralis.music

import com.auralis.music.data.download.DownloadTrackOrder
import com.auralis.music.domain.library.PlaylistManager
import com.auralis.music.domain.model.Track
import org.junit.Assert.*
import org.junit.Test

class DownloadTrackOrderTest {
    private val tracks = listOf("a", "b", "c").map { Track(id = it, title = it) }

    @Test fun `download folders support reordering while other smart collections stay protected`() {
        assertTrue(DownloadTrackOrder.supports("smart_downloaded_all"))
        assertTrue(DownloadTrackOrder.supports("smart_downloaded_folder_individual_downloads"))
        assertTrue(DownloadTrackOrder.supports("smart_downloaded_folder_job-123"))
        assertFalse(DownloadTrackOrder.supports("smart_liked"))
        assertFalse(DownloadTrackOrder.supports("smart_cached"))
    }

    @Test fun `moved songs retain independent folder orders after serialization and refresh`() {
        val all = "smart_downloaded_all"
        val folder = "smart_downloaded_folder_job-123"
        val moved = PlaylistManager.reorderTracks(tracks, 0, 2)
        val restored = DownloadTrackOrder.decode(DownloadTrackOrder.encode(mapOf(
            all to moved.map { it.id }, folder to listOf("c", "b", "a")
        )))
        assertEquals(listOf("b", "c", "a"), DownloadTrackOrder.apply(tracks, restored[all]).map { it.id })
        assertEquals(listOf("c", "b", "a"), DownloadTrackOrder.apply(tracks, restored[folder]).map { it.id })
        assertEquals(listOf("a", "b", "c"), tracks.map { it.id })
    }

    @Test fun `deleted songs disappear and newly downloaded songs append in download order`() {
        val refreshed = listOf(tracks[0], Track(id = "new-1"), tracks[2], Track(id = "new-2"))
        assertEquals(listOf("c", "a", "new-1", "new-2"),
            DownloadTrackOrder.apply(refreshed, listOf("b", "c", "a")).map { it.id })
    }

    @Test fun `absent or corrupt saved order falls back to download order`() {
        assertEquals(tracks, DownloadTrackOrder.apply(tracks, null))
        assertTrue(DownloadTrackOrder.decode(null).isEmpty())
        assertTrue(DownloadTrackOrder.decode("broken").isEmpty())
    }
}
