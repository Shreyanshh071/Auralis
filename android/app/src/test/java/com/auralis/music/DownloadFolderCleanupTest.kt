package com.auralis.music

import com.auralis.music.data.download.PlaylistDownloadPaths
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFolderCleanupTest {
    @Test fun `a file counts as kept under either MediaStore prefix`() {
        val listed = listOf("content://media/external/downloads/11", "content://media/external/downloads/12")
        val kept = listOf("content://media/external_primary/downloads/11")
        assertEquals(listOf("content://media/external/downloads/12"), PlaylistDownloadPaths.staleFiles(listed, kept))
    }

    @Test fun `an older copy of a renumbered song is stale`() {
        val listed = listOf("file:/d/07%20-%20Song.m4a", "file:/d/09%20-%20Song.m4a")
        assertEquals(listOf("file:/d/07%20-%20Song.m4a"), PlaylistDownloadPaths.staleFiles(listed, listOf("file:/d/09%20-%20Song.m4a")))
    }

    @Test fun `every tracked file is left alone`() {
        val listed = listOf("content://media/external/downloads/11")
        assertEquals(emptyList<String>(), PlaylistDownloadPaths.staleFiles(listed, listed))
    }
}
