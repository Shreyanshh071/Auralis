package com.auralis.music

import com.auralis.music.data.download.ManagedPlaylistDownloadStorage
import com.auralis.music.data.download.PlaylistDownloadPaths
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

class ManagedPlaylistDownloadStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private fun source(): File = temp.newFile().apply { writeBytes(ByteArray(6000) { (it % 251).toByte() }) }

    @Test fun `new playlist copy is private and leaves public downloads untouched`() {
        val appFiles = temp.newFolder("app-files")
        val publicFolder = temp.newFolder("Download")
        val legacy = File(publicFolder, "Auralis/Chill Hits/01 - Song.m4a").apply {
            parentFile!!.mkdirs(); writeText("legacy file must remain")
        }
        val input = source()
        val (uri, bytes) = ManagedPlaylistDownloadStorage(appFiles).publish(input, "Chill Hits", "01 - Song.m4a")
        val copy = File(URI(uri))
        assertEquals(File(appFiles, "playlist_downloads/Chill Hits/01 - Song.m4a"), copy)
        assertArrayEquals(input.readBytes(), copy.readBytes())
        assertEquals(input.length(), bytes)
        assertEquals("legacy file must remain", legacy.readText())
        assertArrayEquals(input.readBytes(), copy.readBytes())
    }

    @Test fun `saved URI still reads exact bytes after storage reconstruction`() {
        val appFiles = temp.newFolder()
        val input = source()
        val uri = ManagedPlaylistDownloadStorage(appFiles).publish(input, "Playlist", "Track.m4a").first
        ManagedPlaylistDownloadStorage(appFiles)
        assertArrayEquals(input.readBytes(), File(URI(uri)).readBytes())
    }

    @Test fun `same named playlists and repeated song titles keep separate indexed copies`() {
        val appFiles = temp.newFolder()
        val storage = ManagedPlaylistDownloadStorage(appFiles)
        val input = source()
        val first = storage.publish(input, "Hits", "01 - Song.m4a").first
        val second = storage.publish(input, PlaylistDownloadPaths.collisionFolder("Hits", "second-playlist"), "01 - Song.m4a").first
        val repeated = storage.publish(input, "Hits", "02 - Song.m4a").first
        assertEquals(3, setOf(first, second, repeated).size)
        listOf(first, second, repeated).forEach { assertArrayEquals(input.readBytes(), File(URI(it)).readBytes()) }
    }

    @Test fun `untrusted folder and file names remain inside managed root`() {
        val appFiles = temp.newFolder()
        val uri = ManagedPlaylistDownloadStorage(appFiles).publish(source(), "../../outside", "../escape.m4a").first
        val file = File(URI(uri)).canonicalFile
        assertEquals(File(appFiles, "playlist_downloads").canonicalFile, file.parentFile!!.parentFile)
        assertFalse(file.name.contains(".."))
    }

    @Test fun `incomplete source never replaces a saved copy`() {
        val appFiles = temp.newFolder()
        val storage = ManagedPlaylistDownloadStorage(appFiles)
        val saved = File(URI(storage.publish(source(), "Hits", "Song.m4a").first))
        val before = saved.readBytes()
        val incomplete = temp.newFile().apply { writeBytes(ByteArray(10)) }
        assertTrue(runCatching { storage.publish(incomplete, "Hits", "Song.m4a") }.exceptionOrNull() is IllegalArgumentException)
        assertArrayEquals(before, saved.readBytes())
        assertFalse(saved.parentFile!!.listFiles()!!.any { it.name.endsWith(".partial") })
    }

    @Test fun `failed finalization retains existing destination and removes partial file`() {
        val appFiles = temp.newFolder()
        val blocked = File(appFiles, "playlist_downloads/Hits/Song.m4a").apply { mkdirs() }
        val sentinel = File(blocked, "keep").apply { writeText("original") }
        assertTrue(runCatching { ManagedPlaylistDownloadStorage(appFiles).publish(source(), "Hits", "Song.m4a") }.isFailure)
        assertEquals("original", sentinel.readText())
        assertFalse(blocked.parentFile!!.listFiles()!!.any { it.name.endsWith(".partial") })
    }

    @Test fun `empty folder cleanup never deletes songs or legacy public folders`() {
        val appFiles = temp.newFolder()
        val storage = ManagedPlaylistDownloadStorage(appFiles)
        val saved = File(URI(storage.publish(source(), "Hits", "Song.m4a").first))
        val legacy = File(temp.newFolder("Download"), "Auralis/Hits").apply { mkdirs() }
        storage.deleteFolderIfEmpty("Hits")
        assertTrue(saved.exists())
        assertTrue(saved.delete())
        storage.deleteFolderIfEmpty("Hits")
        assertFalse(saved.parentFile!!.exists())
        assertTrue(legacy.exists())
    }
}
