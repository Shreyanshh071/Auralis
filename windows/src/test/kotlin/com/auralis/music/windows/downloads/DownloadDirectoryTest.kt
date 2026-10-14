package com.auralis.music.windows.downloads

import com.auralis.music.desktop.library.DesktopDownloads
import com.auralis.music.domain.model.Track
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

class DownloadDirectoryTest {
    @Test fun changingFolderPreservesOldDownloadsAndPersistsPathsForRestart() = runBlocking<Unit> {
        val sandbox = Files.createTempDirectory("auralis-download-location-").toFile()
        val old = File(sandbox, "old").apply { mkdirs() }
        val next = File(sandbox, "new")
        val audio = ByteArray(2048).apply { "ftyp".toByteArray().copyInto(this,4) }
        val oldSong = File(old,"Individual songs/Old.m4a").apply { parentFile.mkdirs(); writeBytes(audio) }
        val metadata = File(sandbox,"index.json")
        metadata.writeText(JSONArray().put(JSONObject().put("key","old").put("group","individual").put("name","Individual songs")
            .put("folder","Individual songs").put("track",JSONObject().put("id","old").put("title","Old").put("artist","Artist"))
            .put("status","COMPLETED").put("file","Individual songs/Old.m4a").put("bytes",audio.size)).toString())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/audio") { response ->
            response.responseHeaders.add("Content-Type","audio/mp4")
            response.sendResponseHeaders(200,audio.size.toLong())
            response.responseBody.use { it.write(audio) }; response.close()
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        var remembered: File? = null
        try {
            val manager = DesktopDownloads(old, scope, metadataFile = metadata, onRootChanged = { remembered = it }) {
                "http://127.0.0.1:${server.address.port}/audio"
            }
            assertEquals(oldSong.canonicalFile, manager.localFile("old")?.canonicalFile)
            manager.changeDirectory(next)
            assertEquals(next.canonicalFile, remembered)
            assertEquals(next.canonicalFile, manager.root)
            assertEquals(oldSong.canonicalFile, manager.localFile("old")?.canonicalFile)
            manager.enqueue(listOf(Track("new","New","Artist")))
            withTimeout(10000) { while (manager.localFile("new") == null) delay(20) }
            assertTrue(manager.localFile("new")!!.canonicalPath.startsWith(next.canonicalPath+File.separator))
            val restarted = DesktopDownloads(next, scope, metadataFile = metadata)
            assertEquals(oldSong.canonicalFile, restarted.localFile("old")?.canonicalFile)
            assertNotNull(restarted.localFile("new"))
            restarted.remove("old")
            assertFalse(oldSong.exists())
            assertNotNull(restarted.localFile("new"))
        } finally { scope.cancel(); server.stop(0); sandbox.deleteRecursively() }
    }

    @Test fun moveExistingDownloadsTransfersFilesAndCleansOldDirectory() = runBlocking<Unit> {
        val sandbox = Files.createTempDirectory("auralis-move-test-").toFile()
        val oldDir = File(sandbox, "oldDir").apply { mkdirs() }
        val newDir = File(sandbox, "newDir").apply { mkdirs() }
        val audio = ByteArray(2048).apply { "ftyp".toByteArray().copyInto(this, 4) }
        val song1 = File(oldDir, "Individual songs/Song 1 (s1).m4a").apply { parentFile.mkdirs(); writeBytes(audio) }
        val song2 = File(oldDir, "Individual songs/Song 2 (s2).m4a").apply { writeBytes(audio) }

        val metadata = File(sandbox, "index.json")
        metadata.writeText(JSONArray()
            .put(JSONObject().put("key", "k1").put("group", "individual").put("name", "Individual songs")
                .put("folder", "Individual songs").put("track", JSONObject().put("id", "s1").put("title", "Song 1").put("artist", "Artist 1"))
                .put("status", "COMPLETED").put("file", "Individual songs/Song 1 (s1).m4a").put("bytes", audio.size.toLong()))
            .put(JSONObject().put("key", "k2").put("group", "individual").put("name", "Individual songs")
                .put("folder", "Individual songs").put("track", JSONObject().put("id", "s2").put("title", "Song 2").put("artist", "Artist 2"))
                .put("status", "COMPLETED").put("file", "Individual songs/Song 2 (s2).m4a").put("bytes", audio.size.toLong()))
            .toString())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = DesktopDownloads(oldDir, scope, metadataFile = metadata)
            assertEquals(0, manager.countFilesOutsideCurrentRoot())
            assertEquals(song1.canonicalFile, manager.localFile("s1")?.canonicalFile)
            assertEquals(song2.canonicalFile, manager.localFile("s2")?.canonicalFile)

            // User changed the directory
            manager.changeDirectory(newDir, transferExisting = false)
            assertEquals(2, manager.countFilesOutsideCurrentRoot())
            assertEquals(2, manager.countGroupFilesOutsideCurrentRoot("individual"))
            assertTrue(song1.exists())
            assertTrue(song2.exists())

            // Transfer the files
            val moved = manager.moveExistingDownloadsToCurrentRoot()
            assertEquals(2, moved)
            assertEquals(0, manager.countFilesOutsideCurrentRoot())

            // Files must be deleted from old directory
            assertFalse(song1.exists(), "Old song 1 must be deleted from where it was")
            assertFalse(song2.exists(), "Old song 2 must be deleted from where it was")
            assertFalse(File(oldDir, "Individual songs").exists(), "Old empty folder must be pruned")

            // Files must exist in new directory
            val newSong1 = File(newDir, "Individual songs/Song 1 (s1).m4a")
            val newSong2 = File(newDir, "Individual songs/Song 2 (s2).m4a")
            assertTrue(newSong1.exists(), "Song 1 must exist in new directory")
            assertTrue(newSong2.exists(), "Song 2 must exist in new directory")
            assertEquals(newSong1.canonicalFile, manager.localFile("s1")?.canonicalFile)
            assertEquals(newSong2.canonicalFile, manager.localFile("s2")?.canonicalFile)

            // Restart with metadata to verify persistence
            val reloaded = DesktopDownloads(newDir, scope, metadataFile = metadata)
            assertEquals(0, reloaded.countFilesOutsideCurrentRoot())
            assertEquals(newSong1.canonicalFile, reloaded.localFile("s1")?.canonicalFile)
            assertEquals(newSong2.canonicalFile, reloaded.localFile("s2")?.canonicalFile)
        } finally {
            scope.cancel()
            sandbox.deleteRecursively()
        }
    }

    @Test fun changingDirectoryWithTransferExistingMovesFilesImmediately() = runBlocking<Unit> {
        val sandbox = Files.createTempDirectory("auralis-change-transfer-").toFile()
        val oldDir = File(sandbox, "oldDir").apply { mkdirs() }
        val newDir = File(sandbox, "newDir").apply { mkdirs() }
        val audio = ByteArray(2048).apply { "ftyp".toByteArray().copyInto(this, 4) }
        val oldSong = File(oldDir, "Individual songs/Track (tr1).m4a").apply { parentFile.mkdirs(); writeBytes(audio) }

        val metadata = File(sandbox, "index.json")
        metadata.writeText(JSONArray()
            .put(JSONObject().put("key", "k1").put("group", "individual").put("name", "Individual songs")
                .put("folder", "Individual songs").put("track", JSONObject().put("id", "tr1").put("title", "Track").put("artist", "Artist"))
                .put("status", "COMPLETED").put("file", "Individual songs/Track (tr1).m4a").put("bytes", audio.size.toLong()))
            .toString())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = DesktopDownloads(oldDir, scope, metadataFile = metadata)
            val moved = manager.changeDirectory(newDir, transferExisting = true)
            assertEquals(1, moved)
            assertFalse(oldSong.exists())
            val newSong = File(newDir, "Individual songs/Track (tr1).m4a")
            assertTrue(newSong.exists())
            assertEquals(newSong.canonicalFile, manager.localFile("tr1")?.canonicalFile)
        } finally {
            scope.cancel()
            sandbox.deleteRecursively()
        }
    }

    @Test fun transferringToParentDirectoryResolvesToAuralisFolderAndPreservesIndividualAndPlaylistsStructure() = runBlocking<Unit> {
        val sandbox = Files.createTempDirectory("auralis-res-test-").toFile()
        val cDrive = File(sandbox, "C_Drive")
        val oldAuralis = File(cDrive, "Auralis").apply { mkdirs() }
        val downloadsFolder = File(sandbox, "Downloads").apply { mkdirs() }

        val resolved = DesktopDownloads.resolveDownloadDirectory(downloadsFolder)
        assertEquals(File(downloadsFolder, "Auralis").canonicalFile, resolved.canonicalFile)
        assertEquals(resolved.canonicalFile, DesktopDownloads.resolveDownloadDirectory(resolved).canonicalFile)

        val audio = ByteArray(2048).apply { "ftyp".toByteArray().copyInto(this, 4) }
        val song = File(oldAuralis, "Individual songs/Song (s1).m4a").apply { parentFile.mkdirs(); writeBytes(audio) }
        val plSong = File(oldAuralis, "Playlists/Favourites/01 - Fav (f1).m4a").apply { parentFile.mkdirs(); writeBytes(audio) }

        val metadata = File(sandbox, "index.json")
        metadata.writeText(JSONArray()
            .put(JSONObject().put("key", "k1").put("group", "individual").put("name", "Individual songs")
                .put("folder", "Individual songs").put("track", JSONObject().put("id", "s1").put("title", "Song").put("artist", "Artist"))
                .put("status", "COMPLETED").put("file", "Individual songs/Song (s1).m4a").put("bytes", audio.size.toLong()))
            .put(JSONObject().put("key", "k2").put("group", "pl1").put("name", "Favourites")
                .put("folder", "Playlists/Favourites").put("track", JSONObject().put("id", "f1").put("title", "Fav").put("artist", "Artist"))
                .put("status", "COMPLETED").put("file", "Playlists/Favourites/01 - Fav (f1).m4a").put("bytes", audio.size.toLong()))
            .toString())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = DesktopDownloads(oldAuralis, scope, metadataFile = metadata)
            val moved = manager.changeDirectory(resolved, transferExisting = true)
            assertEquals(2, moved)

            // Both Individual songs and Playlists must be inside the target Auralis folder
            assertTrue(File(resolved, "Individual songs").isDirectory)
            assertTrue(File(resolved, "Playlists").isDirectory)
            assertTrue(File(resolved, "Individual songs/Song (s1).m4a").exists())
            assertTrue(File(resolved, "Playlists/Favourites/01 - Fav (f1).m4a").exists())

            // Old files and empty old Auralis folder must be removed
            assertFalse(song.exists())
            assertFalse(plSong.exists())
            assertFalse(oldAuralis.exists(), "Old empty Auralis folder must be completely pruned")
        } finally {
            scope.cancel()
            sandbox.deleteRecursively()
        }
    }
}
