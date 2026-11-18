package com.auralis.music.windows.player

import com.auralis.music.desktop.playback.DesktopPlaybackController.YouTubeFallback
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YouTubeEmbedPageTest {
    @Test fun servesOnlyTheRequestedVideoAndResumePosition() {
        YouTubeEmbedPage(YouTubeFallback("local-track", "M7lc1UVf-VE", 47)).use { page ->
            val uri = URI.create(page.url)
            assertEquals("127.0.0.1", uri.host)
            val request = uri.toURL().openConnection() as HttpURLConnection
            try {
                assertEquals(200, request.responseCode)
                assertEquals("strict-origin-when-cross-origin", request.getHeaderField("Referrer-Policy"))
                assertContains(request.inputStream.bufferedReader().use { it.readText() },
                    "https://www.youtube.com/embed/M7lc1UVf-VE?start=47")
            } finally {
                request.disconnect()
            }

            val otherPath = uri.resolve("${uri.path}/other").toURL().openConnection() as HttpURLConnection
            try {
                assertEquals(404, otherPath.responseCode)
            } finally {
                otherPath.disconnect()
            }
        }
    }

    @Test fun rejectsAnInvalidVideoIdBeforeServingHtml() {
        assertFailsWith<IllegalArgumentException> {
            YouTubeEmbedPage(YouTubeFallback("local-track", "../invalid", 0)).close()
        }
    }
}
