package com.auralis.music.desktop.playback

import java.io.File
import java.util.concurrent.Executor
import kotlin.test.*

class StreamLinkCacheTest {
    private var now = 1_800_000_000_000L
    private val file = File.createTempFile("stream-links", ".properties").apply { delete(); deleteOnExit() }
    private val sameThread = Executor { it.run() }
    private fun cache() = StreamLinkCache({ file }, { now }, maxEntries = 3, writer = sameThread)
    private fun link(id: String, url: String = "https://rr1.googlevideo.com/videoplayback?id=$id&itag=251",
        profile: String = "HIGH:VISIONOS", route: String = "net-a", expires: Long = now + 3 * 3600_000L) =
        PlaybackResource(id, url, expires, itag = 251, mimeType = "audio/webm", contentLength = 1234L, client = "VISIONOS", profile = profile, route = route)

    @AfterTest fun cleanUp() { file.delete() }

    @Test fun anotherQualityOrNetworkNeverReusesALink() {
        val c = cache()
        c.put(link("aaaaaaaaaaa"))
        assertNotNull(c.get("aaaaaaaaaaa", "HIGH:VISIONOS", "net-a"))
        assertNull(c.get("aaaaaaaaaaa", "LOW:VISIONOS", "net-a"), "quality change")
        assertNull(c.get("aaaaaaaaaaa", "HIGH:IOS+VISIONOS", "net-a"), "client set change")
        assertNull(c.get("aaaaaaaaaaa", "HIGH:VISIONOS", "net-b"), "network change")
    }

    @Test fun linksSurviveARestartOnlyWithAComfortableLifetimeLeft() {
        cache().apply {
            put(link("aaaaaaaaaaa"))
            put(link("bbbbbbbbbbb", expires = now + 4 * 60_000L)) // inside the disk safety margin
            put(link("ccccccccccc", expires = now + 30 * 24 * 3600_000L)) // unknown/huge expiry is capped
        }
        val restarted = cache()
        val restored = assertNotNull(restarted.get("aaaaaaaaaaa", "HIGH:VISIONOS", "net-a"))
        assertTrue(restored.fromDisk)
        assertEquals(251, restored.itag)
        assertEquals(1234L, restored.contentLength)
        assertNull(restarted.get("bbbbbbbbbbb", "HIGH:VISIONOS", "net-a"))
        val capped = assertNotNull(restarted.get("ccccccccccc", "HIGH:VISIONOS", "net-a"))
        assertTrue(capped.expiresAtWallMs <= now + StreamLinkCache.MAX_LIFETIME_MS)
        now += 3 * 3600_000L
        assertNull(restarted.get("aaaaaaaaaaa", "HIGH:VISIONOS", "net-a"), "expired links are not handed out")
    }

    @Test fun diskEntriesAreBoundedAndHoldNoSessionSecrets() {
        val c = cache()
        listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd", "eeeeeeeeeee").forEachIndexed { i, id ->
            c.put(link(id, expires = now + (i + 1) * 3600_000L))
        }
        val text = file.readText()
        assertEquals(3, c.size, "the in-memory cache is bounded too")
        assertEquals(3, text.lines().count { it.contains("googlevideo") })
        assertFalse(text.contains("aaaaaaaaaaa|"), "the soonest-expiring entries are dropped first")
        assertFalse(Regex("(?i)cookie|visitor|authorization").containsMatchIn(text))
    }

    @Test fun olderFormatsAndCorruptFilesAreDiscardedNotTrusted() {
        // The previous format: keyed by video alone, no profile or network.
        file.writeText("aaaaaaaaaaa=${now + 3600_000L}|https\\://rr1.googlevideo.com/videoplayback?id\\=a\n")
        assertNull(cache().get("aaaaaaaaaaa", "HIGH:VISIONOS", "net-a"))
        assertFalse(file.exists())
        file.writeBytes(byteArrayOf(0, 1, 2, '\\'.code.toByte(), 'u'.code.toByte(), 'z'.code.toByte()))
        assertNull(cache().get("aaaaaaaaaaa", "HIGH:VISIONOS", "net-a"))
    }

    @Test fun aRejectedLinkIsForgottenButANewerOneStays() {
        val c = cache()
        val old = link("aaaaaaaaaaa", url = "https://rr1.googlevideo.com/videoplayback?id=old")
        c.put(old)
        c.put(old.copy(url = "https://rr2.googlevideo.com/videoplayback?id=new"))
        assertFalse(c.removeIfUrl("aaaaaaaaaaa", old.url), "only the rejected link is removed")
        assertNotNull(c.get("aaaaaaaaaaa", "HIGH:VISIONOS", "net-a"))
        assertTrue(c.remove("aaaaaaaaaaa"))
        assertNull(cache().get("aaaaaaaaaaa", "HIGH:VISIONOS", "net-a"), "a removed link isn't reloaded after a restart")
    }

    @Test fun sameMediaNeedsMatchingFormatNotJustLength() {
        val a = "https://x.googlevideo.com/videoplayback?itag=251&clen=1000&mime=audio%2Fwebm"
        assertTrue(PlaybackResource.sameMedia(a, "https://y.googlevideo.com/videoplayback?itag=251&clen=1000&mime=audio%2Fwebm"))
        assertFalse(PlaybackResource.sameMedia(a, "https://y.googlevideo.com/videoplayback?itag=140&clen=1000&mime=audio%2Fwebm"))
        assertFalse(PlaybackResource.sameMedia(a, "https://y.googlevideo.com/videoplayback?itag=251&clen=1000&mime=audio%2Fmp4"))
    }
}
