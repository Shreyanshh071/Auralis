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
}
