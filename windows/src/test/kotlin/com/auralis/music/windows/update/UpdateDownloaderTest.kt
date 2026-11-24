package com.auralis.music.windows.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random
import kotlin.test.*

class UpdateDownloaderTest {
    private val bytes = Random(7).nextBytes(300_000)
    private val sha = sha(bytes)
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var directory: File
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val routes = mutableMapOf<String, (HttpExchange) -> Unit>()

    @BeforeTest fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += "${exchange.requestURI.path} ${exchange.requestHeaders.getFirst("Range").orEmpty()}".trim()
            try { (routes[exchange.requestURI.path] ?: { it.sendResponseHeaders(404, -1) })(exchange) } finally { exchange.close() }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        directory = kotlin.io.path.createTempDirectory("auralis-updates-test").toFile()
    }

    @AfterTest fun stopServer() {
        server.stop(0)
        directory.deleteRecursively()
    }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun downloader() = UpdateDownloader(OkHttpClient(), UpdateFeed.fromSystem("$base/releases").policy, directory)
    private fun release(sha256: String? = sha, size: Long = bytes.size.toLong(), path: String = "/Auralis-1.2.0.msi", checksum: ReleaseAsset? = null) =
        DesktopRelease(ReleasePlatform.WINDOWS, Version(1, 2, 0), "1.2.0", "https://github.com/x/releases/tag/windows-v1.2.0", "",
            ReleaseAsset("Auralis-1.2.0.msi", "$base$path", size, sha256), checksum)

    /** Serves [data], honouring a Range request when [ranges] is true. */
    private fun serve(path: String, data: ByteArray, ranges: Boolean = true) {
        routes[path] = { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
            if (ranges && range != null) {
                exchange.responseHeaders.add("Content-Range", "bytes $range-${data.size - 1}/${data.size}")
                exchange.sendResponseHeaders(206, (data.size - range).toLong())
                exchange.responseBody.write(data, range, data.size - range)
            } else {
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.write(data)
            }
        }
    }

    @Test fun downloadsVerifiesAndReusesAVerifiedFile() {
        serve("/Auralis-1.2.0.msi", bytes)
        val progress = mutableListOf<Long>()
        val file = downloader().download(release(), sha) { done, _ -> progress += done }
        assertContentEquals(bytes, file.readBytes())
        assertEquals("Auralis-1.2.0.msi", file.name)
        assertEquals(bytes.size.toLong(), progress.last())
        assertFalse(File(directory, "Auralis-1.2.0.msi.part").exists())
        val before = requests.size
        assertEquals(file, downloader().download(release(), sha))
        assertEquals(before, requests.size, "A verified download is not fetched again")
    }

    @Test fun aWrongDigestIsRefusedAndNothingIsKept() {
        serve("/Auralis-1.2.0.msi", bytes)
        val wrong = "00".repeat(32)
        assertFailsWith<UpdateVerificationException> { downloader().download(release(wrong), wrong) }
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".msi") || it.name.endsWith(".part") })
    }

    @Test fun aTamperedFileOnDiskIsDownloadedAgain() {
        serve("/Auralis-1.2.0.msi", bytes)
        File(directory, "Auralis-1.2.0.msi").writeBytes(bytes.copyOf().also { it[10] = (it[10] + 1).toByte() })
        assertContentEquals(bytes, downloader().download(release(), sha).readBytes())
    }

    @Test fun moreBytesThanPublishedAreRefused() {
        serve("/Auralis-1.2.0.msi", bytes + byteArrayOf(1, 2, 3))
        assertFailsWith<UpdateVerificationException> { downloader().download(release(), sha) }
        assertFalse(File(directory, "Auralis-1.2.0.msi.part").exists())
    }

    @Test fun aPartialDownloadResumesWithARangeRequest() {
        serve("/Auralis-1.2.0.msi", bytes)
        File(directory, "Auralis-1.2.0.msi.part").writeBytes(bytes.copyOf(120_000))
        File(directory, "Auralis-1.2.0.msi.part.sha256").writeText(sha)
        assertContentEquals(bytes, downloader().download(release(), sha).readBytes())
        assertEquals(listOf("/Auralis-1.2.0.msi bytes=120000-"), requests)
    }

    @Test fun aServerIgnoringRangesRestartsTheDownload() {
        serve("/Auralis-1.2.0.msi", bytes, ranges = false)
        File(directory, "Auralis-1.2.0.msi.part").writeBytes(bytes.copyOf(120_000))
        File(directory, "Auralis-1.2.0.msi.part.sha256").writeText(sha)
        assertContentEquals(bytes, downloader().download(release(), sha).readBytes())
    }

    @Test fun aPartialFileForAnotherReleaseIsDiscarded() {
        serve("/Auralis-1.2.0.msi", bytes)
        File(directory, "Auralis-1.2.0.msi.part").writeBytes(ByteArray(120_000))
        File(directory, "Auralis-1.2.0.msi.part.sha256").writeText("11".repeat(32))
        assertContentEquals(bytes, downloader().download(release(), sha).readBytes())
        assertEquals(listOf("/Auralis-1.2.0.msi"), requests)
    }

    @Test fun aCorruptResumedFileIsRetriedOnceFromScratch() {
        serve("/Auralis-1.2.0.msi", bytes)
        File(directory, "Auralis-1.2.0.msi.part").writeBytes(ByteArray(120_000))
        File(directory, "Auralis-1.2.0.msi.part.sha256").writeText(sha)
        assertContentEquals(bytes, downloader().download(release(), sha).readBytes())
        assertEquals(listOf("/Auralis-1.2.0.msi bytes=120000-", "/Auralis-1.2.0.msi"), requests)
    }

    @Test fun anInterruptedDownloadKeepsItsPartForLater() {
        routes["/Auralis-1.2.0.msi"] = { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes, 0, 100_000)
            exchange.responseBody.flush()
            // Closing early: the client sees the stream end before Content-Length.
        }
        assertFailsWith<IOException> { downloader().download(release(), sha) }
        assertTrue(File(directory, "Auralis-1.2.0.msi.part").length() in 1..100_000L)
        serve("/Auralis-1.2.0.msi", bytes)
        assertContentEquals(bytes, downloader().download(release(), sha).readBytes())
        assertTrue(requests.last().contains("bytes="), "The second attempt resumes")
    }

    @Test fun redirectsAreFollowedOnlyWithinTheAllowList() {
        serve("/assets/real.msi", bytes)
        routes["/Auralis-1.2.0.msi"] = { it.responseHeaders.add("Location", "/assets/real.msi"); it.sendResponseHeaders(302, -1) }
        assertContentEquals(bytes, downloader().download(release(), sha).readBytes())
        File(directory, "Auralis-1.2.0.msi").delete()
        routes["/Auralis-1.2.0.msi"] = { it.responseHeaders.add("Location", "https://downloads.example.test/Auralis.msi"); it.sendResponseHeaders(302, -1) }
        val refused = assertFailsWith<IOException> { downloader().download(release(), sha) }
        assertTrue(refused.message!!.contains("downloads.example.test"), refused.message)
        routes["/Auralis-1.2.0.msi"] = { it.responseHeaders.add("Location", "http://127.0.0.1:1/Auralis.msi"); it.sendResponseHeaders(302, -1) }
        assertFailsWith<IOException> { downloader().download(release(), sha) }
        // Never a URL outside the policy, even as the first request.
        val github = UpdateDownloader(OkHttpClient(), UpdateHostPolicy(), directory)
        assertFailsWith<IOException> { github.download(release(), sha) }
    }

    @Test fun theChecksumAssetIsUsedWhenTheApiHasNoDigest() {
        val checksum = ReleaseAsset("Auralis-1.2.0.msi.sha256", "$base/Auralis-1.2.0.msi.sha256", 100, null)
        routes["/Auralis-1.2.0.msi.sha256"] = { exchange ->
            val body = "${sha.uppercase()}  Auralis-1.2.0.msi\r\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
        }
        assertEquals(sha, downloader().expectedSha256(release(sha256 = null, checksum = checksum)))
        assertEquals("ab".repeat(32), downloader().expectedSha256(release(sha256 = "ab".repeat(32), checksum = checksum)), "The API digest wins")
        assertFailsWith<UpdateVerificationException> { downloader().expectedSha256(release(sha256 = null)) }
        routes["/Auralis-1.2.0.msi.sha256"] = { exchange ->
            val body = "$sha  Auralis-1.1.0.msi".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
        }
        assertFailsWith<UpdateVerificationException> { downloader().expectedSha256(release(sha256 = null, checksum = checksum)) }
    }

    @Test fun cleanupKeepsOnlyTheCurrentDownload() {
        for (name in listOf("Auralis-1.1.0.msi", "Auralis-1.2.0.msi", "Auralis-1.2.0.msi.part", "install-1.1.0.log", "install.json"))
            File(directory, name).writeText("x")
        val downloader = downloader()
        downloader.cleanup(keep = downloader.filesFor(release()) + "install.json")
        assertEquals(setOf("Auralis-1.2.0.msi", "Auralis-1.2.0.msi.part", "install.json"), directory.list()!!.toSet())
        downloader.cleanup()
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test fun unsafeAssetNamesNeverLeaveTheUpdatesFolder() {
        for (name in listOf("../Auralis.msi", "..\\Auralis.msi", "C:\\Auralis.msi", "Auralis/../../x.msi", "Auralis..msi", ".Auralis.msi"))
            assertFailsWith<UpdateVerificationException>(name) { UpdateDownloader.fileName(name) }
        assertEquals("Auralis-1.2.0-beta.1.msi", UpdateDownloader.fileName("Auralis-1.2.0-beta.1.msi"))
    }

    @Test fun theFeedIsReadPageByPageAndOnlyFromAllowedHosts() {
        routes["/releases"] = { exchange ->
            exchange.responseHeaders.add("Link", "<$base/releases2>; rel=\"next\"")
            val body = """[{"tag_name":"windows-v1.0.0"}]""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
        }
        routes["/releases2"] = { exchange ->
            exchange.responseHeaders.add("Link", "<https://feed.example.test/releases3>; rel=\"next\"")
            val body = """[{"tag_name":"windows-v1.1.0"}]""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
        }
        assertFailsWith<IOException> { downloader().fetchFeed("$base/releases") }
        routes["/releases2"] = { exchange ->
            val body = """[{"tag_name":"windows-v1.1.0"}]""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
        }
        assertEquals(2, downloader().fetchFeed("$base/releases").length())
    }
}
