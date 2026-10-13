package com.auralis.music.windows.downloads

import com.auralis.music.desktop.library.pruneEmptyDownloadFolders
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DownloadFolderCleanupTest {
    @Test fun removesEmptyDownloadTreeIncludingOwnedRoot() {
        val sandbox = Files.createTempDirectory("auralis-prune-").toFile()
        try {
            val root = File(sandbox,"Auralis")
            val folder = File(root,"Individual songs").apply { mkdirs() }
            pruneEmptyDownloadFolders(folder,root,removeRoot = true)
            assertFalse(folder.exists())
            assertFalse(root.exists())
            assertTrue(sandbox.isDirectory)
        } finally { sandbox.deleteRecursively() }
    }
    @Test fun preservesOtherFilesAndCustomDestinationAndRejectsPathsOutsideRoot() {
        val sandbox = Files.createTempDirectory("auralis-prune-").toFile()
        try {
            val root = File(sandbox,"Chosen folder").apply { mkdirs() }
            val folder = File(root,"Songs").apply { mkdirs() }
            val keep = File(folder,"keep.txt").apply { writeText("User file") }
            pruneEmptyDownloadFolders(folder,root,removeRoot = true)
            assertTrue(keep.isFile)
            keep.delete()
            pruneEmptyDownloadFolders(folder,root)
            assertFalse(folder.exists())
            assertTrue(root.isDirectory)
            assertFailsWith<IllegalArgumentException> { pruneEmptyDownloadFolders(sandbox,root,true) }
        } finally { sandbox.deleteRecursively() }
    }
}
