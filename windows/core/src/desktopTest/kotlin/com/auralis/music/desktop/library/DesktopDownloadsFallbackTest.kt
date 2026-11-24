package com.auralis.music.desktop.library

import com.auralis.music.domain.model.Track
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Downloads while YouTube refuses: the same recording from JioSaavn's file or SoundCloud's HLS. */
class DesktopDownloadsFallbackTest {
    private val paused = "YouTube requests are paused (VERIFICATION_CHALLENGE); try again in 30 min"
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val root = Files.createTempDirectory("auralis-download-fallback").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val song = Track("5tpQaCAq6Qc", "Loving Machine", "TV Girl", album = "Who Really Cares", duration = 227)

    private fun audio(signature: ByteArray, size: Int, seed: Int) =
        ByteArray(size) { ((it * 31 + seed) % 251).toByte() }.also { signature.copyInto(it, 0) }
    private val mp4 = audio(byteArrayOf(0, 0, 0, 0x18) + "ftypM4A ".toByteArray(), 6000, 1)
    private val init = audio(byteArrayOf(0, 0, 0, 0x18) + "ftypiso6".toByteArray(), 700, 2)
    private val fragments = List(3) { audio("\u0000\u0000\u0004\u0000moof".toByteArray(), 1500 + it, 3 + it) }
    private val mp3Segments = List(2) { audio("ID3".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 0), 1200, 9 + it) }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun serve(path: String, type: String, body: ByteArray) = server.createContext(path) { e ->
        hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
        respond(e, type, body)
    }

    /** Answers a Range request with 206 like a CDN, otherwise the whole body. */
    private fun respond(e: HttpExchange, type: String, body: ByteArray) {
        e.responseHeaders.add("Content-Type", type)
        val range = e.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
        if (range != null) {
            val start = range[0].toInt()
            val end = minOf(range[1].toInt(), body.size - 1)
            e.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
            e.sendResponseHeaders(206, (end - start + 1).toLong())
            e.responseBody.use { it.write(body, start, end - start + 1) }
        } else {
            e.sendResponseHeaders(200, body.size.toLong())
            e.responseBody.use { it.write(body) }
        }
        e.close()
    }

    init {
        serve("/saavn/song_320.mp4", "video/mp4", mp4)
        serve("/youtube/error", "text/html", ByteArray(2048))
        serve("/sc/aac/playlist.m3u8", "application/vnd.apple.mpegurl", """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="${url("/sc/aac/init.mp4")}?expires=1&Signature=a~b"
            #EXTINF:10.0,
            data000.m4s?expires=1
            #EXTINF:10.0,
            data001.m4s?expires=1
            #EXTINF:4.5,
            data002.m4s?expires=1
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray())
        serve("/sc/aac/init.mp4", "video/mp4", init)
        fragments.forEachIndexed { i, bytes -> serve("/sc/aac/data00$i.m4s", "video/iso.segment", bytes) }
        serve("/sc/master.m3u8", "application/vnd.apple.mpegurl", """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=64000
            low/playlist.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=128000
            mp3/playlist.m3u8
        """.trimIndent().toByteArray())
        serve("/sc/mp3/playlist.m3u8", "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXTINF:10,\n0.mp3\n#EXTINF:10,\n1.mp3\n#EXT-X-ENDLIST\n".toByteArray())
        mp3Segments.forEachIndexed { i, bytes -> serve("/sc/mp3/$i.mp3", "audio/mpeg", bytes) }
        serve("/sc/low/playlist.m3u8", "application/vnd.apple.mpegurl", "#EXTM3U\n".toByteArray())
        serve("/sc/encrypted/playlist.m3u8", "application/vnd.apple.mpegurl",
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\"\n#EXTINF:10,\n0.m4s\n#EXT-X-ENDLIST\n".toByteArray())
        server.start()
    }

    @AfterTest fun cleanUp() {
        scope.cancel(); server.stop(0); root.deleteRecursively()
    }

    private fun manager(saavn: String?, soundCloud: String?, youtube: suspend (Track) -> String = { error(paused) },
        asked: MutableList<String> = mutableListOf()) =
        DesktopDownloads(root, scope, readTimeoutMs = 3000L, alternates = listOf(
            DesktopDownloads.Source("JioSaavn") { asked += "JioSaavn"; saavn },
            DesktopDownloads.Source("SoundCloud") { asked += "SoundCloud"; soundCloud },
        ), stream = youtube)

    private suspend fun DesktopDownloads.settled(count: Int = 1) = withTimeout(15_000) {
        while (entries.value.size != count || entries.value.any { it.status in setOf("QUEUED", "DOWNLOADING") }) delay(20)
    }

    @Test fun youtubeRefusedSavesJioSaavnsFileAndRemembersWhereItCameFrom() = runBlocking {
        val asked = mutableListOf<String>()
        val downloads = manager(url("/saavn/song_320.mp4"), soundCloud = null, asked = asked)
        downloads.enqueue(listOf(song)); downloads.settled()
        val entry = downloads.entries.value.single()
        assertEquals("COMPLETED", entry.status, entry.error)
        assertEquals("JioSaavn", entry.source)
        assertEquals(listOf("JioSaavn"), asked, "SoundCloud is only asked when JioSaavn has no copy")
        assertTrue(entry.file!!.endsWith(".m4a"), entry.file)
        assertContentEquals(mp4, assertNotNull(downloads.localFile(song.id)).readBytes())

        val reloaded = DesktopDownloads(root, scope) { error("Offline files must not need network") }
        assertEquals("JioSaavn", reloaded.entries.value.single().source)
        assertContentEquals(mp4, assertNotNull(reloaded.localFile(song.id)).readBytes())
    }

    @Test fun soundCloudHlsIsJoinedIntoOneM4aInPlaylistOrder() = runBlocking {
        val downloads = manager(saavn = null, soundCloud = url("/sc/aac/playlist.m3u8?expires=1&Signature=x"))
        downloads.enqueue(listOf(song), "mix", "Late Night Mix"); downloads.settled()
        val entry = downloads.entries.value.single()
        assertEquals("COMPLETED", entry.status, entry.error)
        assertEquals("SoundCloud", entry.source)
        val file = assertNotNull(downloads.localFile(song.id))
        assertEquals("01 - Loving Machine (${DesktopDownloads.hash(song.id)}).m4a", file.name)
        assertContentEquals(init + fragments.reduce(ByteArray::plus), file.readBytes())
        assertEquals(file.length(), entry.bytes)
        assertEquals(entry.bytes, entry.total)
        assertFalse(root.walkTopDown().any { it.name.endsWith(".partial") })
    }

    @Test fun mp3HlsTakesTheBestVariantAndIsSavedAsMp3() = runBlocking {
        val downloads = manager(saavn = null, soundCloud = url("/sc/master.m3u8"))
        downloads.enqueue(listOf(song)); downloads.settled()
        val entry = downloads.entries.value.single()
        assertEquals("COMPLETED", entry.status, entry.error)
        assertTrue(entry.file!!.endsWith(".mp3"), entry.file)
        assertContentEquals(mp3Segments.reduce(ByteArray::plus), downloads.localFile(song.id)!!.readBytes())
        assertNull(hits["/sc/low/playlist.m3u8"], "the lower-bandwidth variant is never fetched")
    }

    @Test fun aYouTubeTransferThatFailsAlsoFallsBack() = runBlocking {
        val downloads = manager(url("/saavn/song_320.mp4"), soundCloud = null, youtube = { url("/youtube/error") })
        downloads.enqueue(listOf(song)); downloads.settled()
        val entry = downloads.entries.value.single()
        assertEquals("COMPLETED", entry.status, entry.error)
        assertEquals("JioSaavn", entry.source)
    }

    @Test fun youtubeStillComesFirstWhenItWorks() = runBlocking {
        val downloads = DesktopDownloads(root, scope, readTimeoutMs = 3000L, alternates = listOf(
            DesktopDownloads.Source("JioSaavn") { fail("No other catalog is asked when YouTube plays") },
        )) { url("/saavn/song_320.mp4") }
        downloads.enqueue(listOf(song)); downloads.settled()
        val entry = downloads.entries.value.single()
        assertEquals("COMPLETED", entry.status, entry.error)
        assertEquals("YouTube", entry.source)
    }

    @Test fun aSongNoSourceHasIsRetriedThenFailsSayingWhy() = runBlocking {
        val asked = mutableListOf<String>()
        val downloads = manager(saavn = null, soundCloud = null, asked = asked)
        downloads.enqueue(listOf(song)); downloads.settled()
        val entry = downloads.entries.value.single()
        assertEquals("FAILED", entry.status)
        assertEquals("$paused; no exact copy on JioSaavn or SoundCloud", entry.error)
        assertEquals(listOf("JioSaavn", "SoundCloud", "JioSaavn", "SoundCloud"), asked, "both attempts ask every catalog")
        assertNull(downloads.localFile(song.id))
    }

    @Test fun encryptedHlsIsNotSavedAndTheEntryFails() = runBlocking {
        val downloads = manager(saavn = null, soundCloud = url("/sc/encrypted/playlist.m3u8"))
        downloads.enqueue(listOf(song)); downloads.settled()
        val entry = downloads.entries.value.single()
        assertEquals("FAILED", entry.status)
        assertTrue(entry.error!!.startsWith("$paused; Encrypted HLS"), entry.error)
        assertFalse(root.walkTopDown().any { it.isFile && it.parentFile.name == "Individual songs" })
    }

    @Test fun aTestResolverGetsNoCatalogsBehindIt() = runBlocking {
        val downloads = DesktopDownloads(root, scope, readTimeoutMs = 3000L) { error(paused) }
        downloads.enqueue(listOf(song)); downloads.settled()
        val entry = downloads.entries.value.single()
        assertEquals("FAILED", entry.status)
        assertEquals(paused, entry.error, "without alternates the YouTube reason is reported unchanged")
    }

    /**
     * Run with AURALIS_VERIFY_DOWNLOADS=1 (and AURALIS_VLC_DIR for playback). YouTube is replaced by
     * a refusal; the real JioSaavn and SoundCloud lookups must save both songs whole.
     */
    @Test fun liveSongsDownloadFromJioSaavnAndSoundCloudWhileYouTubeRefuses() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AURALIS_VERIFY_DOWNLOADS") == "1")
        val folder = File("build/download-fallback-live").apply { deleteRecursively(); mkdirs() }
        val downloads = DesktopDownloads(folder, scope, alternates = DesktopDownloads.CATALOGS) { error(paused) }
        val saavnSong = Track("5gg46GQ8lvQ", "AI hai Ki De Aai Hai", "Vishal Bhatt & Doli Rani")
        val expected = mapOf(saavnSong.id to ("JioSaavn" to 185L), song.id to ("SoundCloud" to 227L))
        downloads.enqueue(listOf(saavnSong, song))
        withTimeout(240_000) {
            while (downloads.entries.value.size < 2 || downloads.entries.value.any { it.status in setOf("QUEUED", "DOWNLOADING") }) delay(200)
        }
        for (entry in downloads.entries.value) {
            assertEquals("COMPLETED", entry.status, "${entry.track.title}: ${entry.error}")
            assertEquals(expected.getValue(entry.track.id).first, entry.source, entry.track.title)
            val file = assertNotNull(downloads.localFile(entry.track.id))
            assertEquals(AudioContainer.MP4, AudioContainer.of(file.inputStream().use { it.readNBytes(16) }))
            assertTrue(file.name.endsWith(".m4a"))
            println("Saved '${entry.track.title}' from ${entry.source}: ${file.length()} bytes at ${file.absolutePath}")
        }
        val vlc = File(System.getenv("AURALIS_VLC_DIR") ?: "C:/Program Files/VideoLAN/VLC")
        val player = com.auralis.music.desktop.playback.VlcAudioPlayer.createOrNull(vlc) ?: return@runBlocking println("No libVLC at $vlc; playback not checked")
        player.setVolume(0f)
        val playback = com.auralis.music.desktop.playback.DesktopPlaybackController(player, scope,
            resolveStream = { error("Offline playback must not use the stream resolver") }, offlineFile = downloads::localFile)
        try {
            playback.setVolume(0f)
            for (track in listOf(saavnSong, song)) {
                playback.playFrom(track, listOf(track))
                withTimeout(20_000) { while (player.lengthMs.value <= 0L || player.positionMs.value < 1500L) delay(100) }
                val seconds = player.lengthMs.value / 1000.0
                println("Offline '${track.title}' plays: ${player.positionMs.value} ms in, length ${"%.1f".format(seconds)} s")
                assertTrue(kotlin.math.abs(seconds - expected.getValue(track.id).second) <= 3.0, "${track.title} is ${seconds}s, not the whole song")
            }
        } finally { playback.release() }
    }
}
